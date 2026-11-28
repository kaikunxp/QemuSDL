package com.qemusdl.app.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qemusdl.app.data.GuestArchitecture
import com.qemusdl.app.data.OsPreset
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.qemu.QemuCommandBuilder
import com.qemusdl.app.sdl.RuntimeProbeStatus
import com.qemusdl.app.ui.adaptive.AppFormFactor
import com.qemusdl.app.ui.adaptive.AppTooltipBox
import com.qemusdl.app.ui.adaptive.desktopHandCursor
import com.qemusdl.app.ui.adaptive.desktopTextCursor
import com.qemusdl.app.ui.adaptive.desktopWaitCursor
import com.qemusdl.app.ui.viewport.VmLauncherActivity
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun VmLibraryScreen(
    viewModel: VmLibraryViewModel,
    onCreateVm: () -> Unit,
    onEditVm: (String) -> Unit,
    onRunSetup: (String) -> Unit,
    onBootVm: (VmConfig) -> Unit,
    onOpenIsoDownloader: () -> Unit,
    onOpenRuntimeSetup: () -> Unit,
    formFactor: AppFormFactor = AppFormFactor.PHONE,
    selectedVmId: String? = null,
    onSelectVm: ((String) -> Unit)? = null
) {
    val vms by viewModel.vms.collectAsState()
    val disks by viewModel.disks.collectAsState()
    val runtimeStatus by viewModel.runtimeStatus.collectAsState()
    val isProbing by viewModel.isProbing.collectAsState()

    val isPhone = formFactor == AppFormFactor.PHONE
    val isDesktop = formFactor == AppFormFactor.DESKTOP

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState()

    var selectedArchFilterName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedArchFilter = selectedArchFilterName?.let { name ->
        GuestArchitecture.entries.firstOrNull { it.name == name }
    }
    val visibleVms = remember(vms, selectedArchFilter, isPhone) {
        if (isPhone || selectedArchFilter == null) vms
        else vms.filter { it.architecture == selectedArchFilter }
    }

    var cliPreviewVmId by rememberSaveable { mutableStateOf<String?>(null) }
    var intentInfoVmId by rememberSaveable { mutableStateOf<String?>(null) }
    var longPressedVmId by rememberSaveable { mutableStateOf<String?>(null) }
    val cliPreviewVm = cliPreviewVmId?.let { id -> vms.firstOrNull { it.id == id } }
    val intentInfoVm = intentInfoVmId?.let { id -> vms.firstOrNull { it.id == id } }
    val longPressedVm = longPressedVmId?.let { id -> vms.firstOrNull { it.id == id } }

    fun requestPinShortcut(vm: VmConfig) {
        val message = when (viewModel.pinVmShortcut(vm)) {
            ShortcutPinResult.REQUESTED -> "Shortcut request sent for ${vm.name}"
            ShortcutPinResult.UNSUPPORTED_LAUNCHER -> "Current launcher does not support pinned shortcuts"
            ShortcutPinResult.FAILED -> "Could not create shortcut for ${vm.name}"
        }
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .desktopWaitCursor(isProbing)
            .then(if (isPhone) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isPhone) {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "QemuSDL",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(
                                text = "x86_64, AArch64 & PowerPC Virtualization · Native SDL2 Viewport",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        AppTooltipBox(tooltip = "Download ISOs") {
                            FilledTonalIconButton(
                                onClick = onOpenIsoDownloader,
                                modifier = Modifier.desktopHandCursor()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDownload,
                                    contentDescription = "Download ISOs"
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        AppTooltipBox(tooltip = "Runtime and virtual disks") {
                            FilledTonalIconButton(
                                onClick = onOpenRuntimeSetup,
                                modifier = Modifier.desktopHandCursor()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Storage,
                                    contentDescription = "Runtime and virtual disks"
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    },
                    scrollBehavior = scrollBehavior
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Virtual Machines (${visibleVms.size})",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = if (isDesktop) {
                                    "Click a VM to inspect · Ctrl+N for new VM"
                                } else {
                                    "x86_64, AArch64 & PowerPC · Native SDL2"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        AppTooltipBox(tooltip = "Refresh native runtime (Ctrl+R)") {
                            IconButton(
                                onClick = { viewModel.refreshRuntime() },
                                modifier = Modifier.desktopHandCursor()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Refresh runtime"
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = if (isDesktop) 310.dp else 340.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = if (isPhone) 112.dp else 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!isDesktop) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RuntimeHeroCard(
                            status = runtimeStatus,
                            diskCount = disks.size,
                            isProbing = isProbing,
                            onOpenSetup = onOpenRuntimeSetup,
                            onRefresh = { viewModel.refreshRuntime() }
                        )
                    }
                }

                if (!isPhone) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = selectedArchFilter == null,
                                onClick = { selectedArchFilterName = null },
                                label = { Text("All (${vms.size})") },
                                modifier = Modifier.desktopHandCursor()
                            )
                            GuestArchitecture.entries.forEach { arch ->
                                val count = vms.count { it.architecture == arch }
                                FilterChip(
                                    selected = selectedArchFilter == arch,
                                    onClick = { selectedArchFilterName = arch.name },
                                    label = { Text("${arch.badgeText} ($count)") },
                                    modifier = Modifier.desktopHandCursor()
                                )
                            }
                        }
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Virtual Machines (${vms.size})",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "Zero-VNC · Venus Vulkan 3D",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (visibleVms.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyVmLibraryCard(onCreateVm = onCreateVm)
                    }
                } else {
                    items(visibleVms, key = { it.id }) { vm ->
                        ExpressiveVmCard(
                            vm = vm,
                            isSelected = !isPhone && selectedVmId == vm.id,
                            onCardClick = {
                                if (onSelectVm != null) {
                                    onSelectVm(vm.id)
                                } else if (vm.needsFirstTimeSetup) {
                                    onRunSetup(vm.id)
                                } else {
                                    onBootVm(vm)
                                }
                            },
                            onPrimaryAction = {
                                onSelectVm?.invoke(vm.id)
                                if (vm.needsFirstTimeSetup) onRunSetup(vm.id) else onBootVm(vm)
                            },
                            onLongPress = { longPressedVmId = vm.id },
                            onForceBoot = {
                                onSelectVm?.invoke(vm.id)
                                onBootVm(vm)
                            },
                            onRunSetup = {
                                onSelectVm?.invoke(vm.id)
                                onRunSetup(vm.id)
                            },
                            onAddToHomeScreen = { requestPinShortcut(vm) },
                            onShowLaunchIntent = { intentInfoVmId = vm.id },
                            onEdit = {
                                onSelectVm?.invoke(vm.id)
                                onEditVm(vm.id)
                            },
                            onClone = {
                                viewModel.cloneVm(vm.id)
                                scope.launch { snackbarHostState.showSnackbar("Cloned ${vm.name}") }
                            },
                            onShowCli = { cliPreviewVmId = vm.id },
                            onDelete = {
                                viewModel.deleteVm(vm.id)
                                scope.launch { snackbarHostState.showSnackbar("Deleted ${vm.name}") }
                            }
                        )
                    }
                }
            }

            if (isPhone) {
                // Expressive bottom floating toolbar + integrated vibrant FAB (Phone UI)
                HorizontalFloatingToolbar(
                    expanded = true,
                    floatingActionButton = {
                        AppTooltipBox(tooltip = "New VM") {
                            FloatingToolbarDefaults.VibrantFloatingActionButton(
                                onClick = onCreateVm,
                                modifier = Modifier.desktopHandCursor()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "New VM"
                                )
                            }
                        }
                    },
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                ) {
                    AppTooltipBox(tooltip = "ISO Downloader") {
                        IconButton(onClick = onOpenIsoDownloader, modifier = Modifier.desktopHandCursor()) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = "ISO Downloader"
                            )
                        }
                    }
                    AppTooltipBox(tooltip = "Disks & Native Runtime") {
                        IconButton(onClick = onOpenRuntimeSetup, modifier = Modifier.desktopHandCursor()) {
                            Icon(
                                imageVector = Icons.Rounded.Storage,
                                contentDescription = "Disks & Native Runtime"
                            )
                        }
                    }
                    AppTooltipBox(tooltip = "Re-probe native SDL2/QEMU libs") {
                        IconButton(onClick = { viewModel.refreshRuntime() }, modifier = Modifier.desktopHandCursor()) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Re-probe native SDL2/QEMU libs"
                            )
                        }
                    }
                    AppTooltipBox(tooltip = "Quick Launch Top VM") {
                        IconButton(
                            onClick = {
                                val first = vms.firstOrNull()
                                when {
                                    first == null -> onCreateVm()
                                    first.needsFirstTimeSetup -> onRunSetup(first.id)
                                    else -> onBootVm(first)
                                }
                            },
                            modifier = Modifier.desktopHandCursor()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Quick Launch Top VM"
                            )
                        }
                    }
                }
            }
        }
    }

    cliPreviewVm?.let { vm ->
        val cli = remember(vm) { QemuCommandBuilder.formatCommandLine(vm) }
        AlertDialog(
            onDismissRequest = { cliPreviewVmId = null },
            icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
            title = { Text("${vm.name} CLI") },
            text = {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    SelectionContainer(modifier = Modifier.desktopTextCursor()) {
                        Text(
                            text = cli,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("qemu-cli", cli))
                        cliPreviewVmId = null
                        scope.launch { snackbarHostState.showSnackbar("Copied ${vm.architecture.qemuBinaryName} command") }
                    }
                ) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { cliPreviewVmId = null }) {
                    Text("Close")
                }
            }
        )
    }

    longPressedVm?.let { vm ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { longPressedVmId = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(
                        text = vm.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${vm.architecture.badgeText} · ${vm.machineType.qemuFlag.uppercase()} · ${vm.memoryMb} MB RAM",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()

                ListItem(
                    headlineContent = { Text("Add to Home Screen") },
                    supportingContent = { Text("Create a launcher shortcut to directly boot this VM") },
                    leadingContent = {
                        Icon(
                            Icons.AutoMirrored.Rounded.AddToHomeScreen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.desktopHandCursor().clickable {
                        longPressedVmId = null
                        requestPinShortcut(vm)
                    }
                )
                ListItem(
                    headlineContent = { Text("Launch Intent & URI") },
                    supportingContent = { Text("View or copy external shortcut / automation Intent") },
                    leadingContent = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.desktopHandCursor().clickable {
                        longPressedVmId = null
                        intentInfoVmId = vm.id
                    }
                )
                ListItem(
                    headlineContent = { Text(if (vm.needsFirstTimeSetup) "Boot SDL2 Anyway" else "Boot SDL2") },
                    leadingContent = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.desktopHandCursor().clickable {
                        longPressedVmId = null
                        onBootVm(vm)
                    }
                )
                ListItem(
                    headlineContent = { Text("Configure Hardware") },
                    leadingContent = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.desktopHandCursor().clickable {
                        longPressedVmId = null
                        onEditVm(vm.id)
                    }
                )
                ListItem(
                    headlineContent = { Text("Inspect QEMU CLI") },
                    leadingContent = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.desktopHandCursor().clickable {
                        longPressedVmId = null
                        cliPreviewVmId = vm.id
                    }
                )
                ListItem(
                    headlineContent = { Text("Clone Profile") },
                    leadingContent = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.desktopHandCursor().clickable {
                        longPressedVmId = null
                        viewModel.cloneVm(vm.id)
                        scope.launch { snackbarHostState.showSnackbar("Cloned ${vm.name}") }
                    }
                )
                ListItem(
                    headlineContent = {
                        Text("Delete VM", color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.desktopHandCursor().clickable {
                        longPressedVmId = null
                        viewModel.deleteVm(vm.id)
                        scope.launch { snackbarHostState.showSnackbar("Deleted ${vm.name}") }
                    }
                )
            }
        }
    }

    intentInfoVm?.let { vm ->
        val deepLinkUri = remember(vm.id) { VmLauncherActivity.formatLaunchUriString(vm) }
        val adbCmd = remember(vm.id) { VmLauncherActivity.formatAdbLaunchCommand(vm) }
        AlertDialog(
            onDismissRequest = { intentInfoVmId = null },
            icon = { Icon(Icons.Rounded.Link, contentDescription = null) },
            title = { Text("Launch Intent · ${vm.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Use this exported URI or Intent in custom launchers, Tasker, or ADB to boot this VM directly:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        SelectionContainer(modifier = Modifier.desktopTextCursor()) {
                            Text(
                                text = buildString {
                                    appendLine("URI:")
                                    appendLine(deepLinkUri)
                                    appendLine()
                                    appendLine("Action: ${VmLauncherActivity.ACTION_LAUNCH_VM}")
                                    appendLine("Extra: ${VmLauncherActivity.EXTRA_VM_ID}=${vm.id}")
                                    appendLine()
                                    appendLine("ADB:")
                                    append(adbCmd)
                                },
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("qemusdl-vm-uri", deepLinkUri))
                        intentInfoVmId = null
                        scope.launch { snackbarHostState.showSnackbar("Copied launch URI") }
                    }
                ) {
                    Text("Copy URI")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            intentInfoVmId = null
                            requestPinShortcut(vm)
                        }
                    ) {
                        Text("Pin Shortcut")
                    }
                    TextButton(onClick = { intentInfoVmId = null }) {
                        Text("Close")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RuntimeHeroCard(
    status: RuntimeProbeStatus,
    diskCount: Int,
    isProbing: Boolean,
    onOpenSetup: () -> Unit,
    onRefresh: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    val containerColor = if (status.isFullQemuReady) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = if (status.isFullQemuReady) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    val titleText = when {
        status.isFullQemuReady && status.readyArchLabels.isNotEmpty() ->
            "SDL2 + QEMU (${status.readyArchLabels.joinToString(" · ")}) Ready"
        else -> "SDL2 GLES3 Viewport Active"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = onContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(onContainer.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isProbing) {
                            LoadingIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(
                                imageVector = if (status.isFullQemuReady) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.DeveloperBoard
                                },
                                contentDescription = null
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "ABI: ${status.abi} · $diskCount local disk(s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = onContainer.copy(alpha = 0.8f)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh status")
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) {
                                Icons.Rounded.KeyboardArrowUp
                            } else {
                                Icons.Rounded.KeyboardArrowDown
                            },
                            contentDescription = if (expanded) "Collapse runtime details" else "Expand runtime details"
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        text = status.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.9f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = onOpenSetup) {
                            Icon(
                                imageVector = Icons.Rounded.SettingsApplications,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Runtime & Disks")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
private fun ExpressiveVmCard(
    vm: VmConfig,
    isSelected: Boolean,
    onCardClick: () -> Unit,
    onPrimaryAction: () -> Unit,
    onLongPress: () -> Unit,
    onForceBoot: () -> Unit,
    onRunSetup: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    onShowLaunchIntent: () -> Unit,
    onEdit: () -> Unit,
    onClone: () -> Unit,
    onShowCli: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val badgeContainer = when (vm.osPreset) {
        OsPreset.LINUX_ALPINE, OsPreset.LINUX_ALPINE_AARCH64 -> MaterialTheme.colorScheme.secondaryContainer
        OsPreset.LINUX_DEBIAN, OsPreset.LINUX_DEBIAN_AARCH64 -> MaterialTheme.colorScheme.primaryContainer
        OsPreset.WINDOWS, OsPreset.WINDOWS_AARCH64, OsPreset.MACOS_9, OsPreset.MACOS_X_PPC -> MaterialTheme.colorScheme.tertiaryContainer
        OsPreset.FREEDOS -> MaterialTheme.colorScheme.surfaceContainerHighest
        OsPreset.CUSTOM, OsPreset.CUSTOM_AARCH64, OsPreset.CUSTOM_PPC -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val badgeContent = when (vm.osPreset) {
        OsPreset.LINUX_ALPINE, OsPreset.LINUX_ALPINE_AARCH64 -> MaterialTheme.colorScheme.onSecondaryContainer
        OsPreset.LINUX_DEBIAN, OsPreset.LINUX_DEBIAN_AARCH64 -> MaterialTheme.colorScheme.onPrimaryContainer
        OsPreset.WINDOWS, OsPreset.WINDOWS_AARCH64, OsPreset.MACOS_9, OsPreset.MACOS_X_PPC -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .desktopHandCursor()
            .combinedClickable(
                onClick = onCardClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            ),
        shape = MaterialTheme.shapes.large,
        color = if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        tonalElevation = if (isSelected) 3.dp else 1.dp,
        shadowElevation = if (isSelected) 2.dp else 1.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(100),
                        color = badgeContainer,
                        contentColor = badgeContent
                    ) {
                        Text(
                            text = vm.osPreset.badgeText,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (vm.needsFirstTimeSetup) {
                        Surface(
                            shape = RoundedCornerShape(100),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ) {
                            Text(
                                text = "SETUP",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                Text(
                    text = "${vm.architecture.badgeText} · ${vm.machineType.qemuFlag.uppercase()} · ${vm.resolution.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = vm.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SpecPill(text = "${vm.cpuCores} vCPU (${vm.cpuModel})")
                SpecPill(text = "${vm.memoryMb} MB RAM")
                SpecPill(text = vm.displayDevice.label)
                SpecPill(text = if (vm.sdlGlAcceleration) "SDL2 GLES" else "SDL2 Soft")
            }

            AnimatedVisibility(
                visible = vm.diskDisplayName.isNotBlank() || vm.isoDisplayName.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    if (vm.diskDisplayName.isNotBlank()) {
                        Text(
                            text = "Disk: ${vm.diskDisplayName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (vm.isoDisplayName.isNotBlank()) {
                        Text(
                            text = "ISO: ${vm.isoDisplayName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    SplitButtonLayout(
                        leadingButton = {
                            SplitButtonDefaults.LeadingButton(onClick = onPrimaryAction) {
                                Icon(
                                    imageVector = if (vm.needsFirstTimeSetup) {
                                        Icons.Rounded.AutoAwesome
                                    } else {
                                        Icons.Rounded.PlayArrow
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (vm.needsFirstTimeSetup) "Run Setup" else "Boot SDL2")
                            }
                        },
                        trailingButton = {
                            SplitButtonDefaults.TrailingButton(
                                checked = menuExpanded,
                                onCheckedChange = { menuExpanded = it }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "More VM actions"
                                )
                            }
                        }
                    )

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (vm.needsFirstTimeSetup) {
                            DropdownMenuItem(
                                text = { Text("Boot SDL2 Anyway") },
                                leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onForceBoot()
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Re-run Setup Wizard") },
                                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRunSetup()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Add to Home Screen") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.AddToHomeScreen, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onAddToHomeScreen()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Launch Intent & URI") },
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onShowLaunchIntent()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Configure Hardware") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Inspect QEMU CLI") },
                            leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onShowCli()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clone Profile") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onClone()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete VM") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }

                FilledTonalIconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "Edit ${vm.name}"
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecPill(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyVmLibraryCard(onCreateVm: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.Computer,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No x86_64 VMs Configured",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Create your first x86_64 virtual machine with a direct SDL2 OpenGL ES viewport.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onCreateVm) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create x86_64 VM")
            }
        }
    }
}
