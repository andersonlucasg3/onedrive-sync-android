package com.anderson.onedrivesync.log

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.BuildConfig
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.db.ConflictsDao
import com.anderson.onedrivesync.db.Db
import com.anderson.onedrivesync.db.LogDao
import com.anderson.onedrivesync.db.LogRow
import com.anderson.onedrivesync.db.PairsDao
import com.anderson.onedrivesync.db.PendingDao
import com.anderson.onedrivesync.hyperos.HyperOsPermissions
import com.anderson.onedrivesync.sync.SyncState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Log export: the only diagnostic channel for a user without ADB.
 *
 * The header carries the context support needs (build, device, account state, permissions, counts)
 * and never a token: only the client id, masked down to the last four characters.
 */
private val HEADER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

suspend fun buildLogText(context: Context, db: Db, maxLines: Int = 2000): String =
    withContext(Dispatchers.IO) {
        val app = context.applicationContext as App
        val container = app.container
        val database = db.readableDatabase
        val zone = ZoneId.systemDefault()
        val rows = LogDao.recent(database, maxLines)
        val header = buildHeader(
            nowMs = System.currentTimeMillis(),
            zone = zone,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            signedIn = container.authManager.isSignedIn(),
            clientId = BuildConfig.DEFAULT_CLIENT_ID,
            checklist = HyperOsPermissions.checklist(context.applicationContext),
            pairCount = PairsDao.list(database).size,
            pendingCount = PendingDao.list(database).size,
            conflictCount = ConflictsDao.list(database).size,
            syncState = syncStateText(container.syncEngine.state.value),
        )
        buildString {
            appendLine(header)
            rows.forEach { row -> appendLine(formatLogLine(row, zone)) }
        }
    }

/** `cacheDir/logs/sync-YYYYMMDD-HHmmss.txt`, ready for [shareLog]. */
suspend fun writeLogFile(context: Context, db: Db): File = withContext(Dispatchers.IO) {
    val text = buildLogText(context, db)
    val dir = File(context.cacheDir, LOG_DIR).apply { mkdirs() }
    val stamp = FILE_TIME.format(Instant.now().atZone(ZoneId.systemDefault()))
    File(dir, "sync-$stamp.txt").apply { writeText(text) }
}

/** `ACTION_SEND` of the file through the app's [FileProvider]; the chooser picks the target app. */
fun shareLog(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_share_subject))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.log_share_title))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // No app can receive ACTION_SEND: nothing to do, the file is still in cacheDir.
    }
}

/** Pure: the export format is asserted by [com.anderson.onedrivesync.log.LogExportTest]. */
internal fun buildHeader(
    nowMs: Long,
    zone: ZoneId,
    versionName: String,
    versionCode: Int,
    manufacturer: String,
    model: String,
    sdkInt: Int,
    signedIn: Boolean,
    clientId: String?,
    checklist: HyperOsPermissions.Checklist,
    pairCount: Int,
    pendingCount: Int,
    conflictCount: Int,
    syncState: String,
): String = buildString {
    appendLine("=== OneDrive Sync — diagnóstico ===")
    appendLine("Versão: $versionName (código $versionCode)")
    appendLine("Aparelho: ${manufacturer.ifBlank { "?" }} ${model.ifBlank { "?" }} · SDK $sdkInt")
    appendLine("Data: ${HEADER_TIME.format(Instant.ofEpochMilli(nowMs).atZone(zone))}")
    appendLine("Conta: ${if (signedIn) "conectada" else "não conectada"}")
    appendLine("Client ID: ${maskClientId(clientId)}")
    appendLine("Permissões: ${permissionsText(checklist)}")
    appendLine("Pares: $pairCount")
    appendLine("Pendências: $pendingCount · conflitos: $conflictCount")
    appendLine("Estado do sync: $syncState")
    append("--- log (mais recente primeiro) ---")
}

/** Only the last four characters survive: a client id is not a secret, but it is not public either. */
internal fun maskClientId(clientId: String?): String {
    val trimmed = clientId?.trim().orEmpty()
    if (trimmed.isEmpty()) return "(não configurado)"
    if (trimmed.length <= 4) return "****"
    return "****" + trimmed.takeLast(4)
}

internal fun permissionsText(checklist: HyperOsPermissions.Checklist): String = listOf(
    "arquivos=${yesNo(checklist.allFiles)}",
    "notificações=${yesNo(checklist.notifications)}",
    "bateria=${yesNo(checklist.battery)}",
    "autostart=${yesNo(checklist.autostart)}",
).joinToString(", ")

internal fun formatLogLine(row: LogRow, zone: ZoneId): String =
    "${LOG_TIME.format(Instant.ofEpochMilli(row.ts).atZone(zone))} ${row.level} [${row.tag}] ${row.msg}"

internal fun syncStateText(state: SyncState): String = when (state) {
    SyncState.Idle -> "Idle"
    SyncState.Scanning -> "Scanning"
    is SyncState.Running -> "Running(${state.pairName}: ${state.step} ${state.done}/${state.total})"
}

private fun yesNo(value: Boolean): String = if (value) "sim" else "não"

private const val LOG_DIR = "logs"
