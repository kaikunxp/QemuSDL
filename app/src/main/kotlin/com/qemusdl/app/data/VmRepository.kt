package com.qemusdl.app.data

import android.content.Context
import com.qemusdl.app.qemu.DiskFormat
import com.qemusdl.app.qemu.DiskImageCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class VmRepository(private val appContext: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val configFile: File = File(appContext.filesDir, "vms.json")
    private val disksDir: File = File(appContext.filesDir, "disks").apply { mkdirs() }

    private val _vms = MutableStateFlow<List<VmConfig>>(loadInitialVms())
    val vms: StateFlow<List<VmConfig>> = _vms.asStateFlow()

    private val _disks = MutableStateFlow<List<VirtualDiskInfo>>(scanLocalDisks())
    val disks: StateFlow<List<VirtualDiskInfo>> = _disks.asStateFlow()

    fun getVmById(id: String): VmConfig? = _vms.value.firstOrNull { it.id == id }

    suspend fun upsertVm(config: VmConfig) = withContext(Dispatchers.IO) {
        val current = _vms.value.toMutableList()
        val idx = current.indexOfFirst { it.id == config.id }
        if (idx >= 0) {
            current[idx] = config
        } else {
            current.add(0, config)
        }
        persistVms(current)
    }

    suspend fun deleteVm(id: String) = withContext(Dispatchers.IO) {
        val updated = _vms.value.filterNot { it.id == id }
        persistVms(updated)
    }

    suspend fun cloneVm(id: String) = withContext(Dispatchers.IO) {
        val source = _vms.value.firstOrNull { it.id == id } ?: return@withContext
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} (Copy)"
        )
        val updated = listOf(copy) + _vms.value
        persistVms(updated)
    }

    suspend fun createVirtualDisk(
        baseName: String,
        sizeMb: Int,
        format: DiskFormat
    ): VirtualDiskInfo = withContext(Dispatchers.IO) {
        val safeName = baseName.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "disk_${System.currentTimeMillis()}" }
        val fileName = if (safeName.endsWith(".${format.extension}", ignoreCase = true)) {
            safeName
        } else {
            "$safeName.${format.extension}"
        }
        val target = File(disksDir, fileName)
        DiskImageCreator.createDisk(target, sizeMb, format)
        refreshDisks()
        VirtualDiskInfo(
            name = target.name,
            path = target.absolutePath,
            format = format.name,
            allocatedBytes = target.length(),
            virtualSizeMb = sizeMb
        )
    }

    suspend fun deleteVirtualDisk(path: String) = withContext(Dispatchers.IO) {
        val file = File(path)
        if (file.exists() && file.parentFile?.canonicalPath == disksDir.canonicalPath) {
            file.delete()
        }
        refreshDisks()
    }

    fun refreshDisks() {
        _disks.value = scanLocalDisks()
    }

    private fun scanLocalDisks(): List<VirtualDiskInfo> {
        val files = disksDir.listFiles().orEmpty()
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
        return files.map { file ->
            val isQcow2 = file.extension.equals("qcow2", ignoreCase = true)
            VirtualDiskInfo(
                name = file.name,
                path = file.absolutePath,
                format = if (isQcow2) "QCOW2" else "RAW",
                allocatedBytes = file.length(),
                virtualSizeMb = if (isQcow2) {
                    DiskImageCreator.readQcow2VirtualSizeMb(file)
                } else {
                    (file.length() / (1024L * 1024L)).toInt()
                }
            )
        }
    }

    private fun persistVms(list: List<VmConfig>) {
        _vms.value = list
        runCatching {
            configFile.writeText(json.encodeToString(list))
        }
    }

    private fun loadInitialVms(): List<VmConfig> {
        if (configFile.exists()) {
            val loaded = runCatching {
                json.decodeFromString<List<VmConfig>>(configFile.readText())
            }.getOrNull()
            if (!loaded.isNullOrEmpty()) {
                // Migrate untouched starter VMs from older installs so they enter first-time setup
                var mutated = false
                val migrated = loaded.map { vm ->
                    if (vm.id.startsWith("preset-") &&
                        vm.isoPathOrUri.isBlank() &&
                        vm.diskPathOrUri.isBlank() &&
                        vm.recommendedIsoId == null
                    ) {
                        mutated = true
                        val recIso = when (vm.osPreset) {
                            OsPreset.LINUX_ALPINE -> "alpine-virt-x86_64"
                            OsPreset.LINUX_DEBIAN -> "debian-netinst-amd64"
                            OsPreset.FREEDOS -> "freedos-13-livecd"
                            else -> "alpine-virt-x86_64"
                        }
                        vm.copy(isSetupComplete = false, recommendedIsoId = recIso)
                    } else {
                        vm
                    }
                }
                if (mutated) {
                    runCatching { configFile.writeText(json.encodeToString(migrated)) }
                }
                return migrated
            }
        }
        val defaults = defaultStarterVms()
        runCatching { configFile.writeText(json.encodeToString(defaults)) }
        return defaults
    }

    private fun defaultStarterVms(): List<VmConfig> = listOf(
        VmConfig(
            id = "preset-alpine-x86_64",
            name = "Alpine Linux x86_64",
            osPreset = OsPreset.LINUX_ALPINE,
            cpuModel = "qemu64",
            cpuCores = 2,
            memoryMb = 1024,
            machineType = MachineType.Q35,
            displayDevice = DisplayDevice.VIRTIO_VGA,
            resolution = DisplayResolution.HD_1280x720,
            sdlGlAcceleration = true,
            bootOrder = BootOrder.CDROM_FIRST,
            networkEnabled = true,
            audioEnabled = true,
            usbTabletEnabled = true,
            isSetupComplete = false,
            recommendedIsoId = "alpine-virt-x86_64"
        ),
        VmConfig(
            id = "preset-debian-x86_64",
            name = "Debian 12 Desktop",
            osPreset = OsPreset.LINUX_DEBIAN,
            cpuModel = "max",
            cpuCores = 4,
            memoryMb = 2048,
            machineType = MachineType.Q35,
            displayDevice = DisplayDevice.VIRTIO_VGA,
            resolution = DisplayResolution.HD_1280x720,
            sdlGlAcceleration = true,
            bootOrder = BootOrder.CDROM_FIRST,
            networkEnabled = true,
            audioEnabled = true,
            usbTabletEnabled = true,
            isSetupComplete = false,
            recommendedIsoId = "debian-netinst-amd64"
        ),
        VmConfig(
            id = "preset-freedos-retro",
            name = "FreeDOS 1.3 Retro",
            osPreset = OsPreset.FREEDOS,
            cpuModel = "pentium3",
            cpuCores = 1,
            memoryMb = 256,
            machineType = MachineType.PC,
            displayDevice = DisplayDevice.STD,
            resolution = DisplayResolution.SVGA_800x600,
            sdlGlAcceleration = false,
            bootOrder = BootOrder.CDROM_FIRST,
            networkEnabled = true,
            networkAdapter = NetworkAdapterModel.RTL8139,
            audioEnabled = true,
            usbTabletEnabled = false,
            isSetupComplete = false,
            recommendedIsoId = "freedos-13-livecd"
        )
    )

    companion object {
        @Volatile
        private var instance: VmRepository? = null

        fun getInstance(context: Context): VmRepository =
            instance ?: synchronized(this) {
                instance ?: VmRepository(context.applicationContext).also { instance = it }
            }
    }
}
