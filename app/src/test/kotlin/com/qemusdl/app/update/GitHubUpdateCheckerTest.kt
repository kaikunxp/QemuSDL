package com.qemusdl.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerTest {

    @Test
    fun isNewerVersion_handlesSemVerAndLeadingV() {
        assertTrue(GitHubUpdateChecker.isNewerVersion("1.4.0", "v1.4.1"))
        assertTrue(GitHubUpdateChecker.isNewerVersion("1.4.0", "v1.5.0"))
        assertTrue(GitHubUpdateChecker.isNewerVersion("1.4.0", "v2.0.0"))
        assertTrue(GitHubUpdateChecker.isNewerVersion("1.4.0", "v1.10.0"))
        assertTrue(GitHubUpdateChecker.isNewerVersion("1.4", "v1.4.1"))
        assertTrue(GitHubUpdateChecker.isNewerVersion("1.4.0-beta.1", "v1.4.0"))

        assertFalse(GitHubUpdateChecker.isNewerVersion("1.4.0", "v1.4.0"))
        assertFalse(GitHubUpdateChecker.isNewerVersion("1.4.0", "1.4"))
        assertFalse(GitHubUpdateChecker.isNewerVersion("1.4.0", "v1.3.9"))
        assertFalse(GitHubUpdateChecker.isNewerVersion("1.4.0", "v1.4.0-rc.1"))
    }

    @Test
    fun parseReleaseJson_extractsMetadataAndPrefersMatchingAbiApk() {
        val sampleJson = """
            {
              "tag_name": "v1.5.0",
              "name": "QemuSDL 1.5.0",
              "html_url": "https://github.com/kaikunxp/QemuSDL/releases/tag/v1.5.0",
              "published_at": "2026-12-01T12:00:00Z",
              "body": "- Added new features\n- Fixed bugs",
              "assets": [
                {
                  "name": "QemuSDL-v1.5.0-x86_64.apk",
                  "browser_download_url": "https://github.com/kaikunxp/QemuSDL/releases/download/v1.5.0/QemuSDL-v1.5.0-x86_64.apk",
                  "size": 52428800
                },
                {
                  "name": "QemuSDL-v1.5.0-arm64-v8a.apk",
                  "browser_download_url": "https://github.com/kaikunxp/QemuSDL/releases/download/v1.5.0/QemuSDL-v1.5.0-arm64-v8a.apk",
                  "size": 62914560
                }
              ]
            }
        """.trimIndent()

        val armRelease = GitHubUpdateChecker.parseReleaseJson(sampleJson, preferredAbi = "arm64-v8a")
        assertNotNull(armRelease)
        assertEquals("v1.5.0", armRelease!!.tagName)
        assertEquals("1.5.0", armRelease.versionName)
        assertEquals("QemuSDL 1.5.0", armRelease.title)
        assertEquals("QemuSDL-v1.5.0-arm64-v8a.apk", armRelease.apkAssetName)
        assertEquals(
            "https://github.com/kaikunxp/QemuSDL/releases/download/v1.5.0/QemuSDL-v1.5.0-arm64-v8a.apk",
            armRelease.apkDownloadUrl
        )
        assertEquals(62914560L, armRelease.apkSizeBytes)

        val x86Release = GitHubUpdateChecker.parseReleaseJson(sampleJson, preferredAbi = "x86_64")
        assertEquals("QemuSDL-v1.5.0-x86_64.apk", x86Release!!.apkAssetName)
    }
}
