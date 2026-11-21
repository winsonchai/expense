package com.winsonchai.expense.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

val paymentMethods = listOf("Unspecified", "Cash", "E-wallet", "Credit card", "Debit card", "Bank transfer", "Prepaid card", "Other")
val categories = listOf("Uncategorized", "Food & drinks", "Transport", "Groceries", "Shopping", "Household bills", "Health", "Leisure & travel", "Other")
val currencies = listOf("MYR", "SGD", "USD", "AUD", "EUR", "CNY", "THB", "INR", "IDR", "JPY", "BHD", "KWD")

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val status: String = "DRAFT",
    val date: String = "",
    val amountMinor: Long? = null,
    val amountInput: String = "",
    val currency: String = "MYR",
    val category: String = "Uncategorized",
    val merchant: String = "",
    val method: String = "Unspecified",
    val accountId: String? = null,
    val note: String = "",
    val source: String = "Manual",
    val receiptPath: String? = null,
    val mimeType: String? = null,
    val receiptHash: String? = null,
    val rawText: String = "",
    val warnings: String = "",
    val candidates: String = "",
    @ColumnInfo(defaultValue = "''") val receiptNo: String = "",
    @ColumnInfo(defaultValue = "''") val transactionTime: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "accounts")
data class PaymentAccount(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val method: String,
    val provider: String,
    val nickname: String,
    val lastFour: String = "",
    val active: Boolean = true
) {
    fun label(): String = "$provider · $nickname" + if (lastFour.isBlank()) "" else " · $lastFour"
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC, transactionTime DESC, createdAt DESC")
    fun observeExpenses(): Flow<List<Expense>>
    @Query("SELECT * FROM accounts ORDER BY provider, nickname")
    fun observeAccounts(): Flow<List<PaymentAccount>>
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun expense(id: String): Expense?
    @Query("SELECT EXISTS(SELECT 1 FROM expenses WHERE receiptHash = :hash AND id != :id)")
    suspend fun hasDuplicate(hash: String, id: String): Boolean
    @Upsert suspend fun put(expense: Expense)
    @Upsert suspend fun putAccount(account: PaymentAccount)
    @Query("DELETE FROM expenses WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT COUNT(*) FROM accounts") suspend fun accountCount(): Int
    @Transaction suspend fun seedAccounts() {
        if (accountCount() == 0) listOf("ShopeePay", "TNG eWallet", "GrabPay", "Boost").forEach {
            putAccount(PaymentAccount(id = "initial-$it", method = "E-wallet", provider = it, nickname = "Personal"))
        }
    }
}

@Database(entities = [Expense::class, PaymentAccount::class], version = 2, exportSchema = true)
abstract class ExpenseDatabase : RoomDatabase() {
    abstract fun dao(): ExpenseDao
    companion object {
        @Volatile private var instance: ExpenseDatabase? = null
        fun get(context: Context): ExpenseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ExpenseDatabase::class.java, "expense.db")
                .addMigrations(object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        context.applicationContext.assets.open("migrations/1_2.sql").bufferedReader().useLines { lines ->
                            lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("--") }.forEach(db::execSQL)
                        }
                    }
                }).build().also { instance = it }
        }
    }
}
