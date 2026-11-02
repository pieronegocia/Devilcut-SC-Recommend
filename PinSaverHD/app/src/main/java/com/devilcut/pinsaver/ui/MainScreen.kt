package com.devilcut.pinsaver.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.devilcut.pinsaver.DownloadItem
import com.devilcut.pinsaver.DownloadStatus
import com.devilcut.pinsaver.ErrorKind
import com.devilcut.pinsaver.MainViewModel
import com.devilcut.pinsaver.Phase
import com.devilcut.pinsaver.R
import com.devilcut.pinsaver.core.PinInfo
import com.devilcut.pinsaver.core.VariantKind
import com.devilcut.pinsaver.core.VideoClip
import com.devilcut.pinsaver.core.VideoVariant
import com.devilcut.pinsaver.download.StoragePermission
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingClip by remember { mutableStateOf<Int?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val clip = pendingClip
        pendingClip = null
        if (granted && clip != null) viewModel.download(clip) else if (!granted) toast(context, R.string.permission_denied)
    }
    val onDownload: (Int) -> Unit = { clipIndex ->
        if (StoragePermission.isRequired(context)) {
            pendingClip = clipIndex
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.download(clipIndex)
        }
    }
    val onPaste: () -> Unit = {
        val text = readClipboard(context)
        if (text.isNullOrBlank()) {
            toast(context, R.string.clipboard_empty)
        } else {
            viewModel.onInputChange(text)
            viewModel.analyze()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "input") {
                LinkInputCard(
                    input = state.input,
                    loading = state.phase == Phase.Loading,
                    autoDownload = state.autoDownload,
                    onInputChange = viewModel::onInputChange,
                    onClear = viewModel::clearInput,
                    onPaste = onPaste,
                    onAnalyze = { viewModel.analyze() },
                    onAutoDownloadChange = viewModel::setAutoDownload,
                )
            }

            when (val phase = state.phase) {
                Phase.Idle -> item(key = "howto") { HowToCard() }
                Phase.Loading -> item(key = "loading") { LoadingCard() }
                is Phase.Failed -> item(key = "error") { ErrorCard(phase, onRetry = { viewModel.analyze() }) }
                is Phase.Ready -> {
                    item(key = "header-${phase.pin.pinId}") { PinHeader(phase.pin) }
                    items(phase.pin.clips, key = { "clip-${phase.pin.pinId}-${it.index}" }) { clip ->
                        ClipCard(
                            clip = clip,
                            clipCount = phase.pin.clips.size,
                            fallbackThumbnail = phase.pin.thumbnailUrl,
                            selectedIndex = phase.selectedIndex(clip.index),
                            onSelect = { variantIndex -> viewModel.selectVariant(clip.index, variantIndex) },
                            onDownload = { onDownload(clip.index) },
                        )
                    }
                }
            }

            if (state.downloads.isNotEmpty()) {
                item(key = "downloads-title") {
                    Text(
                        stringResource(R.string.downloads),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(state.downloads, key = { "download-${it.id}" }) { item ->
                    DownloadRow(
                        item = item,
                        onOpen = { uri -> openVideo(context, uri) },
                        onShare = { uri -> shareVideo(context, uri) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkInputCard(
    input: String,
    loading: Boolean,
    autoDownload: Boolean,
    onInputChange: (String) -> Unit,
    onClear: () -> Unit,
    onPaste: () -> Unit,
    onAnalyze: () -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.link_label)) },
                placeholder = { Text(stringResource(R.string.link_placeholder)) },
                singleLine = true,
                trailingIcon = if (input.isNotEmpty()) {
                    {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear))
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onAnalyze() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f)) {
                    Icon(AppIcons.Paste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.paste))
                }
                Button(onClick = onAnalyze, enabled = !loading, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.analyze))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.auto_download_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.auto_download_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoDownload, onCheckedChange = onAutoDownloadChange)
            }
        }
    }
}

@Composable
private fun HowToCard() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.how_to_title), style = MaterialTheme.typography.titleMedium)
            listOf(R.string.how_to_step1, R.string.how_to_step2, R.string.how_to_step3).forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(step), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            Text(
                stringResource(R.string.how_to_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingCard() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorCard(phase: Phase.Failed, onRetry: () -> Unit) {
    val message = when (phase.error) {
        ErrorKind.EMPTY_INPUT -> stringResource(R.string.error_empty)
        ErrorKind.INVALID_LINK -> stringResource(R.string.error_invalid)
        ErrorKind.NETWORK -> stringResource(R.string.error_network)
        ErrorKind.NOT_FOUND -> stringResource(R.string.error_not_found)
        ErrorKind.NO_VIDEO -> stringResource(R.string.error_no_video)
        ErrorKind.UNKNOWN -> stringResource(R.string.error_unknown, phase.detail.orEmpty())
    }
    val canRetry = phase.error == ErrorKind.NETWORK || phase.error == ErrorKind.UNKNOWN
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (canRetry) {
                TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun PinHeader(pin: PinInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        pin.title?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            stringResource(R.string.pin_id, pin.pinId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClipCard(
    clip: VideoClip,
    clipCount: Int,
    fallbackThumbnail: String?,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDownload: () -> Unit,
) {
    val selected = clip.variants.getOrElse(selectedIndex) { clip.best }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(clip.thumbnailUrl ?: fallbackThumbnail, Modifier.size(width = 72.dp, height = 96.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (clipCount > 1) stringResource(R.string.clip_n, clip.index + 1, clipCount) else stringResource(R.string.video),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    clip.durationMs?.let {
                        Text(
                            stringResource(R.string.duration, formatDuration(it)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.best_quality, clip.best.qualityLabel ?: stringResource(R.string.quality_unknown)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(stringResource(R.string.choose_quality), style = MaterialTheme.typography.labelLarge)
            Column {
                clip.variants.forEachIndexed { index, variant ->
                    VariantRow(variant, selected = index == selectedIndex, onClick = { onSelect(index) })
                }
            }

            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    selected.qualityLabel?.let { stringResource(R.string.download_quality, it) }
                        ?: stringResource(R.string.download),
                )
            }
        }
    }
}

@Composable
private fun VariantRow(variant: VideoVariant, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    variant.qualityLabel ?: stringResource(R.string.quality_unknown),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (variant.isHd) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.hd),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(
                variantDetails(variant),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun variantDetails(variant: VideoVariant): String {
    val context = LocalContext.current
    val parts = mutableListOf<String>()
    parts += stringResource(if (variant.kind == VariantKind.HLS) R.string.kind_hls else R.string.kind_mp4)
    if (variant.isHevc) parts += stringResource(R.string.hevc)
    if (variant.width != null && variant.height != null) parts += "${variant.width}×${variant.height}"
    when {
        variant.sizeBytes != null -> parts += Formatter.formatShortFileSize(context, variant.sizeBytes!!)
        variant.estimatedBytes != null ->
            parts += stringResource(R.string.size_estimated, Formatter.formatShortFileSize(context, variant.estimatedBytes!!))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun Thumbnail(url: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onOpen: (Uri) -> Unit, onShare: (Uri) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    item.qualityLabel,
                    stringResource(if (item.kind == VariantKind.HLS) R.string.kind_hls else R.string.kind_mp4),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val status = item.status) {
                is DownloadStatus.Running -> {
                    val progress = status.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val label = when {
                        progress != null && item.kind == VariantKind.HLS ->
                            stringResource(R.string.converting) + "  " +
                                stringResource(R.string.progress_percent, (progress * 100).roundToInt())
                        progress != null -> stringResource(R.string.progress_percent, (progress * 100).roundToInt())
                        item.kind == VariantKind.HLS -> stringResource(R.string.converting)
                        else -> stringResource(R.string.preparing)
                    }
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
                is DownloadStatus.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.saved), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onOpen(status.uri) }) { Text(stringResource(R.string.open)) }
                    TextButton(onClick = { onShare(status.uri) }) { Text(stringResource(R.string.share)) }
                }
                is DownloadStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.download_failed, status.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(1)
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private fun readClipboard(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

private fun openVideo(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast(context, R.string.no_app_to_open)
    }
}

private fun shareVideo(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)))
}

private fun toast(context: Context, @StringRes message: Int) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
