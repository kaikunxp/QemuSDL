package com.qemusdl.app.qemu

import com.qemusdl.app.data.DiskInterface
import com.qemusdl.app.data.DisplayDevice
import com.qemusdl.app.data.DisplayResolution
import com.qemusdl.app.data.MachineType
import com.qemusdl.app.data.VmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuCommandBuilderTest {

    @Test
    fun buildArgs_usesSdlDisplayAndNeverVnc() {
        val vm = VmConfig(
            name = "Alpine Test",
            cpuCores = 4,
            memoryMb = 2048,
            machineType = MachineType.Q35,
            displayDevice = DisplayDevice.VIRTIO_VGA,
            resolution = DisplayResolution.HD_1280x720,
            sdlGlAcceleration = true,
            usbTabletEnabled = true,
            networkEnabled = true,
            audioEnabled = true
        )

        val args = QemuCommandBuilder.buildArgs(
            config = vm,
            resolvedDiskPath = "/data/disks/alpine.qcow2",
            resolvedIsoPath = "/proc/self/fd/42",
            firmwareDir = "/data/user/0/com.qemusdl.app/files/qemu-firmware"
        )

        assertEquals("qemu-system-x86_64", args.first())
        val lIdx = args.indexOf("-L")
        assertTrue("Expected -L firmware flag", lIdx >= 0)
        assertEquals("/data/user/0/com.qemusdl.app/files/qemu-firmware", args[lIdx + 1])

        val displayIdx = args.indexOf("-display")
        assertTrue("Expected -display flag", displayIdx >= 0)
        assertEquals("sdl,window-close=off,full-screen=on,show-cursor=on", args[displayIdx + 1])
        assertFalse("VNC must not be used", args.any { it.contains("vnc", ignoreCase = true) })
        assertTrue(args.contains("virtio-vga,xres=1280,yres=720"))
        assertTrue(args.contains("tcg,thread=multi,tb-size=128"))
        assertTrue(args.contains("iothread,id=io1"))
        assertTrue(args.contains("virtio-blk-pci,drive=disk0,iothread=io1"))
        assertTrue(args.contains("virtio-rng-pci"))
        assertTrue(args.any { it.contains("usb-tablet") })
        assertTrue(args.contains("user,model=e1000"))
        assertTrue(args.contains("/proc/self/fd/42"))
        assertTrue(
            args.any {
                it.contains("file=/data/disks/alpine.qcow2") &&
                    it.contains("format=qcow2") &&
                    it.contains("cache=writeback") &&
                    it.contains("aio=threads") &&
                    it.contains("l2-cache-size=8M")
            }
        )
    }

    @Test
    fun buildArgs_supportsSataAhciAndQuotedExtraArgs() {
        val vm = VmConfig(
            diskInterface = DiskInterface.SATA,
            sdlGlAcceleration = false,
            displayDevice = DisplayDevice.STD,
            networkEnabled = false,
            audioEnabled = false,
            extraArgs = "-rtc base=utc -name \"My Test VM\""
        )

        val args = QemuCommandBuilder.buildArgs(vm, resolvedDiskPath = "/tmp/win.img", tcgTbSizeMb = 256)
        assertTrue(args.contains("sdl,window-close=off,full-screen=on,show-cursor=on"))
        assertTrue(args.contains("tcg,thread=multi,tb-size=256"))
        assertTrue(args.contains("ahci,id=ahci"))
        assertTrue(
            args.any {
                it.contains("file=/tmp/win.img") &&
                    it.contains("cache=writeback") &&
                    it.contains("aio=threads") &&
                    !it.contains("l2-cache-size")
            }
        )
        assertTrue(args.contains("none"))
        assertTrue(args.contains("My Test VM"))
    }

    @Test
    fun buildArgs_usbPassthroughWithActiveHostFd() {
        val usbDev = com.qemusdl.app.data.UsbPassthroughDevice(
            vendorId = 0x0781,
            productId = 0x5583,
            deviceName = "/dev/bus/usb/001/002",
            productName = "Ultra Fit",
            manufacturerName = "SanDisk",
            enabled = true
        )
        val vm = VmConfig(
            name = "USB Test VM",
            usbXhciEnabled = true,
            usbTabletEnabled = true,
            usbPassthroughDevices = listOf(usbDev)
        )

        val args = QemuCommandBuilder.buildArgs(
            config = vm,
            activeUsbDeviceFds = mapOf("0781:5583" to 24)
        )

        assertTrue(args.contains("qemu-xhci,id=xhci"))
        assertTrue(args.contains("usb-tablet,bus=xhci.0"))
        assertTrue(args.contains("usb-host,bus=xhci.0,hostdevice=/proc/self/fd/24"))
    }

    @Test
    fun buildArgs_usbPassthroughFallbackWithVendorProductId() {
        val usbDev = com.qemusdl.app.data.UsbPassthroughDevice(
            vendorId = 0x0403,
            productId = 0x6001,
            productName = "FT232R USB UART",
            enabled = true
        )
        val vm = VmConfig(
            name = "USB Serial Test",
            usbXhciEnabled = true,
            usbTabletEnabled = false,
            usbPassthroughDevices = listOf(usbDev)
        )

        val args = QemuCommandBuilder.buildArgs(vm)

        assertTrue(args.contains("qemu-xhci,id=xhci"))
        assertTrue(args.contains("usb-host,bus=xhci.0,vendorid=0x0403,productid=0x6001"))
        assertFalse(args.contains("usb-tablet"))
    }

    @Test
    fun buildArgs_venus3dHardwareAcceleration() {
        val vm = VmConfig(
            name = "Venus 3D Test",
            displayDevice = DisplayDevice.VIRTIO_VGA_GL,
            venus3dEnabled = true,
            venusHostMemoryMb = 4096,
            resolution = DisplayResolution.FHD_1920x1080
        )

        val args = QemuCommandBuilder.buildArgs(vm)

        val displayIdx = args.indexOf("-display")
        assertTrue(displayIdx >= 0)
        assertTrue(
            "Display should request gl=es for hardware accelerated viewport",
            args[displayIdx + 1].contains("gl=es")
        )

        val deviceIdx = args.indexOf("-device")
        assertTrue(deviceIdx >= 0)
        assertTrue(
            args.any {
                it.contains("virtio-vga-gl") &&
                        it.contains("hostmem=4096M") &&
                        it.contains("blob=true") &&
                        it.contains("venus=true") &&
                        it.contains("xres=1920,yres=1080")
            }
        )
    }

    @Test
    fun buildArgs_aarch64VirtWithPflashUefiAndVenusGpu() {
        val vm = VmConfig(
            name = "Alpine AArch64 Test",
            architecture = com.qemusdl.app.data.GuestArchitecture.AARCH64,
            cpuModel = "max",
            cpuCores = 4,
            memoryMb = 2048,
            machineType = MachineType.VIRT,
            displayDevice = DisplayDevice.VIRTIO_GPU_PCI,
            venus3dEnabled = true,
            venusHostMemoryMb = 2048,
            resolution = DisplayResolution.HD_1280x720,
            usbTabletEnabled = false // Should still attach usb-kbd and usb-tablet on AArch64 virt
        )

        val args = QemuCommandBuilder.buildArgs(
            config = vm,
            resolvedDiskPath = "/data/disks/alpine_arm64.qcow2",
            resolvedIsoPath = "/data/isos/alpine-virt-aarch64.iso",
            firmwareDir = "/data/files/qemu-firmware",
            edk2CodePath = "/data/files/qemu-firmware/edk2-aarch64-code.fd",
            edk2VarsPath = "/data/files/nvram/vm1-edk2-arm-vars.fd"
        )

        assertEquals("qemu-system-aarch64", args.first())
        val machineIdx = args.indexOf("-machine")
        assertEquals("virt", args[machineIdx + 1])
        val accelIdx = args.indexOf("-accel")
        assertEquals("tcg,thread=multi,tb-size=128", args[accelIdx + 1])
        val cpuIdx = args.indexOf("-cpu")
        assertEquals("max", args[cpuIdx + 1])

        // Verify EDK2 UEFI pflash unit=0 (code, readonly) and unit=1 (per-VM writable vars)
        assertTrue(args.contains("if=pflash,format=raw,unit=0,readonly=on,file=/data/files/qemu-firmware/edk2-aarch64-code.fd"))
        assertTrue(args.contains("if=pflash,format=raw,unit=1,file=/data/files/nvram/vm1-edk2-arm-vars.fd"))

        // Verify virtio-gpu-gl-pci with Venus Vulkan 3D passthrough instead of virtio-vga
        assertTrue(
            args.any {
                it.contains("virtio-gpu-gl-pci") &&
                        it.contains("hostmem=2048M") &&
                        it.contains("blob=true") &&
                        it.contains("venus=true") &&
                        it.contains("xres=1280,yres=720")
            }
        )
        assertFalse(args.any { it.contains("virtio-vga") })

        // Verify xHCI + usb-kbd + usb-tablet always attached for AArch64 virt
        assertTrue(args.contains("qemu-xhci,id=xhci"))
        assertTrue(args.contains("usb-kbd,bus=xhci.0"))
        assertTrue(args.contains("usb-tablet,bus=xhci.0"))

        // Verify virtio-scsi CD-ROM for AArch64 ISO
        assertTrue(args.contains("virtio-scsi-pci,id=scsi0"))
        assertTrue(args.any { it.contains("file=/data/isos/alpine-virt-aarch64.iso") && it.contains("media=cdrom") })
    }

    @Test
    fun buildArgs_ppcMac99PmuForMacOs9() {
        val vm = VmConfig(
            name = "Mac OS 9.2.2",
            architecture = com.qemusdl.app.data.GuestArchitecture.PPC,
            osPreset = com.qemusdl.app.data.OsPreset.MACOS_9,
            cpuModel = "g4",
            cpuCores = 4, // mac99 max_cpus = 1; must be clamped to 1
            memoryMb = 512,
            machineType = MachineType.MAC99_PMU,
            displayDevice = DisplayDevice.STD,
            resolution = DisplayResolution.XGA_1024x768,
            bootOrder = com.qemusdl.app.data.BootOrder.CDROM_FIRST,
            diskInterface = DiskInterface.IDE,
            networkEnabled = true,
            networkAdapter = com.qemusdl.app.data.NetworkAdapterModel.SUNGEM,
            audioEnabled = true,
            usbXhciEnabled = true, // Must NOT attach qemu-xhci on Mac OS 9
            usbTabletEnabled = true
        )

        val args = QemuCommandBuilder.buildArgs(
            config = vm,
            resolvedDiskPath = "/data/disks/macos9.qcow2",
            resolvedIsoPath = "/data/isos/macos922.iso",
            firmwareDir = "/data/files/qemu-firmware"
        )

        assertEquals("qemu-system-ppc", args.first())
        val machineIdx = args.indexOf("-machine")
        assertEquals("mac99,via=pmu", args[machineIdx + 1])
        val accelIdx = args.indexOf("-accel")
        assertEquals("tcg,thread=single,tb-size=128", args[accelIdx + 1])
        val cpuIdx = args.indexOf("-cpu")
        assertEquals("g4", args[cpuIdx + 1])
        val smpIdx = args.indexOf("-smp")
        assertEquals("1", args[smpIdx + 1])
        val memIdx = args.indexOf("-m")
        assertEquals("512", args[memIdx + 1])

        // OpenBIOS prom-env + standard VGA + 32-bit resolution mode for qemu_vga.ndrv
        assertTrue(args.contains("auto-boot?=true"))
        assertTrue(args.contains("vga-ndrv?=true"))
        assertTrue(args.contains("1024x768x32"))
        val vgaIdx = args.indexOf("-vga")
        assertEquals("std", args[vgaIdx + 1])

        // OpenBIOS boot flag '-boot d' (no SeaBIOS menu=on)
        val bootIdx = args.indexOf("-boot")
        assertEquals("d", args[bootIdx + 1])

        // No xHCI / no usb-tablet / no Venus GL / no intel-hda / no virtio-rng on Mac OS 9
        // (Mac OS 9 uses mac99's built-in OHCI usb-mouse; attaching usb-tablet hijacks pointer input!)
        assertFalse(args.any { it.contains("qemu-xhci") })
        assertFalse(args.contains("usb-tablet"))
        assertFalse(QemuCommandBuilder.usesAbsolutePointer(vm))
        assertFalse(args.any { it.contains("gl=es") })
        assertFalse(args.any { it.contains("intel-hda") })
        assertFalse(args.contains("virtio-rng-pci"))

        // IDE disk (index=0) + IDE CD-ROM (index=2), SunGEM NIC, USB audio
        assertTrue(args.contains("file=/data/disks/macos9.qcow2,if=ide,index=0,format=qcow2,media=disk,cache=writeback,aio=threads,l2-cache-size=8M"))
        assertTrue(args.contains("file=/data/isos/macos922.iso,if=ide,index=2,format=raw,media=cdrom"))
        assertTrue(args.contains("user,model=sungem"))
        assertTrue(args.contains("usb-audio,audiodev=snd0"))
    }

    @Test
    fun buildArgs_ppcG3BeigeDiskBootWithAtiVgaAndAudio() {
        val vm = VmConfig(
            name = "Beige G3",
            architecture = com.qemusdl.app.data.GuestArchitecture.PPC,
            osPreset = com.qemusdl.app.data.OsPreset.MACOS_9,
            cpuModel = "g3",
            cpuCores = 1,
            memoryMb = 4096, // PPC max is 2048 MB; must be clamped
            machineType = MachineType.G3_BEIGE,
            displayDevice = DisplayDevice.ATI_VGA,
            resolution = DisplayResolution.SVGA_800x600,
            bootOrder = com.qemusdl.app.data.BootOrder.DISK_FIRST,
            diskInterface = DiskInterface.IDE,
            networkEnabled = true,
            networkAdapter = com.qemusdl.app.data.NetworkAdapterModel.RTL8139,
            audioEnabled = true,
            usbTabletEnabled = false
        )

        val args = QemuCommandBuilder.buildArgs(
            config = vm,
            resolvedDiskPath = "/data/disks/g3.qcow2",
            firmwareDir = "/data/files/qemu-firmware"
        )

        val machineIdx = args.indexOf("-machine")
        assertEquals("g3beige", args[machineIdx + 1])
        val accelIdx = args.indexOf("-accel")
        assertEquals("tcg,thread=single,tb-size=128", args[accelIdx + 1])
        val memIdx = args.indexOf("-m")
        assertEquals("2048", args[memIdx + 1])
        val bootIdx = args.indexOf("-boot")
        assertEquals("c", args[bootIdx + 1])

        // ATI Rage 128 Pro display instead of std VGA; no vga-ndrv? prom-env needed
        assertTrue(args.contains("ati-vga"))
        assertFalse(args.contains("vga-ndrv?=true"))

        // g3beige needs explicit -usb when usb-audio is enabled even if usb-tablet is off
        assertTrue(args.contains("-usb"))
        assertTrue(args.contains("usb-audio,audiodev=snd0"))
    }
}
