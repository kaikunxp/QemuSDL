package com.qemusdl.app.qemu

import android.content.Context
import com.qemusdl.app.data.DownloadedIsoInfo
import com.qemusdl.app.data.GuestArchitecture
import com.qemusdl.app.data.OsPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

enum class IsoResolveStrategy {
    DIRECT_URL,
    ALPINE_LATEST_YAML,
    DEBIAN_CURRENT_NETINST
}

data class IsoCatalogEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val approxSizeLabel: String,
    val preset: OsPreset,
    val architecture: GuestArchitecture = preset.defaultArch,
    val targetFileName: String,
    val resolveStrategy: IsoResolveStrategy,
    val primaryUrl: String,
    val regexPattern: String? = null,
    val baseDownloadUrl: String? = null,
    val extractIsoFromZip: Boolean = false
)

sealed interface IsoDownloadState {
    data object Idle : IsoDownloadState
    data class Resolving(val status: String = "Finding latest mirror...") : IsoDownloadState
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long
    ) : IsoDownloadState {
        val progress: Float?
            get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
    }
    data class Extracting(val status: String = "Extracting .iso from archive...") : IsoDownloadState
    data class Completed(val filePath: String, val fileName: String) : IsoDownloadState
    data class Failed(val errorMessage: String) : IsoDownloadState
}

object IsoCatalog {
    val entries: List<IsoCatalogEntry> = listOf(
        // x86_64 Images
        IsoCatalogEntry(
            id = "alpine-virt-x86_64",
            title = "Alpine Linux Virtual (x86_64)",
            subtitle = "Ultra-lightweight x86_64 Linux tailored for VMs — boots fast on QEMU TCG",
            approxSizeLabel = "~65 MB",
            preset = OsPreset.LINUX_ALPINE,
            architecture = GuestArchitecture.X86_64,
            targetFileName = "alpine-virt-latest-x86_64.iso",
            resolveStrategy = IsoResolveStrategy.ALPINE_LATEST_YAML,
            primaryUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/x86_64/latest-releases.yaml",
            regexPattern = """file:\s*(alpine-virt-[0-9A-Za-z._-]+-x86_64\.iso)""",
            baseDownloadUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/x86_64/"
        ),
        IsoCatalogEntry(
            id = "alpine-standard-x86_64",
            title = "Alpine Linux Standard (x86_64)",
            subtitle = "Full standard Alpine x86_64 live & installer ISO with extra packages",
            approxSizeLabel = "~210 MB",
            preset = OsPreset.LINUX_ALPINE,
            architecture = GuestArchitecture.X86_64,
            targetFileName = "alpine-standard-latest-x86_64.iso",
            resolveStrategy = IsoResolveStrategy.ALPINE_LATEST_YAML,
            primaryUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/x86_64/latest-releases.yaml",
            regexPattern = """file:\s*(alpine-standard-[0-9A-Za-z._-]+-x86_64\.iso)""",
            baseDownloadUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/x86_64/"
        ),
        IsoCatalogEntry(
            id = "tinycore-pure64",
            title = "Tiny Core Pure64 (x86_64)",
            subtitle = "Minimal 64-bit Linux ISO with rapid boot — great for instant testing",
            approxSizeLabel = "~25 MB",
            preset = OsPreset.CUSTOM,
            architecture = GuestArchitecture.X86_64,
            targetFileName = "CorePure64-current.iso",
            resolveStrategy = IsoResolveStrategy.DIRECT_URL,
            primaryUrl = "https://distro.ibiblio.org/tinycorelinux/15.x/x86_64/release/CorePure64-current.iso"
        ),
        IsoCatalogEntry(
            id = "debian-netinst-amd64",
            title = "Debian Stable Netinst (amd64)",
            subtitle = "Official Debian x86_64 network installer with desktop environment options",
            approxSizeLabel = "~750 MB",
            preset = OsPreset.LINUX_DEBIAN,
            architecture = GuestArchitecture.X86_64,
            targetFileName = "debian-stable-amd64-netinst.iso",
            resolveStrategy = IsoResolveStrategy.DEBIAN_CURRENT_NETINST,
            primaryUrl = "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/SHA256SUMS",
            regexPattern = """(debian-[0-9.]+-amd64-netinst\.iso)""",
            baseDownloadUrl = "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/"
        ),
        IsoCatalogEntry(
            id = "freedos-13-livecd",
            title = "FreeDOS 1.3 LiveCD",
            subtitle = "Classic DOS operating system — boots immediately on legacy PC / VGA",
            approxSizeLabel = "~400 MB",
            preset = OsPreset.FREEDOS,
            architecture = GuestArchitecture.X86_64,
            targetFileName = "FD13LIVE.iso",
            resolveStrategy = IsoResolveStrategy.DIRECT_URL,
            primaryUrl = "https://www.ibiblio.org/pub/micro/pc-stuff/freedos/files/distributions/1.3/official/FD13-LiveCD.zip",
            extractIsoFromZip = true
        ),
        // AArch64 (ARM64) Images
        IsoCatalogEntry(
            id = "alpine-virt-aarch64",
            title = "Alpine Linux Virtual (AArch64)",
            subtitle = "Ultra-lightweight ARM64 Linux tailored for QEMU ARM virt + EDK2 UEFI",
            approxSizeLabel = "~60 MB",
            preset = OsPreset.LINUX_ALPINE_AARCH64,
            architecture = GuestArchitecture.AARCH64,
            targetFileName = "alpine-virt-latest-aarch64.iso",
            resolveStrategy = IsoResolveStrategy.ALPINE_LATEST_YAML,
            primaryUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml",
            regexPattern = """file:\s*(alpine-virt-[0-9A-Za-z._-]+-aarch64\.iso)""",
            baseDownloadUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/"
        ),
        IsoCatalogEntry(
            id = "alpine-standard-aarch64",
            title = "Alpine Linux Standard (AArch64)",
            subtitle = "Full standard Alpine ARM64 live & installer ISO with extra packages",
            approxSizeLabel = "~230 MB",
            preset = OsPreset.LINUX_ALPINE_AARCH64,
            architecture = GuestArchitecture.AARCH64,
            targetFileName = "alpine-standard-latest-aarch64.iso",
            resolveStrategy = IsoResolveStrategy.ALPINE_LATEST_YAML,
            primaryUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml",
            regexPattern = """file:\s*(alpine-standard-[0-9A-Za-z._-]+-aarch64\.iso)""",
            baseDownloadUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/"
        ),
        IsoCatalogEntry(
            id = "debian-netinst-arm64",
            title = "Debian Stable Netinst (arm64)",
            subtitle = "Official Debian AArch64 UEFI network installer with desktop options",
            approxSizeLabel = "~720 MB",
            preset = OsPreset.LINUX_DEBIAN_AARCH64,
            architecture = GuestArchitecture.AARCH64,
            targetFileName = "debian-stable-arm64-netinst.iso",
            resolveStrategy = IsoResolveStrategy.DEBIAN_CURRENT_NETINST,
            primaryUrl = "https://cdimage.debian.org/debian-cd/current/arm64/iso-cd/SHA256SUMS",
            regexPattern = """(debian-[0-9.]+-arm64-netinst\.iso)""",
            baseDownloadUrl = "https://cdimage.debian.org/debian-cd/current/arm64/iso-cd/"
        )
    )

    fun findById(id: String?): IsoCatalogEntry? = entries.firstOrNull { it.id == id }

    fun entriesForArch(arch: GuestArchitecture?): List<IsoCatalogEntry> =
        if (arch == null) entries else entries.filter { it.architecture == arch }

    fun recommendedForPreset(preset: OsPreset, arch: GuestArchitecture = preset.defaultArch): IsoCatalogEntry = when (preset) {
        OsPreset.LINUX_ALPINE -> findById("alpine-virt-x86_64") ?: entries.first()
        OsPreset.LINUX_DEBIAN -> findById("debian-netinst-amd64") ?: entries.first()
        OsPreset.FREEDOS -> findById("freedos-13-livecd") ?: entries.first()
        OsPreset.LINUX_ALPINE_AARCH64 -> findById("alpine-virt-aarch64") ?: entries.first()
        OsPreset.LINUX_DEBIAN_AARCH64 -> findById("debian-netinst-arm64") ?: entries.first()
        else -> if (arch == GuestArchitecture.AARCH64) {
            findById("alpine-virt-aarch64") ?: entries.first()
        } else {
            findById("alpine-virt-x86_64") ?: entries.first()
        }
    }
}

class IsoDownloader(context: Context) {

    private val isosDir: File = File(context.applicationContext.filesDir, "isos").apply { mkdirs() }

    private val _states = MutableStateFlow<Map<String, IsoDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, IsoDownloadState>> = _states.asStateFlow()

    private val _downloadedIsos = MutableStateFlow<List<DownloadedIsoInfo>>(scanDownloadedIsos())
    val downloadedIsos: StateFlow<List<DownloadedIsoInfo>> = _downloadedIsos.asStateFlow()

    fun refreshLocalIsos() {
        _downloadedIsos.value = scanDownloadedIsos()
    }

    fun getDownloadedFile(entry: IsoCatalogEntry): File? {
        val f = File(isosDir, entry.targetFileName)
        return if (f.exists() && f.length() > 0L) f else null
    }

    fun deleteLocalIso(path: String) {
        val file = File(path)
        if (file.exists() && file.parentFile?.canonicalPath == isosDir.canonicalPath) {
            file.delete()
        }
        refreshLocalIsos()
    }

    fun clearError(id: String) {
        _states.update { it - id }
    }

    suspend fun downloadCatalogEntry(entry: IsoCatalogEntry): Result<File> = withContext(Dispatchers.IO) {
        try {
            updateState(entry.id, IsoDownloadState.Resolving())
            val resolvedUrl = resolveDownloadUrl(entry)
            val destFile = File(isosDir, entry.targetFileName)
            val resultFile = downloadUrlToFile(
                downloadId = entry.id,
                urlString = resolvedUrl,
                destFile = destFile,
                extractIsoFromZip = entry.extractIsoFromZip
            )
            updateState(entry.id, IsoDownloadState.Completed(resultFile.absolutePath, resultFile.name))
            refreshLocalIsos()
            Result.success(resultFile)
        } catch (ce: CancellationException) {
            updateState(entry.id, IsoDownloadState.Idle)
            throw ce
        } catch (t: Throwable) {
            val msg = t.message?.take(160) ?: "Download failed"
            updateState(entry.id, IsoDownloadState.Failed(msg))
            Result.failure(t)
        }
    }

    suspend fun downloadCustomUrl(urlString: String, preferredFileName: String? = null): Result<File> =
        withContext(Dispatchers.IO) {
            val id = CUSTOM_DOWNLOAD_ID
            try {
                val trimmed = urlString.trim()
                require(trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                    "URL must start with https:// or http://"
                }
                updateState(id, IsoDownloadState.Resolving("Connecting to server..."))

                val rawName = preferredFileName?.takeIf { it.isNotBlank() }
                    ?: trimmed.substringAfterLast('/').substringBefore('?').ifBlank { "custom_os.iso" }
                val safeBase = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val isZip = safeBase.endsWith(".zip", ignoreCase = true)
                val targetName = when {
                    isZip -> safeBase.removeSuffix(".zip").removeSuffix(".ZIP") + ".iso"
                    safeBase.endsWith(".iso", ignoreCase = true) -> safeBase
                    else -> "$safeBase.iso"
                }

                val destFile = File(isosDir, targetName)
                val resultFile = downloadUrlToFile(
                    downloadId = id,
                    urlString = trimmed,
                    destFile = destFile,
                    extractIsoFromZip = isZip
                )
                updateState(id, IsoDownloadState.Completed(resultFile.absolutePath, resultFile.name))
                refreshLocalIsos()
                Result.success(resultFile)
            } catch (ce: CancellationException) {
                updateState(id, IsoDownloadState.Idle)
                throw ce
            } catch (t: Throwable) {
                val msg = t.message?.take(160) ?: "Custom download failed"
                updateState(id, IsoDownloadState.Failed(msg))
                Result.failure(t)
            }
        }

    internal fun resolveDownloadUrl(entry: IsoCatalogEntry): String {
        if (entry.resolveStrategy == IsoResolveStrategy.DIRECT_URL) {
            return entry.primaryUrl
        }
        val manifestText = fetchText(entry.primaryUrl)
        val pattern = Regex(requireNotNull(entry.regexPattern))
        val match = pattern.find(manifestText)
            ?: error("Could not locate latest ${entry.architecture.badgeText} ISO in mirror manifest.")
        val fileName = match.groupValues[1]
        val base = requireNotNull(entry.baseDownloadUrl)
        return "$base$fileName"
    }

    private suspend fun downloadUrlToFile(
        downloadId: String,
        urlString: String,
        destFile: File,
        extractIsoFromZip: Boolean
    ): File = withContext(Dispatchers.IO) {
        val tempFile = File(isosDir, "${destFile.name}.part")
        if (tempFile.exists()) tempFile.delete()

        val conn = openFollowingRedirects(urlString)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("Server returned HTTP $code")
            }
            val totalBytes = conn.contentLengthLong
            var downloaded = 0L
            var lastTickTime = System.currentTimeMillis()
            var lastTickBytes = 0L

            updateState(downloadId, IsoDownloadState.Downloading(0L, totalBytes, 0L))

            conn.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!isActive) {
                            throw CancellationException("Download cancelled")
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastTickTime >= 250L) {
                            val dtMs = (now - lastTickTime).coerceAtLeast(1L)
                            val speed = ((downloaded - lastTickBytes) * 1000L) / dtMs
                            lastTickTime = now
                            lastTickBytes = downloaded
                            updateState(
                                downloadId,
                                IsoDownloadState.Downloading(downloaded, totalBytes, speed)
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        } finally {
            conn.disconnect()
        }

        if (extractIsoFromZip) {
            updateState(downloadId, IsoDownloadState.Extracting())
            extractFirstIsoFromZip(tempFile, destFile)
            tempFile.delete()
        } else {
            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
        }
        destFile
    }

    private fun extractFirstIsoFromZip(zipFile: File, destIsoFile: File) {
        var extracted = false
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".iso", ignoreCase = true)) {
                    val tempOut = File(isosDir, "${destIsoFile.name}.extracting")
                    tempOut.outputStream().use { out -> zis.copyTo(out, 64 * 1024) }
                    if (destIsoFile.exists()) destIsoFile.delete()
                    if (!tempOut.renameTo(destIsoFile)) {
                        tempOut.copyTo(destIsoFile, overwrite = true)
                        tempOut.delete()
                    }
                    extracted = true
                    break
                }
            }
        }
        if (!extracted) {
            error("Archive did not contain a bootable .iso image.")
        }
    }

    private fun fetchText(urlString: String): String {
        val conn = openFollowingRedirects(urlString)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) error("Mirror check failed (HTTP $code)")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openFollowingRedirects(urlString: String): HttpURLConnection {
        var currentUrl = urlString
        repeat(6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "QemuSDL-Android/1.0")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                    ?: error("Redirect missing Location header")
                conn.disconnect()
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        error("Too many HTTP redirects")
    }

    private fun updateState(id: String, state: IsoDownloadState) {
        _states.update { it + (id to state) }
    }

    private fun scanDownloadedIsos(): List<DownloadedIsoInfo> =
        isosDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("iso", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                DownloadedIsoInfo(
                    name = file.name,
                    path = file.absolutePath,
                    sizeBytes = file.length()
                )
            }

    companion object {
        const val CUSTOM_DOWNLOAD_ID = "custom_url_download"

        @Volatile
        private var instance: IsoDownloader? = null

        fun getInstance(context: Context): IsoDownloader =
            instance ?: synchronized(this) {
                instance ?: IsoDownloader(context.applicationContext).also { instance = it }
            }
    }
}
