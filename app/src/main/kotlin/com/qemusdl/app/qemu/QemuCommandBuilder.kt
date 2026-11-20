package com.qemusdl.app.qemu

import com.qemusdl.app.data.DiskInterface
import com.qemusdl.app.data.DisplayDevice
import com.qemusdl.app.data.VmConfig

/**
 * Generates exact `qemu-system-x86_64` CLI arguments targeting an in-process SDL2 viewport
 * (`-display sdl`) with VNC explicitly omitted.
 */
object QemuCommandBuilder {

    fun buildArgs(
        config: VmConfig,
        resolvedDiskPath: String? = null,
        resolvedIsoPath: String? = null,
        firmwareDir: String? = null
    ): List<String> {
        val args = mutableListOf<String>()
        args += "qemu-system-x86_64"

        // Firmware ROM & keymaps search directory (SeaBIOS, VGABIOS)
        if (!firmwareDir.isNullOrBlank()) {
            args += listOf("-L", firmwareDir)
        }

        // Machine & TCG software CPU acceleration (Android lacks unprivileged /dev/kvm)
        args += listOf("-machine", "${config.machineType.qemuFlag},accel=tcg")
        args += listOf("-cpu", config.cpuModel.ifBlank { "qemu64" })
        args += listOf("-smp", config.cpuCores.coerceIn(1, 8).toString())
        args += listOf("-m", config.memoryMb.coerceIn(128, 8192).toString())

        // Direct SDL2 viewport instead of VNC!
        // full-screen=on sets SDL_WINDOW_FULLSCREEN_DESKTOP in QEMU's sdl2.c so that guest mode
        // switches (SeaBIOS 720x400 -> bootloader 640x480 -> desktop) use SDL_RenderSetLogicalSize
        // without SDL_SetWindowSize shrinking the Android window dimensions.
        // show-cursor=on keeps SDL2 mouse coordinates in window space without Android pointer capture.
        args += listOf("-display", "sdl,window-close=off,full-screen=on,show-cursor=on")

        // Graphics adapter
        when (config.displayDevice) {
            DisplayDevice.VIRTIO_VGA -> {
                args += listOf(
                    "-device",
                    "virtio-vga,xres=${config.resolution.width},yres=${config.resolution.height}"
                )
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

        // Boot order
        args += listOf("-boot", config.bootOrder.qemuFlag)

        // USB controller & absolute pointer tablet (seamless SDL2 touch/mouse tracking)
        if (config.usbTabletEnabled) {
            args += listOf("-usb", "-device", "usb-tablet")
        }

        // Primary Disk Image
        val diskTarget = (resolvedDiskPath ?: config.diskPathOrUri).trim()
        if (diskTarget.isNotEmpty()) {
            val format = if (diskTarget.endsWith(".qcow2", ignoreCase = true)) "qcow2" else "raw"
            if (config.diskInterface == DiskInterface.SATA) {
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
            args += listOf("-cdrom", isoTarget)
        }

        // Networking (user-mode SLIRP — requires no root or TUN device on Android)
        if (config.networkEnabled) {
            args += listOf("-nic", "user,model=${config.networkAdapter.qemuModel}")
        } else {
            args += listOf("-nic", "none")
        }

        // Audio over SDL2/OpenSL ES
        if (config.audioEnabled) {
            args += listOf("-audiodev", "sdl,id=snd0", "-device", "intel-hda", "-device", "hda-duplex,audiodev=snd0")
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
