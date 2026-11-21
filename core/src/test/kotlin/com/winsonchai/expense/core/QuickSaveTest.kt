package com.winsonchai.expense.core

import org.junit.Assert.*
import org.junit.Test

class QuickSaveTest {
    private val receipt = "Total paid: MYR 12.50\n2026-11-21 13:42:09\nReceipt No.: INV-123"

    @Test fun optInIsRequiredAndCanBeTurnedOff() {
        val parsed = ReceiptParser.parse(receipt)
        assertFalse(QuickSave.eligible(false, parsed, false))
        assertTrue(QuickSave.eligible(true, parsed, false))
    }
    @Test fun duplicateAndRiskyOrIncompleteReceiptsRemainDrafts() {
        assertFalse(QuickSave.eligible(true, ReceiptParser.parse(receipt), true))
        for (input in listOf("Total: MYR 10.00", "$receipt\nPending", "$receipt\nTop-up", "$receipt\nUSD 1.00", "MYR 1.00\nMYR 2.00\n2026-11-21")) {
            assertFalse(input, QuickSave.eligible(true, ReceiptParser.parse(input), false))
        }
    }
    @Test fun optionalReferenceAndTimeDoNotPreventQuickSave() {
        val parsed = ReceiptParser.parse("Total: RM 15.00\n2026-11-21")
        assertNull(parsed.receiptNo)
        assertNull(parsed.transactionTime)
        assertTrue(QuickSave.eligible(true, parsed, false))
    }
    @Test fun referenceAndTwentyFourHourTimeAreExtracted() {
        val parsed = ReceiptParser.parse(receipt)
        assertEquals("INV-123", parsed.receiptNo)
        assertEquals("13:42:09", parsed.transactionTime)
        assertEquals("09:05:00", ReceiptParser.parse("Time: 09:05").transactionTime)
    }
    @Test fun twelveHourTimesAndRefundSignsAreHandled() {
        assertEquals("00:00:00", ReceiptParser.parse("Time: 12:00 AM").transactionTime)
        assertEquals("12:00:00", ReceiptParser.parse("Time: 12:00 PM").transactionTime)
        assertNull(ReceiptParser.parse("Time: 00:30 AM").transactionTime)
        assertFalse(QuickSave.eligible(true, ReceiptParser.parse("Total: -RM 10.00\n2026-11-21"), false))
        assertFalse(QuickSave.eligible(true, ReceiptParser.parse("$receipt\nDeclined"), false))
    }
    @Test fun conflictingReferencesAndTimesAreNotGuessed() {
        assertNull(ReceiptParser.parse("Receipt #: ABC\nInvoice No: DEF").receiptNo)
        assertNull(ReceiptParser.parse("09:05\n10:30").transactionTime)
        assertEquals("21:05:00", ReceiptParser.parse("Time: 09:05 PM").transactionTime)
        assertNull(ReceiptParser.parse("Time: 25:10").transactionTime)
        assertNull(ReceiptParser.parse("Card: 1234").receiptNo)
    }
}
