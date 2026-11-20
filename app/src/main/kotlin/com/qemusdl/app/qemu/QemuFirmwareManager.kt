package com.qemusdl.app.qemu

import android.content.Context
import java.io.File

/**
 * Extracts bundled x86_64 SeaBIOS, VGABIOS, PXE ROMs, and keymaps from
 * `assets/qemu-firmware/` into `filesDir/qemu-firmware/` so QEMU can boot
 * via `-L <firmwareDir>` on Android without requiring `/usr/share/qemu`.
 */
object QemuFirmwareManager {

    private const val ASSET_ROOT = "qemu-firmware"
    private const val MARKER_VERSION = "qemu-9.2.0-v2"

    fun ensureFirmwareExtracted(context: Context): File? {
        val targetDir = File(context.filesDir, ASSET_ROOT)
        val markerFile = File(targetDir, ".extracted_version")
        if (markerFile.exists() && markerFile.readText().trim() == MARKER_VERSION) {
            return targetDir
        }

        val topEntries = runCatching { context.assets.list(ASSET_ROOT) }.getOrNull()
        if (topEntries.isNullOrEmpty()) {
            return null
        }

        targetDir.mkdirs()
        copyAssetDir(context, ASSET_ROOT, targetDir)
        markerFile.writeText(MARKER_VERSION)
        return targetDir
    }

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        destDir.mkdirs()
        val children = context.assets.list(assetPath).orEmpty()
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val grandChildren = context.assets.list(childAssetPath).orEmpty()
            val outFile = File(destDir, child)
            if (grandChildren.isNotEmpty()) {
                copyAssetDir(context, childAssetPath, outFile)
            } else {
                context.assets.open(childAssetPath).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
