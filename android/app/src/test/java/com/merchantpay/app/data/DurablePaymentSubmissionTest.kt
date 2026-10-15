package com.merchantpay.app.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DurablePaymentSubmissionTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var store: SqlitePaymentAttemptStore
    private val merchant = "merchant-1"
    private fun requestId() = UUID.randomUUID().toString()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dbName = "attempts-" + UUID.randomUUID().toString() + ".db"
        store = SqlitePaymentAttemptStore(context, dbName)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(dbName)
    }

    @Test fun persists_same_key_across_store_reopen_before_any_dispatch() {
        val request = requestId()
        val prepared = store.prepare(merchant, request, 50001)
        assertEquals(AttemptState.PREPARED, prepared.state)
        assertEquals(50001L, prepared.amountMinor)
        assertNotNull(UUID.fromString(prepared.idempotencyKey))
        store.close()
        store = SqlitePaymentAttemptStore(context, dbName)
        val recovered = store.prepare(merchant, request, 50001)
        assertEquals(prepared, recovered)
        assertEquals(1, store.unresolved(merchant).size)
    }

    @Test fun conflicting_amount_for_same_logical_request_fails_closed() {
        val request = requestId()
        val original = store.prepare(merchant, request, 100)
        try { store.prepare(merchant, request, 101); fail("Expected conflict") }
        catch (_: IllegalStateException) { }
        assertEquals(original, store.find(merchant, request))
    }

    @Test fun merchant_namespace_is_isolated() {
        val request = requestId()
        val a = store.prepare("merchant-a", request, 100)
        val b = store.prepare("merchant-b", request, 100)
        assertNotEquals(a.idempotencyKey, b.idempotencyKey)
        assertEquals(1, store.unresolved("merchant-a").size)
        assertEquals(1, store.unresolved("merchant-b").size)
        assertTrue(store.unresolved("merchant-c").isEmpty())
    }

    @Test fun acknowledgement_is_created_not_paid() {
        val request = requestId()
        store.prepare(merchant, request, 100)
        val intentId = UUID.randomUUID().toString()
        val response = PaymentIntent(intentId, merchant, 100, "KES", "created")
        val acknowledged = store.acknowledge(merchant, request, response)
        assertEquals(AttemptState.ACKNOWLEDGED, acknowledged.state)
        assertEquals(intentId, acknowledged.serverIntentId)
        assertTrue(store.unresolved(merchant).isEmpty())
        assertEquals(acknowledged, store.markUncertain(merchant, request))
        assertEquals(acknowledged, store.acknowledge(merchant, request, response))
    }

    @Test fun rejects_tampered_or_paid_server_responses() {
        val request = requestId()
        store.prepare(merchant, request, 100)
        for (response in listOf(
            PaymentIntent(UUID.randomUUID().toString(), "other-merchant", 100, "KES", "created"),
            PaymentIntent(UUID.randomUUID().toString(), merchant, 101, "KES", "created"),
            PaymentIntent(UUID.randomUUID().toString(), merchant, 100, "USD", "created"),
            PaymentIntent(UUID.randomUUID().toString(), merchant, 100, "KES", "paid")
        )) {
            try { store.acknowledge(merchant, request, response); fail("Expected mismatch") }
            catch (_: IllegalStateException) { }
            assertEquals(AttemptState.PREPARED, store.find(merchant, request)?.state)
        }
    }

    @Test fun acknowledged_intent_cannot_be_overwritten() {
        val request = requestId()
        store.prepare(merchant, request, 100)
        val first = PaymentIntent(UUID.randomUUID().toString(), merchant, 100, "KES", "created")
        store.acknowledge(merchant, request, first)
        try {
            store.acknowledge(
                merchant, request,
                PaymentIntent(UUID.randomUUID().toString(), merchant, 100, "KES", "created")
            )
            fail("Expected conflicting server intent")
        } catch (_: IllegalStateException) { }
        assertEquals(first.id, store.find(merchant, request)?.serverIntentId)
    }

    @Test fun request_is_durable_before_network_and_acked_after_response() = runBlocking {
        val request = requestId()
        var calls = 0
        var observedKey: String? = null
        val api = object : PaymentRepository {
            override suspend fun createIntent(
                merchantId: String, amountMinor: Long, idempotencyKey: String
            ): PaymentIntent {
                calls++
                val persisted = store.find(merchantId, request)
                assertEquals(AttemptState.PREPARED, persisted?.state)
                assertEquals(idempotencyKey, persisted?.idempotencyKey)
                observedKey = idempotencyKey
                return PaymentIntent(UUID.randomUUID().toString(), merchantId, amountMinor, "KES", "created")
            }
        }
        val coordinator = DurablePaymentSubmission(store, api)
        val first = coordinator.submit(merchant, request, 100)
        val second = coordinator.submit(merchant, request, 100)
        assertEquals(first, second)
        assertEquals(1, calls)
        assertEquals(observedKey, store.find(merchant, request)?.idempotencyKey)
        assertEquals(AttemptState.ACKNOWLEDGED, store.find(merchant, request)?.state)
    }

    @Test fun unknown_network_outcome_reuses_same_key_after_reopen() = runBlocking {
        val request = requestId()
        val observedKeys = mutableListOf<String>()
        var attempts = 0
        val api = object : PaymentRepository {
            override suspend fun createIntent(
                merchantId: String, amountMinor: Long, idempotencyKey: String
            ): PaymentIntent {
                observedKeys.add(idempotencyKey)
                attempts++
                if (attempts == 1) throw PaymentApiError.OutcomeUnknown()
                return PaymentIntent(UUID.randomUUID().toString(), merchantId, amountMinor, "KES", "created")
            }
        }
        val coordinator = DurablePaymentSubmission(store, api)
        try { coordinator.submit(merchant, request, 100); fail("Expected uncertain outcome") }
        catch (_: PaymentApiError.OutcomeUnknown) { }
        assertEquals(AttemptState.UNCERTAIN, store.find(merchant, request)?.state)
        store.close()
        store = SqlitePaymentAttemptStore(context, dbName)
        val recovered = DurablePaymentSubmission(store, api)
        assertEquals(1, recovered.unresolved(merchant).size)
        val result = recovered.retryExisting(merchant, request)
        assertEquals("created", result.status)
        assertEquals(2, attempts)
        assertEquals(observedKeys[0], observedKeys[1])
        assertEquals(AttemptState.ACKNOWLEDGED, store.find(merchant, request)?.state)
    }

    @Test fun cancellation_marks_request_uncertain() = runBlocking {
        val request = requestId()
        val api = object : PaymentRepository {
            override suspend fun createIntent(merchantId: String, amountMinor: Long, idempotencyKey: String): PaymentIntent {
                throw CancellationException("simulated cancellation")
            }
        }
        val coordinator = DurablePaymentSubmission(store, api)
        try { coordinator.submit(merchant, request, 100); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(AttemptState.UNCERTAIN, store.find(merchant, request)?.state)
    }

    @Test fun cannot_retry_nonexistent_request() = runBlocking {
        val api = object : PaymentRepository {
            override suspend fun createIntent(merchantId: String, amountMinor: Long, idempotencyKey: String): PaymentIntent {
                fail("Network should not be called")
                error("unreachable")
            }
        }
        val coordinator = DurablePaymentSubmission(store, api)
        try { coordinator.retryExisting(merchant, requestId()); fail("Expected unknown request") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun concurrent_submissions_from_one_coordinator_dispatch_once() = runBlocking {
        val request = requestId()
        var calls = 0
        val api = object : PaymentRepository {
            override suspend fun createIntent(
                merchantId: String, amountMinor: Long, idempotencyKey: String
            ): PaymentIntent {
                calls++
                kotlinx.coroutines.delay(5)
                return PaymentIntent(UUID.randomUUID().toString(), merchantId, amountMinor, "KES", "created")
            }
        }
        val coordinator = DurablePaymentSubmission(store, api)
        val results = coroutineScope {
            (1..20).map {
                async(Dispatchers.Default) { coordinator.submit(merchant, request, 100) }
            }.awaitAll()
        }
        assertEquals(1, calls)
        assertEquals(1, results.map { it.id }.distinct().size)
        assertTrue(store.unresolved(merchant).isEmpty())
    }

    @Test fun rejects_invalid_request_id_and_nonpositive_amount() {
        try { store.prepare(merchant, "../escape", 100); fail("Expected bad request ID") }
        catch (_: IllegalArgumentException) { }
        try { store.prepare(merchant, requestId(), 0); fail("Expected bad amount") }
        catch (_: IllegalArgumentException) { }
        assertTrue(store.unresolved(merchant).isEmpty())
    }
}
