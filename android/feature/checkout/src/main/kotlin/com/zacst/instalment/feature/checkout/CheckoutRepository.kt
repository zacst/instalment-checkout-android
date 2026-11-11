package com.zacst.instalment.feature.checkout

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

sealed interface SubmitResult {
    data class Confirmed(val chargeId: String) : SubmitResult

    /** We cannot tell whether the charge happened. Safe to retry ONLY with the same key. */
    data class Unknown(val cause: Throwable) : SubmitResult

    /** The server definitively refused. No money moved. */
    data class Rejected(val reason: String) : SubmitResult
}

class CheckoutRepository(
    private val api: PaymentApi,
    private val store: PaymentIntentStore,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun getQuote(amountMinor: Long, tenorMonths: Int): Quote = api.getQuote(amountMinor, tenorMonths)

    /** Pure and non-suspending: the key is minted here, once, never inside a retry path. */
    fun newIntent(quote: Quote) = PaymentIntent(newKey(), quote.quoteId, quote.amountMinor)

    suspend fun restoreActive(): PaymentIntent? = store.findActive()

    /** Safe to call repeatedly for the same [intent]: every call carries the same Idempotency-Key. */
    suspend fun submit(intent: PaymentIntent): SubmitResult {
        // Persist BEFORE the first byte hits the network. If we die after the server commits,
        // the key is still on disk and the retry can replay it.
        store.save(intent.copy(status = IntentStatus.SUBMITTING))
        return try {
            val res = api.checkout(
                idempotencyKey = intent.idempotencyKey,
                requestId = UUID.randomUUID().toString(), // per attempt, for log correlation
                body = CheckoutRequest(intent.quoteId, intent.amountMinor),
            )
            withContext(NonCancellable) {
                store.save(intent.copy(status = IntentStatus.CONFIRMED, chargeId = res.chargeId))
            }
            SubmitResult.Confirmed(res.chargeId)
        } catch (e: CancellationException) {
            throw e // leave the row SUBMITTING; the next session will reconcile it
        } catch (e: HttpException) {
            val definitive = e.code() in 400..499 && e.code() != 408 && e.code() != 429
            if (definitive) {
                store.save(intent.copy(status = IntentStatus.FAILED))
                SubmitResult.Rejected("Payment rejected (${e.code()})")
            } else {
                store.save(intent.copy(status = IntentStatus.PENDING))
                SubmitResult.Unknown(e)
            }
        } catch (e: IOException) {
            store.save(intent.copy(status = IntentStatus.PENDING))
            SubmitResult.Unknown(e)
        } catch (e: SerializationException) { // 200 with an unreadable body: money may have moved
            store.save(intent.copy(status = IntentStatus.PENDING))
            SubmitResult.Unknown(e)
        }
    }
}