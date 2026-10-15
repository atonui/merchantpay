package com.merchantpay.app.auth

import com.merchantpay.app.data.PaymentApiClient
import com.merchantpay.app.data.PaymentApiError
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Proves that the existing API client respects the session boundary, without real network. */
class PaymentApiSessionIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: AuthSessionManager
    private lateinit var api: PaymentApiClient
    private val now = Instant.parse("2026-10-15T06:00:00Z")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        sessions = AuthSessionManager(Clock.fixed(now, ZoneOffset.UTC))
        api = PaymentApiClient(
            server.url("/"), sessions, allowInsecureLoopbackForTests = true
        )
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun token_is_never_sent_when_signed_out() = runBlocking {
        try {
            api.createIntent("merchant-a", 100, UUID.randomUUID().toString())
            fail("Expected local authentication failure")
        } catch (_: PaymentApiError.AuthenticationRequired) { }
        assertEquals(0, server.requestCount)
    }

    @Test fun successful_local_session_passes_token_and_logout_blocks_future_requests() = runBlocking {
        val attempt = sessions.beginSignIn()
        sessions.completeSignIn(attempt, AccessTokenGrant("session-test-token", now.epochSecond + 3600))
        val intentId = UUID.randomUUID().toString()
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"id":"$intentId","merchant_id":"merchant-a","amount_minor":100,"currency":"KES","status":"created"}"""
        ))
        assertEquals(intentId, api.createIntent("merchant-a", 100, UUID.randomUUID().toString()).id)
        assertEquals("Bearer session-test-token", server.takeRequest().getHeader("Authorization"))

        sessions.clearLocalSession()
        try {
            api.createIntent("merchant-a", 100, UUID.randomUUID().toString())
            fail("Expected local authentication failure after logout")
        } catch (_: PaymentApiError.AuthenticationRequired) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun expired_session_cannot_initiate_network_request() = runBlocking {
        val attempt = sessions.beginSignIn()
        sessions.completeSignIn(attempt, AccessTokenGrant("short-lived", now.epochSecond + 30))
        try {
            api.createIntent("merchant-a", 100, UUID.randomUUID().toString())
            fail("Expected local authentication failure for near-expiry token")
        } catch (_: PaymentApiError.AuthenticationRequired) { }
        assertEquals(0, server.requestCount)
    }
}
