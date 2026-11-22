package com.winsonchai.expense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winsonchai.expense.core.ReceiptAssignment
import com.winsonchai.expense.core.ReceiptField
import com.winsonchai.expense.core.ReceiptFit
import com.winsonchai.expense.data.Expense
import com.winsonchai.expense.importing.ReceiptWord
import com.winsonchai.expense.importing.SelectableReceipt
import kotlinx.coroutines.CancellationException

/** Inline and enlarged photos share the same OCR and explicit confirmation flow. */
@Composable
fun ReceiptPhotoSection(model: ExpenseViewModel, expense: Expense, busy: Boolean) {
    var scan by remember(expense.receiptPath) { mutableStateOf<SelectableReceipt?>(null) }
    var error by remember(expense.receiptPath) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var enlarged by rememberSaveable(expense.id) { mutableStateOf(expense.source == "Camera" && expense.status == "DRAFT") }
    var showWords by rememberSaveable(expense.id) { mutableStateOf(false) }
    var pendingText by rememberSaveable(expense.id) { mutableStateOf<String?>(null) }
    var pendingContext by rememberSaveable(expense.id) { mutableStateOf("") }
    var selected by remember(expense.id) { mutableStateOf<ReceiptWord?>(null) }
    var feedback by rememberSaveable(expense.id) { mutableStateOf("") }
    LaunchedEffect(expense.receiptPath, busy, attempt) {
        if (busy || scan != null) return@LaunchedEffect
        error = null
        try { scan = model.importer.selectable(expense.receiptPath!!) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "Could not read the photo. Try again or enter the details manually." }
    }
    val select: (ReceiptWord?) -> Unit = { word ->
        if (!busy) {
            selected = word
            pendingContext = word?.context.orEmpty()
            pendingText = word?.text.orEmpty()
        }
    }
    Text("Original receipt · Tap text to choose a field", style = MaterialTheme.typography.titleSmall)
    val receipt = scan
    if (receipt != null) {
        ReceiptPhoto(receipt, selected, !busy, select, Modifier.fillMaxWidth().height(300.dp))
        Text("Tap photo text, check or change its type and value, then confirm. Nothing changes until you confirm.", style = MaterialTheme.typography.bodySmall)
    } else {
        if (error != null) {
            Text(error!!, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { attempt++ }, enabled = !busy) { Text("Try recognition again") }
        } else {
            if (!busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Preparing the tappable photo…", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { enlarged = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Enlarge photo / detected text") }
    TextButton(onClick = { select(null) }, enabled = !busy) { Text("Enter a field manually") }
    if (enlarged && !busy) Dialog(onDismissRequest = { enlarged = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tap receipt text", style = MaterialTheme.typography.titleLarge)
                Text("Tap text first, then check its type and value. Use detected text for multi-word values.", style = MaterialTheme.typography.bodySmall)
                if (receipt != null) {
                    ReceiptPhoto(receipt, selected, true, select, Modifier.fillMaxWidth().weight(1f))
                    if (receipt.words.isEmpty()) Text("No text detected. Tap the photo to enter a field manually.")
                    else {
                        TextButton(onClick = { showWords = !showWords }) { Text(if (showWords) "Hide detected text" else "Show detected text") }
                        if (showWords) Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                            receipt.words.distinctBy { it.text }.forEach { word ->
                                TextButton(onClick = { select(word) }, modifier = Modifier.fillMaxWidth()) { Text(word.text) }
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
                if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { select(null) }) { Text("Enter a field manually") }
                Button(onClick = { enlarged = false }, modifier = Modifier.fillMaxWidth()) { Text("Done · Review expense") }
            }
        }
    }
    pendingText?.let { text ->
        ReceiptAssignmentDialog(text, pendingContext, expense.currency, onDismiss = { pendingText = null }) { field, value, label ->
            model.edit { when (field) {
                ReceiptField.AMOUNT -> it.copy(amountInput = value)
                ReceiptField.RECEIPT_NUMBER -> it.copy(receiptNo = value)
                ReceiptField.DATE -> it.copy(date = value)
                ReceiptField.TIME -> it.copy(transactionTime = value)
                ReceiptField.MERCHANT -> it.copy(merchant = value)
                ReceiptField.OTHER -> it.copy(note = ReceiptAssignment.appendNote(it.note, label, value))
            } }
            feedback = if (field == ReceiptField.OTHER) "Added to Notes: $label: $value" else "${field.label} filled: $value"
            pendingText = null
        }
    }
}

@Composable
private fun ReceiptPhoto(receipt: SelectableReceipt, selected: ReceiptWord?, enabled: Boolean,
    onSelect: (ReceiptWord?) -> Unit, modifier: Modifier) {
    val currentSelect by rememberUpdatedState(onSelect)
    Box(modifier) {
        Image(receipt.bitmap.asImageBitmap(), "Receipt photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = "Receipt text boxes. Use detected text for accessible text buttons."
            if (enabled) onClick("Enter a field manually") { currentSelect(null); true }
        }.pointerInput(receipt, enabled) {
            if (enabled) detectTapGestures { tap ->
                val fit = ReceiptFit.inside(receipt.bitmap.width, receipt.bitmap.height, size.width.toFloat(), size.height.toFloat())
                val x = (tap.x - fit.left) / fit.scale
                val y = (tap.y - fit.top) / fit.scale
                if (x >= 0 && x <= receipt.bitmap.width && y >= 0 && y <= receipt.bitmap.height) {
                    val word = receipt.words.filter { x >= it.bounds.left && x <= it.bounds.right && y >= it.bounds.top && y <= it.bounds.bottom }
                        .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
                    currentSelect(word)
                }
            }
        }) {
            val fit = ReceiptFit.inside(receipt.bitmap.width, receipt.bitmap.height, size.width, size.height)
            receipt.words.forEach { word ->
                val box = word.bounds
                drawRect(if (word == selected) Color(0xFFFF8F00) else Color(0xFF008577),
                    Offset(fit.left + box.left * fit.scale, fit.top + box.top * fit.scale),
                    Size(box.width() * fit.scale, box.height() * fit.scale), style = Stroke(1.dp.toPx()))
            }
        }
    }
}

@Composable
private fun ReceiptAssignmentDialog(text: String, context: String, currency: String,
    onDismiss: () -> Unit, onConfirm: (ReceiptField, String, String) -> Unit) {
    val suggested = remember(text, context, currency) { ReceiptAssignment.suggest(text, context, currency) }
    var fieldName by rememberSaveable(text, context) { mutableStateOf(suggested.name) }
    val field = ReceiptField.valueOf(fieldName)
    var value by rememberSaveable(text, context) { mutableStateOf(text) }
    var label by rememberSaveable(text, context) { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val normalized = ReceiptAssignment.normalize(field, value, currency)
    val error = when {
        value.isBlank() -> "Enter a value."
        field == ReceiptField.OTHER && label.isBlank() -> "Enter what this data represents."
        normalized == null -> when (field) {
            ReceiptField.AMOUNT -> "Enter a positive amount in $currency, or change the field type."
            ReceiptField.DATE -> "Enter a valid date in YYYY-MM-DD or DD/MM/YYYY format."
            ReceiptField.TIME -> "Enter a valid time such as 14:30 or 2:30 PM."
            else -> "Check the value or choose another field type."
        }
        else -> null
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Check selected text") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (text.isBlank()) "No text selected. Enter the value and choose its type." else "Selected: $text")
                Text("Suggested type: ${suggested.label}. You can change it.", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Field type: ${field.label} ▾") }
                    DropdownMenu(expanded, { expanded = false }) {
                        ReceiptField.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label) }, onClick = { fieldName = option.name; expanded = false })
                        }
                    }
                }
                if (field == ReceiptField.OTHER) {
                    OutlinedTextField(label, { label = it }, label = { Text("What is this data? e.g. Table number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Saved to Notes as your label and value.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value, { value = it }, label = { Text(if (field == ReceiptField.AMOUNT) "Value ($currency)" else "Value") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (field == ReceiptField.AMOUNT) KeyboardType.Decimal else KeyboardType.Text))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (error == null) Text("Will fill ${if (field == ReceiptField.OTHER) "Notes" else field.label}: $normalized", style = MaterialTheme.typography.bodySmall)
                Text("Confirm fills the form. Use Save expense after reviewing the receipt.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { normalized?.let { onConfirm(field, it, label) } }, enabled = error == null) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
