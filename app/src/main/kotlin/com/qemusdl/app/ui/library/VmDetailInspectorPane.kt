package com.qemusdl.app.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.qemu.QemuCommandBuilder
import com.qemusdl.app.ui.adaptive.AppFormFactor
import com.qemusdl.app.ui.adaptive.AppTooltipBox
import com.qemusdl.app.ui.adaptive.desktopHandCursor
import com.qemusdl.app.ui.adaptive.desktopTextCursor
import com.qemusdl.app.ui.viewport.VmLauncherActivity
import kotlinx.coroutines.launch

/**
 * Right-hand inspector pane rendered by Navigation 3's ListDetailSceneStrategy on unfolded
 * foldables/tablets and GooglebookOS Desktop when browsing the VM library.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmDetailInspectorPane(
    vm: VmConfig?,
    formFactor: AppFormFactor,
    onCreateVm: () -> Unit,
    onBootVm: (VmConfig) -> Unit,
    onBootSideBySide: ((VmConfig) -> Unit)? = null,
    onEditVm: (String) -> Unit,
    onRunSetup: (String) -> Unit,
    onCloneVm: (String) -> Unit,
    onDeleteVm: (String) -> Unit,
    onPinShortcut: (VmConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        if (vm == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Rounded.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Select a Virtual Machine",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Choose any x86_64, AArch64, or PowerPC VM from the library to inspect hardware specs, launch directly in SDL2, or edit its configuration.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onCreateVm,
                    modifier = Modifier.desktopHandCursor()
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create Virtual Machine")
                }
            }
            return@Scaffold
        }

        val cli = remember(vm) { QemuCommandBuilder.formatCommandLine(vm) }
        val launchUri = remember(vm.id) { VmLauncherActivity.formatLaunchUriString(vm) }
        val pointerLabel = if (QemuCommandBuilder.usesAbsolutePointer(vm)) {
            "USB Tablet (Absolute)"
        } else {
            "Native Mouse (Relative)"
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (formFactor == AppFormFactor.DESKTOP) 28.dp else 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Header Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        InspectorBadge(text = vm.osPreset.badgeText, primary = true)
                        InspectorBadge(text = vm.architecture.badgeText, primary = false)
                        InspectorBadge(text = vm.machineType.qemuFlag.uppercase(), primary = false)
                        if (vm.needsFirstTimeSetup) {
                            InspectorBadge(text = "NEEDS SETUP", primary = true)
                        }
                    }

                    Text(
                        text = vm.name,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (vm.needsFirstTimeSetup) onRunSetup(vm.id) else onBootVm(vm)
                            },
                            modifier = Modifier.desktopHandCursor()
                        ) {
                            Icon(
                                imageVector = if (vm.needsFirstTimeSetup) Icons.Rounded.AutoAwesome else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (vm.needsFirstTimeSetup) "Run Setup Wizard" else "Boot SDL2")
                        }

                        if (vm.needsFirstTimeSetup) {
                            FilledTonalButton(
                                onClick = { onBootVm(vm) },
                                modifier = Modifier.desktopHandCursor()
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Boot Anyway")
                            }
                        }

                        FilledTonalButton(
                            onClick = { onEditVm(vm.id) },
                            modifier = Modifier.desktopHandCursor()
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Configure Hardware")
                        }

                        if (onBootSideBySide != null) {
                            AppTooltipBox(tooltip = "Launch VM side-by-side in split-screen") {
                                OutlinedButton(
                                    onClick = { onBootSideBySide(vm) },
                                    modifier = Modifier.desktopHandCursor()
                                ) {
                                    Icon(Icons.Rounded.ViewWeek, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Side-by-Side")
                                }
                            }
                        }

                        if (!vm.needsFirstTimeSetup) {
                            OutlinedButton(
                                onClick = { onRunSetup(vm.id) },
                                modifier = Modifier.desktopHandCursor()
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Setup Wizard")
                            }
                        }
                    }
                }
            }

            // Hardware Specs Grid (2 balanced rows)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InspectorSpecCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Memory,
                    title = "Compute",
                    lines = listOf(
                        "${vm.cpuCores} vCPU · ${vm.cpuModel}",
                        "${vm.memoryMb} MB RAM",
                        "Chipset: ${vm.machineType.label}"
                    )
                )
                InspectorSpecCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Monitor,
                    title = "Display & Input",
                    lines = listOf(
                        "${vm.displayDevice.label} · ${vm.resolution.label}",
                        if (vm.sdlGlAcceleration) "SDL2 GLES3 Accelerated" else "SDL2 Software",
                        pointerLabel
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InspectorSpecCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Storage,
                    title = "Storage & Boot",
                    lines = listOf(
                        "Disk: ${vm.diskDisplayName.ifBlank { "None attached" }}",
                        "ISO: ${vm.isoDisplayName.ifBlank { "None attached" }}",
                        "Bus: ${vm.diskInterface.label} · ${vm.bootOrder.label}"
                    )
                )
                InspectorSpecCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Router,
                    title = "I/O & Peripherals",
                    lines = listOf(
                        if (vm.networkEnabled) "SLIRP NAT (${vm.networkAdapter.label})" else "Networking Disabled",
                        if (vm.audioEnabled) "Audio Enabled (OpenSL ES)" else "Audio Disabled",
                        "USB Passthrough: ${vm.usbPassthroughDevices.count { it.enabled }} device(s)"
                    )
                )
            }

            // Quick Actions Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Shortcuts & Profile Actions",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onPinShortcut(vm) },
                            modifier = Modifier.desktopHandCursor()
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.AddToHomeScreen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pin Shortcut")
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("qemusdl-vm-uri", launchUri))
                                scope.launch { snackbarHostState.showSnackbar("Copied $launchUri") }
                            },
                            modifier = Modifier.desktopHandCursor()
                        ) {
                            Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Launch URI")
                        }
                        OutlinedButton(
                            onClick = {
                                onCloneVm(vm.id)
                                scope.launch { snackbarHostState.showSnackbar("Cloned ${vm.name}") }
                            },
                            modifier = Modifier.desktopHandCursor()
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Clone")
                        }
                        OutlinedButton(
                            onClick = { onDeleteVm(vm.id) },
                            modifier = Modifier.desktopHandCursor()
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Live QEMU Command Preview
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Live QEMU Command", style = MaterialTheme.typography.titleMedium)
                        }
                        AppTooltipBox(tooltip = "Copy QEMU command") {
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("qemu-cli", cli))
                                    scope.launch { snackbarHostState.showSnackbar("Copied QEMU command") }
                                },
                                modifier = Modifier.desktopHandCursor()
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy QEMU command")
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
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
                }
            }
        }
    }
}

@Composable
private fun InspectorBadge(
    text: String,
    primary: Boolean
) {
    Surface(
        shape = RoundedCornerShape(100),
        color = if (primary) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (primary) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun InspectorSpecCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    lines: List<String>
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
