package com.qemusdl.app.usb

import com.qemusdl.app.data.UsbPassthroughDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbPassthroughDeviceTest {

    @Test
    fun usbPassthroughDevice_formatsHexCorrectly() {
        val dev = UsbPassthroughDevice(
            vendorId = 0x04b8,
            productId = 0x0005,
            productName = "Receipt Printer",
            manufacturerName = "EPSON"
        )
        assertEquals("04b8", dev.vendorIdHex)
        assertEquals("0005", dev.productIdHex)
        assertEquals("EPSON Receipt Printer", dev.displayName)
    }

    @Test
    fun usbPassthroughDevice_fallbackDisplayName() {
        val dev = UsbPassthroughDevice(
            vendorId = 0x1234,
            productId = 0x5678,
            deviceName = "/dev/bus/usb/001/003"
        )
        assertEquals("/dev/bus/usb/001/003", dev.displayName)

        val devNoName = UsbPassthroughDevice(
            vendorId = 0x1234,
            productId = 0x5678
        )
        assertEquals("USB Device [1234:5678]", devNoName.displayName)
    }
}
