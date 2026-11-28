package com.qemusdl.app.qemu

import com.qemusdl.app.data.BootOrder
import com.qemusdl.app.data.DiskInterface
import com.qemusdl.app.data.DisplayDevice
import com.qemusdl.app.data.GuestArchitecture
import com.qemusdl.app.data.MachineType
import com.qemusdl.app.data.OsPreset
import com.qemusdl.app.data.VmConfig

/**
 * Generates exact `qemu-system-x86_64`, `qemu-system-aarch64`, or `qemu-system-ppc` CLI
 * arguments targeting an in-process SDL2 viewport (`-display sdl`) with VNC explicitly omitted.
 */
object QemuCommandBuilder {

    const val DEFAULT_TCG_TB_SIZE_MB = 128

    /**
     * Returns true when this VM attaches an absolute HID tablet (`usb-tablet`) that QEMU routes
     * absolute pointer coordinates to. Classic Mac OS 9 has no USB absolute tablet driver and uses
     * QEMU's built-in relative OHCI `usb-mouse` (mac99,via=pmu) or `adb-mouse` (pmu-adb/cuda/g3beige).
     */
    fun usesAbsolutePointer(config: VmConfig): Boolean = when (config.architecture) {
        GuestArchitecture.AARCH64 -> true
        GuestArchitecture.X86_64 -> config.usbTabletEnabled
        GuestArchitecture.PPC -> config.usbTabletEnabled && config.osPreset != OsPreset.MACOS_9
    }

    fun buildArgs(
        config: VmConfig,
        resolvedDiskPath: String? = null,
        resolvedIsoPath: String? = null,
        resolvedVirtioIsoPath: String? = null,
        firmwareDir: String? = null,
        edk2CodePath: String? = null,
        edk2VarsPath: String? = null,
        activeUsbDeviceFds: Map<String, Int> = emptyMap(),
        tcgTbSizeMb: Int = DEFAULT_TCG_TB_SIZE_MB,
        guestAgentSocketPath: String? = null,
        virtio9pHostPath: String? = null
    ): List<String> {
        val args = mutableListOf<String>()
        val arch = config.architecture
        args += arch.qemuBinaryName

        // Firmware ROM & keymaps search directory (SeaBIOS, VGABIOS, EDK2 AArch64 UEFI, OpenBIOS PPC + qemu_vga.ndrv)
        if (!firmwareDir.isNullOrBlank()) {
            args += listOf("-L", firmwareDir)
        }

        // Machine & TCG software CPU acceleration (Android lacks unprivileged /dev/kvm)
        val effectiveMachine = if (config.machineType.supportedArch == arch) {
            config.machineType
        } else {
            MachineType.defaultForArch(arch)
        }
        // PowerPC mac99 and g3beige machines support at most 1 CPU core (mc->max_cpus = 1 in QEMU)
        val effectiveCores = if (arch == GuestArchitecture.PPC) {
            1
        } else {
            config.cpuCores.coerceIn(1, 8)
        }
        val tcgThreadMode = if (effectiveCores > 1) "multi" else "single"
        val effectiveTbSizeMb = tcgTbSizeMb.coerceIn(64, 512)
        args += listOf("-machine", effectiveMachine.qemuFlag)
        args += listOf("-accel", "tcg,thread=$tcgThreadMode,tb-size=$effectiveTbSizeMb")

        val effectiveCpu = config.cpuModel.ifBlank { arch.defaultCpuModel }
        args += listOf("-cpu", effectiveCpu)
        args += listOf("-smp", effectiveCores.toString())

        // Classic PowerPC Mac machines (mac99 / g3beige) support up to 2048 MB RAM (512 MB recommended for Mac OS 9)
        val effectiveMemoryMb = if (arch == GuestArchitecture.PPC) {
            config.memoryMb.coerceIn(64, 2048)
        } else {
            config.memoryMb.coerceIn(128, 8192)
        }
        args += listOf("-m", effectiveMemoryMb.toString())

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

        // PowerPC OpenBIOS PROM environment for clean Mac OS 9 / Mac OS X auto-boot and qemu_vga.ndrv driver loading
        if (arch == GuestArchitecture.PPC) {
            args += listOf("-prom-env", "auto-boot?=true")
            if (config.displayDevice == DisplayDevice.STD || !config.displayDevice.isSupportedOn(arch)) {
                args += listOf("-prom-env", "vga-ndrv?=true")
            }
        }

        // Direct SDL2 viewport instead of VNC!
        // When Venus 3D / virgl acceleration is enabled (x86_64 / AArch64), gl=es enables SDL2 OpenGLES texture streaming.
        // full-screen=on sets SDL_WINDOW_FULLSCREEN_DESKTOP in QEMU's sdl2.c so that guest mode
        // switches use SDL_RenderSetLogicalSize without SDL_SetWindowSize shrinking the Android window dimensions.
        // show-cursor=on keeps SDL2 mouse coordinates in window space without Android pointer capture.
        val isVenusActive = arch != GuestArchitecture.PPC && (
            config.venus3dEnabled ||
                config.displayDevice == DisplayDevice.VIRTIO_VGA_GL ||
                config.displayDevice == DisplayDevice.VIRTIO_GPU_GL_PCI
            )
        val glArg = if (isVenusActive) "gl=es," else ""
        args += listOf("-display", "sdl,${glArg}window-close=off,full-screen=on,show-cursor=on")

        // Graphics adapter
        val hostmemStr = "${config.venusHostMemoryMb.coerceIn(256, 8192)}M"
        val resOpts = "xres=${config.resolution.width},yres=${config.resolution.height}"
        when (arch) {
            GuestArchitecture.PPC -> {
                // OpenBIOS + qemu_vga.ndrv uses -g WxHx32 (32-bit Millions of Colors) for Mac OS 9 / OS X
                args += listOf("-g", "${config.resolution.width}x${config.resolution.height}x32")
                when (config.displayDevice) {
                    DisplayDevice.ATI_VGA -> args += listOf("-vga", "none", "-device", "ati-vga")
                    DisplayDevice.BOCHS_DISPLAY -> args += listOf("-vga", "none", "-device", "bochs-display")
                    else -> args += listOf("-vga", "std")
                }
            }
            GuestArchitecture.AARCH64 -> {
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
            }
            GuestArchitecture.X86_64 -> {
                when (config.displayDevice) {
                    DisplayDevice.VIRTIO_VGA_GL -> {
                        val venusOpts = if (config.venus3dEnabled) ",hostmem=$hostmemStr,blob=true,venus=true" else ""
                        args += listOf("-device", "virtio-vga-gl$venusOpts,$resOpts")
                    }
                    DisplayDevice.VIRTIO_GPU_GL_PCI -> {
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
                    DisplayDevice.ATI_VGA -> {
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
        }

        // Boot order (PowerPC OpenBIOS expects concise 'd' / 'c' / 'order=dc' rather than x86 SeaBIOS menu=on)
        if (arch == GuestArchitecture.PPC) {
            val ppcBootFlag = when (config.bootOrder) {
                BootOrder.DISK_FIRST -> "c"
                BootOrder.CDROM_FIRST -> "d"
                BootOrder.BOOT_MENU -> "order=dc"
            }
            args += listOf("-boot", ppcBootFlag)
        } else {
            args += listOf("-boot", config.bootOrder.qemuFlag)
        }

        // USB controller & HID input
        val enabledUsbDevices = config.usbPassthroughDevices.filter { it.enabled }
        val hasXhci = arch != GuestArchitecture.PPC && (
            config.usbXhciEnabled ||
                arch == GuestArchitecture.AARCH64 ||
                enabledUsbDevices.isNotEmpty()
            )
        when (arch) {
            GuestArchitecture.PPC -> {
                // mac99 automatically creates pci-ohci + usb-kbd + usb-mouse (via=pmu) or adb-keyboard + adb-mouse (cuda/pmu-adb).
                // g3beige only creates pci-ohci when -usb is passed.
                // Classic Mac OS 9 has NO driver for QEMU's usb-tablet, and attaching usb-tablet hijacks
                // pointer input away from usb-mouse / adb-mouse (and exhausts OHCI's 3 ports with usb-audio).
                val attachPpcTablet = usesAbsolutePointer(config)
                val needsOhciBus = attachPpcTablet || config.audioEnabled || enabledUsbDevices.isNotEmpty()
                if (needsOhciBus && effectiveMachine == MachineType.G3_BEIGE) {
                    args += "-usb"
                }
                if (attachPpcTablet) {
                    args += listOf("-device", "usb-tablet")
                }
            }
            GuestArchitecture.AARCH64 -> {
                // On AArch64 (-machine virt), always attach qemu-xhci + usb-kbd + usb-tablet since virt lacks PS/2
                args += listOf("-device", "qemu-xhci,id=xhci")
                args += listOf("-device", "usb-kbd,bus=xhci.0")
                args += listOf("-device", "usb-tablet,bus=xhci.0")
            }
            GuestArchitecture.X86_64 -> {
                if (hasXhci) {
                    args += listOf("-device", "qemu-xhci,id=xhci")
                    if (config.usbTabletEnabled) {
                        args += listOf("-device", "usb-tablet,bus=xhci.0")
                    }
                } else if (config.usbTabletEnabled) {
                    args += listOf("-usb", "-device", "usb-tablet")
                }
            }
        }

        // USB Host Passthrough Devices (via UsbManager openDevice /proc/self/fd/<fd> or vendor/product ID)
        val usbBus = if (hasXhci) "xhci.0" else "usb-bus.0"
        for (device in enabledUsbDevices) {
            val key = "${device.vendorIdHex}:${device.productIdHex}"
            val activeFd = activeUsbDeviceFds[key] ?: activeUsbDeviceFds[device.deviceName]
            if (activeFd != null && activeFd >= 0) {
                val busArg = if (arch == GuestArchitecture.PPC) "" else "bus=$usbBus,"
                args += listOf("-device", "usb-host,${busArg}hostdevice=/proc/self/fd/$activeFd")
            } else {
                val busArg = if (arch == GuestArchitecture.PPC) "" else "bus=$usbBus,"
                args += listOf(
                    "-device",
                    "usb-host,${busArg}vendorid=0x${device.vendorIdHex},productid=0x${device.productIdHex}"
                )
            }
        }

        // Primary Disk Image (cache=writeback + aio=threads + 8 MiB QCOW2 L2 cache; VirtIO uses dedicated IOThread)
        val diskTarget = (resolvedDiskPath ?: config.diskPathOrUri).trim()
        if (diskTarget.isNotEmpty()) {
            val format = if (diskTarget.endsWith(".qcow2", ignoreCase = true)) "qcow2" else "raw"
            val qcow2CacheOpt = if (format == "qcow2") ",l2-cache-size=8M" else ""
            when {
                config.diskInterface == DiskInterface.VIRTIO -> {
                    args += listOf(
                        "-object", "iothread,id=io1",
                        "-drive", "id=disk0,file=$diskTarget,if=none,format=$format,cache=writeback,aio=threads$qcow2CacheOpt",
                        "-device", "virtio-blk-pci,drive=disk0,iothread=io1"
                    )
                }
                arch == GuestArchitecture.PPC -> {
                    // PowerMac mac99 / g3beige use built-in MacIO IDE controller (index=0 = primary master hard disk)
                    args += listOf(
                        "-drive",
                        "file=$diskTarget,if=ide,index=0,format=$format,media=disk,cache=writeback,aio=threads$qcow2CacheOpt"
                    )
                }
                config.diskInterface == DiskInterface.SATA || (arch == GuestArchitecture.AARCH64 && config.diskInterface == DiskInterface.IDE) -> {
                    args += listOf("-device", "ahci,id=ahci")
                    args += listOf(
                        "-drive",
                        "id=disk0,file=$diskTarget,if=none,format=$format,cache=writeback,aio=threads$qcow2CacheOpt"
                    )
                    args += listOf("-device", "ide-hd,drive=disk0,bus=ahci.0")
                }
                else -> {
                    args += listOf(
                        "-drive",
                        "file=$diskTarget,if=${config.diskInterface.qemuIf},format=$format,cache=writeback,aio=threads$qcow2CacheOpt"
                    )
                }
            }
        }

        // Hardware RNG entropy source for x86_64 & AArch64 (prevents Linux boot entropy stalls; ignored by other OSes)
        if (arch != GuestArchitecture.PPC) {
            args += listOf("-device", "virtio-rng-pci")
        }

        // Bootable ISO / CD-ROM
        val isoTarget = (resolvedIsoPath ?: config.isoPathOrUri).trim()
        if (isoTarget.isNotEmpty()) {
            when (arch) {
                GuestArchitecture.PPC -> {
                    // Attach Mac OS 9 / PPC CD-ROM on MacIO secondary IDE master (index=2) for OpenBIOS -boot d
                    args += listOf(
                        "-drive",
                        "file=$isoTarget,if=ide,index=2,format=raw,media=cdrom"
                    )
                }
                GuestArchitecture.AARCH64 -> {
                    // ARM virt has no default IDE bus; attach ISO via virtio-scsi CD-ROM so EDK2 UEFI boots it cleanly
                    args += listOf(
                        "-device", "virtio-scsi-pci,id=scsi0",
                        "-drive", "id=cdrom0,file=$isoTarget,if=none,format=raw,readonly=on,media=cdrom",
                        "-device", "scsi-cd,bus=scsi0.0,drive=cdrom0,bootindex=1"
                    )
                }
                GuestArchitecture.X86_64 -> {
                    args += listOf("-cdrom", isoTarget)
                }
            }
        }

        // Secondary CD-ROM: Open-source VirtIO Drivers & Guest Tools ISO (virtio-win.iso)
        val virtioIsoTarget = (resolvedVirtioIsoPath ?: config.virtioDriversIsoPath).trim()
        if (config.attachVirtioDriversIso && virtioIsoTarget.isNotEmpty()) {
            if (arch == GuestArchitecture.X86_64) {
                // Secondary IDE/AHCI slave slot (index=3) works alongside -cdrom (index=2) on both Q35 and i440FX
                args += listOf(
                    "-drive",
                    "file=$virtioIsoTarget,if=ide,index=3,format=raw,media=cdrom,readonly=on"
                )
            } else if (arch == GuestArchitecture.AARCH64) {
                // Attach via USB mass storage on qemu-xhci so Windows 11 ARM64 Setup sees VirtIO drivers with zero extra storage drivers
                args += listOf(
                    "-drive", "id=virtiocd,file=$virtioIsoTarget,if=none,format=raw,readonly=on,media=cdrom",
                    "-device", "usb-storage,bus=xhci.0,drive=virtiocd,removable=on"
                )
            }
        }

        // Shared virtio-serial-pci controller for QEMU Guest Agent and/or SPICE qemu-vdagent clipboard
        val qgaSocket = guestAgentSocketPath?.trim().orEmpty()
        val attachQga = arch != GuestArchitecture.PPC && config.guestAgentEnabled && qgaSocket.isNotEmpty()
        val attachSpiceClipboard = arch != GuestArchitecture.PPC && config.spiceClipboardEnabled
        if (attachQga || attachSpiceClipboard) {
            args += listOf("-device", "virtio-serial-pci,id=virtio-serial0")
        }

        // QEMU Guest Agent (org.qemu.guest_agent.0) for graceful shutdown, clock sync & live telemetry
        if (attachQga) {
            args += listOf(
                "-chardev", "socket,path=$qgaSocket,server=on,wait=off,id=qga0",
                "-device", "virtserialport,bus=virtio-serial0.0,chardev=qga0,name=org.qemu.guest_agent.0"
            )
        }

        // Built-in SPICE qemu-vdagent chardev (com.redhat.spice.0) for bidirectional Android <-> guest clipboard
        // Keep mouse=off so SDL2 / usb-tablet pointer handling remains authoritative.
        if (attachSpiceClipboard) {
            args += listOf(
                "-chardev", "qemu-vdagent,id=vdagent0,name=vdagent,clipboard=on,mouse=off",
                "-device", "virtserialport,bus=virtio-serial0.0,chardev=vdagent0,name=com.redhat.spice.0"
            )
        }

        // VirtIO-9p native Linux shared folder (security_model=none works cleanly on Android FUSE/sdcardfs)
        val ninePPath = virtio9pHostPath?.trim().orEmpty()
        if (arch != GuestArchitecture.PPC && config.virtio9pEnabled && ninePPath.isNotEmpty()) {
            args += listOf(
                "-fsdev", "local,id=fsdev0,path=$ninePPath,security_model=none",
                "-device", "virtio-9p-pci,fsdev=fsdev0,mount_tag=hostshare"
            )
        }

        // Networking (user-mode SLIRP — requires no root or TUN device on Android)
        if (config.networkEnabled) {
            args += listOf("-nic", "user,model=${config.networkAdapter.qemuModel}")
        } else {
            args += listOf("-nic", "none")
        }

        // Audio over SDL2/OpenSL ES
        // On PPC (Mac OS 9 / OS X), use usb-audio (native Apple USB Audio Class driver) instead of intel-hda
        if (config.audioEnabled) {
            if (arch == GuestArchitecture.PPC) {
                args += listOf("-audiodev", "sdl,id=snd0", "-device", "usb-audio,audiodev=snd0")
            } else {
                args += listOf("-audiodev", "sdl,id=snd0", "-device", "intel-hda", "-device", "hda-output,audiodev=snd0")
            }
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
