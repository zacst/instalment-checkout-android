package com.zacst.instalment.feature.checkout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Idle -> Quoted -> Submitting -> (Pending <-> Submitting) -> Confirmed | Failed
 *
 * Transitions out of Quoted/Pending use compareAndSet, so a double tap can claim the transition
 * only once. Defense in depth: the server also dedupes on the Idempotency-Key.
 */
class CheckoutViewModel(private val repository: CheckoutRepository) : ViewModel() {

    private val _state = MutableStateFlow<CheckoutState>(CheckoutState.Idle)
    val state: StateFlow<CheckoutState> = _state.asStateFlow()

    // Completed once we know whether an unfinished payment exists, so a fresh quote can never race it.
    private val restored = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            val active = try {
                repository.restoreActive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = CheckoutState.Failed("Could not verify payment status")
                restored.complete(Unit)
                return@launch
            }
            if (active != null) _state.value = CheckoutState.Pending(active) // resume, never Idle
            restored.complete(Unit)
            if (active != null) settle(active) // replays the SAME key
        }
    }

    fun loadQuote(amountMinor: Long, tenorMonths: Int) {
        viewModelScope.launch {
            restored.await()
            val current = _state.value
            if (current !is CheckoutState.Idle && current !is CheckoutState.Quoted) return@launch
            try {
                val quote = repository.getQuote(amountMinor, tenorMonths)
                _state.update { if (it is CheckoutState.Idle || it is CheckoutState.Quoted) CheckoutState.Quoted(quote) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it is CheckoutState.Idle) CheckoutState.Failed("Could not load quote") else it }
            }
        }
    }

    fun onPayClicked() {
        val quoted = _state.value as? CheckoutState.Quoted ?: return
        val intent = repository.newIntent(quoted.quote) // key minted once, here
        if (!_state.compareAndSet(quoted, CheckoutState.Submitting(intent))) return // lost the race
        viewModelScope.launch { settle(intent) }
    }

    fun onRetryPending() {
        val pending = _state.value as? CheckoutState.Pending ?: return
        if (!_state.compareAndSet(pending, CheckoutState.Submitting(pending.intent))) return
        viewModelScope.launch { settle(pending.intent) }
    }

    fun dismissFailure() {
        _state.update { if (it is CheckoutState.Failed) CheckoutState.Idle else it }
    }

    private suspend fun settle(intent: PaymentIntent) {
        _state.value = when (val result = repository.submit(intent)) {
            is SubmitResult.Confirmed -> CheckoutState.Confirmed(result.chargeId)
            is SubmitResult.Unknown -> CheckoutState.Pending(intent)
            is SubmitResult.Rejected -> CheckoutState.Failed(result.reason)
        }
    }

    companion object {
        fun factory(repository: CheckoutRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { CheckoutViewModel(repository) } }
    }
}