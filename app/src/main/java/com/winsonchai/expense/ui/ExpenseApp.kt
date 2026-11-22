package com.winsonchai.expense.ui

import android.graphics.Bitmap
import android.net.Uri
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.winsonchai.expense.core.Money
import com.winsonchai.expense.data.*
import java.math.BigInteger
import java.io.File
import java.time.YearMonth
import java.util.Currency

private val Teal = Color(0xFF006A60)

@Composable
fun ExpenseApp(model: ExpenseViewModel) {
    val context = LocalContext.current
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = pendingCameraUri?.let(Uri::parse)
        pendingCameraUri = null
        uri?.let {
            context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (taken) model.receive(uri = it, fromCamera = true)
            else runCatching { context.contentResolver.delete(it, null, null) }
        }
    }
    val takeReceipt: () -> Unit = {
        if (!model.busy.value && pendingCameraUri == null) {
            runCatching {
                val directory = File(context.cacheDir, "receipt-camera").apply { mkdirs() }
                // Abandoned captures are temporary; never delete an in-flight camera file.
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
                val file = File.createTempFile("receipt-", ".jpg", directory)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.receipt-camera", file)
                pendingCameraUri = uri.toString()
                camera.launch(uri)
            }.onFailure {
                pendingCameraUri?.let { value -> runCatching { context.contentResolver.delete(Uri.parse(value), null, null) } }
                pendingCameraUri = null
                model.message.value = "Unable to open the camera. Try Import receipt or add the details manually."
            }
        }
    }
    val expenses by model.expenses.collectAsStateWithLifecycle()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val editor by model.editor.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val addedAccount by model.addedAccount.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.receive(uri = it) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(model::exportCsv) }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::previewCsv) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); if (model.message.value == it) model.message.value = null } }
    BackHandler(editor != null) { model.close() }
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, secondary = Color(0xFF496563), background = Color(0xFFF7F8FA))) {
        CsvImportDialogs(model)
        if (addedAccount != null) AlertDialog(
            onDismissRequest = model::dismissAccountAdded,
            title = { Text("Account added") },
            text = { Text("Payment account \"$addedAccount\" was saved successfully.") },
            confirmButton = { TextButton(onClick = model::dismissAccountAdded) { Text("OK") } }
        )
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (editor == null) NavigationBar {
                    listOf("Home", "Records", "Stats", "Accounts", "Settings").forEachIndexed { index, title ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text(listOf("⌂", "≡", "▥", "○", "⚙")[index]) }, label = { Text(title) })
                    }
                }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).imePadding().padding(horizontal = 20.dp)) {
                if (editor != null) {
                    Editor(model, editor!!, expenses, accounts, busy)
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Expense", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Personal expenses · Stored on this device", style = MaterialTheme.typography.bodySmall)
                        }
                        if (tab < 3) TextButton(onClick = { export.launch("expense-${YearMonth.now()}.csv") }) { Text("Export CSV") }
                    }
                    Column(Modifier.verticalScroll(rememberScrollState()).weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (tab) {
                            0 -> Home(expenses, accounts, model::open, model::newExpense,
                                { picker.launch(arrayOf("image/*", "application/pdf")) }, takeReceipt)
                            1 -> Records(expenses, accounts, model::open)
                            2 -> Statistics(expenses, accounts)
                            3 -> Accounts(accounts, model)
                            4 -> Settings(model, { csvPicker.launch(arrayOf("*/*")) }, { export.launch("expense-${YearMonth.now()}.csv") })
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Home(expenses: List<Expense>, accounts: List<PaymentAccount>, open: (Expense) -> Unit, add: () -> Unit, import: () -> Unit, scan: () -> Unit) {
    val month = YearMonth.now().toString()
    Card(colors = CardDefaults.cardColors(containerColor = Teal), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$month · Saved expenses this month", color = Color.White)
            val records = expenses.filter { it.status == "SAVED" && it.date.startsWith(month) }
            if (records.isEmpty()) Text("MYR 0.00", color = Color.White, style = MaterialTheme.typography.headlineLarge)
            else records.groupBy { it.currency }.forEach { (currency, items) ->
                Text("$currency ${total(items, currency)}", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
            Text("Drafts are excluded. Each currency is totalled separately.", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = add, modifier = Modifier.weight(1f)) { Text("+ Add expense") }
        OutlinedButton(onClick = import, modifier = Modifier.weight(1f)) { Text("Import receipt") }
    }
    OutlinedButton(onClick = scan, modifier = Modifier.fillMaxWidth()) { Text("Scan receipt") }
    Text("Take a photo and tap its text. Check or change the suggested field type and value, then confirm to fill the form.", style = MaterialTheme.typography.bodySmall)
    Text("After paying, share the receipt from your wallet to Expense. Review and save, or enable Quick save in Settings.", style = MaterialTheme.typography.bodyMedium)
    val drafts = expenses.filter { it.status == "DRAFT" }
    if (drafts.isNotEmpty()) {
        SectionTitle("Drafts to review · ${drafts.size}")
        drafts.forEach { ExpenseRow(it, accounts, open) }
    }
    SectionTitle("Recent expenses")
    val saved = expenses.filter { it.status == "SAVED" }.take(8)
    if (saved.isEmpty()) EmptyState("No expenses yet", "Add your first expense or share a receipt from your wallet.")
    saved.forEach { ExpenseRow(it, accounts, open) }
}

@Composable
private fun Records(expenses: List<Expense>, accounts: List<PaymentAccount>, open: (Expense) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All categories") }
    var accountId by rememberSaveable { mutableStateOf("") }
    SectionTitle("Expense records")
    Field(query, "Search merchant, date, receipt number or notes", { query = it })
    Choice("Category", listOf("All categories") + categories, category, { category = it })
    Choice("Accounts", listOf("" to "All accounts") + accounts.map { it.id to it.label() }, accountId, { accountId = it })
    val filtered = expenses.filter { expense ->
        (category == "All categories" || expense.category == category) && (accountId.isEmpty() || expense.accountId == accountId) &&
            (query.isBlank() || "${expense.date} ${expense.transactionTime} ${expense.receiptNo} ${expense.merchant} ${expense.note}".contains(query, ignoreCase = true))
    }
    if (filtered.isEmpty()) EmptyState("No matching records", "Try different search filters.")
    filtered.forEach { ExpenseRow(it, accounts, open) }
}

@Composable
private fun Statistics(expenses: List<Expense>, accounts: List<PaymentAccount>) {
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    SectionTitle("Spending statistics")
    Field(month, "Month YYYY-MM", { month = it })
    val valid = runCatching { YearMonth.parse(month) }.isSuccess
    if (!valid) { Text("Enter a valid month, for example 2026-11."); return }
    val records = expenses.filter { it.status == "SAVED" && it.date.startsWith("$month-") }
    if (records.isEmpty()) { EmptyState("No expenses this month", "Only saved expenses are included in statistics."); return }
    for ((currency, items) in records.groupBy { it.currency }) {
        SectionTitle("$currency ${total(items, currency)}")
        Text("By category", fontWeight = FontWeight.Bold)
        items.groupBy { it.category }.forEach { (label, group) -> SummaryRow(label, "$currency ${total(group, currency)}") }
        Text("By payment method", fontWeight = FontWeight.Bold)
        items.groupBy { it.method }.forEach { (label, group) -> SummaryRow(label, "$currency ${total(group, currency)}") }
        Text("By payment account", fontWeight = FontWeight.Bold)
        items.groupBy { it.accountId }.forEach { (id, group) ->
            SummaryRow(accounts.find { it.id == id }?.label() ?: "Unspecified account", "$currency ${total(group, currency)}")
        }
    }
}

@Composable
private fun ColumnScope.Editor(model: ExpenseViewModel, expense: Expense, expenses: List<Expense>, accounts: List<PaymentAccount>, busy: Boolean) {
    val quickSave by model.quickSave.collectAsStateWithLifecycle()
    var reviewed by rememberSaveable(expense.id) { mutableStateOf(false) }
    var duplicateAccepted by rememberSaveable(expense.id) { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    val duplicate = expenses.any { expense.receiptHash != null && it.id != expense.id && it.receiptHash == expense.receiptHash }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = model::close, enabled = !busy) { Text("Back") }
        Text(if (expense.status == "DRAFT") "Review expense draft" else "Edit expense", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = { deleteDialog = true }, enabled = !busy) { Text("Delete") }
    }
    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Processing receipt. Please wait…") }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (expense.receiptPath != null) {
            if (expense.mimeType?.startsWith("image/") == true) ReceiptPhotoSection(model, expense, busy)
            else ReceiptPreview(model, expense)
        }
        if (expense.warnings.isNotBlank()) Notice(expense.warnings)
        if (duplicate) {
            Notice("This receipt was imported before. Check the existing record in Home or Records to avoid a duplicate.")
            Check("I checked the existing record and want to save this one too", duplicateAccepted, { duplicateAccepted = it }, !busy)
        }
        Field(expense.amountInput, "Amount (required)", { value -> model.edit { it.copy(amountInput = value) } }, !busy, KeyboardType.Decimal)
        if (expense.candidates.isNotBlank()) {
            Text("Amounts on receipt (may include balance / discounts): ${expense.candidates.replace("|", ", ")}", style = MaterialTheme.typography.bodySmall)
        }
        Choice("Currency", currencies, expense.currency, { value -> model.edit { it.copy(currency = value) } }, !busy)
        Field(expense.date, "Transaction date YYYY-MM-DD (required)", { value -> model.edit { it.copy(date = value) } }, !busy)
        Field(expense.transactionTime, "Transaction time HH:mm:ss (optional)", { value -> model.edit { it.copy(transactionTime = value) } }, !busy)
        Choice("Category", categories, expense.category, { value -> model.edit { it.copy(category = value) } }, !busy)
        Field(expense.merchant, "Merchant", { value -> model.edit { it.copy(merchant = value) } }, !busy)
        Field(expense.receiptNo, "Receipt / Invoice No. (optional)", { value -> model.edit { it.copy(receiptNo = value) } }, !busy)
        Choice("Payment method", paymentMethods, expense.method, { value -> model.edit { it.copy(method = value, accountId = null) } }, !busy)
        val choices = accounts.filter { it.method == expense.method && (it.active || it.id == expense.accountId) }
        Choice("Payment account", listOf("" to "Not selected") + choices.map { it.id to (it.label() + if (it.active) "" else " (inactive)") }, expense.accountId.orEmpty(),
            { value -> model.edit { it.copy(accountId = value.ifEmpty { null }) } }, !busy)
        Field(expense.note, "Notes", { value -> model.edit { it.copy(note = value) } }, !busy)
        Text("Source: ${expense.source}", style = MaterialTheme.typography.bodySmall)
        if (expense.rawText.isNotBlank()) {
            SectionTitle("Original / recognized text")
            Text(expense.rawText, style = MaterialTheme.typography.bodySmall)
        }
        if (expense.source != "Manual" && !quickSave) Check("I checked the amount, currency, date and account. This is a completed purchase, not a top-up, transfer, repayment or refund.", reviewed, { reviewed = it }, !busy)
        if (expense.status == "DRAFT") Text("Changes are kept as a draft. Only saved expenses appear in statistics.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { model.save(reviewed, duplicateAccepted) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save expense") }
        Spacer(Modifier.height(20.dp))
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("Delete this expense?") },
        text = { Text("The expense and its original receipt will be removed from this device.") }, confirmButton = {
            TextButton(onClick = { deleteDialog = false; model.delete(expense) }) { Text("Delete") }
        }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } })
}

@Composable
private fun ReceiptPreview(model: ExpenseViewModel, expense: Expense) {
    val bitmap by produceState<Bitmap?>(null, expense.receiptPath) {
        value = model.importer.preview(expense.receiptPath!!, expense.mimeType.orEmpty())
    }
    Text(if (expense.mimeType == "application/pdf") "Original receipt · PDF first page" else "Original receipt", fontWeight = FontWeight.Bold)
    bitmap?.let { Image(it.asImageBitmap(), "Receipt preview", Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit) }
    if (bitmap == null) Text("Preview is loading or unavailable. Use the receipt text below or enter the details manually.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Settings(model: ExpenseViewModel, importCsv: () -> Unit, exportCsv: () -> Unit) {
    val quickSave by model.quickSave.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val savingAccount by model.savingAccount.collectAsStateWithLifecycle()
    SectionTitle("Move saved records between versions")
    Text("In the old app, use Export CSV and keep the file. Import it here to preview and restore saved expenses and payment accounts. CSV does not include receipt files or unsaved drafts.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = importCsv, enabled = !busy && !savingAccount, modifier = Modifier.fillMaxWidth()) { Text("Import CSV") }
    OutlinedButton(onClick = exportCsv, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Export CSV") }
    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Processing. Please wait…") }
    SectionTitle("Import options")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quick save imported receipts", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Switch(checked = quickSave, onCheckedChange = model::setQuickSave, modifier = Modifier.semantics { contentDescription = "Quick save imported receipts" })
            }
            Text("When enabled, receipts with a clear amount, currency and transaction date are saved automatically. Missing or ambiguous details, duplicates and payment-status warnings keep the receipt as a draft.")
            Text("When disabled, review each imported receipt before saving. You can change this option at any time.")
            Text("Merchant, category and payment account may remain unspecified. Open a saved expense in Records to update them.", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("Receipt and invoice numbers, and transaction times, are optional. They help you distinguish similar expenses without slowing down entry.")
}

@Composable
private fun Accounts(accounts: List<PaymentAccount>, model: ExpenseViewModel) {
    val savingAccount by model.savingAccount.collectAsStateWithLifecycle()
    val addedAccount by model.addedAccount.collectAsStateWithLifecycle()
    var method by rememberSaveable { mutableStateOf("E-wallet") }
    var provider by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var lastFour by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editProvider by rememberSaveable { mutableStateOf("") }
    var editName by rememberSaveable { mutableStateOf("") }
    var editLastFour by rememberSaveable { mutableStateOf("") }
    SectionTitle("Payment account")
    Text("Keep wallet providers, issuing banks and account nicknames. For cards, enter only the last four digits.", style = MaterialTheme.typography.bodySmall)
    accounts.forEach { account ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(account.label(), fontWeight = FontWeight.Bold)
                Text(account.method + if (account.active) "" else " · Inactive")
                Row {
                    TextButton(onClick = {
                        editingId = account.id
                        editProvider = account.provider
                        editName = account.nickname
                        editLastFour = account.lastFour
                    }, enabled = !savingAccount) { Text("Edit") }
                    TextButton(onClick = { model.updateAccount(account.copy(active = !account.active)) }, enabled = !savingAccount) { Text(if (account.active) "Deactivate" else "Activate") }
                }
            }
        }
    }
    SectionTitle("Add account")
    Choice("Payment method", paymentMethods.filter { it != "Unspecified" }, method, { method = it })
    Field(provider, "Provider / bank, e.g. TNG eWallet or CIMB", { provider = it })
    Field(nickname, "Nickname, e.g. Personal or Everyday", { nickname = it })
    if (method in listOf("Credit card", "Debit card", "Prepaid card")) Field(lastFour, "Card last four (optional)", { lastFour = it.take(4) }, keyboardType = KeyboardType.Number)
    Button(onClick = {
        model.addAccount(method, provider, nickname, if (method in listOf("Credit card", "Debit card", "Prepaid card")) lastFour else "")
    }, enabled = !savingAccount && addedAccount == null) { Text(if (savingAccount) "Saving…" else "Add payment account") }
    Text("Deactivated accounts remain linked to past expenses. Uninstalling removes local data. CSV is for analysis; full backup and restore are not available yet.", style = MaterialTheme.typography.bodySmall)
    accounts.find { it.id == editingId }?.let { account ->
        val isCard = account.method in listOf("Credit card", "Debit card", "Prepaid card")
        val error = when {
            editProvider.isBlank() || editName.isBlank() -> "Enter a provider or bank and an account nickname."
            isCard && editLastFour.isNotBlank() && !Regex("\\d{4}").matches(editLastFour) -> "Enter exactly four digits, or leave this field empty."
            else -> null
        }
        AlertDialog(
            onDismissRequest = { if (!savingAccount) editingId = null },
            title = { Text("Edit payment account") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Payment method: ${account.method}")
                    Field(editProvider, "Provider / bank", { editProvider = it }, !savingAccount)
                    Field(editName, "Account nickname", { editName = it }, !savingAccount)
                    if (isCard) Field(editLastFour, "Card last four (optional)", { editLastFour = it.take(4) }, !savingAccount, KeyboardType.Number)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    model.updateAccount(account.copy(provider = editProvider, nickname = editName,
                        lastFour = if (isCard) editLastFour else "")) { editingId = null }
                }, enabled = !savingAccount && error == null) { Text(if (savingAccount) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(onClick = { editingId = null }, enabled = !savingAccount) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ExpenseRow(expense: Expense, accounts: List<PaymentAccount>, open: (Expense) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { open(expense) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(expense.merchant.ifBlank { expense.category }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${expense.currency} ${expense.amountMinor?.let { Money.display(it, expense.currency) } ?: expense.amountInput.ifBlank { "Needs review" }}", fontWeight = FontWeight.Bold)
            }
            if (expense.receiptNo.isNotBlank()) Text("Receipt: ${expense.receiptNo}", style = MaterialTheme.typography.bodySmall)
            Text("${expense.date.ifBlank { "Date needed" }} ${expense.transactionTime} · ${expense.category}${if (expense.status == "DRAFT") " · Draft" else ""}", style = MaterialTheme.typography.bodySmall)
            Text(accounts.find { it.id == expense.accountId }?.label() ?: expense.method, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
@Composable private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)); Text(value) }
}
@Composable private fun Notice(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF2CC)), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun Check(label: String, checked: Boolean, changed: (Boolean) -> Unit, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, changed, enabled = enabled); Text(label, style = MaterialTheme.typography.bodySmall) }
}
@Composable private fun Field(value: String, label: String, changed: (String) -> Unit, enabled: Boolean = true, keyboardType: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, changed, label = { Text(label) }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType), singleLine = true)
}
@Composable private fun Choice(label: String, options: List<String>, selected: String, changed: (String) -> Unit, enabled: Boolean = true) {
    Choice(label, options.map { it to it }, selected, changed, enabled, Unit)
}
@Composable private fun Choice(label: String, options: List<Pair<String, String>>, selected: String, changed: (String) -> Unit, enabled: Boolean = true, marker: Unit = Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${options.find { it.first == selected }?.second ?: selected}", Modifier.weight(1f)); Text("▾")
        }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (id, text) ->
            DropdownMenuItem(text = { Text(text) }, onClick = { changed(id); expanded = false })
        } }
    }
}
private fun total(expenses: List<Expense>, currency: String): String {
    val sum = expenses.fold(BigInteger.ZERO) { acc, expense -> acc + BigInteger.valueOf(expense.amountMinor ?: 0) }
    return sum.toBigDecimal().movePointLeft(Currency.getInstance(currency).defaultFractionDigits).toPlainString()
}
