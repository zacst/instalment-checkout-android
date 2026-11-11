package com.zacst.instalment.feature.checkout

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface PaymentApi {
    @GET("quote")
    suspend fun getQuote(@Query("amount") amountMinor: Long, @Query("tenor") tenorMonths: Int): Quote

    @POST("checkout")
    suspend fun checkout(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Request-Id") requestId: String,
        @Body body: CheckoutRequest,
    ): CheckoutResponse
}

object PaymentApiFactory {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(baseUrl: String, readTimeoutMs: Long = 10_000): PaymentApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(5_000, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            // Retries are owned by the checkout layer, which replays the SAME idempotency key.
            // Hidden transport-level retries would multiply load and obscure who retried.
            .retryOnConnectionFailure(false)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PaymentApi::class.java)
    }
}