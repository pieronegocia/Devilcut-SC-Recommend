package com.devilcut.pinsaver.download

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves an HLS stream as a regular MP4.
 *
 * Media3 Transformer picks the highest-bitrate rendition of the playlist and,
 * since no edits are applied, only remuxes it (no re-encoding, no quality loss).
 */
@OptIn(UnstableApi::class)
class HlsExporter(private val context: Context) {

    suspend fun export(url: String, fileName: String, onProgress: (Float?) -> Unit): Uri {
        val output = File(context.cacheDir, "export_${System.nanoTime()}.mp4")
        try {
            remux(url, output, onProgress)
            onProgress(null)
            return withContext(Dispatchers.IO) { GallerySaver.saveVideo(context, output, fileName) }
        } finally {
            output.delete()
        }
    }

    /** Transformer must be created and used on a Looper thread, so this runs on the main thread. */
    private suspend fun remux(url: String, output: File, onProgress: (Float?) -> Unit) = withContext(Dispatchers.Main) {
        val finished = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    finished.completeExceptionally(exportException)
                }
            })
            .build()
        transformer.start(MediaItem.fromUri(url), output.absolutePath)

        val progressJob = launch {
            val holder = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
                delay(250)
            }
        }
        try {
            finished.await()
        } catch (e: CancellationException) {
            transformer.cancel()
            throw e
        } finally {
            progressJob.cancel()
        }
    }
}
