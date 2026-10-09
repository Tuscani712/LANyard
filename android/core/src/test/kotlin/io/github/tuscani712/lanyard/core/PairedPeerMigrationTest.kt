package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** A peers.json written before the push-permission fields existed still loads. */
class PairedPeerMigrationTest {
    @Test
    fun oldEntryLoadsWithSafeDefaults() {
        val f = Files.createTempFile("peers", ".json").toFile()
        f.writeText(
            """{"ab":{"fingerprint":"ab","name":"Old","host":"10.0.0.1","port":47800,"browse":true,"push":true,"pairedAt":123}}""",
        )
        val store = JsonFileTrustStore(f)
        val p = store.find("ab")
        assertNotNull(p)
        assertEquals(0L, p!!.pushMaxBytes)
        assertEquals(0L, p.askOver)
        assertEquals(Permission.ALLOW, p.push)
        assertEquals(Permission.ALLOW, p.browse)
        // Text had no field before tri-state permissions; it inherits the push grant.
        assertEquals(Permission.ALLOW, p.text)
    }

    @Test
    fun oldEntryWithFalseMigratesToAskNotNever() {
        val f = Files.createTempFile("peers", ".json").toFile()
        f.writeText(
            """{"ab":{"fingerprint":"ab","name":"Old","host":"10.0.0.1","port":47800,"browse":false,"push":false,"pairedAt":123}}""",
        )
        val store = JsonFileTrustStore(f)
        val p = store.find("ab")!!
        // An explicit old false is migrated to Ask (the new default), never Never.
        assertEquals(Permission.ASK, p.browse)
        assertEquals(Permission.ASK, p.push)
    }

    /**
     * Before tri-state permissions text had no field and was allowed exactly when push was. A
     * legacy entry must therefore inherit the migrated push grant: push=true
     * stays Allow (a device that could send text does not suddenly start
     * prompting), push=false becomes Ask (never Never, the old false rule).
     * This is the "not always Ask" migration rule.
     */
    @Test
    fun legacyEntryWithoutTextFieldInheritsThePushGrant() {
        fun load(pushJson: String): PairedPeer {
            val f = Files.createTempFile("peers", ".json").toFile()
            f.writeText(
                """{"ab":{"fingerprint":"ab","name":"Old","host":"10.0.0.1","port":47800,"browse":true,"push":$pushJson,"pairedAt":123}}""",
            )
            return JsonFileTrustStore(f).find("ab")!!
        }

        // push=true -> Allow, so a peer that could already send text keeps it.
        val allowed = load("true")
        assertEquals(Permission.ALLOW, allowed.push)
        assertEquals(Permission.ALLOW, allowed.text, "legacy push=true must inherit Allow, not Ask")

        // push=false -> Ask (the historic false rule), never Never.
        val asked = load("false")
        assertEquals(Permission.ASK, asked.push)
        assertEquals(Permission.ASK, asked.text, "legacy push=false must inherit Ask, not Never")
    }

    /** An explicit text field always wins over the push fallback. */
    @Test
    fun explicitTextWinsOverThePushFallback() {
        val f = Files.createTempFile("peers", ".json").toFile()
        f.writeText(
            """{"ab":{"fingerprint":"ab","name":"Old","host":"10.0.0.1","port":47800,"browse":true,"push":true,"text":"never","pairedAt":123}}""",
        )
        val p = JsonFileTrustStore(f).find("ab")!!
        assertEquals(Permission.ALLOW, p.push)
        assertEquals(Permission.NEVER, p.text, "an explicit text grant must not be overwritten by push")
    }

    @Test
    fun newTriStateStringsRoundTrip() {
        val f = Files.createTempFile("peers", ".json").toFile().also { it.delete() }
        val store = JsonFileTrustStore(f)
        store.save(
            PairedPeer(
                "ef", "New", "h", 1,
                Permission.ALLOW, Permission.ASK, Permission.NEVER, 9,
                allowBrowse = true, allowPush = true, allowText = false,
            ),
        )
        val p = JsonFileTrustStore(f).find("ef")!!
        assertEquals(Permission.ALLOW, p.browse)
        assertEquals(Permission.ASK, p.push)
        assertEquals(Permission.NEVER, p.text)
        assertEquals(true, p.allowBrowse)
        assertEquals(true, p.allowPush)
        assertEquals(false, p.allowText)
        // Migration rule is unrelated to wire fallback: read the raw file to be
        // sure the store wrote the string form, not a boolean.
        val raw = f.readText()
        assert(raw.contains("\"allow\"")) { raw }
        assert(raw.contains("\"ask\"")) { raw }
        assert(raw.contains("\"never\"")) { raw }
    }

    @Test
    fun newFieldsRoundTrip() {
        val f = Files.createTempFile("peers", ".json").toFile().also { it.delete() }
        val store = JsonFileTrustStore(f)
        store.save(PairedPeer("cd", "New", "h", 1, true, true, 9, pushMaxBytes = 10, askOver = 5))
        val p = store.find("cd")!!
        assertEquals(10L, p.pushMaxBytes)
        assertEquals(5L, p.askOver)
    }
}
