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
        assertEquals("freedos-13-livecd", IsoCatalog.recommendedForPreset(OsPreset.FREEDOS).id)
    }
}
