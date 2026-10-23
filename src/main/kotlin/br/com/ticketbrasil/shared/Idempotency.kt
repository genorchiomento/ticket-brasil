package br.com.ticketbrasil.shared

import java.security.MessageDigest
import java.util.HexFormat

object Idempotency {
    const val HEADER = "Idempotency-Key"
    const val REPLAYED_HEADER = "Idempotent-Replayed"
    private const val MAX_KEY_LENGTH = 100

    fun validate(key: String?, required: Boolean): String? {
        if (key == null) {
            if (required) throw InvalidRequestException("Header $HEADER is required")
            return null
        }
        if (key.isBlank() || key.length > MAX_KEY_LENGTH) {
            throw InvalidRequestException("Header $HEADER must be 1..$MAX_KEY_LENGTH non-blank characters")
        }
        return key
    }

    /**
     * Hash of the semantically relevant request fields. Stored next to the key so a
     * retry with the same key but a different payload is rejected instead of silently
     * returning a result for a request the client did not make.
     */
    fun fingerprint(vararg parts: Any): String {
        val canonical = parts.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return HexFormat.of().formatHex(digest)
    }
}

/** Result of an idempotent create: the resource plus whether it came from a previous call. */
data class IdempotentResult<T>(val value: T, val replayed: Boolean)

/** A persisted resource together with the fingerprint of the request that created it. */
data class Fingerprinted<T>(val value: T, val fingerprint: String?)
