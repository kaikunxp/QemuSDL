package com.qemusdl.app.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DiskImageCreatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun createQcow2Disk_writesValidQcow2V3Header() {
        val target = File(tempFolder.root, "test.qcow2")
        DiskImageCreator.createDisk(target, sizeMb = 2048, format = DiskFormat.QCOW2)

        assertTrue(target.exists())
        // Initial sparse file should be 4 clusters * 64 KiB = 262,144 bytes
        assertEquals(262_144L, target.length())
        assertEquals(2048, DiskImageCreator.readQcow2VirtualSizeMb(target))

        RandomAccessFile(target, "r").use { raf ->
            val header = ByteArray(104)
            raf.readFully(header)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            assertEquals(0x514649FB, bb.int) // magic "QFI\xfb"
            assertEquals(3, bb.int)          // version 3
            bb.position(20)
            assertEquals(16, bb.int)         // cluster_bits = 16
            assertEquals(2048L * 1024L * 1024L, bb.long)
            bb.position(100)
            assertEquals(104, bb.int)        // header_length = 104
        }
    }
}
