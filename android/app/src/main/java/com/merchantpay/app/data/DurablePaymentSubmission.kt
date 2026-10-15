package com.merchantpay.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caller must supply a stable logical request UUID (created before submit).
 * This coordinator is NOT wired to the offline demo UI.
 *
 * A PREPARED record after process death may have reached the server.
 * Recovery is explicitly initiated by the user; there are no automatic retries.
 */
class DurablePaymentSubmission(
    private val store: PaymentAttemptStore,
    private val api: PaymentRepository
) {
    private val mutex = Mutex()

    suspend fun submit(
        merchantId: String, requestId: String, amountMinor: Long
    ): PaymentIntent = withContext(Dispatchers.IO) {
        mutex.withLock {
            val attempt = store.prepare(merchantId, requestId, amountMinor)
            dispatch(attempt)
        }
    }

    suspend fun retryExisting(
        merchantId: String, requestId: String
    ): PaymentIntent = withContext(Dispatchers.IO) {
        mutex.withLock {
            val attempt = requireNotNull(store.find(merchantId, requestId)) {
                "Cannot retry an unknown request with a new key"
            }
            dispatch(attempt)
        }
    }

    /** Read-only recovery list. Does not perform network requests. */
    fun unresolved(merchantId: String): List<StoredPaymentAttempt> =
        store.unresolved(merchantId)

    private suspend fun dispatch(attempt: StoredPaymentAttempt): PaymentIntent {
        if (attempt.state == AttemptState.ACKNOWLEDGED) {
            return PaymentIntent(
                id = checkNotNull(attempt.serverIntentId),
                merchantId = attempt.merchantId,
                amountMinor = attempt.amountMinor,
                currency = "KES",
                status = "created"
            )
        }
        return try {
            val response = api.createIntent(
                attempt.merchantId, attempt.amountMinor, attempt.idempotencyKey
            )
            store.acknowledge(attempt.merchantId, attempt.requestId, response)
            response
        } catch (cancelled: CancellationException) {
            store.markUncertain(attempt.merchantId, attempt.requestId)
            throw cancelled
        } catch (error: Exception) {
            // A crash/timeout/ambiguous response may mean the server accepted it.
            // NEVER generate a fresh key as a response to an unknown outcome.
            store.markUncertain(attempt.merchantId, attempt.requestId)
            throw error
        }
    }
}
