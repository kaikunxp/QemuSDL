package com.qemusdl.app.usb

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.qemusdl.app.data.UsbPassthroughDevice

/**
 * High-level descriptor for a physical USB device detected by Android's [UsbManager].
 */
data class UsbDeviceInfo(
    val device: UsbDevice,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val productName: String,
    val manufacturerName: String,
    val serialNumber: String?,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val deviceClassLabel: String,
    val hasPermission: Boolean,
    val isSystemHub: Boolean
) {
    val vendorIdHex: String
        get() = "%04x".format(vendorId and 0xFFFF)

    val productIdHex: String
        get() = "%04x".format(productId and 0xFFFF)

    val displayTitle: String
        get() = when {
            productName.isNotBlank() && manufacturerName.isNotBlank() -> "$manufacturerName $productName"
            productName.isNotBlank() -> productName
            manufacturerName.isNotBlank() -> manufacturerName
            else -> "USB [$vendorIdHex:$productIdHex]"
        }

    fun toConfig(enabled: Boolean = true): UsbPassthroughDevice =
        UsbPassthroughDevice(
            vendorId = vendorId,
            productId = productId,
            deviceName = deviceName,
            productName = productName,
            manufacturerName = manufacturerName,
            serialNumber = serialNumber.orEmpty(),
            enabled = enabled
        )
}

/**
 * Represents an open USB connection held by QemuSDL for in-process passthrough to QEMU / libusb.
 *
 * The open [connection] keeps the underlying kernel file descriptor valid. QEMU accesses it
 * via `/proc/self/fd/$fileDescriptor` without requiring root or direct /dev/bus/usb access.
 */
class ActiveUsbPassthroughSession(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val fileDescriptor: Int
) : AutoCloseable {

    val procFdPath: String
        get() = "/proc/self/fd/$fileDescriptor"

    override fun close() {
        runCatching { connection.close() }
    }
}

/**
 * Manages Android USB Host API interaction, permission granting, and device file descriptor
 * extraction for libusb / QEMU `usb-host` device passthrough.
 */
class UsbPassthroughManager(private val context: Context) {

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    val isUsbHostAvailable: Boolean
        get() = usbManager != null

    /**
     * Enumerates all physical USB devices currently plugged into the Android device via OTG/USB-C.
     */
    fun getConnectedDevices(): List<UsbDeviceInfo> {
        val manager = usbManager ?: return emptyList()
        val rawDevices = runCatching { manager.deviceList.values.toList() }.getOrDefault(emptyList())

        return rawDevices.map { dev ->
            val hasPerm = manager.hasPermission(dev)
            val isHub = dev.deviceClass == UsbConstants.USB_CLASS_HUB ||
                    (dev.interfaceCount > 0 && dev.getInterface(0).interfaceClass == UsbConstants.USB_CLASS_HUB)

            UsbDeviceInfo(
                device = dev,
                vendorId = dev.vendorId,
                productId = dev.productId,
                deviceName = dev.deviceName,
                productName = runCatching { dev.productName }.getOrNull().orEmpty(),
                manufacturerName = runCatching { dev.manufacturerName }.getOrNull().orEmpty(),
                serialNumber = if (hasPerm) runCatching { dev.serialNumber }.getOrNull() else null,
                deviceClass = dev.deviceClass,
                deviceSubclass = dev.deviceSubclass,
                deviceProtocol = dev.deviceProtocol,
                deviceClassLabel = classifyUsbDevice(dev),
                hasPermission = hasPerm,
                isSystemHub = isHub
            )
        }
    }

    /**
     * Checks if Android runtime permission has already been granted for the specified [device].
     */
    fun hasPermission(device: UsbDevice): Boolean =
        usbManager?.hasPermission(device) == true

    /**
     * Finds a connected [UsbDevice] matching the given [vendorId] and [productId].
     */
    fun findDevice(vendorId: Int, productId: Int): UsbDevice? {
        val manager = usbManager ?: return null
        return manager.deviceList.values.firstOrNull {
            it.vendorId == vendorId && it.productId == productId
        }
    }

    /**
     * Requests user permission to access the USB device via Android's system dialog.
     */
    fun requestPermission(
        activity: Activity,
        device: UsbDevice,
        onResult: (Boolean) -> Unit
    ) {
        val manager = usbManager
        if (manager == null) {
            onResult(false)
            return
        }

        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }

        val action = "$ACTION_USB_PERMISSION_PREFIX.${device.deviceId}.${System.currentTimeMillis()}"
        val filter = IntentFilter(action)

        var receiver: BroadcastReceiver? = null
        receiver = object : BroadcastReceiver() {
            override fun onReceive(recvContext: Context?, intent: Intent?) {
                if (intent?.action == action) {
                    val targetDev = IntentCompat.getParcelableExtra(
                        intent,
                        UsbManager.EXTRA_DEVICE,
                        UsbDevice::class.java
                    )
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (targetDev?.deviceId == device.deviceId) {
                        try {
                            activity.unregisterReceiver(this)
                        } catch (_: Exception) {}
                        onResult(granted)
                    }
                }
            }
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        ContextCompat.registerReceiver(activity, receiver, filter, flags)

        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            device.deviceId,
            Intent(action).setPackage(activity.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        runCatching {
            manager.requestPermission(device, pendingIntent)
        }.onFailure { err ->
            Log.e(TAG, "Failed to request USB permission", err)
            try {
                activity.unregisterReceiver(receiver)
            } catch (_: Exception) {}
            onResult(false)
        }
    }

    /**
     * Opens a physical USB device connection and returns an [ActiveUsbPassthroughSession]
     * holding the kernel file descriptor.
     */
    fun openSession(device: UsbDevice): ActiveUsbPassthroughSession? {
        val manager = usbManager ?: return null
        if (!manager.hasPermission(device)) {
            Log.w(TAG, "Cannot open USB device ${device.deviceName}: permission not granted")
            return null
        }

        val connection = manager.openDevice(device) ?: run {
            Log.e(TAG, "UsbManager.openDevice failed for ${device.deviceName}")
            return null
        }

        val fd = connection.fileDescriptor
        if (fd < 0) {
            Log.e(TAG, "Invalid file descriptor ($fd) for ${device.deviceName}")
            connection.close()
            return null
        }

        Log.i(TAG, "Opened USB device ${device.deviceName} -> FD $fd (/proc/self/fd/$fd)")
        return ActiveUsbPassthroughSession(device, connection, fd)
    }

    companion object {
        private const val TAG = "UsbPassthrough"
        private const val ACTION_USB_PERMISSION_PREFIX = "com.qemusdl.app.USB_PERMISSION"

        /**
         * Resolves a human-readable USB device class category string.
         */
        fun classifyUsbDevice(device: UsbDevice): String {
            var rawClass = device.deviceClass
            if (rawClass == UsbConstants.USB_CLASS_PER_INTERFACE && device.interfaceCount > 0) {
                rawClass = device.getInterface(0).interfaceClass
            }

            return when (rawClass) {
                UsbConstants.USB_CLASS_MASS_STORAGE -> "Mass Storage (Flash Drive / SSD)"
                UsbConstants.USB_CLASS_HID -> "HID (Keyboard / Mouse / Gamepad)"
                UsbConstants.USB_CLASS_COMM -> "CDC Serial / Modem"
                UsbConstants.USB_CLASS_AUDIO -> "USB Audio (DAC / Mic)"
                UsbConstants.USB_CLASS_VIDEO -> "UVC Video (Webcam / Capture)"
                UsbConstants.USB_CLASS_PRINTER -> "Printer"
                UsbConstants.USB_CLASS_HUB -> "USB Hub"
                UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "Wireless Controller (BT / Wi-Fi)"
                UsbConstants.USB_CLASS_CSCID -> "Smart Card (CCID)"
                UsbConstants.USB_CLASS_VENDOR_SPEC -> "Vendor Specific Device"
                else -> "USB Device (Class 0x%02X)".format(rawClass)
            }
        }
    }
}
