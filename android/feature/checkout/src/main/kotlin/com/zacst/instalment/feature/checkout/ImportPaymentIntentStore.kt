package com.zacst.instalment.feature.checkout

import java.util.concurrent.ConcurrentHashMap

/** Stands in for Room. Reusing one instance across two ViewModels models "the DB survived the kill". */
class InMemoryPaymentIntentStore : PaymentIntentStore {
    private val rows = ConcurrentHashMap<String, PaymentIntent>()
    override suspend fun save(intent: PaymentIntent) { rows[intent.idempotencyKey] = intent }
    override suspend fun findActive(): PaymentIntent? =
        rows.values.firstOrNull { it.status == IntentStatus.SUBMITTING || it.status == IntentStatus.PENDING }
}