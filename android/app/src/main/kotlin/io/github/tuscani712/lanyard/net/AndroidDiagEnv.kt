package io.github.tuscani712.lanyard.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import io.github.tuscani712.lanyard.IdentityHolder
import io.github.tuscani712.lanyard.SettingsHolder
import io.github.tuscani712.lanyard.core.DiagEnv
import io.github.tuscani712.lanyard.core.DiagTarget
import io.github.tuscani712.lanyard.core.Identity
import io.github.tuscani712.lanyard.core.ProbeClient
import io.github.tuscani712.lanyard.core.Reachability
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * The real [DiagEnv]: meters, interfaces, mDNS, a live reachability probe,
 * download-folder space, notification and battery state. Blocking calls run on
 * whatever thread the caller chose (the UI runs this on Dispatchers.IO).
 */
class AndroidDiagEnv(
    context: Context,
    private val target: DiagTarget?,
) : DiagEnv {
    private val appContext = context.applicationContext
    private val identity: Identity? = IdentityHolder.identity

    override fun networkConnected(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun networkMetered(): Boolean = AndroidMeteredNetwork(appContext).isMetered()

    override fun networkRestricted(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }

    override fun wifiOnly(): Boolean = SettingsHolder.settings.value.wifiOnly

    override fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    override fun multicastLockHeld(): Boolean {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        val lock = wifi.createMulticastLock("lanyard-diag")
        return try {
            lock.acquire()
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    override fun mdnsDevicesSeen(timeoutMillis: Long): Int {
        val found = Collections.synchronizedSet(HashSet<String>())
        val discovery = NsdDiscovery(appContext)
        discovery.start(
            onFound = { found.add(it.shortId) },
            onLost = { found.remove(it) },
        )
        return try {
            Thread.sleep(timeoutMillis)
            found.size
        } finally {
            discovery.stop()
        }
    }

    override fun reachTarget(): DiagTarget? = target

    override fun probeTarget(host: String, port: Int, expectedFingerprint: String): Reachability {
        val tcp = try {
            Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
            true
        } catch (_: Exception) {
            false
        }
        if (!tcp) return Reachability(tcp = false, tls = false)
        val identity = this.identity ?: return Reachability(tcp = true, tls = false)
        return try {
            val probe = ProbeClient(host, port, identity)
            probe.hello()
            val presented = probe.observedFingerprint()
            Reachability(true, true, presented, presented.equals(expectedFingerprint, ignoreCase = true))
        } catch (_: Exception) {
            Reachability(tcp = true, tls = false)
        }
    }

    override fun downloadFolderSelected(): Boolean = SettingsHolder.settings.value.downloadFolder != null

    override fun downloadFolderFreeBytes(): Long {
        val uri = SettingsHolder.settings.value.downloadFolder?.let(Uri::parse) ?: return -1L
        val path = externalStoragePath(uri) ?: Environment.getExternalStorageDirectory().path
        return runCatching { StatFs(path).availableBytes }.getOrDefault(-1L)
    }

    /** Maps an external-storage tree URI to a filesystem path, best effort. */
    private fun externalStoragePath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val parts = docId.split(":", limit = 2)
        if (parts[0] != "primary") return null
        return "/storage/emulated/0/" + parts.getOrElse(1) { "" }
    }

    override fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun ignoringBatteryOptimizations(): Boolean {
        val power = appContext.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(appContext.packageName)
    }
}
