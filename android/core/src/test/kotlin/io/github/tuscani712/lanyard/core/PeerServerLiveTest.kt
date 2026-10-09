package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File

/**
 * The real interop test: a live Go desktop pairs *to* a JVM [PeerServer] (the
 * phone), and both sides end up listing each other. Skipped unless
 * `LANYARD_BIN` is set.
 */
class PeerServerLiveTest {
    private val bin: String? = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }

    private class Phone : AutoCloseable {
        val identity: Identity = Identity.generate("Pixel 8 Pro")
        private val trustFile = File.createTempFile("lanyard-trust", ".json").also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        private val spool = File.createTempFile("spool", ".d").let { it.delete(); it.mkdirs(); it }
        val received = java.util.concurrent.ConcurrentLinkedQueue<String>()
        private val receiver = InboxReceiver(
            spool,
            PushDestination { rel, _, _ -> received.add(rel); rel },
            { Long.MAX_VALUE },
        )
        private val server = PeerServer(
            sessions, receiver, PairInvites(),
            isPaired = { trust.find(it) }, onUnpair = { trust.remove(it) },
        )
        val port: Int = server.start(identity) { p ->
            JsonObject().apply {
                addProperty("device_id", identity.deviceId.take(16))
                addProperty("fingerprint", identity.deviceId)
                addProperty("name", "Pixel 8 Pro")
                addProperty("os", "android")
                addProperty("version", "test")
                addProperty("port", p)
            }
        }
        override fun close() = server.stop()
    }

    private fun await(timeoutMs: Long = 10_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(150)
        }
        return cond()
    }

    @Test
    @Timeout(120)
    fun desktopPairsToThePhoneAndBothListEachOther() {
        assumeTrue(bin != null)
        Phone().use { phone ->
            GoPeer(bin!!).use { peer ->
                val deskFp = peer.fingerprint()
                peer.addPeer("127.0.0.1:${phone.port}")
                val started = peer.startSessionTo(phone.identity.deviceId)
                val desktopSession = started.get("id").asString

                // The phone's person accepts.
                val request = phone.sessions.pending().first()
                assertTrue(request.sas.length == 6)
                phone.sessions.accept(request.id, request.requested)

                assertTrue(await { peer.refreshSession(desktopSession).get("status").asString == "accepted" })
                peer.confirmSession(desktopSession)
                assertTrue(await { phone.trust.find(deskFp) != null }, "phone did not store the desktop")

                assertTrue(
                    await {
                        peer.pairedDevices().any {
                            it.get("cert_fingerprint")?.asString.orEmpty().equals(phone.identity.deviceId, ignoreCase = true)
                        }
                    },
                    "desktop did not list the phone",
                )

                // A replayed confirm is a no-op success, not an error.
                val replay = runCatching { peer.confirmSession(desktopSession) }
                assertTrue(replay.isSuccess, "a replayed confirm should be a no-op success, not an error")
            }
        }
    }

    @Test
    @Timeout(120)
    fun declinedRequestIsRefusedAndNotStored() {
        assumeTrue(bin != null)
        Phone().use { phone ->
            GoPeer(bin!!).use { peer ->
                val deskFp = peer.fingerprint()
                peer.addPeer("127.0.0.1:${phone.port}")
                val started = peer.startSessionTo(phone.identity.deviceId)
                val desktopSession = started.get("id").asString

                phone.sessions.decline(phone.sessions.pending().first().id)

                assertTrue(await { peer.refreshSession(desktopSession).get("status").asString == "rejected" })
                assertTrue(runCatching { peer.confirmSession(desktopSession) }.isFailure)
                assertNull(phone.trust.find(deskFp), "a declined pairing must store nothing")
            }
        }
    }
}
