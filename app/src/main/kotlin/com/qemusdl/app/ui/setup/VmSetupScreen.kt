package com.qemusdl.app.ui.setup

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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qemusdl.app.data.OsPreset
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.qemu.DiskFormat
import com.qemusdl.app.qemu.IsoCatalog
import com.qemusdl.app.qemu.IsoDownloadState
import com.qemusdl.app.ui.isos.DownloadStateSection
import com.qemusdl.app.ui.library.VmLibraryViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun VmSetupScreen(
    vmId: String,
    viewModel: VmLibraryViewModel,
    onBack: () -> Unit,
    onOpenIsoCatalog: () -> Unit,
    onCompletedAndBoot: (VmConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val allVms by viewModel.vms.collectAsState()
    val downloadedIsos by viewModel.downloadedIsos.collectAsState()
    val downloadStates by viewModel.isoDownloadStates.collectAsState()

    val sourceVm = allVms.firstOrNull { it.id == vmId } ?: VmConfig(id = vmId)
    val recommendedIso = remember(sourceVm.recommendedIsoId, sourceVm.osPreset) {
        IsoCatalog.findById(sourceVm.recommendedIsoId)
            ?: IsoCatalog.recommendedForPreset(sourceVm.osPreset)
    }
    val recommendedDownloadState = downloadStates[recommendedIso.id] ?: IsoDownloadState.Idle
    val existingRecommendedIso = downloadedIsos.firstOrNull { it.name == recommendedIso.targetFileName }

    var selectedIsoPath by remember(sourceVm.isoPathOrUri, existingRecommendedIso?.path) {
        mutableStateOf(
            sourceVm.isoPathOrUri.ifBlank { existingRecommendedIso?.path.orEmpty() }
        )
    }
    var selectedIsoName by remember(sourceVm.isoDisplayName, existingRecommendedIso?.name) {
        mutableStateOf(
            sourceVm.isoDisplayName.ifBlank { existingRecommendedIso?.name.orEmpty() }
        )
    }

    var createDiskOnFinish by remember { mutableStateOf(sourceVm.diskPathOrUri.isBlank()) }
    var diskSizeMb by remember(sourceVm.osPreset) {
        mutableIntStateOf(
            when (sourceVm.osPreset) {
                OsPreset.LINUX_ALPINE -> 4096
                OsPreset.LINUX_DEBIAN -> 16384
                OsPreset.FREEDOS -> 512
                else -> 8192
            }
        )
    }
    var cpuCores by remember(sourceVm.cpuCores) { mutableIntStateOf(sourceVm.cpuCores) }
    var memoryMb by remember(sourceVm.memoryMb) { mutableIntStateOf(sourceVm.memoryMb) }
    var isFinishing by remember { mutableStateOf(false) }

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
            val displayName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "installer.iso"
            selectedIsoPath = uri.toString()
            selectedIsoName = displayName
        }
    }

    val isDownloadingRecommended = recommendedDownloadState is IsoDownloadState.Resolving ||
        recommendedDownloadState is IsoDownloadState.Downloading ||
        recommendedDownloadState is IsoDownloadState.Extracting

    fun finishSetup(bootNow: Boolean) {
        if (isFinishing) return
        isFinishing = true

        val baseUpdated = sourceVm.copy(
            cpuCores = cpuCores,
            memoryMb = memoryMb,
            isoPathOrUri = selectedIsoPath,
            isoDisplayName = selectedIsoName,
            isSetupComplete = true
        )

        if (createDiskOnFinish && baseUpdated.diskPathOrUri.isBlank()) {
            val diskName = "${sourceVm.name.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_disk"
            viewModel.createDisk(diskName, diskSizeMb, DiskFormat.QCOW2) { created ->
                val finalConfig = baseUpdated.copy(
                    diskPathOrUri = created.path,
                    diskDisplayName = "${created.name} ($diskSizeMb MB)"
                )
                viewModel.saveVm(finalConfig)
                isFinishing = false
                if (bootNow) onCompletedAndBoot(finalConfig) else onBack()
            }
        } else {
            viewModel.saveVm(baseUpdated)
            isFinishing = false
            if (bootNow) onCompletedAndBoot(baseUpdated) else onBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("First-Time VM Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { finishSetup(bootNow = true) }) {
                        Text("Skip")
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
            // Expressive Welcome Hero Card
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Set up ${sourceVm.name}",
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Attach or download a bootable x86_64 ISO and create a virtual QCOW2 disk so SeaBIOS boots straight into your OS on the first launch.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Step 1: OS ISO Selection / Download
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
                            Icon(Icons.Rounded.Album, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("1. Bootable OS Image (ISO)", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(10.dp))

                        if (selectedIsoPath.isNotBlank()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("ISO Ready to Boot", style = MaterialTheme.typography.labelMedium)
                                        Text(
                                            text = selectedIsoName.ifBlank { selectedIsoPath },
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Recommended: ${recommendedIso.title} (${recommendedIso.approxSizeLabel})",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = recommendedIso.subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(12.dp))

                                    DownloadStateSection(
                                        state = recommendedDownloadState,
                                        onCancel = { viewModel.cancelIsoDownload(recommendedIso.id) }
                                    )

                                    if (!isDownloadingRecommended) {
                                        Button(
                                            onClick = {
                                                viewModel.startIsoDownload(
                                                    entry = recommendedIso,
                                                    onComplete = { file ->
                                                        selectedIsoPath = file.absolutePath
                                                        selectedIsoName = file.name
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar("Attached ${file.name}")
                                                        }
                                                    },
                                                    onError = { msg ->
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar("Download failed: $msg")
                                                        }
                                                    }
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Download ${recommendedIso.title}")
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        if (downloadedIsos.isNotEmpty()) {
                            Text("Downloaded ISOs:", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                downloadedIsos.forEach { iso ->
                                    FilterChip(
                                        selected = selectedIsoPath == iso.path,
                                        onClick = {
                                            selectedIsoPath = iso.path
                                            selectedIsoName = iso.name
                                        },
                                        label = { Text(iso.name) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(onClick = onOpenIsoCatalog) {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("ISO Catalog")
                            }
                            OutlinedButton(onClick = { isoPicker.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pick File")
                            }
                        }
                    }
                }
            }

            // Step 2: Virtual Hard Disk
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
                            Icon(Icons.Rounded.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("2. Virtual Hard Disk (QCOW2)", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Create sparse QCOW2 v3 disk", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Starts at ~256 KB on disk and grows dynamically as the guest writes data.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = createDiskOnFinish, onCheckedChange = { createDiskOnFinish = it })
                        }

                        if (createDiskOnFinish) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Virtual Disk Size: ${if (diskSizeMb >= 1024) "${diskSizeMb / 1024} GB" else "$diskSizeMb MB"}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Slider(
                                value = diskSizeMb.toFloat(),
                                onValueChange = {
                                    diskSizeMb = ((it / 512f).roundToInt() * 512).coerceIn(512, 32768)
                                },
                                valueRange = 512f..32768f
                            )
                        }
                    }
                }
            }

            // Step 3: Compute Quick-Tune
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
                            Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("3. Compute Allocation", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("CPU Cores: $cpuCores vCPU", style = MaterialTheme.typography.titleMedium)
                        Slider(
                            value = cpuCores.toFloat(),
                            onValueChange = { cpuCores = it.roundToInt() },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        Text("Memory: $memoryMb MB RAM", style = MaterialTheme.typography.titleMedium)
                        Slider(
                            value = memoryMb.toFloat(),
                            onValueChange = { memoryMb = ((it / 128f).roundToInt() * 128).coerceIn(256, 4096) },
                            valueRange = 256f..4096f,
                            steps = 29
                        )
                    }
                }
            }

            // Finish & Boot CTA
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { finishSetup(bootNow = true) },
                        enabled = !isDownloadingRecommended && !isFinishing,
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (selectedIsoPath.isNotBlank()) "Complete Setup & Boot SDL2"
                            else "Boot SDL2 Without ISO"
                        )
                    }
                    OutlinedButton(
                        onClick = { finishSetup(bootNow = false) },
                        enabled = !isDownloadingRecommended && !isFinishing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Setup for Later")
                    }
                }
            }
        }
    }
}
