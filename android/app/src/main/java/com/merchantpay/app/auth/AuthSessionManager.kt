package com.merchantpay.app.auth

import com.merchantpay.app.data.AccessTokenProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant

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
 * A generation fence and one-shot pending attempt ensure late/replayed OAuth
 * exchanges cannot sign the user back in after logout or a newer attempt.
 */
class AuthSessionManager(
    private val clock: Clock = Clock.systemUTC(),
    private val minimumValidity: Duration = Duration.ofSeconds(60)
) : AccessTokenProvider {
    private val lock = Any()
    private var generation = 0L
    private var pendingAttempt: Long? = null
    private var active: AccessTokenGrant? = null

    init {
        require(!minimumValidity.isNegative)
    }

    fun beginSignIn(): Long = synchronized(lock) {
        generation += 1
        pendingAttempt = generation
        active = null
        generation
    }

    /** Called only by a future trusted OAuth exchange adapter. */
    fun completeSignIn(attempt: Long, grant: AccessTokenGrant): Boolean = synchronized(lock) {
        if (attempt <= 0 || attempt != pendingAttempt) return@synchronized false
        pendingAttempt = null // Consume even invalid results; no replay.
        if (!isUsable(grant)) {
            active = null
            return@synchronized false
        }
        active = grant
        true
    }

    fun failSignIn(attempt: Long) = synchronized(lock) {
        if (attempt == pendingAttempt) {
            pendingAttempt = null
            active = null
        }
    }

    /** Local logout only: revocation at the identity provider is NOT implemented. */
    fun clearLocalSession() = synchronized(lock) {
        generation += 1
        pendingAttempt = null
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
        // Opaque bearer values may vary, but must be safe to place in an HTTP header.
        if (token.isEmpty() || token.length > 8192 || token.any { it.code !in 33..126 }) {
            return false
        }
        return try {
            val cutoff = clock.instant().plus(minimumValidity)
            Instant.ofEpochSecond(grant.expiresAtEpochSeconds).isAfter(cutoff)
        } catch (_: RuntimeException) {
            false
        }
    }
}
