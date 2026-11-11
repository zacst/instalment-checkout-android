package com.zacst.instalment.feature.checkout

import androidx.lifecycle.viewModelScope
import com.zacst.instalment.feature.checkout.CheckoutRepository
import com.zacst.instalment.feature.checkout.CheckoutRequest
import com.zacst.instalment.feature.checkout.CheckoutState
import com.zacst.instalment.feature.checkout.CheckoutViewModel
import com.zacst.instalment.feature.checkout.InMemoryPaymentIntentStore
import com.zacst.instalment.feature.checkout.PaymentApiFactory
import com.zacst.instalment.feature.checkout.PaymentIntentStore
import com.zacst.instalment.feature.checkout.Quote
import com.zacst.instalment.feature.checkout.SubmitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Real Retrofit + OkHttp over real sockets against a MockWebServer that implements the SAME
 * idempotency contract as stub-server/main.go (verify the Go side with scripts/stub_smoke.sh).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutIdempotencyTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryPaymentIntentStore
    private lateinit var ledger: IdempotentPaymentDispatcher

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = InMemoryPaymentIntentStore()
        ledger = IdempotentPaymentDispatcher(store)
        server = MockWebServer().apply { dispatcher = ledger; start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun api(readTimeoutMs: Long = 400) =
        PaymentApiFactory.create(server.url("/").toString(), readTimeoutMs)

    private fun newViewModel(readTimeoutMs: Long = 400) =
        CheckoutViewModel(CheckoutRepository(api(readTimeoutMs), store))

    private suspend fun CheckoutViewModel.awaitQuoted() {
        loadQuote(12_000_000, 6)
        state.first { it is CheckoutState.Quoted }
    }

    // (a) The server commits the charge, the response never arrives, the client retries.
    @Test
    fun timeoutAfterCommit_thenRetry_chargesExactlyOnce() = runTest(timeout = 20.seconds) {
        ledger.hangAfterCommit = true
        val vm = newViewModel()
        vm.awaitQuoted()

        vm.onPayClicked()
        val pending = vm.state.first { it is CheckoutState.Pending } as CheckoutState.Pending
        assertEquals("charge committed server-side before the timeout", 1, ledger.chargeCount.get())

        vm.onRetryPending()
        val confirmed = vm.state.first { it is CheckoutState.Confirmed } as CheckoutState.Confirmed

        assertEquals("retry must not double-charge", 1, ledger.chargeCount.get())
        assertEquals(2, ledger.requestCount.get())
        assertEquals("both attempts carry the same key", 1, ledger.keysSeen.toSet().size)
        assertEquals(pending.intent.idempotencyKey, ledger.keysSeen.first())
        assertEquals("ch_0001", confirmed.chargeId)
        assertTrue("key was on disk before the first request arrived", ledger.persistedBeforeNetwork.get())
    }

    // (b) Impatient user hammers "Pay" while the first request is still in flight.
    @Test
    fun doubleTapOnPay_chargesExactlyOnce() = runTest(timeout = 20.seconds) {
        ledger.headersDelayMs = 300
        val vm = newViewModel()
        vm.awaitQuoted()

        repeat(3) { vm.onPayClicked() }
        vm.state.first { it is CheckoutState.Confirmed }

        assertEquals(1, ledger.chargeCount.get())
        assertEquals("extra taps must never reach the network", 1, ledger.requestCount.get())
    }

    // (b') Defense in depth: even if the UI guard were bypassed, the server dedupes on the key.
    @Test
    fun concurrentSubmitsOfSameIntent_chargeExactlyOnce() = runTest(timeout = 20.seconds) {
        ledger.headersDelayMs = 200
        val repo = CheckoutRepository(api(), store)
        val intent = repo.newIntent(Quote("q_1", 1, 12_000_000, 6, 2_144_000))

        val results = listOf(async { repo.submit(intent) }, async { repo.submit(intent) }).awaitAll()

        assertTrue(results.all { it is SubmitResult.Confirmed })
        assertEquals(1, ledger.chargeCount.get())
        assertEquals(2, ledger.requestCount.get())
    }

    // (c) Process dies mid-submit. A new ViewModel must resume as Pending and replay the same key.
    @Test
    fun processDeathMidSubmit_resumesAsPending_andReplaysSameKey() = runTest(timeout = 20.seconds) {
        ledger.hangAfterCommit = true
        val vm1 = newViewModel(readTimeoutMs = 60_000) // the "process" dies long before this fires
        vm1.awaitQuoted()
        vm1.onPayClicked()
        assertTrue(vm1.state.value is CheckoutState.Submitting)
        assertTrue("server never committed", ledger.firstCommit.await(5, TimeUnit.SECONDS))

        vm1.viewModelScope.cancel() // process death: no more callbacks, in-flight call torn down
        ledger.hangAfterCommit = false
        ledger.headersDelayMs = 300 // keep the replay in flight long enough to observe Pending

        val vm2 = newViewModel() // same store instance == Room survived the kill
        val resumed = vm2.state.value
        assertTrue("must resume as Pending, was $resumed", resumed is CheckoutState.Pending)

        vm2.state.first { it is CheckoutState.Confirmed }
        assertEquals("replay, not a second charge", 1, ledger.chargeCount.get())
        assertEquals(1, ledger.keysSeen.toSet().size)
        assertEquals((resumed as CheckoutState.Pending).intent.idempotencyKey, ledger.keysSeen.first())
    }

    // Control: proves this harness CAN fail. Minting a new key per attempt (the naive bug) double-charges.
    @Test
    fun control_newKeyPerAttempt_doubleCharges() = runTest(timeout = 20.seconds) {
        val api = api()
        val body = CheckoutRequest("q_1", 12_000_000)

        ledger.hangAfterCommit = true
        try {
            api.checkout(UUID.randomUUID().toString(), "r1", body)
        } catch (expected: IOException) { /* timed out after commit */ }
        ledger.hangAfterCommit = false
        api.checkout(UUID.randomUUID().toString(), "r2", body) // "retry" with a fresh key

        assertEquals(2, ledger.chargeCount.get())
    }
}

/** Mirrors stub-server/main.go: first sighting of a key charges; later sightings replay. */
class IdempotentPaymentDispatcher(private val store: PaymentIntentStore) : Dispatcher() {
    val chargeCount = AtomicInteger()
    val requestCount = AtomicInteger()
    val keysSeen = CopyOnWriteArrayList<String>()
    val firstCommit = CountDownLatch(1)
    val persistedBeforeNetwork = AtomicBoolean(true)

    @Volatile var hangAfterCommit = false
    @Volatile var headersDelayMs = 0L

    private val committed = HashMap<String, String>() // key -> chargeId, guarded by `this`

    override fun dispatch(request: RecordedRequest): MockResponse =
        when (request.path?.substringBefore('?')) {
            "/quote" -> MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"quoteId":"q_1","version":1,"amountMinor":12000000,"tenorMonths":6,"instalmentMinor":2144000}""")
            "/checkout" -> checkout(request)
            else -> MockResponse().setResponseCode(404)
        }

    private fun checkout(request: RecordedRequest): MockResponse {
        requestCount.incrementAndGet()
        val key = request.getHeader("Idempotency-Key") ?: return MockResponse().setResponseCode(400)
        keysSeen += key

        val (chargeId, isNew) = synchronized(this) {
            committed[key]?.let { it to false } ?: run {
                val id = "ch_%04d".format(chargeCount.incrementAndGet())
                committed[key] = id
                id to true
            }
        }
        if (isNew) {
            if (runBlocking { store.findActive() }?.idempotencyKey != key) persistedBeforeNetwork.set(false)
            firstCommit.countDown()
        }

        val response = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("""{"chargeId":"$chargeId","status":"CONFIRMED","amountMinor":12000000}""")
        if (isNew && hangAfterCommit) response.socketPolicy = SocketPolicy.NO_RESPONSE // money moved, client hears nothing
        else if (headersDelayMs > 0) response.setHeadersDelay(headersDelayMs, TimeUnit.MILLISECONDS)
        return response
    }
}