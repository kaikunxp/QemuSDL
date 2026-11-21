package com.qemusdl.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class OsPreset(val displayName: String, val badgeText: String) {
    LINUX_ALPINE("Alpine Linux", "ALPINE"),
    LINUX_DEBIAN("Debian / Ubuntu", "LINUX"),
    WINDOWS("Windows x64", "WIN64"),
    FREEDOS("FreeDOS / Retro", "DOS"),
    CUSTOM("Custom x86_64", "X86_64")
}

@Serializable
enum class MachineType(val qemuFlag: String, val label: String) {
    Q35("q35", "Q35 (PCIe Modern)"),
    PC("pc", "i440FX (Legacy PC)")
}

@Serializable
enum class DisplayDevice(val qemuArg: String, val label: String) {
    VIRTIO_VGA("virtio-vga", "VirtIO VGA"),
    STD("std", "Standard VGA"),
    QXL("qxl-vga", "QXL VGA"),
    VMWARE("vmware-svga", "VMware SVGA")
}

@Serializable
enum class DisplayResolution(val width: Int, val height: Int, val label: String) {
    SVGA_800x600(800, 600, "800 x 600"),
    XGA_1024x768(1024, 768, "1024 x 768"),
    HD_1280x720(1280, 720, "1280 x 720"),
    FHD_1920x1080(1920, 1080, "1920 x 1080")
}

@Serializable
enum class SdlScalingMode(val nativeCode: Int, val label: String, val description: String) {
    FIT_ASPECT(0, "Fit Aspect", "Preserve guest aspect ratio with letter/pillarboxing"),
    FILL_STRETCH(1, "Fill Screen", "Stretch guest display to fill the entire viewport"),
    INTEGER_1X1(2, "1:1 Pixel", "Crisp integer pixel scaling centered in viewport")
}

@Serializable
enum class MouseEmulationMode(val label: String, val shortLabel: String, val description: String) {
    TRACKPAD_RELATIVE(
        label = "Trackpad (Relative)",
        shortLabel = "Trackpad",
        description = "Drag anywhere to move cursor relatively; 1-finger tap = Left Click, 2-finger tap = Right Click, 2-finger drag = Scroll"
    ),
    DIRECT_TOUCH(
        label = "Direct Touch (Absolute)",
        shortLabel = "Direct Touch",
        description = "Tap or drag directly at the screen position to move and left-click immediately"
    ),
    DIRECT_HOVER_TAP(
        label = "Hover & Tap (Absolute)",
        shortLabel = "Hover + Tap",
        description = "Drag finger to hover cursor without clicking; quick tap = Left Click, 2-finger tap = Right Click"
    )
}

@Serializable
enum class BootOrder(val qemuFlag: String, val label: String) {
    DISK_FIRST("order=c,menu=on", "Disk First"),
    CDROM_FIRST("order=d,menu=on", "ISO / CD-ROM First"),
    BOOT_MENU("menu=on", "Interactive Boot Menu")
}

@Serializable
enum class DiskInterface(val qemuIf: String, val label: String) {
    VIRTIO("virtio", "VirtIO Block"),
    IDE("ide", "IDE"),
    SATA("none", "AHCI / SATA")
}

@Serializable
enum class NetworkAdapterModel(
    val qemuModel: String,
    val label: String,
    val description: String
) {
    E1000(
        qemuModel = "e1000",
        label = "Intel e1000 (Gigabit)",
        description = "Universal Intel 82540EM Gigabit Ethernet — built-in driver in TinyCore, Alpine, Debian, Windows, and DOS"
    ),
    VIRTIO_NET(
        qemuModel = "virtio-net-pci",
        label = "VirtIO Net",
        description = "Paravirtualized high-throughput NIC for Linux/Windows guests with virtio_net drivers"
    ),
    RTL8139(
        qemuModel = "rtl8139",
        label = "Realtek RTL8139",
        description = "Classic 10/100 Fast Ethernet PCI adapter for older Linux kernels and retro OSes"
    ),
    E1000E(
        qemuModel = "e1000e",
        label = "Intel e1000e (PCIe)",
        description = "Modern Intel 82574L PCIe Gigabit adapter for Q35 machines"
    )
}

@Serializable
data class VmConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Alpine Linux x86_64",
    val osPreset: OsPreset = OsPreset.LINUX_ALPINE,
    val cpuModel: String = "qemu64",
    val cpuCores: Int = 2,
    val memoryMb: Int = 1024,
    val machineType: MachineType = MachineType.Q35,
    val displayDevice: DisplayDevice = DisplayDevice.VIRTIO_VGA,
    val resolution: DisplayResolution = DisplayResolution.HD_1280x720,
    val sdlGlAcceleration: Boolean = true,
    val scalingMode: SdlScalingMode = SdlScalingMode.FIT_ASPECT,
    val mouseEmulationMode: MouseEmulationMode = MouseEmulationMode.TRACKPAD_RELATIVE,
    val bootOrder: BootOrder = BootOrder.CDROM_FIRST,
    val diskPathOrUri: String = "",
    val diskDisplayName: String = "",
    val diskInterface: DiskInterface = DiskInterface.VIRTIO,
    val isoPathOrUri: String = "",
    val isoDisplayName: String = "",
    val networkEnabled: Boolean = true,
    val networkAdapter: NetworkAdapterModel = NetworkAdapterModel.E1000,
    val audioEnabled: Boolean = true,
    val usbTabletEnabled: Boolean = true,
    val usbXhciEnabled: Boolean = true,
    val usbPassthroughDevices: List<UsbPassthroughDevice> = emptyList(),
    val uefiEnabled: Boolean = false,
    val extraArgs: String = "",
    val isSetupComplete: Boolean = true,
    val recommendedIsoId: String? = null
) {
    val needsFirstTimeSetup: Boolean
        get() = !isSetupComplete
}

@Serializable
data class UsbPassthroughDevice(
    val vendorId: Int,
    val productId: Int,
    val deviceName: String = "",
    val productName: String = "",
    val manufacturerName: String = "",
    val serialNumber: String = "",
    val enabled: Boolean = true
) {
    val vendorIdHex: String
        get() = "%04x".format(vendorId and 0xFFFF)

    val productIdHex: String
        get() = "%04x".format(productId and 0xFFFF)

    val displayName: String
        get() = when {
            productName.isNotBlank() && manufacturerName.isNotBlank() -> "$manufacturerName $productName"
            productName.isNotBlank() -> productName
            manufacturerName.isNotBlank() -> manufacturerName
            deviceName.isNotBlank() -> deviceName
            else -> "USB Device [${vendorIdHex}:${productIdHex}]"
        }
}

data class VirtualDiskInfo(
    val name: String,
    val path: String,
    val format: String,
    val allocatedBytes: Long,
    val virtualSizeMb: Int? = null
)

data class DownloadedIsoInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long
)
