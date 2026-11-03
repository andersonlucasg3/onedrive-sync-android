package com.anderson.onedrivesync.ui.vm

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.auth.AuthRequiredException
import com.anderson.onedrivesync.db.Db
import com.anderson.onedrivesync.db.PairsDao
import com.anderson.onedrivesync.graph.DriveItem
import com.anderson.onedrivesync.graph.GraphClient
import com.anderson.onedrivesync.work.Scheduler
import com.anderson.onedrivesync.work.SyncService
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val WIZARD_STEP_REMOTE = 0
const val WIZARD_STEP_LOCAL = 1
const val WIZARD_STEP_CONFIRM = 2

/**
 * A remote folder. [id] is null only for the drive root while browsing; choosing the root fetches
 * the real item id (`GET /me/drive/root`) before the pair is written.
 */
data class RemoteFolder(val id: String?, val name: String)

data class RemoteEntry(val id: String, val name: String)

data class LocalFolder(val name: String, val path: String)

data class WizardUiState(
    val step: Int = WIZARD_STEP_REMOTE,
    val remoteStack: List<RemoteFolder>,
    val remoteItems: List<RemoteEntry> = emptyList(),
    val remoteLoading: Boolean = false,
    val remoteResolving: Boolean = false,
    val remoteError: String? = null,
    val remoteAuthRequired: Boolean = false,
    val remoteNextLink: String? = null,
    val remoteLoadingMore: Boolean = false,
    val chosenRemote: RemoteFolder? = null,
    val storageRoot: String = "",
    val localPath: String = "",
    val localItems: List<LocalFolder> = emptyList(),
    val localLoading: Boolean = false,
    val localError: String? = null,
    val hasLocalContent: Boolean = false,
    val pairName: String = "",
    val creating: Boolean = false,
    val createError: String? = null,
    val createdPairId: Long? = null,
) {
    val currentRemote: RemoteFolder get() = remoteStack.lastOrNull() ?: RemoteFolder(id = null, name = "")

    val canGoUpRemote: Boolean get() = remoteStack.size > 1

    /**
     * Selecting never waits for the listing: the browsed folder is known before its children are.
     * Only the root-id resolution in flight blocks a retap, because it is the selection itself.
     */
    val canChooseRemote: Boolean get() = !remoteResolving

    val remoteEmpty: Boolean
        get() = !remoteLoading && !remoteResolving && remoteError == null &&
            remoteItems.isEmpty() && remoteNextLink == null

    val canGoUpLocal: Boolean
        get() = localPath.isNotEmpty() && storageRoot.isNotEmpty() && localPath != storageRoot

    val canUseLocal: Boolean get() = !localLoading && localError == null && localPath.isNotEmpty()

    val canCreate: Boolean
        get() = !creating && pairName.isNotBlank() && chosenRemote?.id != null && localPath.isNotEmpty()

    val canGoBack: Boolean get() = step > WIZARD_STEP_REMOTE || canGoUpRemote

    /** "/Pasta/Subpasta" from the browsed stack; the root itself renders as "/". */
    val remotePath: String
        get() = remoteStack.drop(1).joinToString("/") { it.name }
            .let { if (it.isEmpty()) "/" else "/$it" }
}

/**
 * Three-step pair creation: remote folder (Graph children), local folder (`File.listFiles`) and the
 * confirmation that writes the pair, schedules periodic work and starts the first sync.
 */
class WizardViewModel(
    private val context: Context,
    private val db: Db,
    private val graphClient: GraphClient,
) : ViewModel() {
    private val _state = MutableStateFlow(
        WizardUiState(
            remoteStack = listOf(RemoteFolder(id = null, name = context.getString(R.string.wizard_root))),
            storageRoot = storageRootPath(),
        ),
    )
    val state: StateFlow<WizardUiState> = _state.asStateFlow()

    private var remoteJob: Job? = null
    private var localJob: Job? = null

    /** Children already fetched per folder id, so going back to a parent never re-downloads it. */
    private val remoteCache = HashMap<String, CachedChildren>()

    private class CachedChildren(val items: List<RemoteEntry>, val nextLink: String?)

    init {
        loadRemote(null)
    }

    fun enterRemote(entry: RemoteEntry) {
        _state.update {
            it.copy(remoteStack = it.remoteStack + RemoteFolder(entry.id, entry.name), remoteItems = emptyList())
        }
        loadRemote(entry.id)
    }

    fun upRemote() {
        val stack = _state.value.remoteStack
        if (stack.size <= 1) return
        _state.update { it.copy(remoteStack = stack.dropLast(1), remoteItems = emptyList()) }
        loadRemote(stack.dropLast(1).last().id)
    }

    fun retryRemote() {
        // A retry must hit the network even when a previous page is cached.
        remoteCache.remove(cacheKey(_state.value.currentRemote.id))
        loadRemote(_state.value.currentRemote.id)
    }

    fun chooseRemote() {
        val state = _state.value
        if (!state.canChooseRemote) return
        val current = state.currentRemote
        if (current.id != null) {
            openLocalStep(current, state)
            return
        }
        // The browsed root has no item id, so resolve it before writing the pair.
        remoteJob?.cancel()
        _state.update { it.copy(remoteResolving = true, remoteLoadingMore = false, remoteError = null, remoteAuthRequired = false) }
        remoteJob = viewModelScope.launch {
            try {
                val root = graphClient.rootItem()
                _state.update { it.copy(remoteResolving = false) }
                openLocalStep(RemoteFolder(root.id, current.name), _state.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRequiredException) {
                _state.update {
                    it.copy(
                        remoteResolving = false,
                        remoteAuthRequired = true,
                        remoteError = context.getString(R.string.auth_required),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        remoteResolving = false,
                        remoteError = e.message ?: context.getString(R.string.wizard_remote_error),
                    )
                }
            }
        }
    }

    private fun openLocalStep(remote: RemoteFolder, state: WizardUiState) {
        _state.update { it.copy(chosenRemote = remote, step = WIZARD_STEP_LOCAL) }
        loadLocal(File(state.localPath.ifEmpty { state.storageRoot }))
    }

    fun enterLocal(folder: LocalFolder) {
        loadLocal(File(folder.path))
    }

    fun upLocal() {
        val state = _state.value
        if (!state.canGoUpLocal) return
        val parent = File(state.localPath).parentFile ?: return
        loadLocal(parent)
    }

    fun retryLocal() {
        val state = _state.value
        loadLocal(File(state.localPath.ifEmpty { state.storageRoot }))
    }

    fun chooseLocal() {
        val state = _state.value
        if (!state.canUseLocal) return
        viewModelScope.launch {
            val hasContent = withContext(Dispatchers.IO) {
                File(state.localPath).listFiles()?.isNotEmpty() == true
            }
            _state.update {
                it.copy(
                    step = WIZARD_STEP_CONFIRM,
                    hasLocalContent = hasContent,
                    pairName = it.pairName.ifBlank { it.chosenRemote?.name.orEmpty() },
                )
            }
        }
    }

    fun setPairName(name: String) {
        _state.update { it.copy(pairName = name) }
    }

    fun createPair() {
        val state = _state.value
        val remoteId = state.chosenRemote?.id ?: return
        if (!state.canCreate) return
        _state.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val id = PairsDao.insert(
                        db = db.writableDatabase,
                        remoteId = remoteId,
                        remotePath = state.remotePath,
                        localPath = state.localPath,
                        displayName = state.pairName.trim(),
                    )
                    Scheduler.ensurePeriodic(context)
                    Result.success(id)
                } catch (e: Exception) {
                    Result.failure<Long>(e)
                }
            }
            result.fold(
                onSuccess = { id ->
                    SyncService.start(context, id)
                    _state.update { it.copy(creating = false, createdPairId = id) }
                },
                onFailure = { failure ->
                    _state.update {
                        it.copy(
                            creating = false,
                            createError = failure.message ?: context.getString(R.string.wizard_create_error),
                        )
                    }
                },
            )
        }
    }

    /** Hardware/gesture back: walk the steps (and the browsed folders) before leaving the wizard. */
    fun goBack() {
        when (_state.value.step) {
            WIZARD_STEP_CONFIRM -> _state.update { it.copy(step = WIZARD_STEP_LOCAL) }
            WIZARD_STEP_LOCAL -> if (_state.value.canGoUpLocal) upLocal() else _state.update { it.copy(step = WIZARD_STEP_REMOTE) }
            else -> if (_state.value.canGoUpRemote) upRemote()
        }
    }

    private fun loadRemote(itemId: String?) {
        remoteJob?.cancel()
        val key = cacheKey(itemId)
        val cached = remoteCache[key]
        _state.update {
            it.copy(
                remoteLoading = cached == null,
                remoteLoadingMore = false,
                remoteResolving = false,
                remoteError = null,
                remoteAuthRequired = false,
                remoteItems = cached?.items.orEmpty(),
                remoteNextLink = cached?.nextLink,
            )
        }
        if (cached != null) return
        remoteJob = viewModelScope.launch {
            try {
                val page = graphClient.childrenPage(itemId)
                val entries = folderEntries(page.items)
                remoteCache[key] = CachedChildren(entries, page.nextLink)
                _state.update {
                    it.copy(remoteLoading = false, remoteItems = entries, remoteNextLink = page.nextLink)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRequiredException) {
                _state.update {
                    it.copy(
                        remoteLoading = false,
                        remoteAuthRequired = true,
                        remoteError = context.getString(R.string.auth_required),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        remoteLoading = false,
                        remoteError = e.message ?: context.getString(R.string.wizard_remote_error),
                    )
                }
            }
        }
    }

    /** Next children page of the current folder; the select button stays usable meanwhile. */
    fun loadMoreRemote() {
        val state = _state.value
        val nextLink = state.remoteNextLink ?: return
        if (state.remoteLoadingMore || state.remoteResolving) return
        val itemId = state.currentRemote.id
        val key = cacheKey(itemId)
        _state.update { it.copy(remoteLoadingMore = true, remoteError = null) }
        remoteJob = viewModelScope.launch {
            try {
                val page = graphClient.childrenPage(itemId, nextLink)
                val merged = (_state.value.remoteItems + folderEntries(page.items))
                    .distinctBy { it.id }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
                remoteCache[key] = CachedChildren(merged, page.nextLink)
                _state.update {
                    it.copy(remoteLoadingMore = false, remoteItems = merged, remoteNextLink = page.nextLink)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRequiredException) {
                _state.update {
                    it.copy(
                        remoteLoadingMore = false,
                        remoteAuthRequired = true,
                        remoteError = context.getString(R.string.auth_required),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        remoteLoadingMore = false,
                        remoteError = e.message ?: context.getString(R.string.wizard_remote_error),
                    )
                }
            }
        }
    }

    /** Only folders are browsable; files in the listing are dropped. */
    private fun folderEntries(items: List<DriveItem>): List<RemoteEntry> =
        items.filter { it.folder != null }
            .mapNotNull { item -> item.name?.let { name -> RemoteEntry(item.id, name) } }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    private fun cacheKey(itemId: String?): String = itemId ?: ROOT_CACHE_KEY

    private fun loadLocal(dir: File) {
        localJob?.cancel()
        _state.update { it.copy(localLoading = true, localError = null) }
        localJob = viewModelScope.launch {
            val folders = withContext(Dispatchers.IO) { listDirectories(dir) }
            if (folders == null) {
                _state.update {
                    it.copy(
                        localLoading = false,
                        localPath = dir.absolutePath,
                        localItems = emptyList(),
                        localError = context.getString(R.string.wizard_local_error),
                    )
                }
            } else {
                _state.update {
                    it.copy(localLoading = false, localPath = dir.absolutePath, localItems = folders)
                }
            }
        }
    }

    private fun listDirectories(dir: File): List<LocalFolder>? =
        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.canRead() }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?.map { LocalFolder(name = it.name, path = it.absolutePath) }
            ?.toList()

    companion object {
        /** Cache key of the drive root, which browses with a null id until it is chosen. */
        private const val ROOT_CACHE_KEY = "\u0000root"

        @Suppress("DEPRECATION") // MANAGE_EXTERNAL_STORAGE + java.io.File is the chosen design (ARCH).
        private fun storageRootPath(): String = Environment.getExternalStorageDirectory().absolutePath

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as App
                WizardViewModel(app, app.container.db, app.container.graphClient)
            }
        }
    }
}
