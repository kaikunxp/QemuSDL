package com.qemusdl.app.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class GuestAgentSnapshot(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val agentVersion: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val ipv4Addresses: List<String> = emptyList(),
    val lastTimeSyncEpochMs: Long? = null
)

/**
 * Pure JSON-RPC encoder/decoder for the QEMU Guest Agent (`org.qemu.guest_agent.0`) wire protocol,
 * separated from Android's `LocalSocket` I/O for deterministic JVM unit testing.
 */
internal object GuestAgentProtocol {
    const val SYNC_SENTINEL_BYTE: Int = 0xFF
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeSyncDelimited(id: Int): ByteArray {
        val payload = buildJsonObject {
            put("execute", "guest-sync-delimited")
            put("arguments", buildJsonObject { put("id", id) })
        }.toString()
        val body = (payload + "\n").toByteArray(Charsets.UTF_8)
        return ByteArray(body.size + 1).apply {
            this[0] = SYNC_SENTINEL_BYTE.toByte()
            body.copyInto(this, destinationOffset = 1)
        }
    }

    fun encodeCommand(command: String, arguments: JsonObject? = null): ByteArray {
        val obj = buildJsonObject {
            put("execute", command)
            if (arguments != null) {
                put("arguments", arguments)
            }
        }
        return (obj.toString() + "\n").toByteArray(Charsets.UTF_8)
    }

    fun encodeSetTimeCommand(timeNanos: Long): ByteArray =
        encodeCommand(
            command = "guest-set-time",
            arguments = buildJsonObject { put("time", timeNanos) }
        )

    fun encodeShutdownCommand(mode: String = "powerdown"): ByteArray =
        encodeCommand(
            command = "guest-shutdown",
            arguments = buildJsonObject { put("mode", mode) }
        )

    fun parseSyncDelimitedId(rawLine: String): Long? {
        val cleaned = rawLine.trimStart { it.code == SYNC_SENTINEL_BYTE || it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        return runCatching {
            val obj = json.parseToJsonElement(cleaned).jsonObject
            obj["return"]?.jsonPrimitive?.longOrNull
        }.getOrNull()
    }

    fun parseReturnObject(rawLine: String): JsonObject? {
        val cleaned = rawLine.trimStart { it.code == SYNC_SENTINEL_BYTE || it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        return runCatching {
            val obj = json.parseToJsonElement(cleaned).jsonObject
            obj["return"]?.jsonObject
        }.getOrNull()
    }

    fun parseAgentVersion(rawLine: String): String? =
        parseReturnObject(rawLine)?.get("version")?.jsonPrimitive?.contentOrNull

    fun parseOsInfo(rawLine: String): Pair<String?, String?> {
        val ret = parseReturnObject(rawLine) ?: return null to null
        val pretty = ret["pretty-name"]?.jsonPrimitive?.contentOrNull
        val name = pretty ?: ret["name"]?.jsonPrimitive?.contentOrNull
        val version = ret["version"]?.jsonPrimitive?.contentOrNull
            ?: ret["version-id"]?.jsonPrimitive?.contentOrNull
        return name to version
    }

    fun parseIpv4Addresses(rawLine: String): List<String> {
        val cleaned = rawLine.trimStart { it.code == SYNC_SENTINEL_BYTE || it.isWhitespace() }
        if (cleaned.isEmpty()) return emptyList()
        return runCatching {
            val arr = json.parseToJsonElement(cleaned).jsonObject["return"]?.jsonArray ?: return emptyList()
            buildList {
                for (ifaceEl in arr) {
                    val iface = ifaceEl.jsonObject
                    val ips = iface["ip-addresses"]?.jsonArray ?: continue
                    for (ipEl in ips) {
                        val ipObj = ipEl.jsonObject
                        val type = ipObj["ip-address-type"]?.jsonPrimitive?.contentOrNull
                        val addr = ipObj["ip-address"]?.jsonPrimitive?.contentOrNull?.trim()
                        if (type == "ipv4" && !addr.isNullOrEmpty() && !addr.startsWith("127.")) {
                            add(addr)
                        }
                    }
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    fun hasError(rawLine: String): Boolean {
        val cleaned = rawLine.trimStart { it.code == SYNC_SENTINEL_BYTE || it.isWhitespace() }
        if (cleaned.isEmpty()) return true
        return runCatching {
            json.parseToJsonElement(cleaned).jsonObject.containsKey("error")
        }.getOrDefault(true)
    }

    fun isOkResponse(rawLine: String): Boolean {
        val cleaned = rawLine.trimStart { it.code == SYNC_SENTINEL_BYTE || it.isWhitespace() }
        if (cleaned.isEmpty()) return false
        return runCatching {
            val obj = json.parseToJsonElement(cleaned).jsonObject
            obj.containsKey("return") && !obj.containsKey("error")
        }.getOrDefault(false)
    }
}

/**
 * Lightweight client for QEMU's `org.qemu.guest_agent.0` virtio-serial Unix socket.
 * Enables graceful OS shutdown (`guest-shutdown`), automatic host->guest clock resync
 * (`guest-set-time`), and live guest OS / IP telemetry without rebuilding QEMU.
 */
class QemuGuestAgentClient(
    private val socketPath: String
) {
    private val syncIdCounter = AtomicInteger(1000)

    private inline fun <T> withSyncedSocket(
        timeoutMs: Int = 1500,
        block: (LocalSocket) -> T
    ): T? {
        if (!File(socketPath).exists()) return null
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = timeoutMs

            val syncId = syncIdCounter.incrementAndGet()
            val out = socket.outputStream
            out.write(GuestAgentProtocol.encodeSyncDelimited(syncId))
            out.flush()

            // Read until we receive the matching delimited sync response (discarding stale pre-sentinel data)
            var matched = false
            repeat(6) {
                val line = readLineSkippingSentinel(socket) ?: return@repeat
                if (GuestAgentProtocol.parseSyncDelimitedId(line) == syncId.toLong()) {
                    matched = true
                    return@repeat
                }
            }
            if (!matched) return null
            block(socket)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readLineSkippingSentinel(socket: LocalSocket): String? {
        val input = socket.inputStream
        val buf = ByteArrayOutputStream(256)
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (buf.size() > 0) buf.toString(Charsets.UTF_8.name()) else null
            }
            if (b == GuestAgentProtocol.SYNC_SENTINEL_BYTE) {
                // New delimited response starts here; discard any partial bytes before sentinel
                buf.reset()
                continue
            }
            if (b == '\n'.code) {
                if (buf.size() == 0) continue
                return buf.toString(Charsets.UTF_8.name())
            }
            if (buf.size() < 65_536) {
                buf.write(b)
            }
        }
    }

    private fun sendAndReadLine(socket: LocalSocket, payload: ByteArray): String? {
        val out = socket.outputStream
        out.write(payload)
        out.flush()
        return readLineSkippingSentinel(socket)
    }

    fun querySnapshot(previousSyncMs: Long? = null): GuestAgentSnapshot {
        val result = withSyncedSocket(timeoutMs = 1200) { socket ->
            val pingResp = sendAndReadLine(socket, GuestAgentProtocol.encodeCommand("guest-ping"))
            if (pingResp == null || !GuestAgentProtocol.isOkResponse(pingResp)) {
                return@withSyncedSocket null
            }
            val infoResp = sendAndReadLine(socket, GuestAgentProtocol.encodeCommand("guest-info"))
            val version = infoResp?.let { GuestAgentProtocol.parseAgentVersion(it) }

            val osResp = sendAndReadLine(socket, GuestAgentProtocol.encodeCommand("guest-get-osinfo"))
            val (osName, osVersion) = osResp?.let { GuestAgentProtocol.parseOsInfo(it) } ?: (null to null)

            val netResp = sendAndReadLine(socket, GuestAgentProtocol.encodeCommand("guest-network-get-interfaces"))
            val ips = netResp?.let { GuestAgentProtocol.parseIpv4Addresses(it) }.orEmpty()

            GuestAgentSnapshot(
                enabled = true,
                connected = true,
                agentVersion = version,
                osName = osName,
                osVersion = osVersion,
                ipv4Addresses = ips,
                lastTimeSyncEpochMs = previousSyncMs
            )
        }
        return result ?: GuestAgentSnapshot(
            enabled = true,
            connected = false,
            lastTimeSyncEpochMs = previousSyncMs
        )
    }

    fun syncGuestTime(epochMs: Long = System.currentTimeMillis()): Boolean {
        val nanos = epochMs * 1_000_000L
        return withSyncedSocket(timeoutMs = 1500) { socket ->
            val resp = sendAndReadLine(socket, GuestAgentProtocol.encodeSetTimeCommand(nanos))
            resp != null && GuestAgentProtocol.isOkResponse(resp)
        } ?: false
    }

    fun requestGracefulShutdown(): Boolean {
        // guest-shutdown does not guarantee a response because the OS powers down immediately.
        return withSyncedSocket(timeoutMs = 1500) { socket ->
            val out = socket.outputStream
            out.write(GuestAgentProtocol.encodeShutdownCommand("powerdown"))
            out.flush()
            true
        } ?: false
    }
}
