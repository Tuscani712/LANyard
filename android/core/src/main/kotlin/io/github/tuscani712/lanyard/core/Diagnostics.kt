package io.github.tuscani712.lanyard.core

/** The state of one diagnostic check. */
enum class CheckStatus { Ok, Warning, Failed, Skipped }

/** One row of the troubleshoot report. */
data class CheckResult(
    val id: String,
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val fix: String = "",
)

/** The paired device chosen for the reachability check. */
data class DiagTarget(
    val name: String,
    val host: String,
    val port: Int,
    val expectedFingerprint: String,
)

/** The result of probing a device: TCP, then TLS, then identity. */
data class Reachability(
    val tcp: Boolean,
    val tls: Boolean,
    val presentedFingerprint: String? = null,
    val match: Boolean = false,
)

/**
 * Everything the diagnostics need, so tests can inject each state. Implemented
 * on Android by a real environment; the checks themselves stay pure.
 */
interface DiagEnv {
    fun networkConnected(): Boolean
    fun networkMetered(): Boolean
    fun networkRestricted(): Boolean
    fun wifiOnly(): Boolean
    fun localAddresses(): List<String>
    fun multicastLockHeld(): Boolean
    fun mdnsDevicesSeen(timeoutMillis: Long): Int
    fun reachTarget(): DiagTarget?
    fun probeTarget(host: String, port: Int, expectedFingerprint: String): Reachability
    fun downloadFolderSelected(): Boolean
    /** Free bytes in the download folder, or a negative value if unreadable. */
    fun downloadFolderFreeBytes(): Long
    fun notificationsGranted(): Boolean
    fun ignoringBatteryOptimizations(): Boolean
}

/**
 * Runs the troubleshoot checks and builds a redacted report. Pure: all input
 * comes from [DiagEnv]. The desktop's peer-port bind, firewall self-probe and
 * clock checks are intentionally omitted (they are rows on neither platform).
 */
object Diagnostics {
    const val MDNS_TIMEOUT_MS = 10_000L

    /** Below this, a download folder is called low on space. */
    const val LOW_SPACE_BYTES = 50L * 1024 * 1024

    fun run(env: DiagEnv): List<CheckResult> = buildList {
        add(networkCheck(env))
        add(addressesCheck(env))
        add(multicastCheck(env))
        add(mdnsCheck(env))
        addAll(reachabilityChecks(env))
        add(diskCheck(env))
        add(notificationsCheck(env))
        add(batteryCheck(env))
    }

    private fun networkCheck(env: DiagEnv): CheckResult {
        val id = "network"
        val title = "Network connection"
        return when {
            !env.networkConnected() -> CheckResult(
                id, title, CheckStatus.Failed, "No network connection.",
                "Connect to Wi-Fi and try again.",
            )
            env.networkMetered() && env.wifiOnly() -> CheckResult(
                id, title, CheckStatus.Warning,
                "Wi-Fi only is on and you are on mobile data.",
                "Connect to Wi-Fi, or turn off Wi-Fi only in Settings.",
            )
            env.networkMetered() -> CheckResult(
                id, title, CheckStatus.Warning,
                "You are on a metered or mobile network.",
                "Connect to Wi-Fi for faster, unmetered transfers.",
            )
            env.networkRestricted() -> CheckResult(
                id, title, CheckStatus.Warning,
                "The network is restricted.",
                "Some guest or public networks block device-to-device traffic.",
            )
            else -> CheckResult(id, title, CheckStatus.Ok, "Connected on Wi-Fi.")
        }
    }

    private fun addressesCheck(env: DiagEnv): CheckResult {
        val count = env.localAddresses().size
        return if (count == 0) {
            CheckResult(
                "addresses", "Local addresses", CheckStatus.Failed, "No local network address found.",
                "Connect to your Wi-Fi network.",
            )
        } else {
            CheckResult("addresses", "Local addresses", CheckStatus.Ok, "Found $count local address(es).")
        }
    }

    private fun multicastCheck(env: DiagEnv): CheckResult =
        if (env.multicastLockHeld()) {
            CheckResult("multicast", "Multicast lock", CheckStatus.Ok, "Multicast reception is available.")
        } else {
            CheckResult(
                "multicast", "Multicast lock", CheckStatus.Warning, "Could not enable multicast reception.",
                "Reconnect to Wi-Fi, then run this check again.",
            )
        }

    private fun mdnsCheck(env: DiagEnv): CheckResult {
        val seen = env.mdnsDevicesSeen(MDNS_TIMEOUT_MS)
        return if (seen > 0) {
            CheckResult("mdns", "Device discovery", CheckStatus.Ok, "Found $seen device(s) nearby.")
        } else {
            CheckResult(
                "mdns", "Device discovery", CheckStatus.Warning, "No devices were found automatically.",
                "Make sure both devices are on the same Wi-Fi network, or add a device by link.",
            )
        }
    }

    private fun reachabilityChecks(env: DiagEnv): List<CheckResult> {
        val target = env.reachTarget() ?: return listOf(
            CheckResult("reach-tcp", "Device reachability", CheckStatus.Skipped, "No device chosen.", "Choose a paired device to test."),
        )
        val result = env.probeTarget(target.host, target.port, target.expectedFingerprint)

        val tcp = CheckResult(
            "reach-tcp", "TCP connection",
            if (result.tcp) CheckStatus.Ok else CheckStatus.Failed,
            if (result.tcp) "Connected to the device." else "Could not connect to the device.",
            if (result.tcp) "" else "Make sure the device is on and on the same network.",
        )
        val tls = when {
            !result.tcp -> CheckResult("reach-tls", "Secure handshake", CheckStatus.Skipped, "Skipped: no connection.", "")
            result.tls -> CheckResult("reach-tls", "Secure handshake", CheckStatus.Ok, "The secure connection was established.")
            else -> CheckResult(
                "reach-tls", "Secure handshake", CheckStatus.Failed, "The secure connection failed.",
                "Try again; if it keeps failing, the other device may need restarting.",
            )
        }
        val identity = when {
            !result.tls -> CheckResult("reach-fp", "Device identity", CheckStatus.Skipped, "Skipped: no secure connection.", "")
            result.match -> CheckResult("reach-fp", "Device identity", CheckStatus.Ok, "The device's identity matches what you paired with.")
            else -> CheckResult(
                "reach-fp", "Device identity", CheckStatus.Failed, "The device's identity changed since you paired.",
                "Do not send anything to it. Remove the pairing and pair again only after verifying the new code out of band.",
            )
        }
        return listOf(tcp, tls, identity)
    }

    private fun diskCheck(env: DiagEnv): CheckResult {
        if (!env.downloadFolderSelected()) {
            return CheckResult(
                "disk", "Download folder", CheckStatus.Skipped, "No default download folder is set.",
                "Set one in Settings so downloads always go to the same place.",
            )
        }
        val free = env.downloadFolderFreeBytes()
        return when {
            free < 0 -> CheckResult(
                "disk", "Download folder", CheckStatus.Warning, "Could not read the free space.",
                "Pick the folder again in Settings.",
            )
            free < LOW_SPACE_BYTES -> CheckResult(
                "disk", "Download folder", CheckStatus.Warning, "Low free space (${free / (1024 * 1024)} MB).",
                "Free up space or choose another folder.",
            )
            else -> CheckResult("disk", "Download folder", CheckStatus.Ok, "${free / (1024 * 1024)} MB free.")
        }
    }

    private fun notificationsCheck(env: DiagEnv): CheckResult =
        if (env.notificationsGranted()) {
            CheckResult("notifications", "Notifications", CheckStatus.Ok, "Notifications are allowed.")
        } else {
            CheckResult(
                "notifications", "Notifications", CheckStatus.Warning, "Notifications are blocked.",
                "Allow them from Settings so you can see transfer progress and results.",
            )
        }

    private fun batteryCheck(env: DiagEnv): CheckResult =
        if (env.ignoringBatteryOptimizations()) {
            CheckResult("battery", "Battery optimization", CheckStatus.Ok, "This app is not restricted by battery optimization.")
        } else {
            CheckResult(
                "battery", "Battery optimization", CheckStatus.Warning, "Android may pause transfers in the background.",
                "In system Settings, set LANyard to Unrestricted so long transfers can finish.",
            )
        }

    /** A plain-text report with secrets redacted. */
    fun copyReport(results: List<CheckResult>, serverEvents: List<String> = emptyList()): String {
        val checks = results.joinToString("\n\n") { r ->
            val fix = if (r.fix.isNotEmpty()) "\nFix: ${r.fix}" else ""
            "[${r.status.name.uppercase()}] ${r.title}\n${r.detail}$fix"
        }
        val events = if (serverEvents.isEmpty()) {
            ""
        } else {
            "\n\nServer events (most recent last):\n" + serverEvents.joinToString("\n")
        }
        return Redaction.redact(checks + events)
    }
}

/** Removes secrets from report text: tokens, invites and full fingerprints. */
object Redaction {
    private val inviteLink = Regex("lanyard://pair[^\\s]*")
    private val urlSecret = Regex("([?&](?:t|token|nonce|invite|n)=)[^&\\s]+")
    private val hex64 = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    // An absolute path under a well-known root (a message can name a file location).
    private val absPath = Regex("/(?:data|storage|sdcard|system|home|users|tmp|private|var|mnt|cache)/[\\w./\\-]+")

    fun redact(text: String): String = text
        .replace(inviteLink, "lanyard://pair?<redacted>")
        .replace(urlSecret) { "${it.groupValues[1]}<redacted>" }
        .replace(hex64) { it.value.take(8) }
        .replace(ipv4, "<ip>")
        .replace(absPath, "<path>")
}
