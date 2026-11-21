package com.winsonchai.expense.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
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
    val warnings: List<String> = emptyList()
)

/** Deliberately conservative: a receipt is a draft until the user reviews it. */
object ReceiptParser {
    private val number = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}"
    private val money = Regex("(?i)(?:RM|MYR|SGD|USD|AUD|EUR|CNY|THB|INR|IDR|JPY|BHD|KWD)\\s*($number)(?!\\d)")
    private val total = Regex("(?i)^\\s*(?:grand total|total paid|amount paid|payment amount|jumlah bayaran|jumlah|总金额|实付|总额|total|amount)\\s*[:：]?\\s*(?:(?:RM|MYR|SGD|USD|AUD|EUR|CNY|THB|INR|IDR|JPY|BHD|KWD)\\s*)?($number)\\s*$")
    private val code = Regex("(?i)(?<![a-z])(?:MYR|SGD|USD|AUD|EUR|CNY|THB|INR|IDR|JPY|BHD|KWD|RM)(?![a-z])")
    private val isoDate = Regex("(?<!\\d)(\\d{4}-\\d{2}-\\d{2})(?!\\d)")
    private val localDate = Regex("(?<!\\d)(\\d{2}/\\d{2}/\\d{4})(?!\\d)")

    fun parse(raw: String): ParsedReceipt {
        val warnings = mutableListOf<String>()
        if (raw.isBlank()) warnings += "没有识别到文字，请手动填写。"
        if (raw.trim().matches(Regex("https?://\\S+", RegexOption.IGNORE_CASE)))
            warnings += "这是网页链接，请返回钱包分享收据图片或交易文字。"
        val currencies = code.findAll(raw).map { if (it.value.equals("RM", true)) "MYR" else it.value.uppercase() }.distinct().toList()
        if (currencies.size != 1) warnings += if (currencies.isEmpty()) "未识别币种，暂用 MYR，请核对。" else "发现多个币种，请手动核对币种及金额。"
        val labelled = raw.lineSequence().mapNotNull { total.matchEntire(it)?.groupValues?.get(1) }.map(::normalize).distinct().toList()
        val candidates = (labelled + money.findAll(raw).map { normalize(it.groupValues[1]) }).distinct()
        val amount = if (currencies.size > 1) null else labelled.singleOrNull() ?: candidates.singleOrNull()
        if (amount == null) warnings += "金额缺失或有多个候选，请核对总付款额；不要使用余额。"
        val dates = (isoDate.findAll(raw).mapNotNull { validDate(it.value, "uuuu-MM-dd") } +
            localDate.findAll(raw).mapNotNull { validDate(it.value, "dd/MM/uuuu") }).distinct().toList()
        if (dates.size != 1) warnings += "交易日期不明确，请手动填写，勿用截图日期。"
        if (Regex("(?i)refund|refunded|failed|pending|unsuccessful|top[ -]?up|reload|transfer|repayment|退款|失败|处理中|充值|转账|还款").containsMatchIn(raw))
            warnings += "凭证可能涉及退款、失败、处理中或资金转移。只有实际消费才保存为支出。"
        // Platform branding alone never assigns a payment account.
        val wallet = when {
            Regex("(?i)(?:paid (?:with|via)|payment method|付款方式)\\s*[:：]?\\s*ShopeePay").containsMatchIn(raw) -> "ShopeePay"
            Regex("(?i)(?:paid (?:with|via)|payment method|付款方式)\\s*[:：]?\\s*(?:TNG|Touch[ 'n]*Go)").containsMatchIn(raw) -> "TNG eWallet"
            else -> null
        }
        return ParsedReceipt(amount, candidates, currencies.singleOrNull() ?: "MYR", dates.singleOrNull(), wallet, warnings)
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
