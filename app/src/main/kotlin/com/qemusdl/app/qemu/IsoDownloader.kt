package com.qemusdl.app.qemu

import android.content.Context
import android.os.PowerManager
import com.qemusdl.app.data.DownloadedIsoInfo
import com.qemusdl.app.data.GuestArchitecture
import com.qemusdl.app.data.OsPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    val isActiveDownload: Boolean
        get() = this is Resolving || this is Downloading || this is Extracting

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
        IsoCatalogEntry(
            id = "windows-10-eval-x64",
            title = "Windows 10 Enterprise 22H2 (x64)",
            subtitle = "Official Microsoft 90-day evaluation ISO (en-US) — boots directly on Q35 without TPM 2.0",
            approxSizeLabel = "~5.2 GB",
            preset = OsPreset.WINDOWS,
            architecture = GuestArchitecture.X86_64,
            targetFileName = "Win10_22H2_Enterprise_Eval_x64_en-us.iso",
            resolveStrategy = IsoResolveStrategy.DIRECT_URL,
            primaryUrl = "https://go.microsoft.com/fwlink/p/?LinkID=2208844&clcid=0x409&culture=en-us&country=US"
        ),
        IsoCatalogEntry(
            id = "windows-11-eval-x64",
            title = "Windows 11 Enterprise 25H2 (x64)",
            subtitle = "Official Microsoft 90-day evaluation ISO (en-US) — direct from Microsoft",
            approxSizeLabel = "~6.6 GB",
            preset = OsPreset.WINDOWS,
            architecture = GuestArchitecture.X86_64,
            targetFileName = "Win11_25H2_Enterprise_Eval_x64_en-us.iso",
            resolveStrategy = IsoResolveStrategy.DIRECT_URL,
            primaryUrl = "https://go.microsoft.com/fwlink/?linkid=2334167"
        ),
        IsoCatalogEntry(
            id = "windows-11-ltsc-x64",
            title = "Windows 11 Enterprise LTSC 2024 (x64)",
            subtitle = "Lighter official Microsoft LTSC evaluation ISO (en-US) — smaller footprint for QEMU",
            approxSizeLabel = "~4.8 GB",
            preset = OsPreset.WINDOWS,
            architecture = GuestArchitecture.X86_64,
            targetFileName = "Win11_LTSC_2024_Eval_x64_en-us.iso",
            resolveStrategy = IsoResolveStrategy.DIRECT_URL,
            primaryUrl = "https://go.microsoft.com/fwlink/?linkid=2289029"
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
        ),
        IsoCatalogEntry(
            id = "windows-11-ltsc-arm64",
            title = "Windows 11 IoT LTSC 2024 (ARM64)",
            subtitle = "Official Microsoft ARM64 evaluation ISO (en-US) for QEMU AArch64 virt + EDK2 UEFI",
            approxSizeLabel = "~4.7 GB",
            preset = OsPreset.WINDOWS_AARCH64,
            architecture = GuestArchitecture.AARCH64,
            targetFileName = "Win11_IoT_LTSC_2024_Eval_ARM64_en-us.iso",
            resolveStrategy = IsoResolveStrategy.DIRECT_URL,
            primaryUrl = "https://go.microsoft.com/fwlink/?linkid=2269595&clcid=0x409&culture=en-us&country=us"
        ),
        // PowerPC (Classic Mac OS) Images
        IsoCatalogEntry(
            id = "macos-921-ppc",
            title = "Mac OS 9.2.1 Install CD (PowerPC)",
            subtitle = "Bootable Classic Mac OS 9.2.1 installer ISO from Internet Archive — ready for mac99,via=pmu",
            approxSizeLabel = "~640 MB",
            preset = OsPreset.MACOS_9,
            architecture = GuestArchitecture.PPC,
            targetFileName = "macos_921_ppc.iso",
            resolveStrategy = IsoResolveStrategy.DIRECT_URL,
            primaryUrl = "https://archive.org/download/os-9-install-cd/macos_921_ppc.iso"
        )
    )

    fun findById(id: String?): IsoCatalogEntry? = entries.firstOrNull { it.id == id }

    fun entriesForArch(arch: GuestArchitecture?): List<IsoCatalogEntry> =
        if (arch == null) entries else entries.filter { it.architecture == arch }

    fun recommendedForPreset(preset: OsPreset, arch: GuestArchitecture = preset.defaultArch): IsoCatalogEntry = when (preset) {
        OsPreset.LINUX_ALPINE -> findById("alpine-virt-x86_64") ?: entries.first()
        OsPreset.LINUX_DEBIAN -> findById("debian-netinst-amd64") ?: entries.first()
        OsPreset.WINDOWS -> findById("windows-10-eval-x64") ?: entries.first()
        OsPreset.FREEDOS -> findById("freedos-13-livecd") ?: entries.first()
        OsPreset.LINUX_ALPINE_AARCH64 -> findById("alpine-virt-aarch64") ?: entries.first()
        OsPreset.LINUX_DEBIAN_AARCH64 -> findById("debian-netinst-arm64") ?: entries.first()
        OsPreset.WINDOWS_AARCH64 -> findById("windows-11-ltsc-arm64") ?: entries.first()
        OsPreset.MACOS_9 -> findById("macos-921-ppc") ?: entries.first()
        else -> when (arch) {
            GuestArchitecture.AARCH64 -> findById("alpine-virt-aarch64") ?: entries.first()
            GuestArchitecture.PPC -> findById("macos-921-ppc") ?: entries.first()
            GuestArchitecture.X86_64 -> findById("alpine-virt-x86_64") ?: entries.first()
        }
    }

    fun recommendedForPresetOrNull(preset: OsPreset, arch: GuestArchitecture = preset.defaultArch): IsoCatalogEntry? =
        when (preset) {
            OsPreset.MACOS_X_PPC, OsPreset.CUSTOM_PPC -> null
            else -> recommendedForPreset(preset, arch)
        }
}

class IsoDownloader(context: Context) {

    private val appContext: Context = context.applicationContext
    private val isosDir: File = File(appContext.filesDir, "isos").apply { mkdirs() }
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeTitles = ConcurrentHashMap<String, String>()
    private val activeDownloadCount = AtomicInteger(0)
    private val wakeLockGuard = Any()
    private var wakeLock: PowerManager.WakeLock? = null

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

    fun getDownloadTitle(id: String): String =
        activeTitles[id] ?: IsoCatalog.findById(id)?.title ?: "OS Image Download"

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

    fun startCatalogDownload(
        entry: IsoCatalogEntry,
        onComplete: (File) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (activeJobs[entry.id]?.isActive == true) return
        activeTitles[entry.id] = entry.title
        updateState(entry.id, IsoDownloadState.Resolving())
        IsoDownloadForegroundService.start(appContext)

        val job = downloadScope.launch {
            try {
                val result = downloadCatalogEntry(entry)
                withContext(Dispatchers.Main.immediate) {
                    result.onSuccess(onComplete)
                    result.onFailure { err -> onError(err.message ?: "Download failed") }
                }
            } finally {
                activeJobs.remove(entry.id)
                activeTitles.remove(entry.id)
            }
        }
        activeJobs[entry.id] = job
    }

    fun startCustomDownload(
        urlString: String,
        preferredFileName: String? = null,
        onComplete: (File) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val id = CUSTOM_DOWNLOAD_ID
        if (activeJobs[id]?.isActive == true) return
        val displayTitle = preferredFileName?.takeIf { it.isNotBlank() }
            ?: urlString.trim().substringAfterLast('/').substringBefore('?').ifBlank { "Custom ISO" }
        activeTitles[id] = displayTitle
        updateState(id, IsoDownloadState.Resolving("Connecting to server..."))
        IsoDownloadForegroundService.start(appContext)

        val job = downloadScope.launch {
            try {
                val result = downloadCustomUrl(urlString, preferredFileName)
                withContext(Dispatchers.Main.immediate) {
                    result.onSuccess(onComplete)
                    result.onFailure { err -> onError(err.message ?: "Custom download failed") }
                }
            } finally {
                activeJobs.remove(id)
                activeTitles.remove(id)
            }
        }
        activeJobs[id] = job
    }

    fun cancelDownload(id: String) {
        activeJobs.remove(id)?.cancel()
        activeTitles.remove(id)
        updateState(id, IsoDownloadState.Idle)
    }

    fun cancelAllDownloads() {
        val ids = activeJobs.keys.toList()
        for (id in ids) {
            cancelDownload(id)
        }
    }

    suspend fun downloadCatalogEntry(entry: IsoCatalogEntry): Result<File> = withContext(Dispatchers.IO) {
        acquireWakeLock()
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
            val msg = t.message?.take(180) ?: "Download failed"
            updateState(entry.id, IsoDownloadState.Failed(msg))
            Result.failure(t)
        } finally {
            releaseWakeLock()
        }
    }

    suspend fun downloadCustomUrl(urlString: String, preferredFileName: String? = null): Result<File> =
        withContext(Dispatchers.IO) {
            val id = CUSTOM_DOWNLOAD_ID
            acquireWakeLock()
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
                val msg = t.message?.take(180) ?: "Custom download failed"
                updateState(id, IsoDownloadState.Failed(msg))
                Result.failure(t)
            } finally {
                releaseWakeLock()
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
        val urlMetaFile = File(isosDir, "${destFile.name}.part.url")

        // Only reuse an existing .part file if it came from the exact same URL
        val savedUrl = if (urlMetaFile.exists()) runCatching { urlMetaFile.readText().trim() }.getOrNull() else null
        if (tempFile.exists() && savedUrl != urlString) {
            tempFile.delete()
        }
        runCatching { urlMetaFile.writeText(urlString) }

        var attempt = 0
        var lastTransientError: IOException? = null
        var downloadComplete = false

        while (!downloadComplete && attempt <= MAX_TRANSIENT_RETRIES) {
            if (!isActive) {
                tempFile.delete()
                urlMetaFile.delete()
                throw CancellationException("Download cancelled")
            }

            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
            if (existingBytes > 0L) {
                updateState(downloadId, IsoDownloadState.Resolving("Resuming download..."))
            }

            var conn: HttpURLConnection? = null
            try {
                conn = openFollowingRedirects(urlString, rangeStart = existingBytes)
                val code = conn.responseCode

                if (code == 416) {
                    val rangeTotal = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                    if (rangeTotal > 0L && existingBytes == rangeTotal) {
                        downloadComplete = true
                        break
                    }
                    // Stale/oversized .part file; reset and restart cleanly from byte 0
                    tempFile.delete()
                    attempt++
                    continue
                }

                if (code == 408 || code == 429 || code in 500..599) {
                    throw IOException("Server returned HTTP $code")
                }
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    tempFile.delete()
                    urlMetaFile.delete()
                    error("Server returned HTTP $code")
                }

                val isResumed = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
                val startOffset = if (isResumed) existingBytes else 0L
                val totalBytes = if (isResumed) {
                    val parsedTotal = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                    when {
                        parsedTotal > 0L -> parsedTotal
                        conn.contentLengthLong > 0L -> startOffset + conn.contentLengthLong
                        else -> -1L
                    }
                } else {
                    conn.contentLengthLong
                }

                var downloaded = startOffset
                var lastTickTime = System.currentTimeMillis()
                var lastTickBytes = downloaded

                updateState(downloadId, IsoDownloadState.Downloading(downloaded, totalBytes, 0L))

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile, isResumed).use { output ->
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
                        output.flush()
                    }
                }

                if (totalBytes > 0L && downloaded < totalBytes) {
                    throw IOException("Connection interrupted at $downloaded / $totalBytes bytes")
                }
                downloadComplete = true
            } catch (ce: CancellationException) {
                tempFile.delete()
                urlMetaFile.delete()
                throw ce
            } catch (ioe: IOException) {
                // Keep tempFile + urlMetaFile so retry or manual restart resumes via HTTP Range
                lastTransientError = ioe
                attempt++
                if (attempt <= MAX_TRANSIENT_RETRIES) {
                    val backoffMs = RETRY_BACKOFF_MS[(attempt - 1).coerceAtMost(RETRY_BACKOFF_MS.lastIndex)]
                    val sec = backoffMs / 1000L
                    updateState(
                        downloadId,
                        IsoDownloadState.Resolving(
                            "Connection interrupted — resuming in ${sec}s ($attempt/$MAX_TRANSIENT_RETRIES)..."
                        )
                    )
                    delay(backoffMs)
                }
            } finally {
                conn?.disconnect()
            }
        }

        if (!downloadComplete) {
            val reason = lastTransientError?.message ?: "Network interrupted"
            throw IOException("Paused (progress saved — tap Download to resume): $reason", lastTransientError)
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
        urlMetaFile.delete()
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

    private fun openFollowingRedirects(urlString: String, rangeStart: Long = 0L): HttpURLConnection {
        var currentUrl = urlString
        repeat(6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 45_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "QemuSDL-Android/1.1")
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
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

    private fun acquireWakeLock() {
        val count = activeDownloadCount.incrementAndGet()
        if (count == 1) {
            synchronized(wakeLockGuard) {
                runCatching {
                    val pm = appContext.getSystemService(PowerManager::class.java)
                    if (wakeLock == null) {
                        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                            setReferenceCounted(false)
                        }
                    }
                    if (wakeLock?.isHeld != true) {
                        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                    }
                }
            }
        }
    }

    private fun releaseWakeLock() {
        val remaining = activeDownloadCount.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0) {
            synchronized(wakeLockGuard) {
                runCatching {
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                    }
                }
            }
        }
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
        private const val MAX_TRANSIENT_RETRIES = 6
        private val RETRY_BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 20_000L, 30_000L)
        private const val WAKE_LOCK_TAG = "QemuSDL:IsoDownloaderCpuLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        internal fun parseContentRangeTotal(contentRangeHeader: String?): Long {
            if (contentRangeHeader.isNullOrBlank()) return -1L
            val slashIdx = contentRangeHeader.lastIndexOf('/')
            if (slashIdx == -1 || slashIdx == contentRangeHeader.lastIndex) return -1L
            val totalStr = contentRangeHeader.substring(slashIdx + 1).trim()
            return totalStr.toLongOrNull() ?: -1L
        }

        @Volatile
        private var instance: IsoDownloader? = null

        fun getInstance(context: Context): IsoDownloader =
            instance ?: synchronized(this) {
                instance ?: IsoDownloader(context.applicationContext).also { instance = it }
            }
    }
}
