package com.winsonchai.expense.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.winsonchai.expense.core.Money
import com.winsonchai.expense.core.ReceiptParser
import com.winsonchai.expense.core.QuickSave
import com.winsonchai.expense.core.ExpenseCsv
import com.winsonchai.expense.data.*
import com.winsonchai.expense.importing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class ExpenseViewModel(app: Application, private val state: SavedStateHandle) : AndroidViewModel(app) {
    private val database = ExpenseDatabase.get(app)
    private val dao = database.dao()
    private val csvImporter = CsvLedgerImporter(database)
    val csvImport = MutableStateFlow<CsvImportPreview?>(null)
    val csvResult = MutableStateFlow<String?>(null)
    val expenses = dao.observeExpenses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val accounts = dao.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categoryNames = dao.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val editor = MutableStateFlow<Expense?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val savingAccount = MutableStateFlow(false)
    val addedAccount = state.getStateFlow<String?>("addedAccount", null)
    private val preferences = app.getSharedPreferences("expense_settings", 0)
    val quickSave = MutableStateFlow(preferences.getBoolean("quick_save", false))
    fun setQuickSave(enabled: Boolean) {
        quickSave.value = enabled
        preferences.edit().putBoolean("quick_save", enabled).apply()
    }
    val importer = ReceiptImporter(app)
    private val writes = Mutex()

    init {
        work {
            dao.seedAccounts()
            if (editor.value == null && !busy.value) state.get<String>("editingId")?.let { id -> editor.value = dao.expense(id) }
        }
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message.value = e.message ?: "The operation failed. Please try again." }
    }

    fun saveCategory(original: String?, input: String) {
        val name = input.trim()
        if (name.isEmpty() || name.length > 80 || name.equals("Uncategorized", ignoreCase = true)) {
            message.value = "Enter a category name between 1 and 80 characters. Uncategorized is reserved."
            return
        }
        changeCategory(original, name)
    }

    fun deleteCategory(name: String) = changeCategory(name, null)

    private fun changeCategory(original: String?, replacement: String?) {
        if (busy.value || editor.value != null || original == replacement || original == "Uncategorized") return
        busy.value = true
        work {
            try {
                writes.withLock {
                    database.withTransaction {
                        val names = dao.allCategoryNames()
                        if (replacement != null && names.any { it != original && it.equals(replacement, ignoreCase = true) }) {
                            message.value = "A category with that name already exists."
                            return@withTransaction
                        }
                        if (replacement != null) dao.insertCategory(ExpenseCategory(replacement))
                        if (original != null) {
                            dao.replaceCategoryOnExpenses(original, replacement ?: "Uncategorized")
                            dao.deleteCategory(original)
                        }
                    }
                }
            } finally {
                busy.value = false
            }
        }
    }

    fun open(expense: Expense) {
        if (busy.value) return
        editor.value = expense
        state["editingId"] = expense.id
    }
    fun newExpense() {
        if (busy.value) return
        val expense = Expense(date = LocalDate.now().toString(), transactionTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")))
        open(expense)
        work { writes.withLock { dao.put(expense) } }
    }
    fun close() {
        if (!busy.value) { editor.value = null; state["editingId"] = null }
    }
    fun edit(transform: (Expense) -> Expense) {
        if (busy.value) return
        val updated = editor.value?.let(transform) ?: return
        editor.value = updated
        if (updated.status == "DRAFT") work { writes.withLock { dao.put(updated) } }
    }
    fun duplicates(expense: Expense): List<Expense> = expenses.value.filter {
        expense.receiptHash != null && it.receiptHash == expense.receiptHash && it.id != expense.id
    }

    fun save(reviewed: Boolean, duplicateAccepted: Boolean) {
        val current = editor.value ?: return
        if (busy.value) return
        val amount = Money.minorUnits(current.amountInput, current.currency)
        val validDate = runCatching { LocalDate.parse(current.date) }.isSuccess
        val error = when {
            current.transactionTime.isNotBlank() && runCatching { LocalTime.parse(current.transactionTime) }.isFailure -> "Enter a valid time in HH:mm or HH:mm:ss format, or leave it empty."
            !validDate -> "Enter a valid date in YYYY-MM-DD format."
            amount == null -> "Enter a positive amount with the precision required by the selected currency."
            current.source != "Manual" && !reviewed && !quickSave.value -> "Check the receipt and confirm this is a completed purchase."
            duplicates(current).isNotEmpty() && !duplicateAccepted -> "This receipt was imported before. Confirm whether to save it again."
            current.accountId != null && accounts.value.none { it.id == current.accountId && it.method == current.method } -> "The payment account does not match the payment method. Select it again."
            else -> null
        }
        if (error != null) { message.value = error; return }
        busy.value = true
        work {
            try {
                writes.withLock { dao.put(current.copy(status = "SAVED", amountMinor = amount)) }
                editor.value = null
                state["editingId"] = null
                message.value = "Expense saved."
            } finally { busy.value = false }
        }
    }
    fun delete(expense: Expense) {
        if (busy.value) return
        busy.value = true
        work {
            try {
                writes.withLock { dao.delete(expense.id) }
                withContext(Dispatchers.IO) { expense.receiptPath?.let { File(it).delete() } }
                if (editor.value?.id == expense.id) { editor.value = null; state["editingId"] = null }
            } finally { busy.value = false }
        }
    }

    fun receive(uri: Uri? = null, text: String? = null, fromCamera: Boolean = false) {
        if (busy.value) {
            message.value = if (fromCamera) "Another receipt is being processed. Take the photo again when it finishes."
                else "Another receipt is being processed. Share this one again when it finishes."
            if (fromCamera && uri != null) work { withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
            } }
            return
        }
        busy.value = true
        work {
            var draft = Expense(source = if (fromCamera) "Camera" else if (uri == null) "Shared text" else "Shared file", warnings = "Import is incomplete. Check the details or import the receipt again.")
            try {
                writes.withLock { dao.put(draft) }
                editor.value = draft
                state["editingId"] = draft.id
                val imported = if (uri != null) importer.copy(uri) else importer.text(text.orEmpty())
                draft = draft.copy(receiptPath = imported.path, receiptHash = imported.hash, mimeType = imported.mime,
                    rawText = imported.text, source = if (fromCamera) "Camera" else when (imported.mime) { "text/plain" -> "Shared text"; "application/pdf" -> "Shared PDF"; else -> "Shared image" })
                // Retain the private original even if PDF decoding or OCR fails, or the process is stopped.
                writes.withLock { dao.put(draft) }
                editor.value = draft
                val raw = importer.recognize(imported)
                val parsed = withContext(Dispatchers.Default) { ReceiptParser.parse(raw) }
                val account = parsed.suggestedWallet?.let { provider -> accounts.value.filter { it.provider == provider && it.active }.singleOrNull() }
                draft = draft.copy(rawText = raw, amountInput = parsed.amount.orEmpty(), currency = parsed.currency,
                    date = parsed.date.orEmpty(), candidates = parsed.amountCandidates.joinToString("|"),
                    warnings = parsed.warnings.joinToString("\n"), accountId = account?.id, method = account?.method ?: "Unspecified",
                    receiptNo = parsed.receiptNo.orEmpty(), transactionTime = parsed.transactionTime.orEmpty())
                val duplicate = draft.receiptHash?.let { dao.hasDuplicate(it, draft.id) } ?: false
                // Camera intake must remain a draft so the user can tap the frozen receipt.
                if (!fromCamera && QuickSave.eligible(quickSave.value, parsed, duplicate)) {
                    val saved = draft.copy(status = "SAVED", amountMinor = Money.minorUnits(draft.amountInput, draft.currency))
                    writes.withLock { dao.put(saved) }
                    editor.value = null
                    state["editingId"] = null
                    message.value = "Expense saved automatically. Open it in Records to edit the details."
                    return@work
                }
                if (!fromCamera && quickSave.value) draft = draft.copy(warnings = (listOf(
                    if (duplicate) "This receipt was imported before. Check the existing record before saving again."
                    else "Quick save needs clear required fields. Complete this draft and save it."
                ) + parsed.warnings).joinToString("\n"))
                writes.withLock { dao.put(draft) }
                editor.value = draft
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                draft = draft.copy(warnings = "Recognition could not finish: ${e.message ?: "Unable to read the receipt"}. Keep the draft and enter the details manually.")
                writes.withLock { dao.put(draft) }
                editor.value = draft
            } finally {
                if (fromCamera && uri != null) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
                }
                busy.value = false
            }
        }
    }

    fun addAccount(method: String, provider: String, nickname: String, lastFour: String) {
        if (savingAccount.value || addedAccount.value != null) return
        if (provider.isBlank() || nickname.isBlank()) { message.value = "Enter a provider or bank and an account nickname."; return }
        if (lastFour.isNotBlank() && !Regex("\\d{4}").matches(lastFour)) { message.value = "The card last four must contain exactly four digits."; return }
        val account = PaymentAccount(method = method, provider = provider.trim(), nickname = nickname.trim(), lastFour = lastFour)
        savingAccount.value = true
        work {
            try {
                dao.putAccount(account)
                state["addedAccount"] = account.label()
            } finally { savingAccount.value = false }
        }
    }
    fun dismissAccountAdded() { state["addedAccount"] = null }
    fun updateAccount(account: PaymentAccount, onSaved: () -> Unit = {}) {
        if (savingAccount.value) return
        if (account.provider.isBlank() || account.nickname.isBlank()) { message.value = "Enter a provider or bank and an account nickname."; return }
        if (account.lastFour.isNotBlank() && !Regex("\\d{4}").matches(account.lastFour)) { message.value = "The card last four must contain exactly four digits."; return }
        savingAccount.value = true
        work {
            try {
                dao.putAccount(account.copy(provider = account.provider.trim(), nickname = account.nickname.trim()))
                onSaved()
            } finally { savingAccount.value = false }
        }
    }

    fun exportCsv(uri: Uri) {
        if (busy.value) return
        work {
            val records = dao.allExpenses().filter { it.status == "SAVED" }.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.transactionTime }.thenByDescending { it.createdAt })
            val accountLookup = dao.allAccounts().associateBy { it.id }
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    writer.write("\uFEFF")
                    writer.appendLine(ExpenseCsv.headers.joinToString(","))
                    records.forEach { record ->
                        writer.appendLine(ExpenseCsv.exportRow(record.csvRecord(accountLookup[record.accountId])))
                    }
                } ?: error("Unable to write to the selected file.")
            }
            message.value = "Exported ${records.size} expenses. CSV does not include receipt files."
        }
    }

    fun previewCsv(uri: Uri) {
        if (busy.value || savingAccount.value) return
        busy.value = true
        csvImport.value = null
        work {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content") { "Choose a CSV file with the system file picker." }
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= ExpenseCsv.MAX_BYTES) { "CSV exceeds 5 MiB. Split the file before importing." }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    } ?: error("Unable to read the CSV. Choose the file again.")
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    ExpenseCsv.parse(text)
                }
                csvImport.value = csvImporter.preview(parsed)
            } catch (e: java.nio.charset.CharacterCodingException) {
                message.value = "This is not a UTF-8 CSV file. Use Export CSV from Expense or save it as UTF-8 CSV."
            } finally { busy.value = false }
        }
    }

    fun dismissCsvImport() { if (!busy.value) csvImport.value = null }
    fun dismissCsvResult() { csvResult.value = null }
    fun confirmCsvImport(skipPossibleDuplicates: Boolean, skipInvalid: Boolean) {
        val preview = csvImport.value ?: return
        if (busy.value || savingAccount.value) return
        if (!skipInvalid && preview.parsed.problems.isNotEmpty()) { message.value = "Fix invalid records or explicitly choose to skip them."; return }
        busy.value = true
        work {
            try {
                val result = writes.withLock { csvImporter.importRecords(preview, skipPossibleDuplicates, skipInvalid) }
                csvImport.value = null
                csvResult.value = result
            } finally { busy.value = false }
        }
    }
}
