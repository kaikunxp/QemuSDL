package com.qemusdl.app.ui.editor

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.selection.SelectionContainer
import android.app.Activity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import com.qemusdl.app.usb.UsbPassthroughManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qemusdl.app.data.BootOrder
import com.qemusdl.app.data.DiskInterface
import com.qemusdl.app.data.DisplayDevice
import com.qemusdl.app.data.DisplayResolution
import com.qemusdl.app.data.GuestArchitecture
import com.qemusdl.app.data.MachineType
import com.qemusdl.app.data.MouseEmulationMode
import com.qemusdl.app.data.NetworkAdapterModel
import com.qemusdl.app.data.OsPreset
import com.qemusdl.app.data.SdlScalingMode
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.qemu.DiskFormat
import com.qemusdl.app.qemu.QemuCommandBuilder
import com.qemusdl.app.ui.adaptive.AppTooltipBox
import com.qemusdl.app.ui.adaptive.desktopHandCursor
import com.qemusdl.app.ui.adaptive.desktopTextCursor
import com.qemusdl.app.ui.library.VmLibraryViewModel
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

private val editorJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val VmConfigSaver = Saver<VmConfig, String>(
    save = { editorJson.encodeToString(it) },
    restore = { raw -> runCatching { editorJson.decodeFromString<VmConfig>(raw) }.getOrElse { VmConfig(name = "New Virtual Machine") } }
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun VmEditorScreen(
    vmId: String?,
    viewModel: VmLibraryViewModel,
    onBack: () -> Unit,
    onOpenIsoDownloader: (String?) -> Unit = {},
    onSavedAndBoot: (VmConfig) -> Unit,
    showBackButton: Boolean = true
) {
    val context = LocalContext.current
    val allVms by viewModel.vms.collectAsState()
    val existing = remember(vmId, allVms) { vmId?.let { id -> allVms.firstOrNull { it.id == id } } }
    val localDisks by viewModel.disks.collectAsState()
    val downloadedIsos by viewModel.downloadedIsos.collectAsState()

    var config by rememberSaveable(vmId, existing?.id, stateSaver = VmConfigSaver) {
        mutableStateOf(existing ?: VmConfig(name = "New Virtual Machine"))
    }
    var showCreateDiskDialog by rememberSaveable { mutableStateOf(false) }

    val usbPassthroughManager = remember(context) { UsbPassthroughManager(context) }
    var connectedUsbDevices by remember { mutableStateOf(usbPassthroughManager.getConnectedDevices()) }
    val gridState = rememberLazyGridState()

    fun saveAndClose() {
        viewModel.saveVm(config.copy(isSetupComplete = true))
        onBack()
    }

    val isoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val displayName = queryUriName(context, uri) ?: uri.lastPathSegment ?: "installer.iso"
            config = config.copy(isoPathOrUri = uri.toString(), isoDisplayName = displayName)
        }
    }

    val diskPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val displayName = queryUriName(context, uri) ?: uri.lastPathSegment ?: "disk.qcow2"
            config = config.copy(diskPathOrUri = uri.toString(), diskDisplayName = displayName)
        }
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown &&
                keyEvent.nativeKeyEvent.repeatCount == 0 &&
                keyEvent.isCtrlPressed &&
                keyEvent.key == Key.S
            ) {
                saveAndClose()
                true
            } else {
                false
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (existing == null) "Create ${config.architecture.badgeText} VM" else "Configure VM")
                },
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
                    AppTooltipBox(tooltip = "Save configuration (Ctrl+S)") {
                        FilledTonalButton(
                            onClick = { saveAndClose() },
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .desktopHandCursor()
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 440.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Identity, Architecture & OS Preset Card
            item {
                EditorSectionCard(title = "Identity, Architecture & Preset", icon = Icons.Rounded.Tune) {
                    OutlinedTextField(
                        value = config.name,
                        onValueChange = { config = config.copy(name = it) },
                        label = { Text("Virtual Machine Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("Guest Target Architecture", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GuestArchitecture.entries.forEach { arch ->
                            FilterChip(
                                selected = config.architecture == arch,
                                onClick = { config = applyArchitectureSwitch(config, arch) },
                                label = { Text(arch.label) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Quick OS Preset (${config.architecture.badgeText})", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))

                    val archPresets = remember(config.architecture) {
                        OsPreset.entries.filter { it.defaultArch == config.architecture }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        archPresets.forEach { preset ->
                            FilterChip(
                                selected = config.osPreset == preset,
                                onClick = { config = applyPresetDefaults(config, preset) },
                                label = { Text(preset.displayName) }
                            )
                        }
                    }
                }
            }

            // Compute Card
            item {
                EditorSectionCard(title = "${config.architecture.badgeText} Compute", icon = Icons.Rounded.Memory) {
                    val isPpc = config.architecture == GuestArchitecture.PPC
                    Text(
                        text = if (isPpc) "CPU Cores: 1 vCPU (PowerMac mac99/g3beige max)" else "CPU Cores: ${config.cpuCores} vCPU",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = if (isPpc) 1f else config.cpuCores.toFloat(),
                        onValueChange = { if (!isPpc) config = config.copy(cpuCores = it.roundToInt()) },
                        enabled = !isPpc,
                        valueRange = 1f..8f,
                        steps = 6
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (isPpc) {
                            "Memory (RAM): ${config.memoryMb.coerceAtMost(2048)} MB (512 MB recommended for Mac OS 9)"
                        } else {
                            "Memory (RAM): ${config.memoryMb} MB"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = if (isPpc) config.memoryMb.coerceIn(128, 2048).toFloat() else config.memoryMb.toFloat(),
                        onValueChange = {
                            val snapped = (it / 128f).roundToInt() * 128
                            val maxMem = if (isPpc) 2048 else 8192
                            val minMem = if (isPpc) 128 else 256
                            config = config.copy(memoryMb = snapped.coerceIn(minMem, maxMem))
                        },
                        valueRange = if (isPpc) 128f..2048f else 256f..4096f,
                        steps = if (isPpc) 14 else 29
                    )

                    Spacer(Modifier.height(10.dp))
                    Text("CPU Model", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        config.architecture.cpuModelOptions.forEach { cpu ->
                            FilterChip(
                                selected = config.cpuModel == cpu,
                                onClick = { config = config.copy(cpuModel = cpu) },
                                label = { Text(cpu) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Machine Chipset", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    val availableMachines = remember(config.architecture) {
                        MachineType.forArch(config.architecture)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableMachines.forEach { machine ->
                            FilterChip(
                                selected = config.machineType == machine,
                                onClick = { config = config.copy(machineType = machine) },
                                label = { Text(machine.label) }
                            )
                        }
                    }
                }
            }

            // SDL2 Display Card
            item {
                EditorSectionCard(title = "SDL2 Viewport & Graphics", icon = Icons.Rounded.Monitor) {
                    Text("Virtual GPU Device", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    val availableDisplays = remember(config.architecture) {
                        DisplayDevice.forArch(config.architecture)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableDisplays.forEach { dev ->
                            FilterChip(
                                selected = config.displayDevice == dev,
                                onClick = { config = config.copy(displayDevice = dev) },
                                label = { Text(dev.label) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Initial Framebuffer Resolution", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DisplayResolution.entries.forEach { res ->
                            FilterChip(
                                selected = config.resolution == res,
                                onClick = { config = config.copy(resolution = res) },
                                label = { Text(res.label) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Viewport Scaling", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SdlScalingMode.entries.forEach { mode ->
                            FilterChip(
                                selected = config.scalingMode == mode,
                                onClick = { config = config.copy(scalingMode = mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Mouse Emulation Mode", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MouseEmulationMode.entries.forEach { mode ->
                            FilterChip(
                                selected = config.mouseEmulationMode == mode,
                                onClick = { config = config.copy(mouseEmulationMode = mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    Text(
                        text = config.mouseEmulationMode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(Modifier.height(12.dp))
                    SwitchRow(
                        title = "SDL2 OpenGL ES Acceleration (gl=es)",
                        subtitle = "Stream guest frames via GLES textures instead of software blitting",
                        checked = config.sdlGlAcceleration,
                        onCheckedChange = { config = config.copy(sdlGlAcceleration = it) }
                    )

                    if (config.displayDevice.supportsVenus) {
                        Spacer(Modifier.height(10.dp))
                        SwitchRow(
                            title = "Venus Vulkan 3D Acceleration (venus=true)",
                            subtitle = "VirtIO GPU 3D passthrough to Android GPU (libvulkan.so) for native-speed Vulkan/Direct3D",
                            checked = config.venus3dEnabled,
                            onCheckedChange = { config = config.copy(venus3dEnabled = it) }
                        )

                        if (config.venus3dEnabled) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Host Memory Window (hostmem): ${config.venusHostMemoryMb} MB",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(Modifier.height(4.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(1024, 2048, 4096, 8192).forEach { mem ->
                                    FilterChip(
                                        selected = config.venusHostMemoryMb == mem,
                                        onClick = { config = config.copy(venusHostMemoryMb = mem) },
                                        label = { Text("${mem / 1024} GB") }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Storage & Boot Card
            item {
                EditorSectionCard(title = "Storage & Boot Media", icon = Icons.Rounded.Storage) {
                    Text("Virtual Hard Disk (.qcow2 / .img)", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    MediaSelectionRow(
                        displayName = config.diskDisplayName.ifBlank { "No disk attached" },
                        hasSelection = config.diskPathOrUri.isNotBlank(),
                        onPick = { diskPicker.launch(arrayOf("*/*")) },
                        onClear = { config = config.copy(diskPathOrUri = "", diskDisplayName = "") }
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showCreateDiskDialog = true }) {
                            Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Create New QCOW2")
                        }
                    }

                    if (localDisks.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Or attach a local app disk:", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            localDisks.forEach { disk ->
                                FilterChip(
                                    selected = config.diskPathOrUri == disk.path,
                                    onClick = {
                                        config = config.copy(
                                            diskPathOrUri = disk.path,
                                            diskDisplayName = "${disk.name} (${disk.virtualSizeMb ?: "?"} MB)"
                                        )
                                    },
                                    label = { Text(disk.name) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("Bootable ISO / CD-ROM", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    MediaSelectionRow(
                        displayName = config.isoDisplayName.ifBlank { "No ISO attached" },
                        hasSelection = config.isoPathOrUri.isNotBlank(),
                        icon = Icons.Rounded.Album,
                        onPick = { isoPicker.launch(arrayOf("*/*")) },
                        onClear = { config = config.copy(isoPathOrUri = "", isoDisplayName = "") }
                    )

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        viewModel.saveVm(config)
                        onOpenIsoDownloader(config.id)
                    }) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download ${config.architecture.badgeText} ISO")
                    }

                    if (downloadedIsos.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Or attach a downloaded ISO:", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            downloadedIsos.forEach { iso ->
                                FilterChip(
                                    selected = config.isoPathOrUri == iso.path,
                                    onClick = {
                                        config = config.copy(
                                            isoPathOrUri = iso.path,
                                            isoDisplayName = iso.name
                                        )
                                    },
                                    label = { Text(iso.name) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Disk Bus Interface", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiskInterface.entries.forEach { bus ->
                            FilterChip(
                                selected = config.diskInterface == bus,
                                onClick = { config = config.copy(diskInterface = bus) },
                                label = { Text(bus.label) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Boot Order", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BootOrder.entries.forEach { order ->
                            FilterChip(
                                selected = config.bootOrder == order,
                                onClick = { config = config.copy(bootOrder = order) },
                                label = { Text(order.label) }
                            )
                        }
                    }
                }
            }

            // USB Host Passthrough & Hardware
            item {
                EditorSectionCard(title = "USB Host Passthrough", icon = Icons.Rounded.Usb) {
                    if (config.architecture == GuestArchitecture.PPC) {
                        Text(
                            text = "PowerPC Mac uses built-in Apple OHCI USB 1.1 (Mac OS 9 does not support xHCI USB 3.0).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        SwitchRow(
                            title = "xHCI USB 3.0 Controller (-device qemu-xhci)",
                            subtitle = "High-speed USB 2.0 / 3.0 controller for guest OS (required for USB passthrough)",
                            checked = config.usbXhciEnabled,
                            onCheckedChange = { config = config.copy(usbXhciEnabled = it) }
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Connected USB OTG Devices", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "Plug in USB flash drives, mice, keyboards, or adapters via OTG to pass through",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            connectedUsbDevices = usbPassthroughManager.getConnectedDevices()
                        }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh USB devices")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    if (connectedUsbDevices.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Usb,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "No physical USB devices currently detected. Connect a USB device via USB-C OTG and tap refresh.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            connectedUsbDevices.forEach { devInfo ->
                                val isConfigured = config.usbPassthroughDevices.any {
                                    it.vendorId == devInfo.vendorId && it.productId == devInfo.productId && it.enabled
                                }
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = devInfo.displayTitle,
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                text = "0x${devInfo.vendorIdHex}:0x${devInfo.productIdHex} • ${devInfo.deviceClassLabel}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (!devInfo.hasPermission) {
                                                Text(
                                                    text = "Android USB permission required",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }

                                        if (!devInfo.hasPermission) {
                                            FilledTonalButton(
                                                onClick = {
                                                    (context as? Activity)?.let { act ->
                                                        usbPassthroughManager.requestPermission(act, devInfo.device) { granted ->
                                                            connectedUsbDevices = usbPassthroughManager.getConnectedDevices()
                                                            if (granted) {
                                                                val newDev = devInfo.toConfig(enabled = true)
                                                                val updated = config.usbPassthroughDevices
                                                                    .filterNot { it.vendorId == devInfo.vendorId && it.productId == devInfo.productId } + newDev
                                                                config = config.copy(usbPassthroughDevices = updated)
                                                            }
                                                        }
                                                    }
                                                }
                                            ) {
                                                Text("Grant")
                                            }
                                        } else {
                                            Switch(
                                                checked = isConfigured,
                                                onCheckedChange = { checked ->
                                                    val updated = if (checked) {
                                                        config.usbPassthroughDevices
                                                            .filterNot { it.vendorId == devInfo.vendorId && it.productId == devInfo.productId } +
                                                                devInfo.toConfig(enabled = true)
                                                    } else {
                                                        config.usbPassthroughDevices
                                                            .filterNot { it.vendorId == devInfo.vendorId && it.productId == devInfo.productId }
                                                    }
                                                    config = config.copy(usbPassthroughDevices = updated)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Peripherals & CLI Preview
            item {
                EditorSectionCard(title = "Peripherals & Extra Args", icon = Icons.Rounded.Terminal) {
                    val isMacOs9 = config.architecture == GuestArchitecture.PPC && config.osPreset == OsPreset.MACOS_9
                    SwitchRow(
                        title = "USB Tablet Pointer (-device usb-tablet)",
                        subtitle = if (isMacOs9) {
                            "Disabled for Mac OS 9 — uses native OHCI usb-mouse / ADB mouse instead"
                        } else {
                            "Enables accurate direct-touch pointer positioning in SDL2"
                        },
                        checked = if (isMacOs9) false else config.usbTabletEnabled,
                        onCheckedChange = {
                            if (!isMacOs9) {
                                config = config.copy(usbTabletEnabled = it)
                            }
                        }
                    )
                    SwitchRow(
                        title = "User Networking (SLIRP NAT + DNS)",
                        subtitle = "Rootless guest internet access (10.0.2.15/24, gateway 10.0.2.2, DNS 10.0.2.3)",
                        checked = config.networkEnabled,
                        onCheckedChange = { config = config.copy(networkEnabled = it) }
                    )
                    if (config.networkEnabled) {
                        Spacer(Modifier.height(6.dp))
                        Text("Network Adapter Model (NIC)", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            NetworkAdapterModel.entries.forEach { nic ->
                                FilterChip(
                                    selected = config.networkAdapter == nic,
                                    onClick = { config = config.copy(networkAdapter = nic) },
                                    label = { Text(nic.label) }
                                )
                            }
                        }
                        Text(
                            text = config.networkAdapter.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                        )
                    }
                    SwitchRow(
                        title = "SDL2 Audio (Intel HDA -> OpenSL ES)",
                        subtitle = "Routes guest sound directly through SDL2 audio backend",
                        checked = config.audioEnabled,
                        onCheckedChange = { config = config.copy(audioEnabled = it) }
                    )

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = config.extraArgs,
                        onValueChange = { config = config.copy(extraArgs = it) },
                        label = { Text("Extra QEMU Arguments (optional)") },
                        placeholder = { Text("-rtc base=utc -serial stdio") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("Live QEMU Command Preview", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        SelectionContainer(modifier = Modifier.desktopTextCursor()) {
                            Text(
                                text = QemuCommandBuilder.formatCommandLine(config),
                                modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Bottom Save & Boot CTA
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { saveAndClose() },
                        modifier = Modifier.weight(1f).desktopHandCursor()
                    ) {
                        Text("Save Only")
                    }
                    Button(
                        onClick = {
                            val saved = config.copy(isSetupComplete = true)
                            viewModel.saveVm(saved)
                            onSavedAndBoot(saved)
                        },
                        modifier = Modifier.weight(1.3f).desktopHandCursor()
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save & Boot SDL2")
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
                    config = config.copy(
                        diskPathOrUri = created.path,
                        diskDisplayName = "${created.name} ($sizeMb MB)"
                    )
                }
                showCreateDiskDialog = false
            }
        )
    }
}

@Composable
private fun EditorSectionCard(
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(text = title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun MediaSelectionRow(
    displayName: String,
    hasSelection: Boolean,
    icon: ImageVector = Icons.Rounded.FolderOpen,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
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
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasSelection) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear selection")
                    }
                }
                FilledTonalButton(onClick = onPick) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse")
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun CreateDiskDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, sizeMb: Int, format: DiskFormat) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("alpine_root") }
    var sizeMb by rememberSaveable { mutableIntStateOf(4096) }
    var formatName by rememberSaveable { mutableStateOf(DiskFormat.QCOW2.name) }
    val format = DiskFormat.entries.firstOrNull { it.name == formatName } ?: DiskFormat.QCOW2

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Storage, contentDescription = null) },
        title = { Text("Create Virtual Disk") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Disk File Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Capacity: ${if (sizeMb >= 1024) "${sizeMb / 1024} GB" else "$sizeMb MB"}")
                Slider(
                    value = sizeMb.toFloat(),
                    onValueChange = { sizeMb = ((it / 512f).roundToInt() * 512).coerceIn(512, 32768) },
                    valueRange = 512f..32768f
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiskFormat.entries.forEach { item ->
                        FilterChip(
                            selected = format == item,
                            onClick = { formatName = item.name },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, sizeMb, format) }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun applyArchitectureSwitch(current: VmConfig, arch: GuestArchitecture): VmConfig {
    if (current.architecture == arch) return current
    val defaultPreset = when (arch) {
        GuestArchitecture.X86_64 -> OsPreset.LINUX_ALPINE
        GuestArchitecture.AARCH64 -> OsPreset.LINUX_ALPINE_AARCH64
        GuestArchitecture.PPC -> OsPreset.MACOS_9
    }
    return applyPresetDefaults(current.copy(architecture = arch), defaultPreset)
}

private fun applyPresetDefaults(current: VmConfig, preset: OsPreset): VmConfig = when (preset) {
    OsPreset.LINUX_ALPINE -> current.copy(
        architecture = GuestArchitecture.X86_64,
        osPreset = preset,
        cpuModel = "qemu64",
        cpuCores = 2,
        memoryMb = 1024,
        machineType = MachineType.Q35,
        displayDevice = DisplayDevice.VIRTIO_VGA,
        resolution = DisplayResolution.HD_1280x720,
        sdlGlAcceleration = true,
        recommendedIsoId = "alpine-virt-x86_64"
    )
    OsPreset.LINUX_DEBIAN -> current.copy(
        architecture = GuestArchitecture.X86_64,
        osPreset = preset,
        cpuModel = "max",
        cpuCores = 4,
        memoryMb = 2048,
        machineType = MachineType.Q35,
        displayDevice = DisplayDevice.VIRTIO_VGA,
        resolution = DisplayResolution.HD_1280x720,
        sdlGlAcceleration = true,
        recommendedIsoId = "debian-netinst-amd64"
    )
    OsPreset.WINDOWS -> current.copy(
        architecture = GuestArchitecture.X86_64,
        osPreset = preset,
        cpuModel = "Skylake-Client",
        cpuCores = 4,
        memoryMb = 4096,
        machineType = MachineType.Q35,
        displayDevice = DisplayDevice.QXL,
        resolution = DisplayResolution.HD_1280x720,
        diskInterface = DiskInterface.SATA,
        sdlGlAcceleration = false,
        recommendedIsoId = "windows-10-eval-x64"
    )
    OsPreset.FREEDOS -> current.copy(
        architecture = GuestArchitecture.X86_64,
        osPreset = preset,
        cpuModel = "pentium3",
        cpuCores = 1,
        memoryMb = 256,
        machineType = MachineType.PC,
        displayDevice = DisplayDevice.STD,
        resolution = DisplayResolution.SVGA_800x600,
        diskInterface = DiskInterface.IDE,
        sdlGlAcceleration = false,
        usbTabletEnabled = false,
        recommendedIsoId = "freedos-13-livecd"
    )
    OsPreset.CUSTOM -> current.copy(
        architecture = GuestArchitecture.X86_64,
        osPreset = preset,
        machineType = if (current.machineType.supportedArch == GuestArchitecture.X86_64) current.machineType else MachineType.Q35,
        displayDevice = if (current.displayDevice.isSupportedOn(GuestArchitecture.X86_64)) current.displayDevice else DisplayDevice.VIRTIO_VGA
    )
    OsPreset.LINUX_ALPINE_AARCH64 -> current.copy(
        architecture = GuestArchitecture.AARCH64,
        osPreset = preset,
        cpuModel = "max",
        cpuCores = 4,
        memoryMb = 1024,
        machineType = MachineType.VIRT,
        displayDevice = DisplayDevice.VIRTIO_GPU_PCI,
        resolution = DisplayResolution.HD_1280x720,
        diskInterface = DiskInterface.VIRTIO,
        sdlGlAcceleration = true,
        usbXhciEnabled = true,
        usbTabletEnabled = true,
        recommendedIsoId = "alpine-virt-aarch64"
    )
    OsPreset.LINUX_DEBIAN_AARCH64 -> current.copy(
        architecture = GuestArchitecture.AARCH64,
        osPreset = preset,
        cpuModel = "max",
        cpuCores = 4,
        memoryMb = 2048,
        machineType = MachineType.VIRT,
        displayDevice = DisplayDevice.VIRTIO_GPU_PCI,
        resolution = DisplayResolution.HD_1280x720,
        diskInterface = DiskInterface.VIRTIO,
        sdlGlAcceleration = true,
        usbXhciEnabled = true,
        usbTabletEnabled = true,
        recommendedIsoId = "debian-netinst-arm64"
    )
    OsPreset.WINDOWS_AARCH64 -> current.copy(
        architecture = GuestArchitecture.AARCH64,
        osPreset = preset,
        cpuModel = "max",
        cpuCores = 4,
        memoryMb = 4096,
        machineType = MachineType.VIRT,
        displayDevice = DisplayDevice.RAMFB,
        resolution = DisplayResolution.HD_1280x720,
        diskInterface = DiskInterface.VIRTIO,
        sdlGlAcceleration = true,
        usbXhciEnabled = true,
        usbTabletEnabled = true,
        recommendedIsoId = "windows-11-ltsc-arm64"
    )
    OsPreset.CUSTOM_AARCH64 -> current.copy(
        architecture = GuestArchitecture.AARCH64,
        osPreset = preset,
        cpuModel = if (current.cpuModel in GuestArchitecture.AARCH64.cpuModelOptions) current.cpuModel else "max",
        machineType = if (current.machineType.supportedArch == GuestArchitecture.AARCH64) current.machineType else MachineType.VIRT,
        displayDevice = if (current.displayDevice.isSupportedOn(GuestArchitecture.AARCH64)) current.displayDevice else DisplayDevice.VIRTIO_GPU_PCI,
        usbXhciEnabled = true,
        usbTabletEnabled = true
    )
    OsPreset.MACOS_9 -> current.copy(
        architecture = GuestArchitecture.PPC,
        osPreset = preset,
        cpuModel = "g4",
        cpuCores = 1,
        memoryMb = 512,
        machineType = MachineType.MAC99_PMU,
        displayDevice = DisplayDevice.STD,
        resolution = DisplayResolution.XGA_1024x768,
        diskInterface = DiskInterface.IDE,
        networkAdapter = NetworkAdapterModel.SUNGEM,
        sdlGlAcceleration = false,
        venus3dEnabled = false,
        usbXhciEnabled = false,
        usbTabletEnabled = false,
        mouseEmulationMode = MouseEmulationMode.TRACKPAD_RELATIVE,
        audioEnabled = true,
        recommendedIsoId = "macos-921-ppc"
    )
    OsPreset.MACOS_X_PPC -> current.copy(
        architecture = GuestArchitecture.PPC,
        osPreset = preset,
        cpuModel = "g4",
        cpuCores = 1,
        memoryMb = 1024,
        machineType = MachineType.MAC99_PMU,
        displayDevice = DisplayDevice.STD,
        resolution = DisplayResolution.XGA_1024x768,
        diskInterface = DiskInterface.IDE,
        networkAdapter = NetworkAdapterModel.SUNGEM,
        sdlGlAcceleration = false,
        venus3dEnabled = false,
        usbXhciEnabled = false,
        usbTabletEnabled = false,
        mouseEmulationMode = MouseEmulationMode.TRACKPAD_RELATIVE,
        audioEnabled = true,
        recommendedIsoId = null
    )
    OsPreset.CUSTOM_PPC -> current.copy(
        architecture = GuestArchitecture.PPC,
        osPreset = preset,
        cpuModel = if (current.cpuModel in GuestArchitecture.PPC.cpuModelOptions) current.cpuModel else "g4",
        cpuCores = 1,
        memoryMb = current.memoryMb.coerceIn(128, 2048),
        machineType = if (current.machineType.supportedArch == GuestArchitecture.PPC) current.machineType else MachineType.MAC99_PMU,
        displayDevice = if (current.displayDevice.isSupportedOn(GuestArchitecture.PPC)) current.displayDevice else DisplayDevice.STD,
        diskInterface = if (current.diskInterface == DiskInterface.SATA) DiskInterface.IDE else current.diskInterface,
        venus3dEnabled = false,
        usbXhciEnabled = false,
        usbTabletEnabled = false,
        recommendedIsoId = null
    )
}

private fun queryUriName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
