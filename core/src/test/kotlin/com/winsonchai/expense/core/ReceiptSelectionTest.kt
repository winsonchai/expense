package com.winsonchai.expense.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ReceiptSelectionTest {
    @Test fun selectedAmountPreservesExactValueAndRejectsAmbiguity() {
        assertEquals("1234.50", ReceiptSelection.amount("RM 1,234.50", "MYR"))
        assertEquals("12", ReceiptSelection.amount("12", "MYR"))
        assertNull(ReceiptSelection.amount("12.50 20.00", "MYR"))
        assertNull(ReceiptSelection.amount("-12.50", "MYR"))
        assertNull(ReceiptSelection.amount("0.00", "MYR"))
        assertNull(ReceiptSelection.amount("1,23.00", "MYR"))
        assertNull(ReceiptSelection.amount("1.001", "MYR"))
        assertNull(ReceiptSelection.amount("USD 12.50", "MYR"))
        assertNull(ReceiptSelection.amount("99999999999999999999.00", "MYR"))
    }
    @Test fun selectedReferenceAndDateAreNotInferredFromOtherFields() {
        assertEquals("INV-00123", ReceiptSelection.receiptNumber("Receipt No: INV-00123"))
        assertEquals("000123", ReceiptSelection.receiptNumber("000123"))
        assertEquals("2026-11-22", ReceiptSelection.date("22/11/2026"))
        assertNull(ReceiptSelection.date("31/02/2026"))
        assertNull(ReceiptSelection.date("2026-11-22 2026-11-23"))
    }
    @Test fun previewMappingIncludesBothKindsOfLetterboxing() {
        val portrait = ReceiptFit.inside(100, 200, 400f, 400f)
        assertEquals(2f, portrait.scale, 0f)
        assertEquals(100f, portrait.left, 0f)
        assertEquals(0f, portrait.top, 0f)
        val landscape = ReceiptFit.inside(200, 100, 400f, 400f)
        assertEquals(0f, landscape.left, 0f)
        assertEquals(100f, landscape.top, 0f)
    }
}
