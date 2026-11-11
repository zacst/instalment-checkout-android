package com.zacst.instalment.feature.checkout

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** Durable record of in-flight payments. Must survive process death. */
interface PaymentIntentStore {
    suspend fun save(intent: PaymentIntent)

    /** Most recent intent whose outcome is not yet known (SUBMITTING or PENDING), if any. */
    suspend fun findActive(): PaymentIntent?
}

@Entity(tableName = "payment_intents")
data class PaymentIntentEntity(
    @PrimaryKey val idempotencyKey: String,
    val quoteId: String,
    val amountMinor: Long,
    val status: String,
    val chargeId: String?,
    val updatedAtMs: Long,
)

@Dao
interface PaymentIntentDao {
    @Upsert
    suspend fun upsert(entity: PaymentIntentEntity)

    @Query(
        "SELECT * FROM payment_intents WHERE status IN ('SUBMITTING','PENDING') " +
                "ORDER BY updatedAtMs DESC LIMIT 1"
    )
    suspend fun findActive(): PaymentIntentEntity?
}

@Database(entities = [PaymentIntentEntity::class], version = 1, exportSchema = false)
abstract class CheckoutDatabase : RoomDatabase() {
    abstract fun paymentIntentDao(): PaymentIntentDao
}

class RoomPaymentIntentStore(
    private val dao: PaymentIntentDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : PaymentIntentStore {
    override suspend fun save(intent: PaymentIntent) = dao.upsert(
        PaymentIntentEntity(
            intent.idempotencyKey, intent.quoteId, intent.amountMinor,
            intent.status.name, intent.chargeId, clock(),
        )
    )

    override suspend fun findActive(): PaymentIntent? = dao.findActive()?.let {
        PaymentIntent(it.idempotencyKey, it.quoteId, it.amountMinor, IntentStatus.valueOf(it.status), it.chargeId)
    }
}