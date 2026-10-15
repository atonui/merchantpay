package com.merchantpay.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.util.UUID

/**
 * The persisted record is an intent-creation attempt, NOT a completed payment.
 * It intentionally contains no access token, PAN, PIN, CVV or customer details.
 */
data class StoredPaymentAttempt(
    val merchantId: String,
    val requestId: String,
    val idempotencyKey: String,
    val amountMinor: Long,
    val state: AttemptState,
    val serverIntentId: String?
)

enum class AttemptState { PREPARED, UNCERTAIN, ACKNOWLEDGED }

interface PaymentAttemptStore {
    fun prepare(merchantId: String, requestId: String, amountMinor: Long): StoredPaymentAttempt
    fun find(merchantId: String, requestId: String): StoredPaymentAttempt?
    fun markUncertain(merchantId: String, requestId: String): StoredPaymentAttempt
    fun acknowledge(merchantId: String, requestId: String, response: PaymentIntent): StoredPaymentAttempt
    fun unresolved(merchantId: String): List<StoredPaymentAttempt>
}

/**
 * Sandbox-only SQLite journal. Transactional insert precedes any network call.
 *
 * Android application sandbox and disabled backup are NOT a substitute for
 * a production at-rest encryption, retention and compromised-device review.
 */
class SqlitePaymentAttemptStore(
    context: Context,
    databaseName: String = "merchantpay-sandbox-attempts.db"
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1),
    PaymentAttemptStore, Closeable {

    private val merchantPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE payment_attempts (
                merchant_id TEXT NOT NULL,
                request_id TEXT NOT NULL,
                idempotency_key TEXT NOT NULL,
                amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
                currency TEXT NOT NULL DEFAULT 'KES' CHECK (currency = 'KES'),
                state TEXT NOT NULL CHECK (state IN ('prepared','uncertain','acknowledged')),
                server_intent_id TEXT,
                PRIMARY KEY (merchant_id, request_id),
                UNIQUE (merchant_id, idempotency_key),
                CHECK ((state = 'acknowledged' AND server_intent_id IS NOT NULL)
                    OR (state != 'acknowledged' AND server_intent_id IS NULL))
            )"""
        )
        db.execSQL("CREATE INDEX payment_attempts_unresolved ON payment_attempts (merchant_id, state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never drop/recreate this table: it holds financial idempotency history.
        throw IllegalStateException("Payment attempt schema migration must be explicit")
    }

    override fun prepare(merchantId: String, requestId: String, amountMinor: Long): StoredPaymentAttempt {
        validateIdentifiers(merchantId, requestId)
        require(amountMinor > 0) { "Amount must be positive KES minor units" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = read(db, merchantId, requestId)
            if (existing != null) {
                check(existing.amountMinor == amountMinor) {
                    "Logical request cannot be reused with a different amount"
                }
                db.setTransactionSuccessful()
                return existing
            }
            val values = ContentValues().apply {
                put("merchant_id", merchantId)
                put("request_id", requestId)
                put("idempotency_key", UUID.randomUUID().toString())
                put("amount_minor", amountMinor)
                put("currency", "KES")
                put("state", "prepared")
            }
            db.insertOrThrow("payment_attempts", null, values)
            val created = checkNotNull(read(db, merchantId, requestId))
            db.setTransactionSuccessful()
            return created
        } finally {
            db.endTransaction()
        }
    }

    override fun find(merchantId: String, requestId: String): StoredPaymentAttempt? {
        validateIdentifiers(merchantId, requestId)
        return read(readableDatabase, merchantId, requestId)
    }

    override fun markUncertain(merchantId: String, requestId: String): StoredPaymentAttempt {
        validateIdentifiers(merchantId, requestId)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = checkNotNull(read(db, merchantId, requestId)) { "Unknown request" }
            if (existing.state != AttemptState.ACKNOWLEDGED) {
                val values = ContentValues().apply { put("state", "uncertain") }
                db.update("payment_attempts", values, "merchant_id=? AND request_id=?",
                    arrayOf(merchantId, requestId))
            }
            val updated = checkNotNull(read(db, merchantId, requestId))
            db.setTransactionSuccessful()
            return updated
        } finally {
            db.endTransaction()
        }
    }

    override fun acknowledge(
        merchantId: String, requestId: String, response: PaymentIntent
    ): StoredPaymentAttempt {
        validateIdentifiers(merchantId, requestId)
        val canonicalId = UUID.fromString(response.id).toString()
        require(canonicalId == response.id) { "Invalid server intent identifier" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = checkNotNull(read(db, merchantId, requestId)) { "Unknown request" }
            check(response.merchantId == merchantId &&
                response.amountMinor == existing.amountMinor &&
                response.currency == "KES" &&
                response.status == "created"
            ) { "Server response does not match persisted request" }
            if (existing.state == AttemptState.ACKNOWLEDGED) {
                check(existing.serverIntentId == response.id) {
                    "Conflicting server intent for acknowledged request"
                }
            } else {
                val values = ContentValues().apply {
                    put("state", "acknowledged")
                    put("server_intent_id", response.id)
                }
                db.update("payment_attempts", values, "merchant_id=? AND request_id=?",
                    arrayOf(merchantId, requestId))
            }
            val updated = checkNotNull(read(db, merchantId, requestId))
            db.setTransactionSuccessful()
            return updated
        } finally {
            db.endTransaction()
        }
    }

    override fun unresolved(merchantId: String): List<StoredPaymentAttempt> {
        require(merchantPattern.matches(merchantId)) { "Invalid merchant identifier" }
        val db = readableDatabase
        db.rawQuery(
            """SELECT merchant_id, request_id, idempotency_key, amount_minor, state, server_intent_id
               FROM payment_attempts
               WHERE merchant_id=? AND state IN ('prepared','uncertain')
               ORDER BY rowid""",
            arrayOf(merchantId)
        ).use { cursor ->
            val result = mutableListOf<StoredPaymentAttempt>()
            while (cursor.moveToNext()) result.add(fromCursor(cursor))
            return result
        }
    }

    private fun validateIdentifiers(merchantId: String, requestId: String) {
        require(merchantPattern.matches(merchantId)) { "Invalid merchant identifier" }
        require(UUID.fromString(requestId).toString() == requestId) {
            "Request identifier must be a canonical UUID"
        }
    }

    private fun read(db: SQLiteDatabase, merchantId: String, requestId: String): StoredPaymentAttempt? =
        db.rawQuery(
            """SELECT merchant_id, request_id, idempotency_key, amount_minor, state, server_intent_id
               FROM payment_attempts WHERE merchant_id=? AND request_id=?""",
            arrayOf(merchantId, requestId)
        ).use { cursor ->
            if (cursor.moveToFirst()) fromCursor(cursor) else null
        }

    private fun fromCursor(cursor: android.database.Cursor) = StoredPaymentAttempt(
        merchantId = cursor.getString(0),
        requestId = cursor.getString(1),
        idempotencyKey = cursor.getString(2),
        amountMinor = cursor.getLong(3),
        state = when (cursor.getString(4)) {
            "prepared" -> AttemptState.PREPARED
            "uncertain" -> AttemptState.UNCERTAIN
            "acknowledged" -> AttemptState.ACKNOWLEDGED
            else -> error("Unknown stored payment attempt state")
        },
        serverIntentId = if (cursor.isNull(5)) null else cursor.getString(5)
    )
}
