package com.winsonchai.expense.core

/** Only normalize text explicitly selected by the user; never pick among several values. */
object ReceiptSelection {
    private val amount = Regex("(?i)^(?:(RM|MYR|SGD|USD|AUD|EUR|CNY|THB|INR|IDR|JPY|BHD|KWD)\\s*)?((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,3})?)$")

    fun amount(text: String, currency: String): String? {
        val match = amount.matchEntire(text.trim()) ?: return null
        val code = match.groupValues[1].uppercase(java.util.Locale.ROOT).let { if (it == "RM") "MYR" else it }
        if (code.isNotEmpty() && code != currency) return null
        val value = match.groupValues[2].replace(",", "")
        return value.takeIf { Money.minorUnits(it, currency) != null }
    }

    fun receiptNumber(text: String): String = ReceiptParser.parse(text).receiptNo ?: text.trim().take(80)
    fun date(text: String): String? = ReceiptParser.parse(text).date
}

/** Fit an upright OCR image into a preview, including centered letterbox margins. */
data class ReceiptFit(val scale: Float, val left: Float, val top: Float) {
    companion object {
        fun inside(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float): ReceiptFit {
            require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
            val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
            return ReceiptFit(scale, (viewWidth - imageWidth * scale) / 2, (viewHeight - imageHeight * scale) / 2)
        }
    }
}
