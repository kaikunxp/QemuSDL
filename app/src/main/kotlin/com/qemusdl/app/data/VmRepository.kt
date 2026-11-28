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

    fun getVmByName(name: String): VmConfig? {
        val needle = name.trim()
        if (needle.isEmpty()) return null
        return _vms.value.firstOrNull { it.name.equals(needle, ignoreCase = true) }
    }

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
        format: DiskFormat,
        uniqueIfExists: Boolean = false
    ): VirtualDiskInfo = withContext(Dispatchers.IO) {
        val safeName = baseName.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "disk_${System.currentTimeMillis()}" }
        val extSuffix = ".${format.extension}"
        val stem = if (safeName.endsWith(extSuffix, ignoreCase = true)) {
            safeName.dropLast(extSuffix.length)
        } else {
            safeName
        }
        var target = File(disksDir, "$stem$extSuffix")
        if (uniqueIfExists && target.exists()) {
            var suffix = 2
            while (target.exists()) {
                target = File(disksDir, "${stem}_$suffix$extSuffix")
                suffix++
            }
        }
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
        val targetCanonical = runCatching { file.canonicalPath }.getOrNull()
        if (file.exists() && file.parentFile?.canonicalPath == disksDir.canonicalPath) {
            file.delete()
        }
        refreshDisks()

        // Detach the deleted disk from any VM configs still pointing to it so VMs don't break on boot
        var changed = false
        val updatedVms = _vms.value.map { vm ->
            if (pathsReferToSameLocalFile(vm.diskPathOrUri, path, targetCanonical)) {
                changed = true
                vm.copy(diskPathOrUri = "", diskDisplayName = "")
            } else {
                vm
            }
        }
        if (changed) {
            persistVms(updatedVms)
        }
    }

    suspend fun detachDeletedIso(path: String) = withContext(Dispatchers.IO) {
        val targetCanonical = runCatching { File(path).canonicalPath }.getOrNull()
        var changed = false
        val updatedVms = _vms.value.map { vm ->
            var next = vm
            if (pathsReferToSameLocalFile(vm.isoPathOrUri, path, targetCanonical)) {
                changed = true
                next = next.copy(isoPathOrUri = "", isoDisplayName = "")
            }
            if (pathsReferToSameLocalFile(vm.virtioDriversIsoPath, path, targetCanonical)) {
                changed = true
                next = next.copy(virtioDriversIsoPath = "")
            }
            next
        }
        if (changed) {
            persistVms(updatedVms)
        }
    }

    private fun pathsReferToSameLocalFile(
        candidatePathOrUri: String,
        targetPath: String,
        targetCanonical: String?
    ): Boolean {
        val trimmed = candidatePathOrUri.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("content://")) return false
        if (trimmed == targetPath) return true
        val candidateCanonical = runCatching { File(trimmed).canonicalPath }.getOrNull()
        return targetCanonical != null && candidateCanonical == targetCanonical
    }

    private fun isMissingLocalFile(pathOrUri: String): Boolean {
        val trimmed = pathOrUri.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("content://")) return false
        return !File(trimmed).isFile
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
                            OsPreset.LINUX_ALPINE_AARCH64 -> "alpine-virt-aarch64"
                            OsPreset.LINUX_DEBIAN_AARCH64 -> "debian-netinst-arm64"
                            OsPreset.MACOS_9 -> "macos-921-ppc"
                            OsPreset.MACOS_X_PPC, OsPreset.CUSTOM_PPC -> null
                            else -> "alpine-virt-x86_64"
                        }
                        vm.copy(isSetupComplete = false, recommendedIsoId = recIso)
                    } else {
                        vm
                    }
                }
                // Self-heal stale local disk/ISO paths if backing files were deleted
                val healed = migrated.map { vm ->
                    var next = vm
                    if (isMissingLocalFile(next.diskPathOrUri)) {
                        mutated = true
                        next = next.copy(diskPathOrUri = "", diskDisplayName = "")
                    }
                    if (isMissingLocalFile(next.isoPathOrUri)) {
                        mutated = true
                        next = next.copy(isoPathOrUri = "", isoDisplayName = "")
                    }
                    if (isMissingLocalFile(next.virtioDriversIsoPath)) {
                        mutated = true
                        next = next.copy(virtioDriversIsoPath = "")
                    }
                    next
                }
                var finalVms = healed
                if (finalVms.none { it.architecture == GuestArchitecture.AARCH64 }) {
                    mutated = true
                    finalVms = finalVms + defaultAarch64StarterVms()
                }
                if (finalVms.none { it.architecture == GuestArchitecture.PPC }) {
                    mutated = true
                    finalVms = finalVms + defaultPpcStarterVms()
                }
                if (mutated) {
                    runCatching { configFile.writeText(json.encodeToString(finalVms)) }
                }
                return finalVms
            }
        }
        val defaults = defaultStarterVms() + defaultAarch64StarterVms() + defaultPpcStarterVms()
        runCatching { configFile.writeText(json.encodeToString(defaults)) }
        return defaults
    }

    private fun defaultStarterVms(): List<VmConfig> = listOf(
        VmConfig(
            id = "preset-alpine-x86_64",
            name = "Alpine Linux x86_64",
            architecture = GuestArchitecture.X86_64,
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
            name = "Debian 12 Desktop (x86_64)",
            architecture = GuestArchitecture.X86_64,
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
            architecture = GuestArchitecture.X86_64,
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

    private fun defaultAarch64StarterVms(): List<VmConfig> = listOf(
        VmConfig(
            id = "preset-alpine-aarch64",
            name = "Alpine Linux AArch64",
            architecture = GuestArchitecture.AARCH64,
            osPreset = OsPreset.LINUX_ALPINE_AARCH64,
            cpuModel = "max",
            cpuCores = 4,
            memoryMb = 1024,
            machineType = MachineType.VIRT,
            displayDevice = DisplayDevice.VIRTIO_GPU_PCI,
            resolution = DisplayResolution.HD_1280x720,
            sdlGlAcceleration = true,
            bootOrder = BootOrder.CDROM_FIRST,
            networkEnabled = true,
            audioEnabled = true,
            usbXhciEnabled = true,
            usbTabletEnabled = true,
            isSetupComplete = false,
            recommendedIsoId = "alpine-virt-aarch64"
        ),
        VmConfig(
            id = "preset-debian-aarch64",
            name = "Debian 12 ARM64 (AArch64)",
            architecture = GuestArchitecture.AARCH64,
            osPreset = OsPreset.LINUX_DEBIAN_AARCH64,
            cpuModel = "max",
            cpuCores = 4,
            memoryMb = 2048,
            machineType = MachineType.VIRT,
            displayDevice = DisplayDevice.VIRTIO_GPU_PCI,
            resolution = DisplayResolution.HD_1280x720,
            sdlGlAcceleration = true,
            bootOrder = BootOrder.CDROM_FIRST,
            networkEnabled = true,
            audioEnabled = true,
            usbXhciEnabled = true,
            usbTabletEnabled = true,
            isSetupComplete = false,
            recommendedIsoId = "debian-netinst-arm64"
        )
    )

    private fun defaultPpcStarterVms(): List<VmConfig> = listOf(
        VmConfig(
            id = "preset-macos9-ppc",
            name = "Mac OS 9.2 (PowerPC G4)",
            architecture = GuestArchitecture.PPC,
            osPreset = OsPreset.MACOS_9,
            cpuModel = "g4",
            cpuCores = 1,
            memoryMb = 512,
            machineType = MachineType.MAC99_PMU,
            displayDevice = DisplayDevice.STD,
            resolution = DisplayResolution.XGA_1024x768,
            sdlGlAcceleration = false,
            bootOrder = BootOrder.CDROM_FIRST,
            diskInterface = DiskInterface.IDE,
            networkEnabled = true,
            networkAdapter = NetworkAdapterModel.SUNGEM,
            audioEnabled = true,
            usbXhciEnabled = false,
            usbTabletEnabled = false,
            mouseEmulationMode = MouseEmulationMode.TRACKPAD_RELATIVE,
            isSetupComplete = false,
            recommendedIsoId = "macos-921-ppc"
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
