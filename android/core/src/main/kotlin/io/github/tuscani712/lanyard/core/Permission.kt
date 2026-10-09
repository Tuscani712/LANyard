package io.github.tuscani712.lanyard.core

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * A tri-state permission for one action (G1). It is the *local* policy for the
 * incoming direction — what this phone allows the peer to do to it — and is the
 * single value the trust store and the device page's "They can" editor hold.
 *
 * The three states are deliberately not a boolean:
 *  - [ALLOW] — always allow, no prompt.
 *  - [ASK]   — allow, but a person must approve each time (the new default; an
 *              existing `false` migrates here rather than to [NEVER], so an old
 *              entry never silently loses access).
 *  - [NEVER] — refuse outright.
 *
 * ## Wire encoding
 *
 * On the wire a permission is either an old-style JSON boolean or a new string:
 *
 *   - new peers: `"allow" | "ask" | "never"` (lowercase, in `*_mode` fields);
 *   - old peers: a bare boolean `browse`/`push`. Reading maps `true -> ALLOW`
 *     and `false -> NEVER` (an old peer can neither ask nor be asked), so an
 *     old peer's plain denial is honoured, not upgraded to a prompt.
 *
 * The store's own JSON uses the same string form under the historic
 * `browse`/`push` keys, with the migration rule `false -> ASK` for anything
 * written before this field existed. Negotiation is gated on the `perm3`
 * capability advertised in `/hello`: a peer that did not advertise it only ever
 * sees booleans, so a new device never sends an old device a mode it cannot
 * parse.
 */
enum class Permission(val wire: String) {
    ALLOW("allow"),
    ASK("ask"),
    NEVER("never");

    /** Whether a prompt is required before the action may proceed. */
    val asks: Boolean get() = this == ASK

    /** Whether the action is permitted at all (an [ASK] still permits it). */
    val permits: Boolean get() = this != NEVER

    companion object {
        /** The `/hello` capability that says a peer understands tri-state modes. */
        const val CAPABILITY = "perm3"

        /** Parses a store/wire string ("allow"/"ask"/"never"); null if unknown. */
        fun fromString(raw: String?): Permission? {
            val s = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == s || it.name.equals(s, ignoreCase = true) }
        }

        /**
         * Migration from the legacy stored boolean: `true -> ALLOW`, anything
         * else (including an old explicit `false`) -> [ASK]. This is the store
         * rule, deliberately different from the wire fallback below.
         */
        fun fromStoredBool(granted: Boolean?): Permission =
            if (granted == true) ALLOW else ASK

        /**
         * The fallback for an old peer that only sends a boolean on the wire:
         * `true -> ALLOW`, `false -> NEVER`. An old peer cannot ask, so a plain
         * denial stays a denial.
         */
        fun fromWireBool(allowed: Boolean?): Permission =
            if (allowed == true) ALLOW else NEVER

        /**
         * The wire bool that stands in for [p] when talking to an old peer:
         * [NEVER] is `false`, [ALLOW] is `true`, and an [ASK] (which cannot be
         * represented) degrades to `false` — "allow else deny", as agreed, so an
         * old peer is never left expecting a prompt that will not come.
         */
        fun wireBool(p: Permission): Boolean = p == ALLOW
    }
}

/**
 * Store adapter: writes the lowercase wire string; reads either that string or
 * the legacy boolean (migration `false -> ASK`). Anything unreadable becomes
 * [Permission.ASK] — the safe "ask rather than silently allow or deny".
 */
class PermissionStoreAdapter : JsonSerializer<Permission>, JsonDeserializer<Permission> {
    override fun serialize(src: Permission, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
        JsonPrimitive(src.wire)

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Permission {
        if (json.isJsonPrimitive) {
            val p = json.asJsonPrimitive
            if (p.isBoolean) return Permission.fromStoredBool(p.asBoolean)
            Permission.fromString(p.asString)?.let { return it }
        }
        return Permission.ASK
    }
}
