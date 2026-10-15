package com.merchantpay.app.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionManagerTest {
    private val now = Instant.parse("2026-10-15T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private fun sessions() = AuthSessionManager(clock)
    private fun grant(secondsFromNow: Long = 3600) =
        AccessTokenGrant("secret-token-do-not-log", now.epochSecond + secondsFromNow)

    @Test fun no_token_before_sign_in() {
        val sessions = sessions()
        assertNull(sessions.accessToken())
        assertEquals(LocalSessionStatus.SIGNED_OUT, sessions.status())
    }

    @Test fun successful_exchange_installs_ephemeral_token() {
        val sessions = sessions()
        val attempt = sessions.beginSignIn()
        assertTrue(sessions.completeSignIn(attempt, grant()))
        assertEquals("secret-token-do-not-log", sessions.accessToken())
        assertEquals(LocalSessionStatus.ACTIVE, sessions.status())
    }

    @Test fun expires_early_to_avoid_sending_near_expiry_token() {
        val sessions = sessions()
        val attempt = sessions.beginSignIn()
        assertFalse(sessions.completeSignIn(attempt, grant(60)))
        assertNull(sessions.accessToken())
        val next = sessions.beginSignIn()
        assertTrue(sessions.completeSignIn(next, grant(61)))
        assertEquals(LocalSessionStatus.ACTIVE, sessions.status())
    }

    @Test fun expired_grant_never_installed() {
        val sessions = sessions()
        val attempt = sessions.beginSignIn()
        assertFalse(sessions.completeSignIn(attempt, grant(-5)))
        assertNull(sessions.accessToken())
    }

    private class AdjustableClock(private var current: Instant) : Clock() {
        override fun instant(): Instant = current
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        fun advanceSeconds(seconds: Long) { current = current.plusSeconds(seconds) }
    }

    @Test fun expiration_clears_token_at_read_time() {
        val adjustableClock = AdjustableClock(now)
        val sessions = AuthSessionManager(adjustableClock, Duration.ZERO)
        val attempt = sessions.beginSignIn()
        assertTrue(sessions.completeSignIn(attempt, grant(1)))
        assertEquals(LocalSessionStatus.ACTIVE, sessions.status())
        adjustableClock.advanceSeconds(2)
        assertNull(sessions.accessToken())
        assertEquals(LocalSessionStatus.SIGNED_OUT, sessions.status())
    }

    @Test fun local_logout_invalidates_token_and_inflight_exchange() {
        val sessions = sessions()
        val pending = sessions.beginSignIn()
        sessions.clearLocalSession()
        assertFalse(sessions.completeSignIn(pending, grant()))
        assertNull(sessions.accessToken())
    }

    @Test fun completed_exchange_is_one_shot() {
        val sessions = sessions()
        val attempt = sessions.beginSignIn()
        assertTrue(sessions.completeSignIn(attempt, grant()))
        assertFalse(sessions.completeSignIn(attempt, AccessTokenGrant("attacker", now.epochSecond + 3600)))
        sessions.failSignIn(attempt)
        assertEquals("secret-token-do-not-log", sessions.accessToken())
    }

    @Test fun failed_exchange_cannot_be_replayed() {
        val sessions = sessions()
        val attempt = sessions.beginSignIn()
        sessions.failSignIn(attempt)
        assertFalse(sessions.completeSignIn(attempt, grant()))
        assertNull(sessions.accessToken())
    }

    @Test fun older_exchange_cannot_override_newer_sign_in() {
        val sessions = sessions()
        val first = sessions.beginSignIn()
        val second = sessions.beginSignIn()
        assertFalse(sessions.completeSignIn(first, grant()))
        assertTrue(sessions.completeSignIn(second, grant()))
    }

    @Test fun invalid_credentials_are_rejected_without_logging() {
        val sessions = sessions()
        for (value in listOf("", "  ", "token with space", "token\nheader", "é", "x".repeat(8193))) {
            val attempt = sessions.beginSignIn()
            assertFalse(sessions.completeSignIn(attempt, AccessTokenGrant(value, now.epochSecond + 3600)))
            assertNull(sessions.accessToken())
        }
        assertFalse(grant().toString().contains("secret-token-do-not-log"))
    }

    @Test fun invalid_attempt_id_cannot_install_token() {
        val sessions = sessions()
        assertFalse(sessions.completeSignIn(0, grant()))
        assertFalse(sessions.completeSignIn(123, grant()))
    }

    @Test fun coordinator_failure_leaves_no_session() = runBlocking {
        val sessions = sessions()
        val coordinator = AuthCoordinator(sessions, ExternalAuthorizationFlow {
            throw IllegalStateException("secret error")
        })
        assertEquals(SignInResult.FAILED, coordinator.signIn())
        assertNull(sessions.accessToken())
    }

    @Test fun logout_while_exchange_inflight_does_not_restore_session() = runBlocking {
        val sessions = sessions()
        val exchange = CompletableDeferred<AccessTokenGrant>()
        val coordinator = AuthCoordinator(sessions, ExternalAuthorizationFlow { exchange.await() })
        val signingIn = async { coordinator.signIn() }
        // The coroutine runs on the current thread until first suspension.
        kotlinx.coroutines.yield()
        coordinator.signOutLocally()
        exchange.complete(grant())
        assertEquals(SignInResult.NOT_ESTABLISHED, signingIn.await())
        assertNull(sessions.accessToken())
    }

    @Test fun successful_coordinator_sign_in() = runBlocking {
        val sessions = sessions()
        val coordinator = AuthCoordinator(sessions, ExternalAuthorizationFlow { grant() })
        assertEquals(SignInResult.ESTABLISHED, coordinator.signIn())
        coordinator.signOutLocally()
        assertNull(sessions.accessToken())
    }
}
