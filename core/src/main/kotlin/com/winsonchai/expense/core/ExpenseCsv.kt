package com.winsonchai.expense.core

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class CsvAccount(val provider: String, val nickname: String, val lastFour: String = "") {
    fun label() = "$provider · $nickname" + if (lastFour.isEmpty()) "" else " · $lastFour"
}

data class CsvExpense(
    val id: String, val date: String, val time: String, val receiptNumber: String,
    val amountMinor: Long, val currency: String, val category: String, val merchant: String,
    val method: String, val account: CsvAccount?, val note: String, val source: String
) {
    /** Identity is separate from content so a repeated import cannot overwrite edited records. */
    private val contentFingerprint by lazy { ExpenseCsv.digest(listOf(date, time, receiptNumber, amountMinor.toString(), currency,
        category, merchant, method, account?.label().orEmpty(), note)) }
    fun fingerprint(): String = contentFingerprint
}

data class CsvExpenseRow(val record: Int, val expense: CsvExpense)
data class CsvProblem(val record: Int, val message: String)
data class ParsedExpenseCsv(val rows: List<CsvExpenseRow>, val problems: List<CsvProblem>, val totalRows: Int)
data class CsvSelection(val rows: List<CsvExpenseRow>, val existingIds: Int, val possibleDuplicates: Int) {
    val skipped get() = existingIds + possibleDuplicates
}

/** Versioned CSV interchange; database columns and their order are deliberately not the file format. */
object ExpenseCsv {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_ROWS = 10_000
    val headers = listOf("date", "time", "receipt_number", "amount", "currency", "category", "merchant",
        "payment_method", "payment_account", "note", "source", "expense_id", "account_provider",
        "account_nickname", "account_last_four", "escaped_fields", "csv_version")
    private val currencies = setOf("MYR", "SGD", "USD", "AUD", "EUR", "CNY", "THB", "INR", "IDR", "JPY", "BHD", "KWD")
    private val aliases = mapOf("transaction_date" to "date", "日期" to "date", "交易日期" to "date",
        "transaction_time" to "time", "交易时间" to "time", "时间" to "time", "receipt_no" to "receipt_number",
        "收据号码" to "receipt_number", "金额" to "amount", "币种" to "currency", "类别" to "category",
        "分类" to "category", "商家" to "merchant", "付款方式" to "payment_method", "支付方式" to "payment_method",
        "付款账户" to "payment_account", "支付账户" to "payment_account", "备注" to "note", "来源" to "source")
    private val legacyLabels = mapOf("未指定" to "Unspecified", "现金" to "Cash", "电子钱包" to "E-wallet",
        "信用卡" to "Credit card", "借记卡" to "Debit card", "银行转账" to "Bank transfer",
        "预付／储值卡" to "Prepaid card", "其他" to "Other", "未分类" to "Uncategorized", "餐饮" to "Food & drinks",
        "交通" to "Transport", "食品杂货" to "Groceries", "购物" to "Shopping", "居家账单" to "Household bills",
        "医疗健康" to "Health", "娱乐旅行" to "Leisure & travel", "手动" to "Manual", "分享文字" to "Shared text",
        "分享文件" to "Shared file", "分享图片" to "Shared image", "分享 PDF" to "Shared PDF")

    fun exportRow(expense: CsvExpense): String {
        val values = listOf(expense.date, expense.time, expense.receiptNumber, Money.display(expense.amountMinor, expense.currency),
            expense.currency, expense.category, expense.merchant, expense.method, expense.account?.label().orEmpty(), expense.note,
            expense.source, expense.id, expense.account?.provider.orEmpty(), expense.account?.nickname.orEmpty(), expense.account?.lastFour.orEmpty())
        val escaped = values.indices.filter { needsProtection(values[it]) }.joinToString("|") { headers[it] }
        return (values + listOf(escaped, "2")).joinToString(",", transform = Csv::cell)
    }

    fun needsProtection(value: String) = value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')

    fun parse(text: String): ParsedExpenseCsv {
        require(text.length <= MAX_BYTES) { "CSV exceeds 5 MiB. Split the file before importing." }
        val records = readRecords(text.removePrefix("\uFEFF"))
        require(records.isNotEmpty()) { "The CSV file is empty." }
        val names = records.first().map { header -> header.trim().lowercase(Locale.ROOT).let { aliases[it] ?: it } }
        require(names.size <= 64) { "CSV has too many columns." }
        require(names.distinct().size == names.size) { "CSV has duplicate column names." }
        require(names.containsAll(listOf("date", "amount", "currency"))) { "CSV needs date, amount and currency columns. Choose an Export CSV file from Expense." }
        val rows = mutableListOf<CsvExpenseRow>()
        val problems = mutableListOf<CsvProblem>()
        val occurrences = mutableMapOf<String, Int>()
        val ids = mutableSetOf<String>()
        records.drop(1).forEachIndexed { index, cells ->
            val record = index + 2
            try {
                require(cells.size == names.size) { "Column count does not match the header." }
                val fields = names.zip(cells).toMap()
                val version = fields["csv_version"].orEmpty()
                require(version.isBlank() || version == "1" || version == "2") { "CSV version is newer than this app supports." }
                val escaped = if (version == "2") fields["escaped_fields"].orEmpty().split('|').toSet() else emptySet()
                fun get(name: String): String {
                    val value = fields[name].orEmpty()
                    return if (name in escaped && value.startsWith("'") && needsProtection(value.drop(1))) value.drop(1) else value
                }
                val date = runCatching { LocalDate.parse(get("date").trim()).toString() }.getOrNull()
                require(date != null) { "Invalid date; use YYYY-MM-DD." }
                val timeText = get("time").trim()
                val time = if (timeText.isEmpty()) "" else runCatching {
                    LocalTime.parse(timeText).format(DateTimeFormatter.ISO_LOCAL_TIME)
                }.getOrNull()
                require(time != null) { "Invalid time; use HH:mm or HH:mm:ss." }
                val currency = get("currency").trim().uppercase(Locale.ROOT).let { if (it == "RM") "MYR" else it }
                require(currency in currencies) { "Missing or unsupported currency." }
                val amount = Money.minorUnits(get("amount").trim(), currency)
                require(amount != null) { "Amount must be positive and use the currency's exact decimal precision." }
                fun label(name: String, fallback: String) = get(name).ifBlank { fallback }.let { legacyLabels[it] ?: it }
                val accountLabel = get("payment_account")
                val provider = get("account_provider")
                val nickname = get("account_nickname")
                val lastFour = get("account_last_four")
                val account = if (provider.isNotEmpty() || nickname.isNotEmpty() || lastFour.isNotEmpty()) {
                    require(provider.isNotBlank() && nickname.isNotBlank()) { "Payment account needs both provider and nickname." }
                    require(lastFour.isEmpty() || Regex("\\d{4}").matches(lastFour)) { "Invalid account last four." }
                    CsvAccount(provider, nickname, lastFour)
                } else if (accountLabel.isNotBlank()) {
                    val parts = accountLabel.split(" · ")
                    require(parts.size in 2..3 && parts[0].isNotBlank() && parts[1].isNotBlank() &&
                        (parts.size == 2 || Regex("\\d{4}").matches(parts[2]))) {
                        "Payment account label is ambiguous. Supply account_provider and account_nickname columns."
                    }
                    CsvAccount(parts[0], parts[1], parts.getOrElse(2) { "" })
                } else null
                val imported = CsvExpense("", date, time, get("receipt_number"), amount, currency,
                    label("category", "Uncategorized"), get("merchant"), label("payment_method", "Unspecified"),
                    account, get("note"), label("source", "CSV import"))
                val fingerprint = imported.fingerprint()
                val occurrence = occurrences.getOrDefault(fingerprint, 0)
                occurrences[fingerprint] = occurrence + 1
                val providedId = get("expense_id").trim()
                val id = if (providedId.isBlank()) UUID.nameUUIDFromBytes("expense-csv-v1:$fingerprint:$occurrence".toByteArray(Charsets.UTF_8)).toString()
                    else runCatching { UUID.fromString(providedId).toString() }.getOrNull()
                require(id != null) { "Invalid expense_id." }
                require(ids.add(id)) { "Duplicate expense_id inside this file." }
                rows += CsvExpenseRow(record, imported.copy(id = id).also { it.fingerprint() })
            } catch (e: IllegalArgumentException) {
                problems += CsvProblem(record, e.message ?: "Invalid record.")
            }
        }
        return ParsedExpenseCsv(rows, problems, records.size - 1)
    }

    fun select(rows: List<CsvExpenseRow>, existingIds: Set<String>, existingFingerprints: Set<String>, skipPossibleDuplicates: Boolean): CsvSelection {
        val ids = existingIds.toMutableSet()
        val fingerprints = existingFingerprints.toMutableSet()
        val selected = mutableListOf<CsvExpenseRow>()
        var known = 0
        var possible = 0
        rows.forEach { row ->
            when {
                row.expense.id in ids -> known++
                skipPossibleDuplicates && row.expense.fingerprint() in fingerprints -> possible++
                else -> {
                    selected += row
                    ids += row.expense.id
                    fingerprints += row.expense.fingerprint()
                }
            }
        }
        return CsvSelection(selected, known, possible)
    }

    internal fun digest(fields: List<String>): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(64) { bytes.forEach { byte ->
            val value = byte.toInt() and 255
            append(hex[value ushr 4]); append(hex[value and 15])
        } }
    }

    /** RFC 4180-style quoting, including multiline fields. Structural errors reject the whole file. */
    private fun readRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var index = 0
        fun endField() { fields += field.toString(); field.setLength(0); afterQuote = false; require(fields.size <= 64) { "CSV has too many columns." } }
        fun endRecord() {
            endField()
            if (fields.any(String::isNotEmpty)) records += fields
            fields = mutableListOf()
            require(records.size <= MAX_ROWS + 1) { "CSV exceeds 10,000 records. Split the file before importing." }
        }
        while (index < text.length) {
            val c = text[index]
            if (quoted) {
                if (c == '"') {
                    if (text.getOrNull(index + 1) == '"') { field.append('"'); index++ }
                    else { quoted = false; afterQuote = true }
                } else field.append(c)
            } else {
                when (c) {
                    '"' -> { require(field.isEmpty() && !afterQuote) { "Malformed CSV quote near record ${records.size + 1}." }; quoted = true }
                    ',' -> endField()
                    '\r', '\n' -> { endRecord(); if (c == '\r' && text.getOrNull(index + 1) == '\n') index++ }
                    else -> { require(!afterQuote) { "Unexpected text after a closing CSV quote near record ${records.size + 1}." }; field.append(c) }
                }
            }
            index++
        }
        require(!quoted) { "CSV contains an unclosed quoted field." }
        if (field.isNotEmpty() || fields.isNotEmpty() || afterQuote) endRecord()
        return records
    }
}
