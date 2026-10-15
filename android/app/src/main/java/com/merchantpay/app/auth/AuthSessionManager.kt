package com.merchantpay.app.auth

import com.merchantpay.app.data.AccessTokenProvider
import java.time.Clock
import java.time.Duration

/**
 * Access token returned by a future vetted OIDC authorization-code/PKCE adapter.
 * Never construct this from untrusted deep-link parameters or ID-token claims.
 *
 * A normal data class would include the bearer credential in generated toString().
 */
class AccessTokenGrant(
    val accessToken: String,
    val expiresAtEpochSeconds: Long
) {
    override fun toString(): String = "AccessTokenGrant(<redacted>)"
}

enum class LocalSessionStatus { SIGNED_OUT, ACTIVE }

/**
 * Volatile, in-memory token lifecycle. No token persistence, refresh or real login.
 * A session is discarded when the process dies. The API verifies tokens independently.
 *
 * A generation fence ensures a late OAuth exchange cannot sign the user back in
 * after a local logout or newer login attempt.
 */
class AuthSessionManager(
    private val clock: Clock = Clock.systemUTC(),
    private val minimumValidity: Duration = Duration.ofSeconds(60)
) : AccessTokenProvider {
    private val lock = Any()
    private var generation = 0L
    private var active: AccessTokenGrant? = null

    init {
        require(!minimumValidity.isNegative)
    }

    fun beginSignIn(): Long = synchronized(lock) {
        generation += 1
        active = null
        generation
    }

    /** Called only by a future trusted OAuth exchange adapter. */
    fun completeSignIn(attempt: Long, grant: AccessTokenGrant): Boolean = synchronized(lock) {
        if (attempt <= 0 || attempt != generation) return@synchronized false
        if (!isUsable(grant)) {
            active = null
            return@synchronized false
        }
        active = grant
        true
    }

    fun failSignIn(attempt: Long) = synchronized(lock) {
        if (attempt == generation) active = null
    }

    /** Local logout only: revocation at the identity provider is NOT implemented. */
    fun clearLocalSession() = synchronized(lock) {
        generation += 1
        active = null
    }

    override fun accessToken(): String? = synchronized(lock) {
        val grant = active ?: return@synchronized null
        if (!isUsable(grant)) {
            active = null
            return@synchronized null
        }
        grant.accessToken
    }

    fun status(): LocalSessionStatus =
        if (accessToken() == null) LocalSessionStatus.SIGNED_OUT else LocalSessionStatus.ACTIVE

    private fun isUsable(grant: AccessTokenGrant): Boolean {
        val token = grant.accessToken
        if (token.isBlank() || token.length > 8192 || token.any { it.isWhitespace() || it.isISOControl() }) {
            return false
        }
        // Use overflow-safe Instant comparison, not Long addition.
        return try {
            val cutoff = clock.instant().plus(minimumValidity)
            java.time.Instant.ofEpochSecond(grant.expiresAtEpochSeconds).isAfter(cutoff)
        } catch (_: RuntimeException) {
            false
        }
    }
}
