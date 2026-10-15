package com.merchantpay.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID

class PaymentApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PaymentApiClient
    private val merchant = "demo-merchant"
    private val id = UUID.randomUUID().toString()
    private val key = UUID.randomUUID().toString()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = PaymentApiClient(
            server.url("/"),
            AccessTokenProvider { "test-access-token" },
            allowInsecureLoopbackForTests = true
        )
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun response(amount: Long = 50001, status: String = "created", merchantId: String = merchant) =
        """{"id":"$id","merchant_id":"$merchantId","amount_minor":$amount,"currency":"KES","status":"$status"}"""

    @Test fun create_sends_scoped_request_and_stable_key() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(response()))
        val result = api.createIntent(merchant, 50001, key)
        assertEquals(id, result.id)
        assertEquals("created", result.status)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/merchants/$merchant/payment-intents", request.path)
        assertEquals("Bearer test-access-token", request.getHeader("Authorization"))
        assertEquals(key, request.getHeader("Idempotency-Key"))
        assertEquals("""{"amount_minor":50001,"currency":"KES"}""", request.body.readUtf8())
    }

    @Test fun get_intent_is_merchant_scoped() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        assertEquals(id, api.getIntent(merchant, id).id)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/merchants/$merchant/payment-intents/$id", request.path)
    }

    @Test fun maps_auth_and_conflict_errors_without_exposing_body() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret token details"))
        try { api.createIntent(merchant, 50001, key); fail("Expected 401") }
        catch (e: PaymentApiError.AuthenticationRequired) {
            assertFalse(e.message.orEmpty().contains("secret"))
        }
        server.enqueue(MockResponse().setResponseCode(403))
        try { api.createIntent(merchant, 50001, key); fail("Expected 403") }
        catch (_: PaymentApiError.MerchantAccessDenied) { }
        server.enqueue(MockResponse().setResponseCode(409))
        try { api.createIntent(merchant, 50001, key); fail("Expected 409") }
        catch (_: PaymentApiError.IdempotencyConflict) { }
        assertEquals(3, server.requestCount)
    }

    @Test fun network_failure_is_unknown_not_declined_and_not_retried() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try { api.createIntent(merchant, 50001, key); fail("Expected uncertainty") }
        catch (_: PaymentApiError.OutcomeUnknown) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun malformed_or_unexpected_success_is_not_payment_confirmation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(response(status = "paid")))
        try { api.createIntent(merchant, 50001, key); fail("Expected unknown") }
        catch (_: PaymentApiError.OutcomeUnknown) { }
        server.enqueue(MockResponse().setResponseCode(201).setBody(response(amount = 99999)))
        try { api.createIntent(merchant, 50001, key); fail("Expected mismatch") }
        catch (_: PaymentApiError.OutcomeUnknown) { }
    }

    @Test fun redirects_are_not_followed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://elsewhere.example/"))
        try { api.createIntent(merchant, 50001, key); fail("Expected unknown") }
        catch (_: PaymentApiError.OutcomeUnknown) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun token_required_before_request() = runBlocking {
        val withoutToken = PaymentApiClient(
            server.url("/"), AccessTokenProvider { null }, allowInsecureLoopbackForTests = true
        )
        try { withoutToken.createIntent(merchant, 50001, key); fail("Expected auth") }
        catch (_: PaymentApiError.AuthenticationRequired) { }
        assertEquals(0, server.requestCount)
    }

    @Test fun invalid_identifiers_are_rejected_before_request() = runBlocking {
        try { api.createIntent("../other", 50001, key); fail("Expected invalid merchant") }
        catch (_: IllegalArgumentException) { }
        try { api.createIntent(merchant, 50001, "short"); fail("Expected invalid key") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }

    @Test fun requires_https_outside_explicit_loopback_tests() {
        try {
            PaymentApiClient("http://api.example.com/".toHttpUrl(), AccessTokenProvider { "token" })
            fail("Expected HTTPS guard")
        } catch (_: IllegalArgumentException) { }
        try {
            PaymentApiClient(server.url("/"), AccessTokenProvider { "token" })
            fail("Expected HTTPS guard even for loopback by default")
        } catch (_: IllegalArgumentException) { }
        assertTrue("https://api.example.com/".toHttpUrl().isHttps)
    }
}
