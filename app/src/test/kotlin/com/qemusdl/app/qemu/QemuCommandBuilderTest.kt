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
        assertTrue(args.contains("usb-tablet"))
        assertTrue(args.contains("user,model=e1000"))
        assertTrue(args.contains("/proc/self/fd/42"))
        assertTrue(args.any { it.contains("file=/data/disks/alpine.qcow2") && it.contains("format=qcow2") })
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

        val args = QemuCommandBuilder.buildArgs(vm, resolvedDiskPath = "/tmp/win.img")
        assertTrue(args.contains("sdl,window-close=off,full-screen=on,show-cursor=on"))
        assertTrue(args.contains("ahci,id=ahci"))
        assertTrue(args.contains("none"))
        assertTrue(args.contains("My Test VM"))
    }
}
