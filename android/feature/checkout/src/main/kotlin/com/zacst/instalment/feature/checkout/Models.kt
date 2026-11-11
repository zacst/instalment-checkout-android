package com.zacst.instalment.feature.checkout

import kotlinx.serialization.Serializable

@Serializable
data class Quote(
    val quoteId: String,
    val version: Int,
    val amountMinor: Long,
    val tenorMonths: Int,
    val instalmentMinor: Long,
)

@Serializable
data class CheckoutRequest(val quoteId: String, val amountMinor: Long)

@Serializable
data class CheckoutResponse(val chargeId: String, val status: String, val amountMinor: Long)

enum class IntentStatus { SUBMITTING, PENDING, CONFIRMED, FAILED }

/**
 * The user's intent to pay. [idempotencyKey] is minted exactly once, when the intent is created,
 * and is reused by every retry, including retries after process death.
 */
data class PaymentIntent(
    val idempotencyKey: String,
    val quoteId: String,
    val amountMinor: Long,
    val status: IntentStatus = IntentStatus.SUBMITTING,
    val chargeId: String? = null,
)

sealed interface CheckoutState {
    data object Idle : CheckoutState
    data class Quoted(val quote: Quote) : CheckoutState
    data class Submitting(val intent: PaymentIntent) : CheckoutState

    /** Outcome unknown (timeout, 5xx, dropped connection). Money may or may not have moved. */
    data class Pending(val intent: PaymentIntent) : CheckoutState
    data class Confirmed(val chargeId: String) : CheckoutState
    data class Failed(val reason: String) : CheckoutState
}