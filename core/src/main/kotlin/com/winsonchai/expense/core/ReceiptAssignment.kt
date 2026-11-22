package com.winsonchai.expense.core

enum class ReceiptField(val label: String) {
    AMOUNT("Amount"), RECEIPT_NUMBER("Receipt No."), DATE("Date"), TIME("Time"),
    MERCHANT("Merchant"), OTHER("Other")
}

/** Suggestions never change an expense. The user must confirm a field and value. */
object ReceiptAssignment {
    private val referenceLabel = Regex("(?i)receipt|invoice|reference|transaction|\\bref\\b|收据|发票|交易编号")
    private val amountLabel = Regex("(?i)\\b(?:total|amount|price|paid|subtotal|balance|tax)\\b|金额|总额|实付")
    private val merchantLabel = Regex("(?i)\\b(?:merchant|store|seller)\\b|商家|店名")
    private val currencyCode = Regex("(?i)\\b(?:RM|MYR|SGD|USD|AUD|EUR|CNY|THB|INR|IDR|JPY|BHD|KWD)\\b")

    fun suggest(text: String, context: String = text, currency: String = "MYR"): ReceiptField {
        val value = text.trim()
        if (ReceiptSelection.date(value) != null) return ReceiptField.DATE
        if (ReceiptParser.parse(value).transactionTime != null) return ReceiptField.TIME
        if (value.any(Char::isDigit) && referenceLabel.containsMatchIn(context)) return ReceiptField.RECEIPT_NUMBER
        if (ReceiptSelection.amount(value, currency) != null &&
            (value.contains('.') || currencyCode.containsMatchIn(value) || amountLabel.containsMatchIn(context))) return ReceiptField.AMOUNT
        if (value.any(Char::isLetter) && value.any(Char::isDigit) &&
            Regex("[\\p{L}\\d._/\\-]+").matches(value) && !currencyCode.containsMatchIn(value)) return ReceiptField.RECEIPT_NUMBER
        if (merchantLabel.containsMatchIn(context) && value.any(Char::isLetter)) return ReceiptField.MERCHANT
        return ReceiptField.OTHER
    }

    fun normalize(field: ReceiptField, text: String, currency: String): String? = when (field) {
        ReceiptField.AMOUNT -> ReceiptSelection.amount(text, currency)
        ReceiptField.RECEIPT_NUMBER -> ReceiptSelection.receiptNumber(text).takeIf(String::isNotBlank)
        ReceiptField.DATE -> ReceiptSelection.date(text)
        ReceiptField.TIME -> ReceiptParser.parse(text).transactionTime
        ReceiptField.MERCHANT, ReceiptField.OTHER -> text.trim().takeIf(String::isNotBlank)
    }

    fun appendNote(note: String, label: String, value: String): String =
        listOf(note.trimEnd(), "${label.trim()}: ${value.trim()}").filter(String::isNotBlank).joinToString("\n")
}
