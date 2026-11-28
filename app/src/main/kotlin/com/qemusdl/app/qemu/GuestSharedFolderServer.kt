package com.qemusdl.app.qemu

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure helper utilities for WebDAV (RFC 4918 Level 1) + HTML directory browsing & safe path
 * confinement, separated for unit testing.
 */
internal object SharedFolderWebDavHelper {

    fun resolveSafeFile(rootDir: File, rawRequestPath: String): File? {
        val pathOnly = rawRequestPath.substringBefore('?').substringBefore('#')
        val decoded = runCatching {
            URLDecoder.decode(pathOnly, Charsets.UTF_8.name())
        }.getOrNull() ?: return null
        val normalized = decoded.replace('\\', '/').trimStart('/')
        if (normalized.contains('\u0000')) return null

        val canonicalRoot = rootDir.canonicalFile
        val candidate = if (normalized.isEmpty()) canonicalRoot else File(canonicalRoot, normalized).canonicalFile
        val rootPath = canonicalRoot.path.trimEnd(File.separatorChar)
        val candidatePath = candidate.path
        if (candidatePath != rootPath && !candidatePath.startsWith(rootPath + File.separator)) {
            return null
        }
        return candidate
    }

    fun parseDestinationPath(destinationHeader: String?): String? {
        if (destinationHeader.isNullOrBlank()) return null
        val trimmed = destinationHeader.trim()
        val schemeIdx = trimmed.indexOf("://")
        return if (schemeIdx >= 0) {
            val slashIdx = trimmed.indexOf('/', schemeIdx + 3)
            if (slashIdx >= 0) trimmed.substring(slashIdx) else "/"
        } else {
            trimmed
        }
    }

    fun encodeHrefPath(rootDir: File, file: File): String {
        val rootPath = rootDir.canonicalFile.path.trimEnd(File.separatorChar)
        val filePath = file.canonicalFile.path
        val relative = if (filePath == rootPath) {
            ""
        } else {
            filePath.removePrefix(rootPath).trimStart(File.separatorChar)
        }
        if (relative.isEmpty()) return "/"
        val encodedSegments = relative.split(File.separatorChar).joinToString("/") { seg ->
            URLEncoder.encode(seg, Charsets.UTF_8.name()).replace("+", "%20")
        }
        return if (file.isDirectory) "/$encodedSegments/" else "/$encodedSegments"
    }

    fun formatHttpDate(epochMs: Long): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(Date(epochMs))
    }

    fun formatIsoDate(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }

    fun escapeXml(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md") -> "text/plain; charset=utf-8"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html; charset=utf-8"
            lower.endsWith(".json") -> "application/json; charset=utf-8"
            lower.endsWith(".xml") -> "application/xml; charset=utf-8"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".iso") -> "application/x-iso9660-image"
            else -> "application/octet-stream"
        }
    }

    fun buildPropfindXml(rootDir: File, target: File, depth: Int): String {
        val items = mutableListOf(target)
        if (target.isDirectory && depth > 0) {
            val children = target.listFiles()
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.US) }))
                .orEmpty()
            items.addAll(children)
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<D:multistatus xmlns:D=\"DAV:\">\n")
            for (item in items) {
                val href = escapeXml(encodeHrefPath(rootDir, item))
                val displayName = escapeXml(if (item.canonicalFile == rootDir.canonicalFile) "shared" else item.name)
                val lastMod = escapeXml(formatHttpDate(item.lastModified()))
                val created = escapeXml(formatIsoDate(item.lastModified()))
                append("  <D:response>\n")
                append("    <D:href>").append(href).append("</D:href>\n")
                append("    <D:propstat>\n")
                append("      <D:prop>\n")
                append("        <D:displayname>").append(displayName).append("</D:displayname>\n")
                append("        <D:creationdate>").append(created).append("</D:creationdate>\n")
                append("        <D:getlastmodified>").append(lastMod).append("</D:getlastmodified>\n")
                if (item.isDirectory) {
                    append("        <D:resourcetype><D:collection/></D:resourcetype>\n")
                } else {
                    append("        <D:resourcetype/>\n")
                    append("        <D:getcontentlength>").append(item.length()).append("</D:getcontentlength>\n")
                    append("        <D:getcontenttype>").append(escapeXml(guessMimeType(item.name))).append("</D:getcontenttype>\n")
                }
                append("      </D:prop>\n")
                append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
                append("    </D:propstat>\n")
                append("  </D:response>\n")
            }
            append("</D:multistatus>\n")
        }
    }

    fun buildDirectoryHtml(rootDir: File, dir: File): String {
        val currentHref = encodeHrefPath(rootDir, dir)
        val children = dir.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.US) }))
            .orEmpty()
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<title>QemuSDL Shared Folder ").append(escapeXml(currentHref)).append("</title>")
            append("<style>body{font-family:system-ui,sans-serif;background:#111318;color:#e4e6eb;max-width:860px;margin:24px auto;padding:0 16px;}")
            append("a{color:#9ab4ff;text-decoration:none;}a:hover{text-decoration:underline;}")
            append("table{width:100%;border-collapse:collapse;margin-top:14px;}th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #2a2f3a;}")
            append(".box{background:#1a1f29;padding:14px;border-radius:10px;margin:14px 0;}</style></head><body>")
            append("<h2>QemuSDL Shared Folder: ").append(escapeXml(currentHref)).append("</h2>")
            append("<div class=\"box\"><form method=\"POST\" enctype=\"multipart/form-data\">")
            append("Upload file to host: <input type=\"file\" name=\"file\" required> ")
            append("<button type=\"submit\">Upload</button></form></div>")
            append("<table><tr><th>Name</th><th>Size</th><th>Modified</th></tr>")
            if (dir.canonicalFile != rootDir.canonicalFile && dir.parentFile != null) {
                val parentHref = encodeHrefPath(rootDir, dir.parentFile!!)
                append("<tr><td><a href=\"").append(escapeXml(parentHref)).append("\">../</a></td><td>-</td><td>-</td></tr>")
            }
            for (child in children) {
                val href = encodeHrefPath(rootDir, child)
                val label = if (child.isDirectory) "${child.name}/" else child.name
                val sizeStr = if (child.isDirectory) "Folder" else formatSize(child.length())
                append("<tr><td><a href=\"").append(escapeXml(href)).append("\">").append(escapeXml(label)).append("</a></td>")
                append("<td>").append(sizeStr).append("</td>")
                append("<td>").append(escapeXml(formatHttpDate(child.lastModified()))).append("</td></tr>")
            }
            append("</table><p style=\"color:#9aa1b2;font-size:12px;\">Tip: Mount as a WebDAV network drive in Windows Explorer, Linux Files, or spice-webdavd.</p></body></html>")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * Localhost-only WebDAV (RFC 4918 Level 1) & HTTP file-sharing server for QEMU guests.
 *
 * Security: binds strictly to `127.0.0.1`, so it is never reachable on Wi-Fi/LAN—only from the
 * running QEMU guest via SLIRP's built-in host loopback gateway (`http://10.0.2.2:<port>/`).
 */
class GuestSharedFolderServer(
    val rootDir: File,
    private val preferredPort: Int = DEFAULT_PORT
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workerPool: ExecutorService? = null

    @Volatile
    var boundPort: Int = -1
        private set

    val guestUrl: String
        get() = if (boundPort > 0) "http://10.0.2.2:$boundPort/" else ""

    fun start(): Int {
        if (!running.compareAndSet(false, true)) return boundPort
        rootDir.mkdirs()
        ensureWelcomeFile(rootDir)

        val loopback = InetAddress.getByName("127.0.0.1")
        val socket = runCatching { ServerSocket(preferredPort, 16, loopback) }
            .getOrElse { ServerSocket(0, 16, loopback) }
        serverSocket = socket
        boundPort = socket.localPort
        workerPool = Executors.newFixedThreadPool(4)

        acceptThread = Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                workerPool?.execute { handleClient(client) }
            }
        }, "QemuSharedFolderAccept").apply {
            isDaemon = true
            start()
        }
        return boundPort
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = -1
        workerPool?.shutdownNow()
        workerPool = null
        acceptThread = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readHttpLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeStatus(output, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val rawTarget = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase(Locale.US)
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val contentLength = headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val targetFile = SharedFolderWebDavHelper.resolveSafeFile(rootDir, rawTarget)
            if (targetFile == null) {
                writeStatus(output, 403, "Forbidden")
                return
            }

            when (method) {
                "OPTIONS" -> {
                    val resp = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("DAV: 1\r\n")
                        append("MS-Author-Via: DAV\r\n")
                        append("Allow: OPTIONS, GET, HEAD, POST, PUT, DELETE, PROPFIND, MKCOL, MOVE, COPY\r\n")
                        append("Content-Length: 0\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    output.write(resp.toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
                "PROPFIND" -> {
                    if (contentLength > 0L) skipExactly(input, contentLength)
                    if (!targetFile.exists()) {
                        writeStatus(output, 404, "Not Found")
                        return
                    }
                    val depth = when (headers["depth"]?.lowercase(Locale.US)) {
                        "0" -> 0
                        else -> 1
                    }
                    val xml = SharedFolderWebDavHelper.buildPropfindXml(rootDir, targetFile, depth)
                    writeBody(output, 207, "Multi-Status", "application/xml; charset=utf-8", xml.toByteArray(Charsets.UTF_8))
                }
                "GET", "HEAD" -> {
                    if (!targetFile.exists()) {
                        writeStatus(output, 404, "Not Found")
                        return
                    }
                    if (targetFile.isDirectory) {
                        val html = SharedFolderWebDavHelper.buildDirectoryHtml(rootDir, targetFile).toByteArray(Charsets.UTF_8)
                        writeHeaders(output, 200, "OK", "text/html; charset=utf-8", html.size.toLong())
                        if (method == "GET") output.write(html)
                        output.flush()
                    } else {
                        val mime = SharedFolderWebDavHelper.guessMimeType(targetFile.name)
                        writeHeaders(output, 200, "OK", mime, targetFile.length())
                        if (method == "GET") {
                            FileInputStream(targetFile).use { fis ->
                                val buf = ByteArray(32 * 1024)
                                while (true) {
                                    val n = fis.read(buf)
                                    if (n <= 0) break
                                    output.write(buf, 0, n)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                "PUT" -> {
                    if (targetFile.isDirectory) {
                        writeStatus(output, 405, "Method Not Allowed")
                        return
                    }
                    val existed = targetFile.exists()
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { fos ->
                        copyLimited(input, fos, contentLength)
                    }
                    if (existed) {
                        writeStatus(output, 204, "No Content")
                    } else {
                        writeStatus(output, 201, "Created")
                    }
                }
                "POST" -> {
                    val ct = headers["content-type"].orEmpty()
                    val boundary = ct.substringAfter("boundary=", "").trim().trim('"')
                    if (!targetFile.isDirectory || boundary.isEmpty() || contentLength <= 0L || contentLength > 256L * 1024L * 1024L) {
                        writeStatus(output, 400, "Bad Request")
                        return
                    }
                    handleMultipartUpload(input, contentLength, boundary, targetFile)
                    val redirect = SharedFolderWebDavHelper.encodeHrefPath(rootDir, targetFile)
                    val resp = "HTTP/1.1 303 See Other\r\nLocation: $redirect\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    output.write(resp.toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
                "MKCOL" -> {
                    if (targetFile.exists()) {
                        writeStatus(output, 405, "Method Not Allowed")
                    } else if (targetFile.mkdirs()) {
                        writeStatus(output, 201, "Created")
                    } else {
                        writeStatus(output, 409, "Conflict")
                    }
                }
                "DELETE" -> {
                    if (targetFile.canonicalFile == rootDir.canonicalFile) {
                        writeStatus(output, 403, "Forbidden")
                    } else if (!targetFile.exists()) {
                        writeStatus(output, 404, "Not Found")
                    } else if (targetFile.deleteRecursively()) {
                        writeStatus(output, 204, "No Content")
                    } else {
                        writeStatus(output, 500, "Internal Server Error")
                    }
                }
                "MOVE", "COPY" -> {
                    val destPath = SharedFolderWebDavHelper.parseDestinationPath(headers["destination"])
                    val destFile = destPath?.let { SharedFolderWebDavHelper.resolveSafeFile(rootDir, it) }
                    if (destFile == null || !targetFile.exists()) {
                        writeStatus(output, 400, "Bad Request")
                        return
                    }
                    val overwrite = headers["overwrite"]?.uppercase(Locale.US) != "F"
                    val existed = destFile.exists()
                    if (existed && !overwrite) {
                        writeStatus(output, 412, "Precondition Failed")
                        return
                    }
                    destFile.parentFile?.mkdirs()
                    if (existed) destFile.deleteRecursively()
                    val ok = if (method == "MOVE") {
                        targetFile.renameTo(destFile) || (targetFile.copyRecursively(destFile, overwrite = true) && targetFile.deleteRecursively())
                    } else {
                        targetFile.copyRecursively(destFile, overwrite = true)
                    }
                    if (ok) {
                        writeStatus(output, if (existed) 204 else 201, if (existed) "No Content" else "Created")
                    } else {
                        writeStatus(output, 500, "Internal Server Error")
                    }
                }
                else -> writeStatus(output, 501, "Not Implemented")
            }
        } catch (_: Throwable) {
            // Connection dropped by guest
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleMultipartUpload(
        input: InputStream,
        contentLength: Long,
        boundary: String,
        targetDir: File
    ) {
        val raw = ByteArrayOutputStream()
        copyLimited(input, raw, contentLength)
        val data = raw.toByteArray()
        val marker = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val headerEnd = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        val firstPart = indexOfBytes(data, marker, 0)
        if (firstPart < 0) return
        val headersStart = firstPart + marker.size + 2
        val bodySep = indexOfBytes(data, headerEnd, headersStart)
        if (bodySep < 0) return

        val partHeaders = String(data, headersStart, bodySep - headersStart, Charsets.UTF_8)
        val rawName = Regex("""filename="([^"]+)"""").find(partHeaders)?.groupValues?.get(1) ?: return
        val safeName = File(rawName).name.trim()
        if (safeName.isEmpty() || safeName == "." || safeName == "..") return

        val bodyStart = bodySep + headerEnd.size
        val nextBoundary = indexOfBytes(data, marker, bodyStart)
        if (nextBoundary < bodyStart) return
        val bodyEnd = (nextBoundary - 2).coerceAtLeast(bodyStart) // trim trailing \r\n before boundary

        val outFile = SharedFolderWebDavHelper.resolveSafeFile(
            rootDir,
            SharedFolderWebDavHelper.encodeHrefPath(rootDir, targetDir) + URLEncoder.encode(safeName, Charsets.UTF_8.name())
        ) ?: return
        FileOutputStream(outFile).use { fos ->
            fos.write(data, bodyStart, bodyEnd - bodyStart)
        }
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, fromIndex: Int): Int {
        if (needle.isEmpty() || fromIndex < 0) return -1
        val limit = haystack.size - needle.size
        outer@ for (i in fromIndex..limit) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readHttpLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (out.size() > 0) out.toString(Charsets.ISO_8859_1.name()) else null
            }
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val len = if (prev == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.ISO_8859_1)
            }
            if (out.size() < 8192) out.write(b)
            prev = b
        }
    }

    private fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Long) {
        var remaining = maxBytes
        val buf = ByteArray(32 * 1024)
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            output.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipExactly(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buf = ByteArray(4096)
        while (remaining > 0L) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun writeStatus(output: OutputStream, code: Int, reason: String) {
        val resp = "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(resp.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun writeHeaders(output: OutputStream, code: Int, reason: String, contentType: String, length: Long) {
        val resp = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(resp.toByteArray(Charsets.US_ASCII))
    }

    private fun writeBody(output: OutputStream, code: Int, reason: String, contentType: String, body: ByteArray) {
        writeHeaders(output, code, reason, contentType, body.size.toLong())
        output.write(body)
        output.flush()
    }

    companion object {
        const val DEFAULT_PORT = 9843

        fun defaultSharedDirectory(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "shared_folder").apply {
                mkdirs()
            }

        private fun ensureWelcomeFile(dir: File) {
            if (dir.listFiles().isNullOrEmpty()) {
                runCatching {
                    File(dir, "README_SHARED_FOLDER.txt").writeText(
                        "QemuSDL Host <-> Guest Shared Folder\n" +
                            "====================================\n" +
                            "Files placed here on Android are accessible inside your VM at:\n" +
                            "  http://10.0.2.2:$DEFAULT_PORT/\n\n" +
                            "- Browser: open http://10.0.2.2:$DEFAULT_PORT/ to download or upload files.\n" +
                            "- Windows Explorer: Map Network Drive -> http://10.0.2.2:$DEFAULT_PORT/\n" +
                            "- Linux Files: dav://10.0.2.2:$DEFAULT_PORT/\n"
                    )
                }
            }
        }
    }
}
