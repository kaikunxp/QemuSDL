package com.qemusdl.app.qemu

import com.qemusdl.app.data.OsPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IsoCatalogTest {

    @Test
    fun alpineRegexMatchesOfficialYamlFormat() {
        val alpine = IsoCatalog.findById("alpine-virt-x86_64")
        assertNotNull(alpine)
        val sampleYaml = """
            - arch: x86_64
              flavor: alpine-virt
              iso: alpine-virt-3.21.3-x86_64.iso
              file: alpine-virt-3.21.3-x86_64.iso
        """.trimIndent()
        val match = Regex(alpine!!.regexPattern!!).find(sampleYaml)
        assertNotNull(match)
        assertEquals("alpine-virt-3.21.3-x86_64.iso", match!!.groupValues[1])
    }

    @Test
    fun debianRegexMatchesOfficialSha256SumsFormat() {
        val debian = IsoCatalog.findById("debian-netinst-amd64")
        assertNotNull(debian)
        val sampleSums = "a1b2c3d4e5f6  debian-12.10.0-amd64-netinst.iso\n"
        val match = Regex(debian!!.regexPattern!!).find(sampleSums)
        assertNotNull(match)
        assertEquals("debian-12.10.0-amd64-netinst.iso", match!!.groupValues[1])
    }

    @Test
    fun presetRecommendationsReturnExpectedEntries() {
        assertEquals("alpine-virt-x86_64", IsoCatalog.recommendedForPreset(OsPreset.LINUX_ALPINE).id)
        assertEquals("debian-netinst-amd64", IsoCatalog.recommendedForPreset(OsPreset.LINUX_DEBIAN).id)
        assertEquals("windows-10-eval-x64", IsoCatalog.recommendedForPreset(OsPreset.WINDOWS).id)
        assertEquals("freedos-13-livecd", IsoCatalog.recommendedForPreset(OsPreset.FREEDOS).id)
        assertEquals("alpine-virt-aarch64", IsoCatalog.recommendedForPreset(OsPreset.LINUX_ALPINE_AARCH64).id)
        assertEquals("debian-netinst-arm64", IsoCatalog.recommendedForPreset(OsPreset.LINUX_DEBIAN_AARCH64).id)
        assertEquals("windows-11-ltsc-arm64", IsoCatalog.recommendedForPreset(OsPreset.WINDOWS_AARCH64).id)
        assertEquals("macos-921-ppc", IsoCatalog.recommendedForPreset(OsPreset.MACOS_9).id)
        assertEquals(
            "https://archive.org/download/os-9-install-cd/macos_921_ppc.iso",
            IsoCatalog.recommendedForPresetOrNull(OsPreset.MACOS_9)?.primaryUrl
        )
        assertNotNull(IsoCatalog.findById("windows-11-eval-x64"))
        assertNotNull(IsoCatalog.findById("windows-11-ltsc-x64"))
        org.junit.Assert.assertNull(IsoCatalog.recommendedForPresetOrNull(OsPreset.MACOS_X_PPC))
    }

    @Test
    fun aarch64AlpineAndDebianRegexMatchesOfficialManifests() {
        val alpineArm = IsoCatalog.findById("alpine-virt-aarch64")
        assertNotNull(alpineArm)
        val sampleAlpineYaml = """
            - arch: aarch64
              flavor: alpine-virt
              iso: alpine-virt-3.21.3-aarch64.iso
              file: alpine-virt-3.21.3-aarch64.iso
        """.trimIndent()
        val alpineMatch = Regex(alpineArm!!.regexPattern!!).find(sampleAlpineYaml)
        assertNotNull(alpineMatch)
        assertEquals("alpine-virt-3.21.3-aarch64.iso", alpineMatch!!.groupValues[1])

        val debianArm = IsoCatalog.findById("debian-netinst-arm64")
        assertNotNull(debianArm)
        val sampleDebianSums = "f0e1d2c3b4a5  debian-12.10.0-arm64-netinst.iso\n"
        val debianMatch = Regex(debianArm!!.regexPattern!!).find(sampleDebianSums)
        assertNotNull(debianMatch)
        assertEquals("debian-12.10.0-arm64-netinst.iso", debianMatch!!.groupValues[1])
    }
}
