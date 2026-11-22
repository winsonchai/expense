package com.winsonchai.expense.core

import org.junit.Assert.*
import org.junit.Test

class ExpenseCsvTest {
    private val id = "d5cc2b04-602a-44a1-8e62-2967f211bbef"
    private fun record() = CsvExpense(id, "2026-11-22", "13:31:00", "0000123", 2850, "MYR",
        "Food & drinks", "Example Cafe", "Credit card", CsvAccount("CIMB", "Personal", "0012"), "Lunch", "Manual")
    private fun file(vararg records: CsvExpense) = "\uFEFF" + ExpenseCsv.headers.joinToString(",") + "\r\n" + records.joinToString("\r\n", transform = ExpenseCsv::exportRow)

    @Test fun legacyExportRestoresOptionalDefaultsAndUsesHeaderNames() {
        val parsed = ExpenseCsv.parse("\uFEFFmerchant,currency,unknown,amount,date,note\r\nCafe,MYR,ignored,28.50,2026-11-22,\"Lunch, tea\"\r\n")
        assertTrue(parsed.problems.isEmpty())
        val row = parsed.rows.single().expense
        assertEquals(2850L, row.amountMinor)
        assertEquals("", row.time)
        assertEquals("", row.receiptNumber)
        assertEquals("Uncategorized", row.category)
        assertEquals("Unspecified", row.method)
        assertEquals("Lunch, tea", row.note)
        assertEquals("Cafe", row.merchant)
    }

    @Test fun oldElevenColumnExportRestoresNumbersNotesAndPaymentAccounts() {
        val parsed = ExpenseCsv.parse("date,time,receipt_number,amount,currency,category,merchant,payment_method,payment_account,note,source\n" +
            "2026-11-22,13:31,0000123,28.50,MYR,Food & drinks,Cafe,Credit card,CIMB · Personal · 0012,\"line one\nline \"\"two\"\"\",Manual\n")
        assertTrue(parsed.problems.isEmpty())
        val expense = parsed.rows.single().expense
        assertEquals("13:31:00", expense.time)
        assertEquals("0000123", expense.receiptNumber)
        assertEquals(CsvAccount("CIMB", "Personal", "0012"), expense.account)
        assertEquals("line one\nline \"two\"", expense.note)
    }

    @Test fun newExportRoundTripPreservesFormulaLikeTextAndAmbiguousAccountNamesExactly() {
        val input = record().copy(receiptNumber = "+000123", merchant = "=HYPERLINK(\"example\")",
            account = CsvAccount("Bank · Branch", "@Home", "0012"), note = "'=literal\nquoted, \"note\"")
        val encoded = file(input)
        assertTrue(encoded.contains("'=HYPERLINK"))
        val parsed = ExpenseCsv.parse(encoded)
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        assertEquals(input, parsed.rows.single().expense)
        assertEquals("'=literal\nquoted, \"note\"", parsed.rows.single().expense.note)
    }

    @Test fun oldChineseLabelsAndHeadersRemainCompatible() {
        val parsed = ExpenseCsv.parse("日期,金额,币种,类别,商家,付款方式,付款账户,备注,来源\n2026-11-22,28.50,MYR,餐饮,示例餐厅,电子钱包,TNG eWallet · 个人,午餐,手动")
        assertTrue(parsed.problems.isEmpty())
        val row = parsed.rows.single().expense
        assertEquals("Food & drinks", row.category)
        assertEquals("E-wallet", row.method)
        assertEquals("示例餐厅", row.merchant)
        assertEquals("个人", row.account?.nickname)
        assertEquals("Manual", row.source)
    }

    @Test fun subsecondTransactionTimesAreNotTruncatedDuringImport() {
        val input = record().copy(time = "13:31:02.123456789")
        assertEquals(input, ExpenseCsv.parse(file(input)).rows.single().expense)
    }

    @Test fun invalidFinancialDataIsReportedWithoutInventingDefaultsOrRounding() {
        val rows = listOf("2026-02-30,28.50,MYR", "2026-11-22,0,MYR", "2026-11-22,-1,MYR",
            "2026-11-22,1.001,MYR", "2026-11-22,99999999999999999999,MYR", "2026-11-22,2,",
            "2026-11-22,1.01,JPY", "2026-11-22,1.234,KWD")
        val parsed = ExpenseCsv.parse("date,amount,currency\n" + rows.joinToString("\n"))
        assertEquals(8, parsed.totalRows)
        assertEquals(7, parsed.problems.size)
        assertEquals(2, parsed.problems.first().record)
        assertEquals(1234L, parsed.rows.single().expense.amountMinor)
        assertEquals("KWD", parsed.rows.single().expense.currency)
    }

    @Test fun malformedStructureAndMissingRequiredHeadersRejectTheWholeFile() {
        for (text in listOf("", "amount,currency\n1,MYR", "date,date,amount,currency\n2026-11-22,2026-11-22,1,MYR",
            "date,amount,currency\n\"2026-11-22,1,MYR", "date,amount,currency\n\"2026-11-22\"junk,1,MYR",
            "date,amount,currency\n2026-\"11-22,1,MYR")) {
            assertThrows(IllegalArgumentException::class.java) { ExpenseCsv.parse(text) }
        }
        val countMismatch = ExpenseCsv.parse("date,amount,currency\n2026-11-22,1,MYR,unexpected")
        assertTrue(countMismatch.rows.isEmpty())
        assertEquals(1, countMismatch.problems.size)
    }

    @Test fun repeatedLegacyImportsKeepStableIdentityEvenAfterEditingTheImportedExpense() {
        val text = "date,amount,currency\n2026-11-22,28.50,MYR"
        val first = ExpenseCsv.parse(text).rows
        val reordered = ExpenseCsv.parse("currency,date,amount\nMYR,2026-11-22,28.500").rows
        assertEquals(first.single().expense.id, reordered.single().expense.id)
        val selection = ExpenseCsv.select(reordered, setOf(first.single().expense.id), emptySet(), false)
        assertTrue(selection.rows.isEmpty())
        assertEquals(1, selection.existingIds)
    }

    @Test fun possibleDuplicatesAreSkippedByDefaultButCanRepresentRealSeparatePurchases() {
        val parsed = ExpenseCsv.parse("date,amount,currency\n2026-11-22,28.50,MYR\n2026-11-22,28.50,MYR")
        assertEquals(2, parsed.rows.map { it.expense.id }.distinct().size)
        val normal = ExpenseCsv.select(parsed.rows, emptySet(), emptySet(), true)
        assertEquals(1, normal.rows.size)
        assertEquals(1, normal.possibleDuplicates)
        val separate = ExpenseCsv.select(parsed.rows, emptySet(), emptySet(), false)
        assertEquals(2, separate.rows.size)
        val again = ExpenseCsv.select(parsed.rows, separate.rows.map { it.expense.id }.toSet(), emptySet(), false)
        assertEquals(2, again.existingIds)
        assertTrue(again.rows.isEmpty())
        val existingManual = ExpenseCsv.select(parsed.rows, emptySet(), setOf(parsed.rows.first().expense.fingerprint()), true)
        assertEquals(2, existingManual.possibleDuplicates)
    }

    @Test fun duplicateIdsAndInvalidTimesOrAccountsAreVisibleRowErrors() {
        val duplicate = ExpenseCsv.parse(file(record(), record().copy(merchant = "Different")))
        assertEquals(1, duplicate.rows.size)
        assertEquals(1, duplicate.problems.size)
        val bad = ExpenseCsv.parse("date,amount,currency,time,payment_account\n2026-11-22,1,MYR,25:00,\n2026-11-22,1,MYR,,Ambiguous")
        assertEquals(2, bad.problems.size)
        assertTrue(bad.rows.isEmpty())
    }

    @Test fun fileLimitsFailBeforeAnImportCanBePrepared() {
        assertThrows(IllegalArgumentException::class.java) { ExpenseCsv.parse("x".repeat(ExpenseCsv.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) {
            ExpenseCsv.parse("date,amount,currency\n" + "2026-11-22,1,MYR\n".repeat(ExpenseCsv.MAX_ROWS + 1))
        }
    }
}
