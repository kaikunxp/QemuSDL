package com.qemusdl.app.ui.isos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import com.qemusdl.app.data.GuestArchitecture
import com.qemusdl.app.qemu.IsoCatalog
import com.qemusdl.app.qemu.IsoCatalogEntry
import com.qemusdl.app.qemu.IsoDownloadState
import com.qemusdl.app.qemu.IsoDownloader
import com.qemusdl.app.ui.adaptive.AppTooltipBox
import com.qemusdl.app.ui.adaptive.desktopHandCursor
import com.qemusdl.app.ui.library.VmLibraryViewModel
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun IsoDownloaderScreen(
    viewModel: VmLibraryViewModel,
    targetVmId: String? = null,
    onBack: () -> Unit,
    onIsoSelectedForVm: (path: String, displayName: String) -> Unit = { _, _ -> },
    showBackButton: Boolean = true
) {
    val context = LocalContext.current
    val downloadStates by viewModel.isoDownloadStates.collectAsState()
    val downloadedIsos by viewModel.downloadedIsos.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Foreground service still runs even if notification visibility is denied */ }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val targetVm = remember(targetVmId) { targetVmId?.let { viewModel.getVm(it) } }
    var selectedArchFilterName by rememberSaveable(targetVm?.architecture?.name) {
        mutableStateOf(targetVm?.architecture?.name)
    }
    val selectedArchFilter = selectedArchFilterName?.let { name ->
        GuestArchitecture.entries.firstOrNull { it.name == name }
    }

    val filteredCatalog = remember(selectedArchFilter) {
        IsoCatalog.entriesForArch(selectedArchFilter)
    }

    var customUrl by rememberSaveable { mutableStateOf("") }
    val customState = downloadStates[IsoDownloader.CUSTOM_DOWNLOAD_ID] ?: IsoDownloadState.Idle

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OS & ISO Downloader")
                        if (targetVm != null) {
                            Text(
                                text = "Selecting ${targetVm.architecture.badgeText} ISO for ${targetVm.name}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (showBackButton) {
                        AppTooltipBox(tooltip = "Back") {
                            IconButton(onClick = onBack, modifier = Modifier.desktopHandCursor()) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 360.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Curated x86_64, AArch64 & PowerPC OS Images",
                        style = MaterialTheme.typography.titleLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedArchFilter == null,
                            onClick = { selectedArchFilterName = null },
                            label = { Text("All Architectures") },
                            modifier = Modifier.desktopHandCursor()
                        )
                        GuestArchitecture.entries.forEach { arch ->
                            FilterChip(
                                selected = selectedArchFilter == arch,
                                onClick = { selectedArchFilterName = arch.name },
                                label = { Text(arch.badgeText) },
                                modifier = Modifier.desktopHandCursor()
                            )
                        }
                    }
                }
            }

            items(filteredCatalog, key = { it.id }) { entry ->
                val state = downloadStates[entry.id] ?: IsoDownloadState.Idle
                val existingFile = downloadedIsos.firstOrNull { it.name == entry.targetFileName }
                IsoCatalogCard(
                    entry = entry,
                    state = state,
                    downloadedPath = existingFile?.path,
                    downloadedSizeBytes = existingFile?.sizeBytes,
                    canAttachToVm = targetVmId != null,
                    onDownload = {
                        ensureNotificationPermission()
                        viewModel.startIsoDownload(
                            entry = entry,
                            autoAttachVmId = targetVmId,
                            onComplete = { file ->
                                scope.launch {
                                    snackbarHostState.showSnackbar("Downloaded ${file.name}")
                                }
                                if (targetVmId != null) {
                                    onIsoSelectedForVm(file.absolutePath, file.name)
                                }
                            },
                            onError = { msg ->
                                scope.launch { snackbarHostState.showSnackbar("Failed: $msg") }
                            }
                        )
                    },
                    onCancel = { viewModel.cancelIsoDownload(entry.id) },
                    onAttach = { path, name ->
                        if (targetVmId != null) {
                            viewModel.attachIsoToVm(targetVmId, path, name)
                            onIsoSelectedForVm(path, name)
                        }
                    }
                )
            }

            // Custom direct ISO URL downloader
            item(span = { GridItemSpan(maxLineSpan) }) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("Download from Custom URL", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Paste a direct HTTPS link to any .iso or .zip archive containing an .iso.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text("https://example.org/os-image.iso") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))

                        DownloadStateSection(
                            state = customState,
                            onCancel = { viewModel.cancelIsoDownload(IsoDownloader.CUSTOM_DOWNLOAD_ID) }
                        )

                        if (!customState.isActiveDownload) {
                            Button(
                                onClick = {
                                    ensureNotificationPermission()
                                    viewModel.startCustomIsoDownload(
                                        url = customUrl,
                                        autoAttachVmId = targetVmId,
                                        onComplete = { file ->
                                            customUrl = ""
                                            scope.launch { snackbarHostState.showSnackbar("Saved ${file.name}") }
                                            if (targetVmId != null) {
                                                onIsoSelectedForVm(file.absolutePath, file.name)
                                            }
                                        },
                                        onError = { err ->
                                            scope.launch { snackbarHostState.showSnackbar(err) }
                                        }
                                    )
                                },
                                enabled = customUrl.trim().startsWith("http")
                            ) {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Download ISO")
                            }
                        }
                    }
                }
            }

            // Downloaded ISOs section
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Downloaded ISOs (${downloadedIsos.size})",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            if (downloadedIsos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Rounded.Album,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("No ISOs downloaded yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Pick an image above to download directly into QemuSDL.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(downloadedIsos, key = { it.path }) { iso ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = iso.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatBytes(iso.sizeBytes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (targetVmId != null) {
                                    FilledTonalButton(
                                        onClick = {
                                            viewModel.attachIsoToVm(targetVmId, iso.path, iso.name)
                                            onIsoSelectedForVm(iso.path, iso.name)
                                        }
                                    ) {
                                        Text("Use ISO")
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.deleteLocalIso(iso.path)
                                        scope.launch { snackbarHostState.showSnackbar("Deleted ${iso.name}") }
                                    }
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Delete ISO",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IsoCatalogCard(
    entry: IsoCatalogEntry,
    state: IsoDownloadState,
    downloadedPath: String?,
    downloadedSizeBytes: Long?,
    canAttachToVm: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onAttach: (path: String, name: String) -> Unit
) {
    val isBusy = state.isActiveDownload

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(100),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Text(
                            text = entry.preset.badgeText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(100),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = entry.architecture.badgeText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Text(
                    text = downloadedSizeBytes?.let { "Saved · ${formatBytes(it)}" } ?: entry.approxSizeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (downloadedPath != null) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(text = entry.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            DownloadStateSection(state = state, onCancel = onCancel)

            if (!isBusy) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (downloadedPath != null) {
                        if (canAttachToVm) {
                            Button(onClick = { onAttach(downloadedPath, entry.targetFileName) }) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Attach to VM")
                            }
                        } else {
                            FilledTonalButton(onClick = onDownload) {
                                Icon(Icons.Rounded.DownloadDone, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Re-download")
                            }
                        }
                    } else {
                        Button(onClick = onDownload) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (canAttachToVm) "Download & Attach" else "Download ISO")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadStateSection(
    state: IsoDownloadState,
    onCancel: () -> Unit
) {
    when (state) {
        IsoDownloadState.Idle, is IsoDownloadState.Completed -> Unit
        is IsoDownloadState.Resolving -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                LoadingIndicator(modifier = Modifier.size(28.dp))
                Text(state.status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
        is IsoDownloadState.Extracting -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                LoadingIndicator(modifier = Modifier.size(28.dp))
                Text(state.status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        is IsoDownloadState.Downloading -> {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                val p = state.progress
                if (p != null) {
                    LinearWavyProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val detail = buildString {
                        append(formatBytes(state.bytesDownloaded))
                        if (state.totalBytes > 0L) {
                            append(" / ")
                            append(formatBytes(state.totalBytes))
                        }
                        if (state.speedBytesPerSec > 0L) {
                            append(" · ")
                            append(formatBytes(state.speedBytesPerSec))
                            append("/s")
                        }
                    }
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel download")
                    }
                }
            }
        }
        is IsoDownloadState.Failed -> {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            ) {
                Text(
                    text = state.errorMessage,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
