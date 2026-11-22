package com.winsonchai.expense.core

import org.junit.Assert.*
import org.junit.Test

class ReceiptAssignmentTest {
    @Test fun suggestionsUseSelectedTextAndLabelsWithoutTreatingEveryNumberAsMoney() {
        assertEquals(ReceiptField.AMOUNT, ReceiptAssignment.suggest("28.50"))
        assertEquals(ReceiptField.AMOUNT, ReceiptAssignment.suggest("28", "TOTAL 28"))
        assertEquals(ReceiptField.RECEIPT_NUMBER, ReceiptAssignment.suggest("000123", "Receipt No: 000123"))
        assertEquals(ReceiptField.RECEIPT_NUMBER, ReceiptAssignment.suggest("28.50", "Invoice No: 28.50"))
        assertEquals(ReceiptField.RECEIPT_NUMBER, ReceiptAssignment.suggest("INV-00123"))
        assertEquals(ReceiptField.DATE, ReceiptAssignment.suggest("22/11/2026"))
        assertEquals(ReceiptField.TIME, ReceiptAssignment.suggest("12:32 PM"))
        assertEquals(ReceiptField.MERCHANT, ReceiptAssignment.suggest("Example Cafe", "Merchant: Example Cafe"))
        assertEquals(ReceiptField.OTHER, ReceiptAssignment.suggest("000123"))
        assertEquals(ReceiptField.OTHER, ReceiptAssignment.suggest("Table"))
        assertEquals(ReceiptField.OTHER, ReceiptAssignment.suggest(""))
    }

    @Test fun overridingTypeUsesTheEnteredValueAndPreservesReferenceZeroes() {
        assertEquals("000123", ReceiptAssignment.normalize(ReceiptField.RECEIPT_NUMBER, "000123", "MYR"))
        assertEquals("28.50", ReceiptAssignment.normalize(ReceiptField.RECEIPT_NUMBER, "28.50", "MYR"))
        assertEquals("29.50", ReceiptAssignment.normalize(ReceiptField.AMOUNT, "29.50", "MYR"))
        assertEquals("2026-11-22", ReceiptAssignment.normalize(ReceiptField.DATE, "22/11/2026", "MYR"))
        assertEquals("12:32:00", ReceiptAssignment.normalize(ReceiptField.TIME, "12:32 PM", "MYR"))
        assertNull(ReceiptAssignment.normalize(ReceiptField.AMOUNT, "not money", "MYR"))
        assertNull(ReceiptAssignment.normalize(ReceiptField.AMOUNT, "USD 28.50", "MYR"))
        assertNull(ReceiptAssignment.normalize(ReceiptField.DATE, "31/02/2026", "MYR"))
        assertNull(ReceiptAssignment.normalize(ReceiptField.TIME, "25:90", "MYR"))
        assertNull(ReceiptAssignment.normalize(ReceiptField.OTHER, "  ", "MYR"))
    }

    @Test fun customFieldsAppendToNotesWithoutLosingExistingContent() {
        assertEquals("Lunch\nTable number: 07", ReceiptAssignment.appendNote("Lunch", "Table number", "07"))
        assertEquals("Tax: 1.50", ReceiptAssignment.appendNote("", " Tax ", " 1.50 "))
    }
}
