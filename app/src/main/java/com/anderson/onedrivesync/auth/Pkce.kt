package com.anderson.onedrivesync.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private const val VERIFIER_LENGTH = 64
private const val STATE_BYTES = 32
private const val VERIFIER_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

private val secureRandom = SecureRandom()

/** RFC 7636 verifier: 43-128 characters from the unreserved set, drawn from a CSPRNG. */
fun newVerifier(): String =
    buildString(VERIFIER_LENGTH) {
        repeat(VERIFIER_LENGTH) { append(VERIFIER_ALPHABET[secureRandom.nextInt(VERIFIER_ALPHABET.length)]) }
    }

/** RFC 7636 S256: BASE64URL(SHA-256(verifier)), no padding. */
fun challenge(verifier: String): String =
    base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

/** Opaque value echoed back by the provider; it binds the redirect to the request we started. */
fun newState(): String = base64Url(ByteArray(STATE_BYTES).also { secureRandom.nextBytes(it) })

private fun base64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** PKCE material saved before the browser opens, so a process death does not lose it. */
data class PendingPkce(
    val verifier: String,
    val state: String,
    val createdAtMs: Long,
)

interface PkceStorage {
    fun savePkce(pkce: PendingPkce)
    fun loadPkce(): PendingPkce?
    fun clearPkce()
}
