package com.qemusdl.app.qemu

import com.qemusdl.app.data.DiskInterface
import com.qemusdl.app.data.DisplayDevice
import com.qemusdl.app.data.GuestArchitecture
import com.qemusdl.app.data.MachineType
import com.qemusdl.app.data.VmConfig

/**
 * Generates exact `qemu-system-x86_64` or `qemu-system-aarch64` CLI arguments targeting an
 * in-process SDL2 viewport (`-display sdl`) with VNC explicitly omitted.
 */
object QemuCommandBuilder {

    fun buildArgs(
        config: VmConfig,
        resolvedDiskPath: String? = null,
        resolvedIsoPath: String? = null,
        firmwareDir: String? = null,
        edk2CodePath: String? = null,
        edk2VarsPath: String? = null,
        activeUsbDeviceFds: Map<String, Int> = emptyMap()
    ): List<String> {
        val args = mutableListOf<String>()
        val arch = config.architecture
        args += arch.qemuBinaryName

        // Firmware ROM & keymaps search directory (SeaBIOS, VGABIOS, EDK2 AArch64 UEFI)
        if (!firmwareDir.isNullOrBlank()) {
            args += listOf("-L", firmwareDir)
        }

        // Machine & TCG software CPU acceleration (Android lacks unprivileged /dev/kvm)
        val effectiveMachine = if (config.machineType.supportedArch == arch) {
            config.machineType
        } else {
            MachineType.defaultForArch(arch)
        }
        args += listOf("-machine", "${effectiveMachine.qemuFlag},accel=tcg")

        val effectiveCpu = config.cpuModel.ifBlank { arch.defaultCpuModel }
        args += listOf("-cpu", effectiveCpu)
        args += listOf("-smp", config.cpuCores.coerceIn(1, 8).toString())
        args += listOf("-m", config.memoryMb.coerceIn(128, 8192).toString())

        // AArch64 (-machine virt) has no SeaBIOS; wire EDK2 UEFI code + per-VM writable NVRAM vars via -drive if=pflash
        if (arch == GuestArchitecture.AARCH64) {
            val codePath = edk2CodePath?.takeIf { it.isNotBlank() }
                ?: if (!firmwareDir.isNullOrBlank()) "$firmwareDir/${QemuFirmwareManager.EDK2_AARCH64_CODE_FILENAME}"
                else QemuFirmwareManager.EDK2_AARCH64_CODE_FILENAME
            val varsPath = edk2VarsPath?.takeIf { it.isNotBlank() }
                ?: if (!firmwareDir.isNullOrBlank()) "$firmwareDir/${QemuFirmwareManager.EDK2_ARM_VARS_FILENAME}"
                else QemuFirmwareManager.EDK2_ARM_VARS_FILENAME

            args += listOf("-drive", "if=pflash,format=raw,unit=0,readonly=on,file=$codePath")
            args += listOf("-drive", "if=pflash,format=raw,unit=1,file=$varsPath")
        }

        // Direct SDL2 viewport instead of VNC!
        // When Venus 3D / virgl acceleration is enabled, gl=es enables SDL2 OpenGLES texture streaming
        // full-screen=on sets SDL_WINDOW_FULLSCREEN_DESKTOP in QEMU's sdl2.c so that guest mode
        // switches (SeaBIOS 720x400 -> bootloader 640x480 -> desktop) use SDL_RenderSetLogicalSize
        // without SDL_SetWindowSize shrinking the Android window dimensions.
        // show-cursor=on keeps SDL2 mouse coordinates in window space without Android pointer capture.
        val isVenusActive = config.venus3dEnabled ||
            config.displayDevice == DisplayDevice.VIRTIO_VGA_GL ||
            config.displayDevice == DisplayDevice.VIRTIO_GPU_GL_PCI
        val glArg = if (isVenusActive) "gl=es," else ""
        args += listOf("-display", "sdl,${glArg}window-close=off,full-screen=on,show-cursor=on")

        // Graphics adapter (with Venus Vulkan / Virgl 3D hardware acceleration when enabled)
        // AArch64 (-machine virt) uses virtio-gpu-pci (and virtio-gpu-gl-pci for Venus) instead of virtio-vga
        val hostmemStr = "${config.venusHostMemoryMb.coerceIn(256, 8192)}M"
        val resOpts = "xres=${config.resolution.width},yres=${config.resolution.height}"
        if (arch == GuestArchitecture.AARCH64) {
            when (config.displayDevice) {
                DisplayDevice.RAMFB -> args += listOf("-device", "ramfb")
                DisplayDevice.BOCHS_DISPLAY -> args += listOf("-device", "bochs-display")
                else -> {
                    if (isVenusActive) {
                        args += listOf(
                            "-device",
                            "virtio-gpu-gl-pci,hostmem=$hostmemStr,blob=true,venus=true,$resOpts"
                        )
                    } else {
                        args += listOf("-device", "virtio-gpu-pci,$resOpts")
                    }
                }
            }
        } else {
            when (config.displayDevice) {
                DisplayDevice.VIRTIO_VGA_GL -> {
                    val venusOpts = if (config.venus3dEnabled) ",hostmem=$hostmemStr,blob=true,venus=true" else ""
                    args += listOf("-device", "virtio-vga-gl$venusOpts,$resOpts")
                }
                DisplayDevice.VIRTIO_GPU_GL_PCI -> {
                    // On x86_64 PC/Q35 (SeaBIOS), use virtio-vga-gl so legacy VGA text/VBE modes
                    // (SeaBIOS, GRUB, and early kernel console) render immediately in addition to
                    // VirtIO-GPU 3D / Venus Vulkan acceleration once the guest KMS driver loads.
                    val venusOpts = if (config.venus3dEnabled) ",hostmem=$hostmemStr,blob=true,venus=true" else ""
                    args += listOf("-device", "virtio-vga-gl$venusOpts,$resOpts")
                }
                DisplayDevice.VIRTIO_VGA -> {
                    if (config.venus3dEnabled) {
                        args += listOf("-device", "virtio-vga-gl,hostmem=$hostmemStr,blob=true,venus=true,$resOpts")
                    } else {
                        args += listOf("-device", "virtio-vga,$resOpts")
                    }
                }
                DisplayDevice.VIRTIO_GPU_PCI -> {
                    // On x86_64 PC/Q35 (SeaBIOS), use virtio-vga / virtio-vga-gl so BIOS/GRUB output is visible.
                    if (config.venus3dEnabled) {
                        args += listOf("-device", "virtio-vga-gl,hostmem=$hostmemStr,blob=true,venus=true,$resOpts")
                    } else {
                        args += listOf("-device", "virtio-vga,$resOpts")
                    }
                }
                DisplayDevice.RAMFB -> {
                    args += listOf("-vga", "none", "-device", "ramfb")
                }
                DisplayDevice.BOCHS_DISPLAY -> {
                    args += listOf("-vga", "none", "-device", "bochs-display")
                }
                DisplayDevice.STD -> {
                    args += listOf("-vga", "std")
                }
                DisplayDevice.QXL -> {
                    args += listOf("-device", "qxl-vga")
                }
                DisplayDevice.VMWARE -> {
                    args += listOf("-vga", "vmware")
                }
            }
        }

        // Boot order
        args += listOf("-boot", config.bootOrder.qemuFlag)

        // USB controller (Modern xHCI USB 3.0 or legacy USB) & absolute pointer tablet / keyboard
        // On AArch64 (-machine virt), always attach qemu-xhci + usb-kbd + usb-tablet since virt lacks PS/2
        val hasXhci = config.usbXhciEnabled ||
            arch == GuestArchitecture.AARCH64 ||
            config.usbPassthroughDevices.any { it.enabled }
        if (arch == GuestArchitecture.AARCH64) {
            args += listOf("-device", "qemu-xhci,id=xhci")
            args += listOf("-device", "usb-kbd,bus=xhci.0")
            args += listOf("-device", "usb-tablet,bus=xhci.0")
        } else if (hasXhci) {
            args += listOf("-device", "qemu-xhci,id=xhci")
            if (config.usbTabletEnabled) {
                args += listOf("-device", "usb-tablet,bus=xhci.0")
            }
        } else if (config.usbTabletEnabled) {
            args += listOf("-usb", "-device", "usb-tablet")
        }

        // USB Host Passthrough Devices (via UsbManager openDevice /proc/self/fd/<fd> or vendor/product ID)
        val enabledUsbDevices = config.usbPassthroughDevices.filter { it.enabled }
        val usbBus = if (hasXhci) "xhci.0" else "usb.0"
        for (device in enabledUsbDevices) {
            val key = "${device.vendorIdHex}:${device.productIdHex}"
            val activeFd = activeUsbDeviceFds[key] ?: activeUsbDeviceFds[device.deviceName]
            if (activeFd != null && activeFd >= 0) {
                args += listOf("-device", "usb-host,bus=$usbBus,hostdevice=/proc/self/fd/$activeFd")
            } else {
                args += listOf(
                    "-device",
                    "usb-host,bus=$usbBus,vendorid=0x${device.vendorIdHex},productid=0x${device.productIdHex}"
                )
            }
        }

        // Primary Disk Image
        val diskTarget = (resolvedDiskPath ?: config.diskPathOrUri).trim()
        if (diskTarget.isNotEmpty()) {
            val format = if (diskTarget.endsWith(".qcow2", ignoreCase = true)) "qcow2" else "raw"
            if (config.diskInterface == DiskInterface.SATA || (arch == GuestArchitecture.AARCH64 && config.diskInterface == DiskInterface.IDE)) {
                args += listOf("-device", "ahci,id=ahci")
                args += listOf("-drive", "id=disk0,file=$diskTarget,if=none,format=$format")
                args += listOf("-device", "ide-hd,drive=disk0,bus=ahci.0")
            } else {
                args += listOf(
                    "-drive",
                    "file=$diskTarget,if=${config.diskInterface.qemuIf},format=$format,cache=writeback"
                )
            }
        }

        // Bootable ISO / CD-ROM
        val isoTarget = (resolvedIsoPath ?: config.isoPathOrUri).trim()
        if (isoTarget.isNotEmpty()) {
            if (arch == GuestArchitecture.AARCH64) {
                // ARM virt has no default IDE bus; attach ISO via virtio-scsi CD-ROM so EDK2 UEFI boots it cleanly
                args += listOf(
                    "-device", "virtio-scsi-pci,id=scsi0",
                    "-drive", "id=cdrom0,file=$isoTarget,if=none,format=raw,readonly=on,media=cdrom",
                    "-device", "scsi-cd,bus=scsi0.0,drive=cdrom0,bootindex=1"
                )
            } else {
                args += listOf("-cdrom", isoTarget)
            }
        }

        // Networking (user-mode SLIRP — requires no root or TUN device on Android)
        if (config.networkEnabled) {
            args += listOf("-nic", "user,model=${config.networkAdapter.qemuModel}")
        } else {
            args += listOf("-nic", "none")
        }

        // Audio over SDL2/OpenSL ES (playback-only speaker device so SDL2 never opens an Android microphone capture stream)
        if (config.audioEnabled) {
            args += listOf("-audiodev", "sdl,id=snd0", "-device", "intel-hda", "-device", "hda-output,audiodev=snd0")
        }

        // Extra user flags (whitespace split, preserving quoted tokens)
        if (config.extraArgs.isNotBlank()) {
            args += tokenizeExtraArgs(config.extraArgs)
        }

        return args
    }

    fun formatCommandLine(config: VmConfig): String =
        buildArgs(config).joinToString(" ") { token ->
            if (token.any { it.isWhitespace() }) "\"$token\"" else token
        }

    internal fun tokenizeExtraArgs(raw: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in raw.trim()) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
