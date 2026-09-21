package io.resupply.karoo.util

import java.security.MessageDigest

/**
 * Hex-encoded SHA-256 of [input]. Used to key a roadbook's favorites by its route geometry:
 * the encoded route polyline is deterministic per route, so its digest is a stable id for
 * "the same route" without persisting the multi-KB polyline itself. Collision risk at the
 * handful-of-routes scale is nil.
 */
fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        for (b in digest) {
            val v = b.toInt() and 0xff
            append(HEX[v ushr 4])
            append(HEX[v and 0x0f])
        }
    }
}

private const val HEX = "0123456789abcdef"
