package com.qemusdl.app.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuGuestAgentProtocolTest {

    @Test
    fun encodeSyncDelimitedPrependsSentinelAndNewline() {
        val bytes = GuestAgentProtocol.encodeSyncDelimited(42)
        assertEquals(0xFF.toByte(), bytes[0])
        val text = String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
        assertTrue(text.endsWith("\n"))
        assertTrue(text.contains("\"execute\":\"guest-sync-delimited\""))
        assertTrue(text.contains("\"id\":42"))
    }

    @Test
    fun parseSyncDelimitedIdHandlesSentinelAndWhitespace() {
        assertEquals(1001L, GuestAgentProtocol.parseSyncDelimitedId("\u00FF{\"return\": 1001}"))
        assertEquals(99L, GuestAgentProtocol.parseSyncDelimitedId("  {\"return\":99} "))
        assertNull(GuestAgentProtocol.parseSyncDelimitedId("{\"error\":{\"class\":\"GenericError\"}}"))
        assertNull(GuestAgentProtocol.parseSyncDelimitedId(""))
    }

    @Test
    fun parseAgentVersionAndOsInfoExtractFields() {
        val infoJson = """{"return":{"version":"9.2.0","supported_commands":[]}}"""
        assertEquals("9.2.0", GuestAgentProtocol.parseAgentVersion(infoJson))

        val osJson = """{"return":{"name":"Debian GNU/Linux","pretty-name":"Debian GNU/Linux 12 (bookworm)","version":"12 (bookworm)","version-id":"12"}}"""
        val (name, version) = GuestAgentProtocol.parseOsInfo(osJson)
        assertEquals("Debian GNU/Linux 12 (bookworm)", name)
        assertEquals("12 (bookworm)", version)
    }

    @Test
    fun parseIpv4AddressesFiltersLoopbackAndIpv6() {
        val netJson = """
            {
              "return": [
                {
                  "name": "lo",
                  "ip-addresses": [
                    {"ip-address-type": "ipv4", "ip-address": "127.0.0.1"},
                    {"ip-address-type": "ipv6", "ip-address": "::1"}
                  ]
                },
                {
                  "name": "eth0",
                  "ip-addresses": [
                    {"ip-address-type": "ipv4", "ip-address": "10.0.2.15"},
                    {"ip-address-type": "ipv6", "ip-address": "fe80::5054:ff:fe12:3456"},
                    {"ip-address-type": "ipv4", "ip-address": "10.0.2.15"}
                  ]
                }
              ]
            }
        """.trimIndent()
        assertEquals(listOf("10.0.2.15"), GuestAgentProtocol.parseIpv4Addresses(netJson))
    }

    @Test
    fun encodeSetTimeAndShutdownCommandsAreValidJsonLines() {
        val setTime = String(GuestAgentProtocol.encodeSetTimeCommand(1_700_000_000_000_000_000L), Charsets.UTF_8)
        assertTrue(setTime.contains("\"execute\":\"guest-set-time\""))
        assertTrue(setTime.contains("1700000000000000000"))
        assertTrue(setTime.endsWith("\n"))

        val shutdown = String(GuestAgentProtocol.encodeShutdownCommand("powerdown"), Charsets.UTF_8)
        assertTrue(shutdown.contains("\"execute\":\"guest-shutdown\""))
        assertTrue(shutdown.contains("\"mode\":\"powerdown\""))
        assertTrue(shutdown.endsWith("\n"))
    }

    @Test
    fun responseStatusHelpersDistinguishOkAndError() {
        assertTrue(GuestAgentProtocol.isOkResponse("""{"return":{}}"""))
        assertFalse(GuestAgentProtocol.hasError("""{"return":{}}"""))
        assertFalse(GuestAgentProtocol.isOkResponse("""{"error":{"class":"CommandNotFound"}}"""))
        assertTrue(GuestAgentProtocol.hasError("""{"error":{"class":"CommandNotFound"}}"""))
    }
}
