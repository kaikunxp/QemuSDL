package com.qemusdl.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String?,
    val apkDownloadUrl: String?,
    val apkAssetName: String?,
    val apkSizeBytes: Long?
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val currentVersion: String,
        val release: GitHubReleaseInfo
    ) : UpdateCheckResult

    data class UpToDate(
        val currentVersion: String,
        val release: GitHubReleaseInfo
    ) : UpdateCheckResult

    data class Error(
        val message: String
    ) : UpdateCheckResult
}

@Serializable
internal data class GitHubReleaseResponse(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList()
)

@Serializable
internal data class GitHubReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0L
)

/**
 * Checks GitHub Releases (`kaikunxp/QemuSDL`) for newer published app versions using pure
 * `HttpURLConnection` + `kotlinx.serialization.json` with ABI-aware `.apk` asset selection.
 */
object GitHubUpdateChecker {

    const val GITHUB_OWNER = "kaikunxp"
    const val GITHUB_REPO = "QemuSDL"
    const val REPO_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    const val RELEASES_URL = "$REPO_URL/releases"
    const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val OBTAINIUM_URL =
        "https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/$REPO_URL"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun checkForUpdates(
        currentVersion: String,
        preferredAbi: String? = null
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "QemuSDL-Android/$currentVersion")
            }

            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val release = parseReleaseJson(body, preferredAbi)
                        ?: return@withContext UpdateCheckResult.Error(
                            "GitHub returned a release without a valid version tag."
                        )
                    if (isNewerVersion(currentVersion, release.tagName)) {
                        UpdateCheckResult.UpdateAvailable(
                            currentVersion = normalizeVersion(currentVersion),
                            release = release
                        )
                    } else {
                        UpdateCheckResult.UpToDate(
                            currentVersion = normalizeVersion(currentVersion),
                            release = release
                        )
                    }
                }
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    UpdateCheckResult.Error(
                        "No published GitHub releases were found yet for $GITHUB_OWNER/$GITHUB_REPO."
                    )
                }
                HttpURLConnection.HTTP_FORBIDDEN, 429 -> {
                    UpdateCheckResult.Error(
                        "GitHub API rate limit reached. Try again in a few minutes or open GitHub Releases directly."
                    )
                }
                else -> {
                    UpdateCheckResult.Error("GitHub returned HTTP $code while checking for updates.")
                }
            }
        } catch (e: IOException) {
            UpdateCheckResult.Error(
                "Couldn't reach GitHub (${e.message ?: "network unavailable"}). Check your internet connection and try again."
            )
        } catch (e: Exception) {
            UpdateCheckResult.Error(
                "Update check failed: ${e.message ?: e::class.java.simpleName}"
            )
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parseReleaseJson(
        rawJson: String,
        preferredAbi: String? = null
    ): GitHubReleaseInfo? {
        val parsed = json.decodeFromString<GitHubReleaseResponse>(rawJson)
        val normalizedVersion = normalizeVersion(parsed.tagName)
        if (normalizedVersion.isBlank()) return null

        val bestApk = selectBestApkAsset(parsed.assets, preferredAbi)
        return GitHubReleaseInfo(
            tagName = parsed.tagName.trim(),
            versionName = normalizedVersion,
            title = parsed.name.trim().ifBlank { parsed.tagName.trim() },
            body = parsed.body.trim(),
            htmlUrl = parsed.htmlUrl.trim().ifBlank { RELEASES_URL },
            publishedAt = parsed.publishedAt?.trim()?.takeIf { it.isNotBlank() },
            apkDownloadUrl = bestApk?.browserDownloadUrl?.takeIf { it.isNotBlank() },
            apkAssetName = bestApk?.name?.takeIf { it.isNotBlank() },
            apkSizeBytes = bestApk?.size?.takeIf { it > 0L }
        )
    }

    internal fun selectBestApkAsset(
        assets: List<GitHubReleaseAsset>,
        preferredAbi: String?
    ): GitHubReleaseAsset? {
        val apks = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) && it.browserDownloadUrl.isNotBlank()
        }
        if (apks.isEmpty()) return null

        val normalizedAbi = preferredAbi?.trim()?.lowercase().orEmpty()
        if (normalizedAbi.isNotEmpty()) {
            apks.firstOrNull { it.name.lowercase().contains(normalizedAbi) }?.let { return it }
            if (normalizedAbi == "arm64-v8a") {
                apks.firstOrNull {
                    val lower = it.name.lowercase()
                    lower.contains("arm64") || lower.contains("aarch64")
                }?.let { return it }
            }
        }
        return apks.firstOrNull { it.name.lowercase().contains("universal") }
            ?: apks.firstOrNull { it.name.lowercase().contains("release") }
            ?: apks.first()
    }

    fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    /**
     * Semantic version comparison returning `true` iff [latestTag] represents a strictly newer
     * release than [currentVersion]. Handles `v` prefixes, differing segment counts (e.g. `1.4` vs
     * `1.4.1`), multi-digit segments (`1.10.0 > 1.4.0`), and pre-release tags (`1.4.0 > 1.4.0-rc1`).
     */
    fun isNewerVersion(currentVersion: String, latestTag: String): Boolean {
        val current = parseSemVer(currentVersion)
        val latest = parseSemVer(latestTag)
        val maxLen = maxOf(current.numbers.size, latest.numbers.size)
        for (i in 0 until maxLen) {
            val c = current.numbers.getOrElse(i) { 0 }
            val l = latest.numbers.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        // Same numeric core: stable release is newer than a pre-release
        val currentPre = current.preRelease
        val latestPre = latest.preRelease
        if (currentPre != null && latestPre == null) return true
        if (currentPre == null && latestPre != null) return false
        if (currentPre != null && latestPre != null) {
            return latestPre > currentPre
        }
        return false
    }

    private data class ParsedSemVer(
        val numbers: List<Int>,
        val preRelease: String?
    )

    private fun parseSemVer(raw: String): ParsedSemVer {
        val cleaned = normalizeVersion(raw).substringBefore('+')
        val dashIdx = cleaned.indexOf('-')
        val core = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
        val pre = if (dashIdx >= 0) cleaned.substring(dashIdx + 1).ifBlank { null } else null
        val nums = core.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return ParsedSemVer(nums, pre)
    }
}
