package com.winsonchai.expense.core

import java.time.LocalDate

/** Automatic saving requires opt-in and complete, unambiguous required fields. */
object QuickSave {
    fun eligible(enabled: Boolean, receipt: ParsedReceipt, duplicate: Boolean): Boolean =
        enabled && !duplicate && receipt.warnings.isEmpty() && receipt.amount != null &&
            Money.minorUnits(receipt.amount, receipt.currency) != null &&
            receipt.date?.let { runCatching { LocalDate.parse(it) }.isSuccess } == true
}
