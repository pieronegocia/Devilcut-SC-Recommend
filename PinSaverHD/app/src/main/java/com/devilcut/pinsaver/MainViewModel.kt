package com.devilcut.pinsaver

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devilcut.pinsaver.core.FileNames
import com.devilcut.pinsaver.core.PinInfo
import com.devilcut.pinsaver.core.PinterestException
import com.devilcut.pinsaver.core.PinterestExtractor
import com.devilcut.pinsaver.core.VariantKind
import com.devilcut.pinsaver.core.VideoClip
import com.devilcut.pinsaver.core.VideoVariant
import com.devilcut.pinsaver.download.HlsExporter
import com.devilcut.pinsaver.download.Mp4Downloader
import com.devilcut.pinsaver.download.StoragePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ErrorKind { EMPTY_INPUT, INVALID_LINK, NETWORK, NOT_FOUND, NO_VIDEO, UNKNOWN }

sealed interface Phase {
    data object Idle : Phase
    data object Loading : Phase
    data class Ready(val pin: PinInfo, val selection: Map<Int, Int> = emptyMap()) : Phase {
        fun selectedIndex(clipIndex: Int): Int = selection[clipIndex] ?: 0
    }
    data class Failed(val error: ErrorKind, val detail: String? = null) : Phase
}

sealed interface DownloadStatus {
    /** [progress] is null while the size is still unknown. */
    data class Running(val progress: Float?) : DownloadStatus
    data class Done(val uri: Uri) : DownloadStatus
    data class Failed(val message: String) : DownloadStatus
}

data class DownloadItem(
    val id: Long,
    val title: String,
    val qualityLabel: String?,
    val kind: VariantKind,
    val status: DownloadStatus,
)

data class UiState(
    val input: String = "",
    val autoDownload: Boolean = false,
    val phase: Phase = Phase.Idle,
    val downloads: List<DownloadItem> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val extractor = PinterestExtractor()
    private val mp4Downloader = Mp4Downloader(application)
    private val hlsExporter = HlsExporter(application)

    private val _state = MutableStateFlow(UiState(autoDownload = prefs.getBoolean(KEY_AUTO_DOWNLOAD, false)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var analyzeJob: Job? = null
    private var nextDownloadId = 1L

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun clearInput() {
        analyzeJob?.cancel()
        _state.update { it.copy(input = "", phase = Phase.Idle) }
    }

    /** Called when another app (usually Pinterest) shares a link with us. */
    fun onSharedText(text: String) {
        _state.update { it.copy(input = text.trim()) }
        analyze(fromShare = true)
    }

    fun setAutoDownload(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, enabled).apply()
        _state.update { it.copy(autoDownload = enabled) }
    }

    fun analyze(fromShare: Boolean = false) {
        val input = _state.value.input.trim()
        if (input.isEmpty()) {
            _state.update { it.copy(phase = Phase.Failed(ErrorKind.EMPTY_INPUT)) }
            return
        }
        analyzeJob?.cancel()
        _state.update { it.copy(phase = Phase.Loading) }
        analyzeJob = viewModelScope.launch {
            val phase = try {
                Phase.Ready(extractor.extract(input))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PinterestException) {
                Phase.Failed(
                    when (e.reason) {
                        PinterestException.Reason.INVALID_LINK -> ErrorKind.INVALID_LINK
                        PinterestException.Reason.NETWORK -> ErrorKind.NETWORK
                        PinterestException.Reason.NOT_FOUND -> ErrorKind.NOT_FOUND
                        PinterestException.Reason.NO_VIDEO -> ErrorKind.NO_VIDEO
                    },
                )
            } catch (e: Exception) {
                Phase.Failed(ErrorKind.UNKNOWN, e.message ?: e.javaClass.simpleName)
            }
            _state.update { it.copy(phase = phase) }

            val app = getApplication<Application>()
            if (phase is Phase.Ready && fromShare && _state.value.autoDownload && !StoragePermission.isRequired(app)) {
                phase.pin.clips.forEach { clip -> startDownload(phase.pin, clip, clip.best) }
            }
        }
    }

    fun selectVariant(clipIndex: Int, variantIndex: Int) {
        _state.update { state ->
            val ready = state.phase as? Phase.Ready ?: return@update state
            state.copy(phase = ready.copy(selection = ready.selection + (clipIndex to variantIndex)))
        }
    }

    fun download(clipIndex: Int) {
        val ready = _state.value.phase as? Phase.Ready ?: return
        val clip = ready.pin.clips.getOrNull(clipIndex) ?: return
        val variant = clip.variants.getOrNull(ready.selectedIndex(clipIndex)) ?: clip.best
        startDownload(ready.pin, clip, variant)
    }

    private fun startDownload(pin: PinInfo, clip: VideoClip, variant: VideoVariant) {
        val id = nextDownloadId++
        val title = pin.title ?: "Pin ${pin.pinId}"
        val fileName = FileNames.build(pin.title, pin.pinId, variant.qualityLabel, clip.index, pin.clips.size)
        val item = DownloadItem(id, title, variant.qualityLabel, variant.kind, DownloadStatus.Running(null))
        _state.update { it.copy(downloads = listOf(item) + it.downloads) }

        viewModelScope.launch {
            val onProgress: (Float?) -> Unit = { progress -> setStatus(id, DownloadStatus.Running(progress)) }
            val status = try {
                val uri = when (variant.kind) {
                    VariantKind.MP4 -> mp4Downloader.download(variant.url, fileName, title, onProgress)
                    VariantKind.HLS -> hlsExporter.export(variant.url, fileName, onProgress)
                }
                DownloadStatus.Done(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DownloadStatus.Failed(e.message ?: e.javaClass.simpleName)
            }
            setStatus(id, status)
        }
    }

    private fun setStatus(id: Long, status: DownloadStatus) {
        _state.update { state ->
            state.copy(downloads = state.downloads.map { if (it.id == id) it.copy(status = status) else it })
        }
    }

    private companion object {
        const val KEY_AUTO_DOWNLOAD = "auto_download"
    }
}
