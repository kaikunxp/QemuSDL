package com.qemusdl.app.ui.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.qemusdl.app.ui.adaptive.AppTooltipBox
import com.qemusdl.app.ui.adaptive.desktopHandCursor
import com.qemusdl.app.ui.adaptive.desktopTextCursor
import com.qemusdl.app.ui.adaptive.desktopWaitCursor
import com.qemusdl.app.ui.editor.CreateDiskDialog
import com.qemusdl.app.ui.library.VmLibraryViewModel
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun RuntimeSetupScreen(
    viewModel: VmLibraryViewModel,
    onBack: () -> Unit,
    showBackButton: Boolean = true
) {
    val status by viewModel.runtimeStatus.collectAsState()
    val disks by viewModel.disks.collectAsState()
    val isProbing by viewModel.isProbing.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val gridState = rememberLazyGridState()
    var showCreateDiskDialog by rememberSaveable { mutableStateOf(false) }

    val soPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importNativeLibrary(uri) { msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
            }
        }
    }

    val buildScriptCmd = "./scripts/build-sdl2-qemu-android.sh --abi=${status.abi}"

    Scaffold(
        modifier = Modifier.desktopWaitCursor(isProbing),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Native SDL2 Runtime & Disks") },
                navigationIcon = {
                    if (showBackButton) {
                        AppTooltipBox(tooltip = "Back") {
                            IconButton(onClick = onBack, modifier = Modifier.desktopHandCursor()) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                            }
                        }
                    }
                },
                actions = {
                    AppTooltipBox(tooltip = "Re-probe native libs (Ctrl+R)") {
                        IconButton(
                            onClick = { viewModel.refreshRuntime() },
                            modifier = Modifier.desktopHandCursor()
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Re-probe native libs")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 400.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Native Libraries Checklist
            item {
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
                            Text("Native Engine Components", style = MaterialTheme.typography.titleLarge)
                            if (isProbing) {
                                LoadingIndicator(modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Detected Device ABI: ${status.abi}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(14.dp))

                        NativeCheckRow(
                            name = "libqemusdl_bridge.so",
                            subtitle = "NDK C++20 JNI bridge & OpenGL ES 3.0 SDL2 viewport",
                            present = status.bridgeLoaded
                        )
                        NativeCheckRow(
                            name = "libSDL2.so",
                            subtitle = status.sdl2Path.ifBlank { "SDL2 Android video/input/audio shared library" },
                            present = status.sdl2Loaded
                        )
                        NativeCheckRow(
                            name = "libqemu-system-x86_64.so",
                            subtitle = status.qemuPath.ifBlank { "In-process QEMU x86_64 softmmu (-display sdl, Venus 3D)" },
                            present = status.qemuLoaded && status.hasQemuEntry
                        )
                        NativeCheckRow(
                            name = "libqemu-system-aarch64.so",
                            subtitle = status.qemuAarch64Path.ifBlank { "In-process QEMU AArch64 softmmu (-machine virt, EDK2 UEFI, Venus 3D)" },
                            present = status.qemuAarch64Loaded && status.hasQemuEntry
                        )
                        NativeCheckRow(
                            name = "libqemu-system-ppc.so",
                            subtitle = status.qemuPpcPath.ifBlank { "In-process QEMU PowerPC softmmu (-machine mac99, OpenBIOS, Mac OS 9)" },
                            present = status.qemuPpcLoaded && status.hasQemuEntry
                        )
                        NativeCheckRow(
                            name = "libusb-1.0.so",
                            subtitle = if (status.hasLibusb) "Android USB OTG host passthrough ready (/proc/self/fd)" else "USB host passthrough library",
                            present = status.hasLibusb
                        )

                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(onClick = { soPicker.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Import .so Binary")
                            }
                        }
                    }
                }
            }

            // Cross-Compile Script Instructions
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Build Full QEMU x86_64 + SDL2", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Run the included cross-compilation script on macOS/Linux to compile SDL2 and qemu-system-x86_64 as Android shared libraries and place them into app/src/main/jniLibs/${status.abi}/:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SelectionContainer(modifier = Modifier.weight(1f).desktopTextCursor()) {
                                    Text(
                                        text = buildScriptCmd,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                AppTooltipBox(tooltip = "Copy build command") {
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("build-cmd", buildScriptCmd))
                                            scope.launch { snackbarHostState.showSnackbar("Copied build command") }
                                        },
                                        modifier = Modifier.desktopHandCursor()
                                    ) {
                                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy command")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Local Virtual Disk Manager
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Local Virtual Disks (${disks.size})", style = MaterialTheme.typography.titleLarge)
                    FilledTonalButton(
                        onClick = { showCreateDiskDialog = true },
                        modifier = Modifier.desktopHandCursor()
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New Disk")
                    }
                }
            }

            if (disks.isEmpty()) {
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
                                Icons.Rounded.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("No local virtual disks yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Create a sparse QCOW2 v3 disk right on-device without external tools.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(disks, key = { it.path }) { disk ->
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(disk.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${disk.format} · Virtual: ${disk.virtualSizeMb ?: "?"} MB · On disk: ${disk.allocatedBytes / 1024} KiB",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    viewModel.deleteDisk(disk.path)
                                    scope.launch { snackbarHostState.showSnackbar("Removed ${disk.name}") }
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Delete disk",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDiskDialog) {
        CreateDiskDialog(
            onDismiss = { showCreateDiskDialog = false },
            onCreate = { name, sizeMb, format ->
                viewModel.createDisk(name, sizeMb, format) { created ->
                    scope.launch {
                        snackbarHostState.showSnackbar("Created ${created.name} ($sizeMb MB ${format.name})")
                    }
                }
                showCreateDiskDialog = false
            }
        )
    }
}

@Composable
private fun NativeCheckRow(
    name: String,
    subtitle: String,
    present: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (present) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
            contentDescription = null,
            tint = if (present) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
