package com.anderson.onedrivesync.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.anderson.onedrivesync.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the update flow needs from the APK layer; faked in the JVM tests. */
interface ApkInstaller {
    suspend fun download(info: ReleaseInfo, dest: File, onProgress: (Long, Long) -> Unit)
    fun install(context: Context, apk: File)
}

/**
 * Downloads a release APK into the cache and hands it to the system installer.
 *
 * Android never installs silently for a sideloaded app: the user confirms in the installer, and
 * when "install unknown apps" is still off the app opens the per-app setting instead.
 */
class UpdateApplier(private val http: OkHttpClient) : ApkInstaller {

    /**
     * Streams [info] into `<dest>.part`, validates size and, when the release carries a digest,
     * SHA-256, then renames it to [dest]. A mismatch deletes the partial file and throws.
     */
    override suspend fun download(info: ReleaseInfo, dest: File, onProgress: (Long, Long) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val partial = File(dest.parentFile ?: dest, dest.name + ".part")
            partial.parentFile?.mkdirs()
            partial.delete()

            val request = Request.Builder()
                .url(info.apkUrl)
                .header("User-Agent", "OneDriveSync/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} ao baixar a atualização")
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
                    var written = 0L
                    FileOutputStream(partial).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                written += read
                                onProgress(written, total)
                            }
                        }
                    }
                }

                if (info.apkSize > 0 && partial.length() != info.apkSize) {
                    throw IOException("tamanho do APK diferente do informado (${partial.length()} != ${info.apkSize})")
                }
                val expected = info.sha256
                if (expected != null && !sha256(partial).equals(expected, ignoreCase = true)) {
                    throw IOException("hash do APK não confere")
                }
                if (!partial.renameTo(dest)) {
                    dest.delete()
                    if (!partial.renameTo(dest)) throw IOException("não foi possível concluir o download do APK")
                }
            } catch (e: Exception) {
                partial.delete()
                throw e
            }
        }

    /**
     * `ACTION_VIEW` of the APK through [FileProvider]; without "install unknown apps" the user is
     * sent to that setting first and has to tap install again.
     */
    override fun install(context: Context, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            openUnknownSourcesSettings(context)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No package installer on the device: nothing else can be done from here.
        }
    }

    private fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Some ROMs hide this screen; the install simply cannot proceed.
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val BUFFER_BYTES = 64 * 1024
    }
}
