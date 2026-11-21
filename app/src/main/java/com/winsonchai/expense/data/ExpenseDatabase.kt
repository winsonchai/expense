package com.winsonchai.expense.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

val paymentMethods = listOf("未指定", "现金", "电子钱包", "信用卡", "借记卡", "银行转账", "预付／储值卡", "其他")
val categories = listOf("未分类", "餐饮", "交通", "食品杂货", "购物", "居家账单", "医疗健康", "娱乐旅行", "其他")
val currencies = listOf("MYR", "SGD", "USD", "AUD", "EUR", "CNY", "THB", "INR", "IDR", "JPY", "BHD", "KWD")

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val status: String = "DRAFT",
    val date: String = "",
    val amountMinor: Long? = null,
    val amountInput: String = "",
    val currency: String = "MYR",
    val category: String = "未分类",
    val merchant: String = "",
    val method: String = "未指定",
    val accountId: String? = null,
    val note: String = "",
    val source: String = "手动",
    val receiptPath: String? = null,
    val mimeType: String? = null,
    val receiptHash: String? = null,
    val rawText: String = "",
    val warnings: String = "",
    val candidates: String = "",
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
    @Query("SELECT * FROM expenses ORDER BY date DESC, createdAt DESC")
    fun observeExpenses(): Flow<List<Expense>>
    @Query("SELECT * FROM accounts ORDER BY provider, nickname")
    fun observeAccounts(): Flow<List<PaymentAccount>>
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun expense(id: String): Expense?
    @Upsert suspend fun put(expense: Expense)
    @Upsert suspend fun putAccount(account: PaymentAccount)
    @Query("DELETE FROM expenses WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT COUNT(*) FROM accounts") suspend fun accountCount(): Int
    @Transaction suspend fun seedAccounts() {
        if (accountCount() == 0) listOf("ShopeePay", "TNG eWallet", "GrabPay", "Boost").forEach {
            putAccount(PaymentAccount(id = "initial-$it", method = "电子钱包", provider = it, nickname = "个人"))
        }
    }
}

@Database(entities = [Expense::class, PaymentAccount::class], version = 1, exportSchema = true)
abstract class ExpenseDatabase : RoomDatabase() {
    abstract fun dao(): ExpenseDao
    companion object {
        @Volatile private var instance: ExpenseDatabase? = null
        fun get(context: Context): ExpenseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ExpenseDatabase::class.java, "expense.db")
                .build().also { instance = it }
        }
    }
}
