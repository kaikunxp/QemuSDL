package com.qemusdl.app.ui.about

import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qemusdl.app.BuildConfig
import com.qemusdl.app.ui.adaptive.AppTooltipBox
import com.qemusdl.app.ui.adaptive.desktopHandCursor
import com.qemusdl.app.ui.adaptive.desktopWaitCursor
import com.qemusdl.app.ui.library.VmLibraryViewModel
import com.qemusdl.app.update.GitHubUpdateChecker
import com.qemusdl.app.update.UpdateCheckResult
import kotlinx.coroutines.launch
import java.util.Locale

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Result(val result: UpdateCheckResult) : UpdateUiState
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun AboutScreen(
    viewModel: VmLibraryViewModel,
    onBack: () -> Unit,
    showBackButton: Boolean = true
) {
    val runtimeStatus by viewModel.runtimeStatus.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    val isChecking = updateState is UpdateUiState.Checking

    fun openExternalUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            scope.launch {
                snackbarHostState.showSnackbar("Could not open link: $url")
            }
        }
    }

    fun triggerUpdateCheck() {
        if (updateState is UpdateUiState.Checking) return
        updateState = UpdateUiState.Checking
        scope.launch {
            val result = GitHubUpdateChecker.checkForUpdates(
                currentVersion = BuildConfig.VERSION_NAME,
                preferredAbi = runtimeStatus.abi
            )
            updateState = UpdateUiState.Result(result)
        }
    }

    Scaffold(
        modifier = Modifier.desktopWaitCursor(isChecking),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("About QemuSDL") },
                navigationIcon = {
                    if (showBackButton) {
                        AppTooltipBox(tooltip = "Back") {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.desktopHandCursor()
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 420.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // App Identity Hero Card
            item(span = { GridItemSpan(maxLineSpan) }) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeveloperBoard,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "QemuSDL",
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Text(
                                    text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Multi-architecture QEMU VM runner with native SDL2 + OpenGL ES viewport",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InfoBadgeChip("ABI: ${runtimeStatus.abi}")
                            InfoBadgeChip("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                            InfoBadgeChip(
                                if (runtimeStatus.readyArchLabels.isNotEmpty()) {
                                    "QEMU: ${runtimeStatus.readyArchLabels.joinToString(" · ")}"
                                } else {
                                    "Viewport: GLES3"
                                }
                            )
                            InfoBadgeChip(BuildConfig.APPLICATION_ID)
                        }
                    }
                }
            }

            // GitHub Update Checker Card
            item(span = { GridItemSpan(maxLineSpan) }) {
                AboutSectionCard(
                    title = "App Updates (GitHub Releases)",
                    icon = Icons.Rounded.SystemUpdate
                ) {
                    Text(
                        text = "Checks ${GitHubUpdateChecker.GITHUB_OWNER}/${GitHubUpdateChecker.GITHUB_REPO} on GitHub for the latest published release and compares it against v${BuildConfig.VERSION_NAME}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(14.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { triggerUpdateCheck() },
                            enabled = !isChecking,
                            modifier = Modifier.desktopHandCursor()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SystemUpdate,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (isChecking) "Checking GitHub..." else "Check for Updates")
                        }

                        OutlinedButton(
                            onClick = { openExternalUrl(GitHubUpdateChecker.RELEASES_URL) },
                            modifier = Modifier.desktopHandCursor()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("All Releases")
                        }
                    }

                    AnimatedContent(
                        targetState = updateState,
                        label = "UpdateCheckState"
                    ) { state ->
                        when (state) {
                            UpdateUiState.Idle -> Unit
                            UpdateUiState.Checking -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    LoadingIndicator(modifier = Modifier.size(32.dp))
                                    Text(
                                        text = "Contacting api.github.com for latest release...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            is UpdateUiState.Result -> {
                                Spacer(Modifier.height(16.dp))
                                when (val result = state.result) {
                                    is UpdateCheckResult.UpdateAvailable -> {
                                        val release = result.release
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = MaterialTheme.shapes.large,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.NewReleases,
                                                        contentDescription = null
                                                    )
                                                    Column {
                                                        Text(
                                                            text = "Update Available: v${release.versionName}",
                                                            style = MaterialTheme.typography.titleMedium
                                                        )
                                                        Text(
                                                            text = buildString {
                                                                append("Installed: v${result.currentVersion} -> Latest: ${release.tagName}")
                                                                release.publishedAt?.take(10)?.let { date ->
                                                                    append(" · $date")
                                                                }
                                                            },
                                                            style = MaterialTheme.typography.labelMedium
                                                        )
                                                    }
                                                }

                                                if (release.title.isNotBlank() && release.title != release.tagName) {
                                                    Text(
                                                        text = release.title,
                                                        style = MaterialTheme.typography.titleSmall
                                                    )
                                                }

                                                if (release.body.isNotBlank()) {
                                                    val previewNotes = remember(release.body) {
                                                        release.body.lines()
                                                            .take(10)
                                                            .joinToString("\n")
                                                            .take(650)
                                                            .let { if (it.length < release.body.length) "$it…" else it }
                                                    }
                                                    Surface(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        shape = MaterialTheme.shapes.medium,
                                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                                        contentColor = MaterialTheme.colorScheme.onSurface
                                                    ) {
                                                        Text(
                                                            text = previewNotes,
                                                            modifier = Modifier.padding(12.dp),
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }
                                                }

                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    if (release.apkDownloadUrl != null) {
                                                        val sizeLabel = release.apkSizeBytes?.let { formatBytes(it) }
                                                        Button(
                                                            onClick = { openExternalUrl(release.apkDownloadUrl) },
                                                            modifier = Modifier.desktopHandCursor()
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Download,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                            Spacer(Modifier.width(8.dp))
                                                            Text(
                                                                if (sizeLabel != null) {
                                                                    "Download APK ($sizeLabel)"
                                                                } else {
                                                                    "Download APK"
                                                                }
                                                            )
                                                        }
                                                    }
                                                    FilledTonalButton(
                                                        onClick = { openExternalUrl(release.htmlUrl) },
                                                        modifier = Modifier.desktopHandCursor()
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                        Text("Open Release Page")
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    is UpdateCheckResult.UpToDate -> {
                                        val release = result.release
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = MaterialTheme.shapes.large,
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.CheckCircle,
                                                        contentDescription = null
                                                    )
                                                    Column {
                                                        Text(
                                                            text = "You're up to date!",
                                                            style = MaterialTheme.typography.titleMedium
                                                        )
                                                        Text(
                                                            text = "Installed v${result.currentVersion} matches or exceeds latest GitHub release (${release.tagName}).",
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }
                                                }
                                                OutlinedButton(
                                                    onClick = { openExternalUrl(release.htmlUrl) },
                                                    modifier = Modifier.desktopHandCursor()
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("View ${release.tagName} on GitHub")
                                                }
                                            }
                                        }
                                    }

                                    is UpdateCheckResult.Error -> {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = MaterialTheme.shapes.large,
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(16.dp),
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ErrorOutline,
                                                    contentDescription = null
                                                )
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(
                                                        text = "Couldn't Check for Updates",
                                                        style = MaterialTheme.typography.titleSmall
                                                    )
                                                    Text(
                                                        text = result.message,
                                                        style = MaterialTheme.typography.bodySmall
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
            }

            // Links & Repository Card
            item {
                AboutSectionCard(
                    title = "Project & Community Links",
                    icon = Icons.Rounded.Code
                ) {
                    Text(
                        text = "QemuSDL is open source on GitHub. Track releases automatically with Obtainium or browse source, build scripts, and release notes directly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { openExternalUrl(GitHubUpdateChecker.REPO_URL) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .desktopHandCursor()
                        ) {
                            Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "github.com/${GitHubUpdateChecker.GITHUB_OWNER}/${GitHubUpdateChecker.GITHUB_REPO}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { openExternalUrl(GitHubUpdateChecker.OBTAINIUM_URL) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .desktopHandCursor()
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Track Automatic Updates in Obtainium")
                        }
                    }
                }
            }

            // Bundled Open-Source Stack Card
            item {
                AboutSectionCard(
                    title = "Bundled Open-Source Stack",
                    icon = Icons.Rounded.Info
                ) {
                    val stackItems = listOf(
                        "QEMU 9.2.0 SoftMMU" to "x86_64, AArch64, and PowerPC targets compiled as in-process Android shared libraries",
                        "SDL2 + OpenGL ES" to "Native Android SurfaceView rendering, touch/trackpad/stylus input, and OpenSL ES audio",
                        "VirtIO & SPICE Tools" to "qemu-vdagent bidirectional clipboard (spice-protocol), QEMU Guest Agent, VirtIO-9p, and WebDAV/HTTP sharing",
                        "Venus Vulkan 3D & USB" to "virglrenderer Venus 3D Vulkan passthrough and libusb-1.0 USB OTG host passthrough",
                        "Bundled Firmware ROMs" to "SeaBIOS, VGABIOS, iPXE, EDK2 AArch64 UEFI, OpenBIOS PPC, and Fedora signed virtio-win drivers"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        stackItems.forEachIndexed { index, (name, description) ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (index < stackItems.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoBadgeChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
