package com.winsonchai.expense.importing

import androidx.room.withTransaction
import com.winsonchai.expense.core.*
import com.winsonchai.expense.data.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class CsvImportPreview(
    val parsed: ParsedExpenseCsv, val existingIds: Set<String>, val fingerprints: Set<String>,
    val token: String = UUID.randomUUID().toString()
)

fun Expense.csvRecord(account: PaymentAccount?): CsvExpense = CsvExpense(
    id, date, transactionTime.takeIf(String::isNotBlank)?.let {
        runCatching { LocalTime.parse(it).format(DateTimeFormatter.ISO_LOCAL_TIME) }.getOrDefault(it)
    }.orEmpty(), receiptNo, amountMinor ?: Money.minorUnits(amountInput, currency) ?: 0,
    currency, category, merchant, method,
    account?.let { CsvAccount(it.provider, it.nickname, it.lastFour) }, note, source
)

/** Fresh duplicate checks and all account/expense writes share one rollback-safe transaction. */
class CsvLedgerImporter(private val database: ExpenseDatabase) {
    private val dao get() = database.dao()

    suspend fun preview(parsed: ParsedExpenseCsv): CsvImportPreview = database.withTransaction {
        val existing = dao.allExpenses()
        val accounts = dao.allAccounts().associateBy { it.id }
        CsvImportPreview(parsed, existing.map { it.id }.toSet(),
            existing.filter { it.status == "SAVED" }.map { it.csvRecord(accounts[it.accountId]).fingerprint() }.toSet())
    }

    suspend fun importRecords(preview: CsvImportPreview, skipPossibleDuplicates: Boolean, skipInvalid: Boolean): String = database.withTransaction {
        require(skipInvalid || preview.parsed.problems.isEmpty()) { "Fix invalid records or explicitly choose to skip them." }
        val existing = dao.allExpenses()
        val accounts = dao.allAccounts().toMutableList()
        val byId = accounts.associateBy { it.id }
        val selection = ExpenseCsv.select(preview.parsed.rows, existing.map { it.id }.toSet(),
            existing.filter { it.status == "SAVED" }.map { it.csvRecord(byId[it.accountId]).fingerprint() }.toSet(), skipPossibleDuplicates)
        fun accountKey(method: String, provider: String, nickname: String, lastFour: String) = listOf(method, provider, nickname, lastFour)
        val accountLookup = accounts.associateBy { accountKey(it.method, it.provider, it.nickname, it.lastFour) }.toMutableMap()
        var createdAccounts = 0
        selection.rows.forEach { row ->
            val record = row.expense
            val account = record.account?.let { imported ->
                val key = accountKey(record.method, imported.provider, imported.nickname, imported.lastFour)
                accountLookup[key] ?: PaymentAccount(method = record.method, provider = imported.provider,
                    nickname = imported.nickname, lastFour = imported.lastFour).also {
                    dao.putAccount(it)
                    accountLookup[key] = it
                    createdAccounts++
                }
            }
            dao.put(Expense(id = record.id, status = "SAVED", date = record.date, transactionTime = record.time,
                receiptNo = record.receiptNumber, amountMinor = record.amountMinor,
                amountInput = Money.display(record.amountMinor, record.currency), currency = record.currency,
                category = record.category, merchant = record.merchant, method = record.method, accountId = account?.id,
                note = record.note, source = record.source))
        }
        "Imported ${selection.rows.size} saved expenses. Skipped ${selection.existingIds} existing IDs, " +
            "${selection.possibleDuplicates} possible duplicates and ${preview.parsed.problems.size} invalid records. " +
            "Created $createdAccounts payment accounts. Existing records were kept."
    }
}
