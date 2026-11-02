package com.devilcut.pinsaver.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.devilcut.pinsaver.core.PinterestExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Downloads direct MP4 files with Android's DownloadManager: the download keeps
 * going (with a system notification) even if the app is closed.
 */
class Mp4Downloader(context: Context) {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    suspend fun download(url: String, fileName: String, title: String, onProgress: (Float?) -> Unit): Uri {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription(fileName)
            .setMimeType("video/mp4")
            .addRequestHeader("User-Agent", PinterestExtractor.USER_AGENT)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "$ALBUM/$fileName")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            request.allowScanningByMediaScanner()
        }

        val id = withContext(Dispatchers.IO) { downloadManager.enqueue(request) }
        while (true) {
            val snapshot = withContext(Dispatchers.IO) { query(id) }
                ?: throw IOException("La descarga se canceló")
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    onProgress(1f)
                    return downloadManager.getUriForDownloadedFile(id)
                        ?: throw IOException("No se encontró el archivo descargado")
                }
                DownloadManager.STATUS_FAILED -> throw IOException(failureMessage(snapshot.reason))
                else -> onProgress(snapshot.fraction)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private class Snapshot(val status: Int, val reason: Int, val fraction: Float?)

    private fun query(id: Long): Snapshot? {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return Snapshot(status, reason, if (total > 0) done.toFloat() / total else null)
        }
    }

    private fun failureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "no hay espacio suficiente"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "no se encontró el almacenamiento"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "el archivo ya existe"
        DownloadManager.ERROR_CANNOT_RESUME, DownloadManager.ERROR_HTTP_DATA_ERROR -> "se cortó la conexión"
        in 400..599 -> "el servidor respondió con el código $reason"
        else -> "error $reason"
    }

    private companion object {
        const val POLL_INTERVAL_MS = 400L
    }
}
