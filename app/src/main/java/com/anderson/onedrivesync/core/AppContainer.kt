package com.anderson.onedrivesync.core

import android.content.Context
import com.anderson.onedrivesync.BuildConfig
import com.anderson.onedrivesync.auth.AuthManager
import com.anderson.onedrivesync.auth.TokenStore
import com.anderson.onedrivesync.db.Db
import com.anderson.onedrivesync.db.DbIndexStore
import com.anderson.onedrivesync.db.Pair
import com.anderson.onedrivesync.graph.GraphClient
import com.anderson.onedrivesync.graph.GraphRemoteApi
import com.anderson.onedrivesync.graph.transferClient
import com.anderson.onedrivesync.sync.FileLocalFs
import com.anderson.onedrivesync.sync.IndexStore
import com.anderson.onedrivesync.sync.LocalFs
import com.anderson.onedrivesync.sync.RemoteApi
import com.anderson.onedrivesync.sync.SyncEngine
import com.anderson.onedrivesync.update.UpdateApplier
import com.anderson.onedrivesync.update.UpdateChecker
import com.anderson.onedrivesync.update.UpdateManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val prefs: Prefs by lazy { Prefs(appContext) }

    val db: Db by lazy { Db(appContext) }

    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Upper bound for a whole call, including body streaming: without it a stuck transfer
            // hangs forever between reads and the cycle never finishes (no state, no log, no error).
            .callTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Content transfers: same client, only read/write 5 min and call 30 min instead of 60s/5min. */
    val transferHttpClient: OkHttpClient by lazy { transferClient(okHttpClient) }

    val authManager: AuthManager by lazy {
        AuthManager(
            clientId = { BuildConfig.DEFAULT_CLIENT_ID },
            tokens = tokenStore,
            pkce = prefs,
            http = okHttpClient,
        )
    }

    val graphClient: GraphClient by lazy {
        GraphClient(
            tokenProvider = { forceRefresh -> authManager.accessToken(forceRefresh) },
            http = okHttpClient,
            transferHttp = transferHttpClient,
            log = { message -> indexStore.log(System.currentTimeMillis(), "WARN", "Graph", message) },
        )
    }

    val indexStore: IndexStore by lazy { DbIndexStore(db) }

    val remoteApi: RemoteApi by lazy {
        // The HTTP status and Graph error code are the only diagnostic signal for a failed call.
        GraphRemoteApi(graphClient) { message ->
            indexStore.log(System.currentTimeMillis(), "WARN", "GraphRemoteApi", message)
        }
    }

    val localFsFor: (Pair) -> LocalFs by lazy {
        { pair ->
            FileLocalFs(File(pair.localPath)) { message ->
                indexStore.log(System.currentTimeMillis(), "WARN", "LocalFs", message)
            }
        }
    }

    val syncEngine: SyncEngine by lazy { SyncEngine(indexStore, remoteApi, localFsFor) }

    val updateChecker: UpdateChecker by lazy { UpdateChecker(okHttpClient) }

    val updateApplier: UpdateApplier by lazy { UpdateApplier(okHttpClient) }

    /** One state machine for the launch check, the Settings card and the dialog over the app. */
    val updateManager: UpdateManager by lazy {
        UpdateManager(
            checker = updateChecker,
            applier = updateApplier,
            prefs = prefs,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            log = { message -> indexStore.log(System.currentTimeMillis(), "WARN", "Update", message) },
        )
    }
}
