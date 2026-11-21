package com.winsonchai.expense.core

import org.junit.Assert.*
import org.junit.Test

class ReceiptParserTest {
    @Test fun totalWinsOverBalanceAndDiscount() {
        val result = ReceiptParser.parse("Total paid: RM 12.50\nBalance RM 300.00\nDiscount RM 2.00\n21/11/2026")
        assertEquals("12.50", result.amount)
        assertEquals("2026-11-21", result.date)
    }
    @Test fun ambiguousAmountsStayEmpty() {
        assertNull(ReceiptParser.parse("RM 10.00\nRM 20.00").amount)
        assertNull(ReceiptParser.parse("Total: RM 10.00\nTotal: RM 20.00").amount)
    }
    @Test fun currencyAndDateAmbiguityNeedReview() {
        val result = ReceiptParser.parse("Total: MYR 10.00\nUSD 2.00\n2026-02-30")
        assertNull(result.amount)
        assertNull(result.date)
        assertTrue(result.warnings.size >= 2)
    }
    @Test fun noBrandBasedAccountAssignment() {
        assertNull(ReceiptParser.parse("ShopeePay\nTotal: RM 1.00").suggestedWallet)
        assertEquals("ShopeePay", ReceiptParser.parse("Paid via ShopeePay\nRM 1.00").suggestedWallet)
    }
    @Test fun failedAndTransferReceiptsAreFlagged() {
        for (status in listOf("FAILED", "pending", "top-up", "transfer", "退款"))
            assertTrue(ReceiptParser.parse("$status\nRM 10.00").warnings.any { it.contains("实际消费") })
    }
    @Test fun preciseMoneyRejectsFractionsAndOverflow() {
        assertEquals(123456L, Money.minorUnits("1234.56", "MYR"))
        assertNull(Money.minorUnits("1.001", "MYR"))
        assertNull(Money.minorUnits("0", "MYR"))
        assertNull(Money.minorUnits("-1", "MYR"))
        assertNull(Money.minorUnits("99999999999999999999999", "MYR"))
        assertEquals(12L, Money.minorUnits("12", "JPY"))
        assertEquals(1234L, Money.minorUnits("1.234", "KWD"))
    }
    @Test fun csvEscapesQuotesAndFormulas() {
        assertEquals("\"'=HYPERLINK(\"\"bad\"\")\"", Csv.cell("=HYPERLINK(\"bad\")"))
        assertEquals("\"two, parts\"", Csv.cell("two, parts"))
    }
}
