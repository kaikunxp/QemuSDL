package com.qemusdl.app.qemu

import android.content.Context
import java.io.File

/**
 * Extracts bundled x86_64 SeaBIOS, VGABIOS, PXE ROMs, AArch64 EDK2 UEFI firmware
 * (`edk2-aarch64-code.fd` + `edk2-arm-vars.fd`), PowerPC OpenBIOS (`openbios-ppc` +
 * `qemu_vga.ndrv` for Mac OS 9), and keymaps from `assets/qemu-firmware/` into
 * `filesDir/qemu-firmware/` so QEMU can boot via `-L <firmwareDir>` and `-drive if=pflash`
 * on Android without `/usr/share/qemu`.
 */
object QemuFirmwareManager {

    private const val ASSET_ROOT = "qemu-firmware"
    private const val MARKER_VERSION = "qemu-9.2.0-v4-ppc"
    const val EDK2_AARCH64_CODE_FILENAME = "edk2-aarch64-code.fd"
    const val EDK2_ARM_VARS_FILENAME = "edk2-arm-vars.fd"
    const val OPENBIOS_PPC_FILENAME = "openbios-ppc"
    const val QEMU_VGA_NDRV_FILENAME = "qemu_vga.ndrv"

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

    /**
     * Returns the extracted read-only `edk2-aarch64-code.fd` file and creates a per-VM writable
     * copy of `edk2-arm-vars.fd` under `filesDir/nvram/<vmId>-edk2-arm-vars.fd` so guest EFI boot
     * variables persist per VM.
     */
    fun prepareAarch64PflashFiles(context: Context, vmId: String): Pair<File, File>? {
        val fwDir = ensureFirmwareExtracted(context) ?: return null
        val codeFile = File(fwDir, EDK2_AARCH64_CODE_FILENAME)
        val templateVarsFile = File(fwDir, EDK2_ARM_VARS_FILENAME)
        if (!codeFile.exists()) return null

        val nvramDir = File(context.filesDir, "nvram").apply { mkdirs() }
        val safeId = vmId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "default" }
        val vmVarsFile = File(nvramDir, "${safeId}-${EDK2_ARM_VARS_FILENAME}")
        if (!vmVarsFile.exists() || vmVarsFile.length() == 0L) {
            if (templateVarsFile.exists()) {
                templateVarsFile.copyTo(vmVarsFile, overwrite = true)
            } else {
                // Fallback: create a 64 MiB zeroed pflash image matching edk2-arm-vars.fd
                java.io.RandomAccessFile(vmVarsFile, "rw").use { raf ->
                    raf.setLength(64L * 1024L * 1024L)
                }
            }
        }
        return codeFile to vmVarsFile
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
