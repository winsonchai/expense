package com.winsonchai.expense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winsonchai.expense.core.ReceiptFit
import com.winsonchai.expense.core.ReceiptSelection
import com.winsonchai.expense.data.Expense
import com.winsonchai.expense.importing.ReceiptWord
import com.winsonchai.expense.importing.SelectableReceipt
import kotlinx.coroutines.CancellationException

@Composable
fun ReceiptSelectionDialog(model: ExpenseViewModel, expense: Expense, onDismiss: () -> Unit) {
    var scan by remember(expense.receiptPath) { mutableStateOf<SelectableReceipt?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var target by rememberSaveable { mutableStateOf("Amount") }
    var feedback by rememberSaveable { mutableStateOf("") }
    var showWords by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ReceiptWord?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(expense.receiptPath, attempt) {
        error = null
        try { scan = model.importer.selectable(expense.receiptPath!!) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "Could not read the photo. Try again or enter the details in the form." }
    }
    fun fill(word: ReceiptWord) {
        selected = word
        val value = when (target) {
            "Amount" -> ReceiptSelection.amount(word.text, expense.currency)
            "Receipt No." -> ReceiptSelection.receiptNumber(word.text).takeIf { it.isNotBlank() }
            else -> ReceiptSelection.date(word.text)
        }
        if (value == null) {
            feedback = "\"${word.text}\" is not a valid ${target.lowercase()}. Select another box or edit the field below."
            return
        }
        model.edit { when (target) {
            "Amount" -> it.copy(amountInput = value)
            "Receipt No." -> it.copy(receiptNo = value)
            else -> it.copy(date = value)
        } }
        feedback = "$target filled: $value"
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tap receipt to fill fields", style = MaterialTheme.typography.titleLarge)
                Text("Choose a field, then tap its text box on the photo. Check the currency in the form before saving.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Amount", "Receipt No.", "Date").forEach { field ->
                        FilterChip(selected = target == field, onClick = { target = field; feedback = "" }, label = { Text(field) })
                    }
                }
                val current = when (target) { "Amount" -> expense.amountInput; "Receipt No." -> expense.receiptNo; else -> expense.date }
                OutlinedTextField(current, { value -> model.edit { when (target) {
                    "Amount" -> it.copy(amountInput = value)
                    "Receipt No." -> it.copy(receiptNo = value)
                    else -> it.copy(date = value)
                } } }, label = { Text(if (target == "Date") "Date YYYY-MM-DD" else "$target (${expense.currency.takeIf { target == "Amount" } ?: "editable"})") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
                val receipt = scan
                if (receipt != null) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Image(receipt.bitmap.asImageBitmap(), "Frozen receipt photo. Use detected text buttons below if the boxes are too small.",
                            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        Canvas(Modifier.fillMaxSize().pointerInput(receipt, target, expense.currency) {
                            detectTapGestures { tap ->
                                val fit = ReceiptFit.inside(receipt.bitmap.width, receipt.bitmap.height, size.width.toFloat(), size.height.toFloat())
                                val x = (tap.x - fit.left) / fit.scale
                                val y = (tap.y - fit.top) / fit.scale
                                // Hit-test in image coordinates with the same transform used to draw boxes.
                                receipt.words.filter { x >= it.bounds.left && x <= it.bounds.right && y >= it.bounds.top && y <= it.bounds.bottom }
                                    .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }?.let(::fill)
                            }
                        }) {
                            val fit = ReceiptFit.inside(receipt.bitmap.width, receipt.bitmap.height, size.width, size.height)
                            receipt.words.forEach { word ->
                                val box = word.bounds
                                drawRect(if (word == selected) Color(0xFFFF8F00) else Color(0xFF008577),
                                    Offset(fit.left + box.left * fit.scale, fit.top + box.top * fit.scale),
                                    Size(box.width() * fit.scale, box.height() * fit.scale), style = Stroke(2.dp.toPx()))
                            }
                        }
                    }
                    if (receipt.words.isEmpty()) Text("No text detected. Take a clearer photo or fill in the form manually.")
                    else {
                        TextButton(onClick = { showWords = !showWords }) { Text(if (showWords) "Hide detected text" else "Boxes too small? Show detected text") }
                        if (showWords) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                            receipt.words.distinctBy { it.text }.forEach { word ->
                                TextButton(onClick = { fill(word) }, modifier = Modifier.fillMaxWidth()) { Text(word.text) }
                            }
                        }
                    }
                } else if (error != null) {
                    Text(error!!, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { attempt++ }) { Text("Try recognition again") }
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Reading the photo on this device…", modifier = Modifier.weight(1f))
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done · Review expense") }
            }
        }
    }
}
