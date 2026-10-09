package io.github.tuscani712.lanyard.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A real `lanyard` peer running on loopback, for the live tests. Start it with
 * the path to a built binary (the `LANYARD_BIN` env var). Mirrors the setup in
 * `SpikeTest`, and exposes just enough of the loopback UI API to drive pairing.
 */
internal class GoPeer(bin: String) : AutoCloseable {
    val dataDir: File = Files.createTempDirectory("lanyard-core").toFile()
    val peerPort: Int = freePort()
    val uiPort: Int = freePort()

    // Keep the Inbox inside the temp data dir: the shipped default is now
    // ~/LANyard, but tests must never write to the developer's home directory.
    init {
        val inbox = File(dataDir, "Inbox")
        File(dataDir, "config.json").writeText("""{"inbox_folder":${com.google.gson.Gson().toJson(inbox.absolutePath)}}""")
    }

    private val process: Process = ProcessBuilder(
        bin, "--data-dir", dataDir.absolutePath,
        "--no-browser", "--no-tray", "--port", "$peerPort", "--ui-port", "$uiPort",
    ).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.to(File(dataDir, "process.log")))
        .start()

    val base: String
    val ui: GoUi

    init {
        val runInfo = awaitRunInfo(dataDir)
        base = runInfo.str("base")
        val token = URI(runInfo.str("ui_url")).rawQuery
            .split("&").map { it.split("=", limit = 2) }
            .first { it[0] == "t" }[1]
        ui = GoUi(base, token)
    }

    /** The one-time pairing invite and this peer's fingerprint. */
    fun pairPayload(): JsonObject = ui.get("/api/pair/payload").asJsonObject

    fun buildLink(fingerprint: String, addrs: List<String>, nonce: String): String =
        PairLink.build(PairLink.Payload(fingerprint = fingerprint, name = "desktop", addrs = addrs, nonce = nonce))

    /** Accepts the next pending session from [deviceFp] through the UI API. */
    fun acceptPending(        deviceFp: String,
        browse: Boolean = true,
        push: Boolean = true,
        timeoutMs: Long = 20_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pending = ui.get("/api/sessions").asJsonArray
                .map { it.asJsonObject }
                .firstOrNull { it.str("peer_fp") == deviceFp && it.str("status") == "pending" }
            if (pending != null) {
                ui.post(
                    "/api/sessions/${pending.str("id")}/accept",
                    """{"permissions":{"browse":$browse,"push":$push},"keep_connected":false}""",
                )
                return true
            }
            Thread.sleep(150)
        }
        return false
    }

    /** A file the receiver has finalized in its Inbox, or a not-yet-existing path. */
    fun inboxFile(name: String): File = File(File(dataDir, "Inbox"), name)

    /** Pushes local files/folders to [deviceFp] through the UI API. */
    fun push(deviceFp: String, paths: List<String>): JsonObject =
        ui.post("/api/push", """{"device":${quote(deviceFp)},"paths":${com.google.gson.Gson().toJson(paths)}}""").asJsonObject

    /** Blocks until the push job [id] is Done or Failed; returns the state. */
    fun waitPush(id: String, timeoutMs: Long = 120_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val job = ui.get("/api/transfers").asJsonArray.map { it.asJsonObject }.firstOrNull { it.str("id") == id }
            val state = job?.str("state") ?: ""
            if (state == "Done") return state
            if (state == "Failed") return "Failed: " + (job?.str("error") ?: "?")
            Thread.sleep(50)
        }
        return "timeout"
    }

    /** Lists the shares a peer exposes (through the desktop's UI). */
    fun remoteShares(deviceFp: String): com.google.gson.JsonArray =
        ui.get("/api/remote/shares?device=$deviceFp").asJsonArray

    /** Starts a download of [shareId]'s paths into [dest]; returns the job. */
    fun download(deviceFp: String, shareId: String, paths: List<String>, dest: String): JsonObject =
        ui.post(
            "/api/transfers",
            """{"device":${quote(deviceFp)},"share_id":${quote(shareId)},"share_label":"folder","paths":${com.google.gson.Gson().toJson(paths)},"dest":${quote(dest)}}""",
        ).asJsonObject

    /** This desktop's fingerprint. */
    fun fingerprint(): String = pairPayload().str("fp")

    /** Starts Connect/Pair toward [deviceFp] through the UI (no invite: SAS path). */
    fun startSessionTo(deviceFp: String, mode: String = "pair"): JsonObject =
        ui.post(
            "/api/sessions/request",
            """{"device":${quote(deviceFp)},"mode":"$mode","permissions":{"browse":true,"push":true}}""",
        ).asJsonObject

    /** Polls the desktop's view of an outgoing session. */
    fun refreshSession(id: String): JsonObject = ui.post("/api/sessions/${id}/refresh", "{}").asJsonObject

    /** Confirms the SAS on the desktop, which activates the session. */
    fun confirmSession(id: String): JsonObject = ui.post("/api/sessions/${id}/confirm", "{}").asJsonObject

    /**
     * Adds a peer by address through the UI API (the manual "add device" path).
     * With [fingerprint] set the presented certificate must match it before the
     * peer is accepted. Returns the peer record (`verified`, `name`, ...).
     */
    fun addPeer(address: String, fingerprint: String = ""): JsonObject =
        ui.post(
            "/api/peers/add",
            """{"address":${quote(address)},"fingerprint":${quote(fingerprint)}}""",
        ).asJsonObject

    /** The devices this peer currently trusts (its paired list). */
    fun pairedDevices(): List<JsonObject> =
        ui.get("/api/trust").asJsonArray.map { it.asJsonObject }

    fun addShare(path: String, label: String): JsonObject =
        ui.post(
            "/api/shares",
            """{"path":${quote(path)},"label":${quote(label)},"lifetime":"until_stopped","visibility":"paired"}""",
        ).asJsonObject

    override fun close() {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun quote(s: String): String = com.google.gson.Gson().toJson(s)

    companion object {
        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /** A port that was just bound and released, so nothing is listening. */
        fun closedPort(): Int = ServerSocket(0).use { it.localPort }

        private fun awaitRunInfo(dir: File, timeoutMs: Long = 20_000): JsonObject {
            val runFile = File(dir, "run.json")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (runFile.isFile) {
                    try {
                        val json = JsonParser.parseString(runFile.readText()).asJsonObject
                        if (json.str("base").isNotEmpty()) return json
                    } catch (_: Exception) {
                        // still being written
                    }
                }
                Thread.sleep(100)
            }
            error("the Go server did not write run.json; see ${File(dir, "process.log")}")
        }

        fun JsonObject.str(key: String): String =
            get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
    }
}

/** A minimal client for the loopback UI API. */
internal class GoUi(private val base: String, private val token: String) {
    fun get(path: String): JsonElement = send("GET", path, null)

    fun post(path: String, body: String): JsonElement = send("POST", path, body)

    private fun send(method: String, path: String, body: String?): JsonElement {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Cookie", "lany=$token")
        if (method != "GET") {
            conn.setRequestProperty("Origin", base)
            conn.setRequestProperty("Content-Type", "application/json")
        }
        conn.connectTimeout = 5_000
        conn.readTimeout = 15_000
        if (body != null) {
            val bytes = body.toByteArray()
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        check(status in 200..299) { "UI $method $path -> $status: $text" }
        return JsonParser.parseString(text)
    }
}
