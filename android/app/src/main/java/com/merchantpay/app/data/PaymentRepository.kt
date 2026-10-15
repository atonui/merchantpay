package com.merchantpay.app.data

import java.util.UUID

data class PaymentIntent(
    val id: String,
    val merchantId: String,
    val amountMinor: Long,
    val currency: String,
    val status: String
)

interface PaymentRepository {
    /** The caller owns the idempotency key; retries must reuse it unchanged. */
    suspend fun createIntent(merchantId: String, amountMinor: Long, idempotencyKey: String): PaymentIntent
}

/** Offline, simulated-only repository. Never initiates network traffic or charges. */
class DemoPaymentRepository : PaymentRepository {
    override suspend fun createIntent(
        merchantId: String,
        amountMinor: Long,
        idempotencyKey: String
    ): PaymentIntent {
        require(amountMinor > 0)
        require(idempotencyKey.isNotBlank())
        return PaymentIntent(
            id = UUID.randomUUID().toString(),
            merchantId = merchantId,
            amountMinor = amountMinor,
            currency = "KES",
            status = "created"
        )
    }
}
