package com.winsonchai.expense.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Currency

object Money {
    fun minorUnits(text: String, currency: String): Long? = runCatching {
        val digits = Currency.getInstance(currency).defaultFractionDigits
        require(digits in 0..3)
        require(Regex("\\d+(?:\\.\\d+)?").matches(text.trim()))
        val value = BigDecimal(text.trim()).setScale(digits, RoundingMode.UNNECESSARY)
        require(value.signum() > 0)
        value.movePointRight(digits).longValueExact()
    }.getOrNull()

    fun display(minor: Long, currency: String): String =
        BigDecimal.valueOf(minor, Currency.getInstance(currency).defaultFractionDigits).toPlainString()
}

data class ParsedReceipt(
    val amount: String? = null,
    val amountCandidates: List<String> = emptyList(),
    val currency: String = "MYR",
    val date: String? = null,
    val suggestedWallet: String? = null,
    val warnings: List<String> = emptyList(),
    val receiptNo: String? = null,
    val transactionTime: String? = null
)

/** Deliberately conservative: a receipt is a draft until the user reviews it. */
object ReceiptParser {
    private val number = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}"
    private val money = Regex("(?i)(?:RM|MYR|SGD|USD|AUD|EUR|CNY|THB|INR|IDR|JPY|BHD|KWD)\\s*($number)(?!\\d)")
    private val total = Regex("(?i)^\\s*(?:grand total|total paid|amount paid|payment amount|jumlah bayaran|jumlah|总金额|实付|总额|total|amount)\\s*[:：]?\\s*(?:(?:RM|MYR|SGD|USD|AUD|EUR|CNY|THB|INR|IDR|JPY|BHD|KWD)\\s*)?($number)\\s*$")
    private val code = Regex("(?i)(?<![a-z])(?:MYR|SGD|USD|AUD|EUR|CNY|THB|INR|IDR|JPY|BHD|KWD|RM)(?![a-z])")
    private val isoDate = Regex("(?<!\\d)(\\d{4}-\\d{2}-\\d{2})(?!\\d)")
    private val localDate = Regex("(?<!\\d)(\\d{2}/\\d{2}/\\d{4})(?!\\d)")

    private val reference = Regex("(?im)^[ \t]*(?:(?:receipt|invoice|bill|transaction|reference|ref)(?:[ \\t]*(?:no\\.?|number|id))?|收据编号|发票号码|交易编号)[ \\t]*[:#：]+[ \\t]*([A-Z0-9][A-Z0-9._/\\-]{0,79})[ \\t]*$")
    private val time = Regex("(?<![\\d:])((?:[01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?)(?![\\d:]|\\s*[AP]M)", RegexOption.IGNORE_CASE)

    private val twelveHourTime = Regex("(?i)(?<![\\d:])(\\d{1,2}:[0-5]\\d(?::[0-5]\\d)?)[ \\t]*(AM|PM)\\b")

    fun parse(raw: String): ParsedReceipt {
        val warnings = mutableListOf<String>()
        if (raw.isBlank()) warnings += "No text was detected. Enter the details manually."
        if (raw.trim().matches(Regex("https?://\\S+", RegexOption.IGNORE_CASE)))
            warnings += "This is a web link. Share a receipt image or transaction text from your wallet."
        val currencies = code.findAll(raw).map { if (it.value.equals("RM", true)) "MYR" else it.value.uppercase() }.distinct().toList()
        if (currencies.size != 1) warnings += if (currencies.isEmpty()) "Currency not detected. MYR is selected; please check it." else "Multiple currencies found. Check the currency and amount."
        val labelled = raw.lineSequence().mapNotNull { total.matchEntire(it)?.groupValues?.get(1) }.map(::normalize).distinct().toList()
        val candidates = (labelled + money.findAll(raw).map { normalize(it.groupValues[1]) }).distinct()
        val amount = if (currencies.size > 1) null else labelled.singleOrNull() ?: candidates.singleOrNull()
        if (amount == null) warnings += "The amount is missing or ambiguous. Check the total paid, not the balance."
        val dates = (isoDate.findAll(raw).mapNotNull { validDate(it.value, "uuuu-MM-dd") } +
            localDate.findAll(raw).mapNotNull { validDate(it.value, "dd/MM/uuuu") }).distinct().toList()
        if (dates.size != 1) warnings += "The transaction date is unclear. Enter it manually; do not use the screenshot date."
        if (Regex("(?i)refund|refunded|failed|pending|unsuccessful|declined|cancelled|canceled|reversed|reversal|top[ -]?up|reload|transfer|repayment|退款|失败|处理中|充值|转账|还款").containsMatchIn(raw))
            warnings += "This may be a refund, failed or pending payment, or a funds transfer. Save only completed purchases as expenses."
        if (Regex("[-(]\\s*(?:RM|MYR|SGD|USD|AUD|EUR|CNY|THB|INR|IDR|JPY|BHD|KWD)?\\s*$number", RegexOption.IGNORE_CASE).containsMatchIn(raw))
            warnings += "A negative amount was found. Check whether this is a refund or reversal before saving."
        // Platform branding alone never assigns a payment account.
        val wallet = when {
            Regex("(?i)(?:paid (?:with|via)|payment method|付款方式)\\s*[:：]?\\s*ShopeePay").containsMatchIn(raw) -> "ShopeePay"
            Regex("(?i)(?:paid (?:with|via)|payment method|付款方式)\\s*[:：]?\\s*(?:TNG|Touch[ 'n]*Go)").containsMatchIn(raw) -> "TNG eWallet"
            else -> null
        }
        val references = reference.findAll(raw).map { it.groupValues[1] }.distinct().toList()
        val twentyFourHourTimes = time.findAll(raw).mapNotNull { match -> runCatching {
            LocalTime.parse(match.groupValues[1]).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        }.getOrNull() }.distinct().toList()
        val twelveHourTimes = twelveHourTime.findAll(raw).mapNotNull { match -> runCatching {
            val clock = match.groupValues[1].split(":")
            val hour = clock[0].toInt()
            require(hour in 1..12)
            LocalTime.of(hour % 12 + if (match.groupValues[2].uppercase(Locale.ROOT) == "PM") 12 else 0,
                clock[1].toInt(), clock.getOrNull(2)?.toInt() ?: 0).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        }.getOrNull() }.toList()
        val times = (twentyFourHourTimes + twelveHourTimes).distinct()
        return ParsedReceipt(amount, candidates, currencies.singleOrNull() ?: "MYR", dates.singleOrNull(), wallet, warnings,
            references.singleOrNull(), times.singleOrNull())
    }

    private fun normalize(value: String) = BigDecimal(value.replace(",", "")).setScale(2).toPlainString()
    private fun validDate(value: String, pattern: String): String? = runCatching {
        LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)).toString()
    }.getOrNull()
}

object Csv {
    /** Neutralize spreadsheet formulas in user-controlled fields. */
    fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
