package com.merchantpay.app.data

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A real identity-provider adapter must supply short-lived access tokens. Never embed secrets. */
fun interface AccessTokenProvider {
    fun accessToken(): String?
}

sealed class PaymentApiError(message: String) : RuntimeException(message) {
    class AuthenticationRequired : PaymentApiError("Authentication required")
    class MerchantAccessDenied : PaymentApiError("Merchant access denied")
    class IdempotencyConflict : PaymentApiError("Idempotency key conflict")
    class NotFound : PaymentApiError("Intent not found")
    class Rejected : PaymentApiError("Invalid request")
    /** Do not blindly retry an uncertain create with a NEW idempotency key. */
    class OutcomeUnknown : PaymentApiError("Outcome unknown; reconcile before retrying")
}

@Serializable
private data class IntentDto(
    val id: String,
    @SerialName("merchant_id") val merchantId: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    val status: String
) {
    fun toDomain() = PaymentIntent(id, merchantId, amountMinor, currency, status)
}

/**
 * Backend API client, NOT connected to the demo UI.
 * No automatic retries, redirects, token storage, logging or card capture.
 * App manifest deliberately excludes INTERNET permission until OIDC is designed.
 */
class PaymentApiClient(
    private val baseUrl: HttpUrl,
    private val tokenProvider: AccessTokenProvider,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    private val allowInsecureLoopbackForTests: Boolean = false
) : PaymentRepository {
    private val json = Json { ignoreUnknownKeys = false }
    private val merchantPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
    private val keyPattern = Regex("[A-Za-z0-9._~-]{16,128}")

    init {
        require(baseUrl.encodedPath == "/") { "API base URL must end at origin root" }
        require(
            baseUrl.isHttps ||
                (allowInsecureLoopbackForTests &&
                    baseUrl.host in setOf("localhost", "127.0.0.1", "::1"))
        ) { "HTTPS required for API traffic" }
    }

    override suspend fun createIntent(
        merchantId: String,
        amountMinor: Long,
        idempotencyKey: String
    ): PaymentIntent = withContext(Dispatchers.IO) {
        require(merchantPattern.matches(merchantId)) { "Invalid merchant identifier" }
        require(keyPattern.matches(idempotencyKey)) { "Invalid idempotency key" }
        require(amountMinor > 0) { "Amount must be positive" }
        val payload = """{"amount_minor":$amountMinor,"currency":"KES"}"""
        val request = Request.Builder()
            .url(intentUrl(merchantId))
            .header("Authorization", bearer())
            .header("Idempotency-Key", idempotencyKey)
            .header("Accept", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val result = execute(request, 201)
        if (result.merchantId != merchantId || result.amountMinor != amountMinor ||
            result.currency != "KES" || result.status != "created"
        ) {
            throw PaymentApiError.OutcomeUnknown()
        }
        result
    }

    suspend fun getIntent(merchantId: String, intentId: String): PaymentIntent =
        withContext(Dispatchers.IO) {
            require(merchantPattern.matches(merchantId)) { "Invalid merchant identifier" }
            UUID.fromString(intentId)
            val request = Request.Builder()
                .url(intentUrl(merchantId, intentId))
                .header("Authorization", bearer())
                .header("Accept", "application/json")
                .get()
                .build()
            val result = execute(request, 200)
            if (result.merchantId != merchantId || result.id != intentId ||
                result.currency != "KES" || result.status != "created"
            ) {
                throw PaymentApiError.OutcomeUnknown()
            }
            result
        }

    private fun intentUrl(merchantId: String, intentId: String? = null): HttpUrl {
        val builder = baseUrl.newBuilder()
            .addPathSegment("v1")
            .addPathSegment("merchants")
            .addPathSegment(merchantId)
            .addPathSegment("payment-intents")
        if (intentId != null) builder.addPathSegment(intentId)
        return builder.build()
    }

    private fun bearer(): String {
        val token = tokenProvider.accessToken()
        if (token.isNullOrBlank() || token.length > 8192 || token.any { it.isWhitespace() }) {
            throw PaymentApiError.AuthenticationRequired()
        }
        return "Bearer $token"
    }

    private fun execute(request: Request, expectedStatus: Int): PaymentIntent {
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    expectedStatus -> {
                        val body = response.body?.string() ?: throw PaymentApiError.OutcomeUnknown()
                        return try {
                            val dto = json.decodeFromString<IntentDto>(body)
                            UUID.fromString(dto.id)
                            dto.toDomain()
                        } catch (_: Exception) {
                            throw PaymentApiError.OutcomeUnknown()
                        }
                    }
                    401 -> throw PaymentApiError.AuthenticationRequired()
                    403 -> throw PaymentApiError.MerchantAccessDenied()
                    404 -> throw PaymentApiError.NotFound()
                    409 -> throw PaymentApiError.IdempotencyConflict()
                    400, 422 -> throw PaymentApiError.Rejected()
                    else -> throw PaymentApiError.OutcomeUnknown()
                }
            }
        } catch (_: IOException) {
            throw PaymentApiError.OutcomeUnknown()
        }
    }
}
