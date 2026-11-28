package com.qemusdl.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.sdl.RuntimeProbeStatus
import com.qemusdl.app.ui.about.AboutScreen
import com.qemusdl.app.ui.adaptive.AppTooltipBox
import com.qemusdl.app.ui.adaptive.desktopHandCursor
import com.qemusdl.app.ui.adaptive.rememberAppAdaptiveInfo
import com.qemusdl.app.ui.editor.VmEditorScreen
import com.qemusdl.app.ui.isos.IsoDownloaderScreen
import com.qemusdl.app.ui.library.VmDetailInspectorPane
import com.qemusdl.app.ui.library.VmLibraryScreen
import com.qemusdl.app.ui.library.VmLibraryViewModel
import com.qemusdl.app.ui.runtime.RuntimeSetupScreen
import com.qemusdl.app.ui.setup.VmSetupScreen
import com.qemusdl.app.ui.viewport.QemuSdlActivity
import com.qemusdl.app.ui.viewport.VmViewportActivity

private enum class TopLevelSection {
    VMS,
    ISOS,
    RUNTIME,
    ABOUT
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MainNavigation(
    viewModel: VmLibraryViewModel = viewModel()
) {
    val context = LocalContext.current
    val vms by viewModel.vms.collectAsState()
    val disks by viewModel.disks.collectAsState()
    val runtimeStatus by viewModel.runtimeStatus.collectAsState()
    val downloadStates by viewModel.isoDownloadStates.collectAsState()
    val activeDownloadCount = downloadStates.values.count { it.isActiveDownload }

    val adaptiveInfo = rememberAppAdaptiveInfo()
    val backStack = rememberNavBackStack(VmLibraryKey)
    var selectedVmId by rememberSaveable { mutableStateOf<String?>(null) }

    val effectiveSelectedVm = remember(vms, selectedVmId) {
        vms.firstOrNull { it.id == selectedVmId } ?: vms.firstOrNull()
    }

    // Use a full Desktop Sidebar on wide GooglebookOS/Desktop windows (>= 900dp),
    // and a compact Navigation Rail on unfolded foldables, tablets, or narrow desktop windows (600..899dp).
    val showDesktopSidebar = adaptiveInfo.isDesktop && adaptiveInfo.windowWidthDp >= 900
    val showNavigationRail = !adaptiveInfo.isPhone && !showDesktopSidebar

    // Compute available width inside the NavDisplay content pane to decide 2-pane ListDetail split cleanly
    val navChromeWidthDp = when {
        showDesktopSidebar -> 252
        showNavigationRail -> 80
        else -> 0
    }
    val contentWidthDp = (adaptiveInfo.windowWidthDp - navChromeWidthDp).coerceAtLeast(0)
    val useTwoPaneListDetail = !adaptiveInfo.isPhone && contentWidthDp >= 640

    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val hasVerticalHinge = remember(windowAdaptiveInfo) {
        windowAdaptiveInfo.windowPosture.hingeList.any { hinge ->
            hinge.isVertical && (hinge.isSeparating || !hinge.isFlat)
        }
    }
    val paneDirective = remember(windowAdaptiveInfo, useTwoPaneListDetail, hasVerticalHinge) {
        val base = calculatePaneScaffoldDirective(windowAdaptiveInfo)
        val partitionSpacer = when {
            !useTwoPaneListDetail -> 0.dp
            hasVerticalHinge -> maxOf(base.horizontalPartitionSpacerSize, 20.dp)
            else -> base.horizontalPartitionSpacerSize.coerceIn(1.dp, 12.dp)
        }
        base.copy(
            maxHorizontalPartitions = if (useTwoPaneListDetail) 2 else 1,
            horizontalPartitionSpacerSize = partitionSpacer
        )
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = paneDirective)
    val singlePaneStrategy = remember { SinglePaneSceneStrategy<NavKey>() }

    val currentTopKey = backStack.lastOrNull()
    val activeSection = when (currentTopKey) {
        is IsoDownloaderKey -> TopLevelSection.ISOS
        is RuntimeSetupKey -> TopLevelSection.RUNTIME
        is AboutKey -> TopLevelSection.ABOUT
        else -> TopLevelSection.VMS
    }

    fun launchVm(vm: VmConfig, launchAdjacent: Boolean = false) {
        val intent = if (runtimeStatus.isReadyForArch(vm.architecture)) {
            QemuSdlActivity.createIntent(context, vm, launchAdjacent = launchAdjacent)
        } else {
            VmViewportActivity.createIntent(context, vm.id).apply {
                if (launchAdjacent) {
                    addFlags(
                        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
            }
        }
        context.startActivity(intent)
    }

    fun popBackSafely() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    fun navigateTopLevel(section: TopLevelSection) {
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
        when (section) {
            TopLevelSection.VMS -> Unit // Root is already VmLibraryKey
            TopLevelSection.ISOS -> backStack.add(IsoDownloaderKey(targetVmId = null))
            TopLevelSection.RUNTIME -> backStack.add(RuntimeSetupKey)
            TopLevelSection.ABOUT -> backStack.add(AboutKey)
        }
    }

    fun openDetailKey(detailKey: NavKey) {
        if (useTwoPaneListDetail) {
            // Keep VmLibraryKey as the list pane root and replace any existing detail pane cleanly
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
        }
        backStack.add(detailKey)
    }

    val navContent: @Composable (Modifier) -> Unit = { contentModifier ->
        NavDisplay(
            backStack = backStack,
            onBack = { popBackSafely() },
            sceneStrategy = if (adaptiveInfo.isPhone) singlePaneStrategy else listDetailStrategy,
            modifier = contentModifier,
            entryProvider = entryProvider {
                entry<VmLibraryKey>(
                    metadata = if (adaptiveInfo.isPhone) {
                        emptyMap()
                    } else {
                        ListDetailSceneStrategy.listPane(
                            detailPlaceholder = {
                                VmDetailInspectorPane(
                                    vm = effectiveSelectedVm,
                                    formFactor = adaptiveInfo.formFactor,
                                    onCreateVm = { openDetailKey(VmEditorKey(vmId = null)) },
                                    onBootVm = { vm -> launchVm(vm) },
                                    onBootSideBySide = { vm -> launchVm(vm, launchAdjacent = true) },
                                    onEditVm = { vmId ->
                                        selectedVmId = vmId
                                        openDetailKey(VmEditorKey(vmId = vmId))
                                    },
                                    onRunSetup = { vmId ->
                                        selectedVmId = vmId
                                        openDetailKey(VmSetupKey(vmId = vmId))
                                    },
                                    onCloneVm = { vmId -> viewModel.cloneVm(vmId) },
                                    onDeleteVm = { vmId -> viewModel.deleteVm(vmId) },
                                    onPinShortcut = { vm -> viewModel.pinVmShortcut(vm) }
                                )
                            }
                        )
                    }
                ) {
                    VmLibraryScreen(
                        viewModel = viewModel,
                        formFactor = adaptiveInfo.formFactor,
                        selectedVmId = effectiveSelectedVm?.id,
                        onSelectVm = if (adaptiveInfo.isPhone) null else { id -> selectedVmId = id },
                        onCreateVm = { openDetailKey(VmEditorKey(vmId = null)) },
                        onEditVm = { vmId ->
                            selectedVmId = vmId
                            openDetailKey(VmEditorKey(vmId = vmId))
                        },
                        onRunSetup = { vmId ->
                            selectedVmId = vmId
                            openDetailKey(VmSetupKey(vmId = vmId))
                        },
                        onBootVm = { vm -> launchVm(vm) },
                        onBootVmSideBySide = if (adaptiveInfo.isPhone) null else { vm ->
                            launchVm(vm, launchAdjacent = true)
                        },
                        onOpenIsoDownloader = {
                            if (adaptiveInfo.isPhone) {
                                backStack.add(IsoDownloaderKey(targetVmId = null))
                            } else {
                                navigateTopLevel(TopLevelSection.ISOS)
                            }
                        },
                        onOpenRuntimeSetup = {
                            if (adaptiveInfo.isPhone) {
                                backStack.add(RuntimeSetupKey)
                            } else {
                                navigateTopLevel(TopLevelSection.RUNTIME)
                            }
                        },
                        onOpenAbout = {
                            if (adaptiveInfo.isPhone) {
                                backStack.add(AboutKey)
                            } else {
                                navigateTopLevel(TopLevelSection.ABOUT)
                            }
                        }
                    )
                }

                entry<VmSetupKey>(
                    metadata = if (adaptiveInfo.isPhone) emptyMap() else ListDetailSceneStrategy.detailPane()
                ) { key ->
                    VmSetupScreen(
                        vmId = key.vmId,
                        viewModel = viewModel,
                        showBackButton = !useTwoPaneListDetail,
                        onBack = { popBackSafely() },
                        onOpenIsoCatalog = { backStack.add(IsoDownloaderKey(targetVmId = key.vmId)) },
                        onCompletedAndBoot = { configuredVm ->
                            selectedVmId = configuredVm.id
                            popBackSafely()
                            launchVm(configuredVm)
                        }
                    )
                }

                entry<IsoDownloaderKey> { key ->
                    IsoDownloaderScreen(
                        viewModel = viewModel,
                        targetVmId = key.targetVmId,
                        showBackButton = adaptiveInfo.isPhone || key.targetVmId != null,
                        onBack = { popBackSafely() },
                        onIsoSelectedForVm = { _, _ ->
                            popBackSafely()
                        }
                    )
                }

                entry<VmEditorKey>(
                    metadata = if (adaptiveInfo.isPhone) emptyMap() else ListDetailSceneStrategy.detailPane()
                ) { key ->
                    VmEditorScreen(
                        vmId = key.vmId,
                        viewModel = viewModel,
                        showBackButton = !useTwoPaneListDetail,
                        onBack = { popBackSafely() },
                        onOpenIsoDownloader = { targetId ->
                            backStack.add(IsoDownloaderKey(targetVmId = targetId))
                        },
                        onSavedAndBoot = { saved ->
                            selectedVmId = saved.id
                            popBackSafely()
                            launchVm(saved)
                        }
                    )
                }

                entry<RuntimeSetupKey> {
                    RuntimeSetupScreen(
                        viewModel = viewModel,
                        showBackButton = adaptiveInfo.isPhone,
                        onBack = { popBackSafely() }
                    )
                }

                entry<AboutKey> {
                    AboutScreen(
                        viewModel = viewModel,
                        showBackButton = adaptiveInfo.isPhone,
                        onBack = { popBackSafely() }
                    )
                }
            }
        )
    }

    // Root desktop keyboard shortcut handler (matches MainActivity.onProvideKeyboardShortcuts 1:1)
    val keyboardShortcutModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0) {
            return@onPreviewKeyEvent false
        }
        when {
            event.isCtrlPressed && event.key == Key.One -> {
                navigateTopLevel(TopLevelSection.VMS)
                true
            }
            event.isCtrlPressed && event.key == Key.Two -> {
                navigateTopLevel(TopLevelSection.ISOS)
                true
            }
            event.isCtrlPressed && event.key == Key.Three -> {
                navigateTopLevel(TopLevelSection.RUNTIME)
                true
            }
            event.isCtrlPressed && event.key == Key.Four -> {
                navigateTopLevel(TopLevelSection.ABOUT)
                true
            }
            event.isCtrlPressed && event.key == Key.N -> {
                openDetailKey(VmEditorKey(vmId = null))
                true
            }
            (event.isCtrlPressed && event.key == Key.R) || event.key == Key.F5 -> {
                viewModel.refreshRuntime()
                true
            }
            event.key == Key.Escape && !adaptiveInfo.isPhone &&
                (currentTopKey is VmEditorKey || currentTopKey is VmSetupKey) -> {
                popBackSafely()
                true
            }
            else -> false
        }
    }

    if (adaptiveInfo.isPhone) {
        // Preserve exact existing Phone UI
        Box(modifier = Modifier.fillMaxSize().then(keyboardShortcutModifier)) {
            navContent(Modifier.fillMaxSize())
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .then(keyboardShortcutModifier)
        ) {
            if (showDesktopSidebar) {
                DesktopWorkspaceSidebar(
                    activeSection = activeSection,
                    vmCount = vms.size,
                    diskCount = disks.size,
                    activeDownloadCount = activeDownloadCount,
                    runtimeStatus = runtimeStatus,
                    onSelectSection = { navigateTopLevel(it) },
                    onCreateVm = { openDetailKey(VmEditorKey(vmId = null)) },
                    onRefreshRuntime = { viewModel.refreshRuntime() }
                )
                VerticalDivider()
            } else if (showNavigationRail) {
                TabletNavigationRail(
                    activeSection = activeSection,
                    activeDownloadCount = activeDownloadCount,
                    onSelectSection = { navigateTopLevel(it) },
                    onCreateVm = { openDetailKey(VmEditorKey(vmId = null)) },
                    onRefreshRuntime = { viewModel.refreshRuntime() }
                )
                VerticalDivider()
            }

            navContent(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun TabletNavigationRail(
    activeSection: TopLevelSection,
    activeDownloadCount: Int,
    onSelectSection: (TopLevelSection) -> Unit,
    onCreateVm: () -> Unit,
    onRefreshRuntime: () -> Unit
) {
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .safeDrawingPadding(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        header = {
            AppTooltipBox(tooltip = "New Virtual Machine (Ctrl+N)") {
                FloatingActionButton(
                    onClick = onCreateVm,
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 12.dp)
                        .desktopHandCursor()
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "New VM")
                }
            }
        }
    ) {
        NavigationRailItem(
            selected = activeSection == TopLevelSection.VMS,
            onClick = { onSelectSection(TopLevelSection.VMS) },
            icon = { Icon(Icons.Rounded.Computer, contentDescription = "Virtual Machines") },
            label = { Text("VMs") },
            modifier = Modifier.desktopHandCursor()
        )
        NavigationRailItem(
            selected = activeSection == TopLevelSection.ISOS,
            onClick = { onSelectSection(TopLevelSection.ISOS) },
            icon = {
                BadgedBox(
                    badge = {
                        if (activeDownloadCount > 0) {
                            Badge { Text("$activeDownloadCount") }
                        }
                    }
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = "OS & ISO Catalog")
                }
            },
            label = { Text("ISOs") },
            modifier = Modifier.desktopHandCursor()
        )
        NavigationRailItem(
            selected = activeSection == TopLevelSection.RUNTIME,
            onClick = { onSelectSection(TopLevelSection.RUNTIME) },
            icon = { Icon(Icons.Rounded.Storage, contentDescription = "Runtime & Disks") },
            label = { Text("Runtime") },
            modifier = Modifier.desktopHandCursor()
        )
        NavigationRailItem(
            selected = activeSection == TopLevelSection.ABOUT,
            onClick = { onSelectSection(TopLevelSection.ABOUT) },
            icon = { Icon(Icons.Rounded.Info, contentDescription = "About & Updates") },
            label = { Text("About") },
            modifier = Modifier.desktopHandCursor()
        )

        Spacer(Modifier.weight(1f))

        AppTooltipBox(tooltip = "Refresh native runtime (Ctrl+R)") {
            IconButton(
                onClick = onRefreshRuntime,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .desktopHandCursor()
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh runtime")
            }
        }
    }
}

@Composable
private fun DesktopWorkspaceSidebar(
    activeSection: TopLevelSection,
    vmCount: Int,
    diskCount: Int,
    activeDownloadCount: Int,
    runtimeStatus: RuntimeProbeStatus,
    onSelectSection: (TopLevelSection) -> Unit,
    onCreateVm: () -> Unit,
    onRefreshRuntime: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(252.dp)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Desktop Brand Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeveloperBoard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "QemuSDL",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Desktop · ${runtimeStatus.abi}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            AppTooltipBox(tooltip = "Create a new virtual machine (Ctrl+N)") {
                Button(
                    onClick = onCreateVm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .desktopHandCursor()
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New Virtual Machine")
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "WORKSPACE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )

            AppTooltipBox(tooltip = "Virtual Machines (Ctrl+1)") {
                NavigationDrawerItem(
                    label = { Text("Virtual Machines", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = activeSection == TopLevelSection.VMS,
                    onClick = { onSelectSection(TopLevelSection.VMS) },
                    icon = { Icon(Icons.Rounded.Computer, contentDescription = null) },
                    badge = { Text("$vmCount", style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.desktopHandCursor()
                )
            }

            AppTooltipBox(tooltip = "OS & ISO Catalog (Ctrl+2)") {
                NavigationDrawerItem(
                    label = { Text("OS & ISO Catalog", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = activeSection == TopLevelSection.ISOS,
                    onClick = { onSelectSection(TopLevelSection.ISOS) },
                    icon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                    badge = {
                        if (activeDownloadCount > 0) {
                            Badge { Text("$activeDownloadCount") }
                        }
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.desktopHandCursor()
                )
            }

            AppTooltipBox(tooltip = "Runtime & Virtual Disks (Ctrl+3)") {
                NavigationDrawerItem(
                    label = { Text("Runtime & Disks", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = activeSection == TopLevelSection.RUNTIME,
                    onClick = { onSelectSection(TopLevelSection.RUNTIME) },
                    icon = { Icon(Icons.Rounded.Storage, contentDescription = null) },
                    badge = { Text("$diskCount", style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.desktopHandCursor()
                )
            }

            AppTooltipBox(tooltip = "About & Updates (Ctrl+4)") {
                NavigationDrawerItem(
                    label = { Text("About & Updates", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = activeSection == TopLevelSection.ABOUT,
                    onClick = { onSelectSection(TopLevelSection.ABOUT) },
                    icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    badge = { Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.desktopHandCursor()
                )
            }

            Spacer(Modifier.weight(1f))
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            // Compact Native Engine Status Footer
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (runtimeStatus.isFullQemuReady) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (runtimeStatus.isFullQemuReady) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (runtimeStatus.isFullQemuReady) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.DeveloperBoard
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = if (runtimeStatus.isFullQemuReady) "Native QEMU Ready" else "GLES3 Viewport",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = runtimeStatus.readyArchLabels.joinToString(" · ")
                                    .ifBlank { runtimeStatus.abi },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    AppTooltipBox(tooltip = "Refresh native runtime (Ctrl+R)") {
                        IconButton(
                            onClick = onRefreshRuntime,
                            modifier = Modifier
                                .size(32.dp)
                                .desktopHandCursor()
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "Refresh runtime",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
