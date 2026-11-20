package com.qemusdl.app.qemu

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class DiskFormat(val extension: String, val label: String) {
    QCOW2("qcow2", "QCOW2 v3 (Sparse)"),
    RAW("img", "Raw Sparse (.img)")
}

/**
 * Pure Kotlin virtual disk creator supporting valid QCOW2 v3 sparse headers
 * and raw sparse disk images, so users can create bootable disks on-device
 * without invoking an external `qemu-img` binary.
 */
object DiskImageCreator {

    private const val QCOW2_MAGIC = 0x514649FB // "QFI\xfb"
    private const val QCOW2_VERSION = 3
    private const val CLUSTER_BITS = 16 // 64 KiB clusters
    private const val CLUSTER_SIZE = 1 shl CLUSTER_BITS
    private const val HEADER_LENGTH = 104 // Standard QCOW2 v3 header length

    fun createDisk(targetFile: File, sizeMb: Int, format: DiskFormat): File {
        require(sizeMb in 16..65536) { "Disk size must be between 16 MB and 64 GB" }
        targetFile.parentFile?.mkdirs()

        when (format) {
            DiskFormat.QCOW2 -> writeQcow2V3(targetFile, sizeMb.toLong() * 1024L * 1024L)
            DiskFormat.RAW -> writeRawSparse(targetFile, sizeMb.toLong() * 1024L * 1024L)
        }
        return targetFile
    }

    private fun writeQcow2V3(file: File, virtualSizeBytes: Long) {
        val l2EntriesPerCluster = CLUSTER_SIZE / 8L // 8192
        val bytesPerL1Entry = l2EntriesPerCluster * CLUSTER_SIZE // 512 MiB per L1 entry
        val l1Size = ((virtualSizeBytes + bytesPerL1Entry - 1L) / bytesPerL1Entry).toInt().coerceAtLeast(1)

        // Layout:
        // Cluster 0 (offset 0x00000): QCOW2 v3 header
        // Cluster 1 (offset 0x10000): Refcount table (points to Cluster 2)
        // Cluster 2 (offset 0x20000): Refcount block (marks clusters 0..3 as refcount=1)
        // Cluster 3 (offset 0x30000): L1 table (zeroed — all virtual clusters unallocated)
        val refcountTableOffset = CLUSTER_SIZE.toLong()
        val refcountBlockOffset = CLUSTER_SIZE.toLong() * 2L
        val l1TableOffset = CLUSTER_SIZE.toLong() * 3L
        val totalInitialBytes = CLUSTER_SIZE.toLong() * 4L

        val header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(QCOW2_MAGIC)              // 0..3: magic
            putInt(QCOW2_VERSION)            // 4..7: version = 3
            putLong(0L)                      // 8..15: backing_file_offset
            putInt(0)                        // 16..19: backing_file_size
            putInt(CLUSTER_BITS)             // 20..23: cluster_bits = 16 (64 KiB)
            putLong(virtualSizeBytes)        // 24..31: virtual disk size in bytes
            putInt(0)                        // 32..35: crypt_method = none
            putInt(l1Size)                   // 36..39: l1_size
            putLong(l1TableOffset)           // 40..47: l1_table_offset
            putLong(refcountTableOffset)     // 48..55: refcount_table_offset
            putInt(1)                        // 56..59: refcount_table_clusters = 1
            putInt(0)                        // 60..63: nb_snapshots
            putLong(0L)                      // 64..71: snapshots_offset
            putLong(0L)                      // 72..79: incompatible_features
            putLong(0L)                      // 80..87: compatible_features
            putLong(0L)                      // 88..95: autoclear_features
            putInt(4)                        // 96..99: refcount_order = 4 (16-bit refcounts)
            putInt(HEADER_LENGTH)            // 100..103: header_length = 104
        }

        val refcountTableEntry = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putLong(refcountBlockOffset)

        // Mark clusters 0, 1, 2, 3 as allocated with refcount = 1 (uint16 big-endian)
        val refcountBlockEntries = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putShort(1)
            .putShort(1)
            .putShort(1)
            .putShort(1)

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(totalInitialBytes)
            raf.seek(0L)
            raf.write(header.array())
            raf.seek(refcountTableOffset)
            raf.write(refcountTableEntry.array())
            raf.seek(refcountBlockOffset)
            raf.write(refcountBlockEntries.array())
        }
    }

    private fun writeRawSparse(file: File, virtualSizeBytes: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(virtualSizeBytes)
        }
    }

    fun readQcow2VirtualSizeMb(file: File): Int? {
        if (!file.exists() || file.length() < 32) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(32)
                raf.readFully(buf)
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
                val magic = bb.int
                if (magic != QCOW2_MAGIC) return@runCatching null
                bb.position(24)
                val bytes = bb.long
                (bytes / (1024L * 1024L)).toInt()
            }
        }.getOrNull()
    }
}
