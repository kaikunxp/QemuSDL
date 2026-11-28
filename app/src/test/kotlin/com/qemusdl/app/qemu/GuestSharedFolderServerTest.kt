package com.qemusdl.app.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files

class GuestSharedFolderServerTest {

    @Test
    fun resolveSafeFileBlocksPathTraversalOutsideRoot() {
        val root = Files.createTempDirectory("qemusdl_shared_test").toFile()
        try {
            val child = File(root, "notes.txt").apply { writeText("hello") }
            assertEquals(root.canonicalFile, SharedFolderWebDavHelper.resolveSafeFile(root, "/"))
            assertEquals(child.canonicalFile, SharedFolderWebDavHelper.resolveSafeFile(root, "/notes.txt"))
            assertEquals(child.canonicalFile, SharedFolderWebDavHelper.resolveSafeFile(root, "/notes.txt?dl=1"))
            assertNull(SharedFolderWebDavHelper.resolveSafeFile(root, "/../etc/passwd"))
            assertNull(SharedFolderWebDavHelper.resolveSafeFile(root, "/%2e%2e/%2e%2e/secret"))
            assertNull(SharedFolderWebDavHelper.resolveSafeFile(root, "/bad\u0000name.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun parseDestinationPathExtractsPathFromAbsoluteOrRelativeHeaders() {
        assertEquals("/moved.txt", SharedFolderWebDavHelper.parseDestinationPath("http://10.0.2.2:9843/moved.txt"))
        assertEquals("/sub/file%201.bin", SharedFolderWebDavHelper.parseDestinationPath("/sub/file%201.bin"))
        assertEquals("/", SharedFolderWebDavHelper.parseDestinationPath("http://10.0.2.2:9843"))
        assertNull(SharedFolderWebDavHelper.parseDestinationPath(null))
        assertNull(SharedFolderWebDavHelper.parseDestinationPath("   "))
    }

    @Test
    fun propfindXmlAndDirectoryHtmlIncludeFolderEntries() {
        val root = Files.createTempDirectory("qemusdl_webdav_xml").toFile()
        try {
            File(root, "hello world.txt").writeText("abcde")
            File(root, "docs").mkdir()

            val xml = SharedFolderWebDavHelper.buildPropfindXml(root, root, depth = 1)
            assertTrue(xml.contains("<D:multistatus xmlns:D=\"DAV:\">"))
            assertTrue(xml.contains("<D:href>/hello%20world.txt</D:href>"))
            assertTrue(xml.contains("<D:href>/docs/</D:href>"))
            assertTrue(xml.contains("<D:getcontentlength>5</D:getcontentlength>"))

            val html = SharedFolderWebDavHelper.buildDirectoryHtml(root, root)
            assertTrue(html.contains("QemuSDL Shared Folder"))
            assertTrue(html.contains("/hello%20world.txt"))
            assertTrue(html.contains("docs/"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localhostServerHandlesWebDavPutGetPropfindAndDelete() {
        val root = Files.createTempDirectory("qemusdl_server_e2e").toFile()
        val server = GuestSharedFolderServer(rootDir = root, preferredPort = 0)
        try {
            val port = server.start()
            assertTrue(port > 0)
            assertEquals("http://10.0.2.2:$port/", server.guestUrl)

            // 1. OPTIONS reports DAV: 1
            val optConn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
                requestMethod = "OPTIONS"
            }
            assertEquals(200, optConn.responseCode)
            assertEquals("1", optConn.getHeaderField("DAV"))
            optConn.disconnect()

            // 2. PUT uploads a file
            val putConn = (URL("http://127.0.0.1:$port/sample.txt").openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", "text/plain")
            }
            putConn.outputStream.use { it.write("Hello from QEMU guest!".toByteArray()) }
            assertEquals(201, putConn.responseCode)
            putConn.disconnect()

            // 3. GET retrieves exact file content
            val getConn = (URL("http://127.0.0.1:$port/sample.txt").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
            }
            assertEquals(200, getConn.responseCode)
            val body = getConn.inputStream.bufferedReader().use { it.readText() }
            assertEquals("Hello from QEMU guest!", body)
            getConn.disconnect()

            // 4. Raw PROPFIND returns 207 Multi-Status listing sample.txt
            val propfindResp = sendRawHttp(
                port = port,
                request = "PROPFIND / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nDepth: 1\r\nConnection: close\r\n\r\n"
            )
            assertNotNull(propfindResp)
            assertTrue(propfindResp.startsWith("HTTP/1.1 207 Multi-Status"))
            assertTrue(propfindResp.contains("/sample.txt"))

            // 5. DELETE removes the file
            val delConn = (URL("http://127.0.0.1:$port/sample.txt").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
            }
            assertEquals(204, delConn.responseCode)
            delConn.disconnect()
            assertFalse(File(root, "sample.txt").exists())
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    private fun sendRawHttp(port: Int, request: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            return socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
        }
    }
}
