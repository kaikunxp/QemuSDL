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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qemusdl.app.data.BootOrder
import com.qemusdl.app.data.DiskInterface
import com.qemusdl.app.data.DisplayDevice
import com.qemusdl.app.data.DisplayResolution
import com.qemusdl.app.data.MachineType
import com.qemusdl.app.data.MouseEmulationMode
import com.qemusdl.app.data.NetworkAdapterModel
import com.qemusdl.app.data.OsPreset
import com.qemusdl.app.data.SdlScalingMode
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.qemu.DiskFormat
import com.qemusdl.app.qemu.QemuCommandBuilder
import com.qemusdl.app.ui.library.VmLibraryViewModel
import kotlin.math.roundToInt

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
    onSavedAndBoot: (VmConfig) -> Unit
) {
    val context = LocalContext.current
    val allVms by viewModel.vms.collectAsState()
    val existing = remember(vmId, allVms) { vmId?.let { id -> allVms.firstOrNull { it.id == id } } }
    val localDisks by viewModel.disks.collectAsState()
    val downloadedIsos by viewModel.downloadedIsos.collectAsState()

    var config by remember(existing) {
        mutableStateOf(existing ?: VmConfig(name = "New x86_64 VM"))
    }
    var showCreateDiskDialog by remember { mutableStateOf(false) }

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
        topBar = {
            TopAppBar(
                title = {
                    Text(if (existing == null) "Create x86_64 VM" else "Configure VM")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = {
                            viewModel.saveVm(config.copy(isSetupComplete = true))
                            onBack()
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Identity & OS Preset Card
            item {
                EditorSectionCard(title = "Identity & OS Preset", icon = Icons.Rounded.Tune) {
                    OutlinedTextField(
                        value = config.name,
                        onValueChange = { config = config.copy(name = it) },
                        label = { Text("Virtual Machine Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("Quick Preset", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OsPreset.entries.forEach { preset ->
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
                EditorSectionCard(title = "x86_64 Compute", icon = Icons.Rounded.Memory) {
                    Text(
                        text = "CPU Cores: ${config.cpuCores} vCPU",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = config.cpuCores.toFloat(),
                        onValueChange = { config = config.copy(cpuCores = it.roundToInt()) },
                        valueRange = 1f..8f,
                        steps = 6
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Memory (RAM): ${config.memoryMb} MB",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = config.memoryMb.toFloat(),
                        onValueChange = {
                            val snapped = (it / 128f).roundToInt() * 128
                            config = config.copy(memoryMb = snapped.coerceIn(256, 8192))
                        },
                        valueRange = 256f..4096f,
                        steps = 29
                    )

                    Spacer(Modifier.height(10.dp))
                    Text("CPU Model", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("qemu64", "max", "Haswell", "Skylake-Client", "pentium3").forEach { cpu ->
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MachineType.entries.forEach { machine ->
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DisplayDevice.entries.forEach { dev ->
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
                        Text("Download x86_64 ISO")
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

            // Peripherals & CLI Preview
            item {
                EditorSectionCard(title = "Peripherals & Extra Args", icon = Icons.Rounded.Terminal) {
                    SwitchRow(
                        title = "USB Tablet Pointer (-device usb-tablet)",
                        subtitle = "Enables accurate direct-touch pointer positioning in SDL2",
                        checked = config.usbTabletEnabled,
                        onCheckedChange = { config = config.copy(usbTabletEnabled = it) }
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
                        Text(
                            text = QemuCommandBuilder.formatCommandLine(config),
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Bottom Save & Boot CTA
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.saveVm(config.copy(isSetupComplete = true))
                            onBack()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Only")
                    }
                    Button(
                        onClick = {
                            val saved = config.copy(isSetupComplete = true)
                            viewModel.saveVm(saved)
                            onSavedAndBoot(saved)
                        },
                        modifier = Modifier.weight(1.3f)
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
    var name by remember { mutableStateOf("alpine_root") }
    var sizeMb by remember { mutableIntStateOf(4096) }
    var format by remember { mutableStateOf(DiskFormat.QCOW2) }

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
                            onClick = { format = item },
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

private fun applyPresetDefaults(current: VmConfig, preset: OsPreset): VmConfig = when (preset) {
    OsPreset.LINUX_ALPINE -> current.copy(
        osPreset = preset,
        cpuModel = "qemu64",
        cpuCores = 2,
        memoryMb = 1024,
        machineType = MachineType.Q35,
        displayDevice = DisplayDevice.VIRTIO_VGA,
        resolution = DisplayResolution.HD_1280x720,
        sdlGlAcceleration = true
    )
    OsPreset.LINUX_DEBIAN -> current.copy(
        osPreset = preset,
        cpuModel = "max",
        cpuCores = 4,
        memoryMb = 2048,
        machineType = MachineType.Q35,
        displayDevice = DisplayDevice.VIRTIO_VGA,
        resolution = DisplayResolution.HD_1280x720,
        sdlGlAcceleration = true
    )
    OsPreset.WINDOWS -> current.copy(
        osPreset = preset,
        cpuModel = "Skylake-Client",
        cpuCores = 4,
        memoryMb = 4096,
        machineType = MachineType.Q35,
        displayDevice = DisplayDevice.QXL,
        resolution = DisplayResolution.HD_1280x720,
        diskInterface = DiskInterface.SATA,
        sdlGlAcceleration = false
    )
    OsPreset.FREEDOS -> current.copy(
        osPreset = preset,
        cpuModel = "pentium3",
        cpuCores = 1,
        memoryMb = 256,
        machineType = MachineType.PC,
        displayDevice = DisplayDevice.STD,
        resolution = DisplayResolution.SVGA_800x600,
        diskInterface = DiskInterface.IDE,
        sdlGlAcceleration = false,
        usbTabletEnabled = false
    )
    OsPreset.CUSTOM -> current.copy(osPreset = preset)
}

private fun queryUriName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
