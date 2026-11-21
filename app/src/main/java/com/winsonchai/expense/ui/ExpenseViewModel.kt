package com.winsonchai.expense.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.winsonchai.expense.core.Csv
import com.winsonchai.expense.core.Money
import com.winsonchai.expense.core.ReceiptParser
import com.winsonchai.expense.data.*
import com.winsonchai.expense.importing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate

class ExpenseViewModel(app: Application, private val state: SavedStateHandle) : AndroidViewModel(app) {
    private val dao = ExpenseDatabase.get(app).dao()
    val expenses = dao.observeExpenses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val accounts = dao.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val editor = MutableStateFlow<Expense?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val savingAccount = MutableStateFlow(false)
    val addedAccount = state.getStateFlow<String?>("addedAccount", null)
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
        catch (e: Exception) { message.value = e.message ?: "操作失败，请重试。" }
    }

    fun open(expense: Expense) {
        if (busy.value) return
        editor.value = expense
        state["editingId"] = expense.id
    }
    fun newExpense() {
        if (busy.value) return
        val expense = Expense(date = LocalDate.now().toString())
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
            !validDate -> "请输入有效日期，格式 YYYY-MM-DD。"
            amount == null -> "请输入大于零的金额，精度须符合所选币种。"
            current.source != "手动" && !reviewed -> "请核对收据，并确认这是已完成的消费支出。"
            duplicates(current).isNotEmpty() && !duplicateAccepted -> "这份凭证已导入过，请确认是否继续保存。"
            current.accountId != null && accounts.value.none { it.id == current.accountId && it.method == current.method } -> "付款账户与付款方式不符，请重新选择。"
            else -> null
        }
        if (error != null) { message.value = error; return }
        busy.value = true
        work {
            try {
                writes.withLock { dao.put(current.copy(status = "SAVED", amountMinor = amount)) }
                editor.value = null
                state["editingId"] = null
                message.value = "费用已保存。"
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

    fun receive(uri: Uri? = null, text: String? = null) {
        if (busy.value) { message.value = "正在处理上一份收据，请稍后重新分享。"; return }
        busy.value = true
        work {
            var draft = Expense(source = if (uri == null) "分享文字" else "分享文件", warnings = "导入尚未完成，请核对或重新导入。")
            try {
                writes.withLock { dao.put(draft) }
                editor.value = draft
                state["editingId"] = draft.id
                val imported = if (uri != null) importer.copy(uri) else importer.text(text.orEmpty())
                draft = draft.copy(receiptPath = imported.path, receiptHash = imported.hash, mimeType = imported.mime,
                    rawText = imported.text, source = when (imported.mime) { "text/plain" -> "分享文字"; "application/pdf" -> "分享 PDF"; else -> "分享图片" })
                // Retain the private original even if PDF decoding or OCR fails, or the process is stopped.
                writes.withLock { dao.put(draft) }
                editor.value = draft
                val raw = importer.recognize(imported)
                val parsed = withContext(Dispatchers.Default) { ReceiptParser.parse(raw) }
                val account = parsed.suggestedWallet?.let { provider -> accounts.value.filter { it.provider == provider && it.active }.singleOrNull() }
                draft = draft.copy(rawText = raw, amountInput = parsed.amount.orEmpty(), currency = parsed.currency,
                    date = parsed.date.orEmpty(), candidates = parsed.amountCandidates.joinToString("|"),
                    warnings = parsed.warnings.joinToString("\n"), accountId = account?.id, method = account?.method ?: "未指定")
                writes.withLock { dao.put(draft) }
                editor.value = draft
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                draft = draft.copy(warnings = "无法完成识别：${e.message ?: "收据无法读取"}。可保留草稿并手动填写。")
                writes.withLock { dao.put(draft) }
                editor.value = draft
            } finally { busy.value = false }
        }
    }

    fun addAccount(method: String, provider: String, nickname: String, lastFour: String) {
        if (savingAccount.value || addedAccount.value != null) return
        if (provider.isBlank() || nickname.isBlank()) { message.value = "请填写品牌／银行与账户昵称。"; return }
        if (lastFour.isNotBlank() && !Regex("\\d{4}").matches(lastFour)) { message.value = "卡片末四位必须是 4 个数字。"; return }
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
        if (account.provider.isBlank() || account.nickname.isBlank()) { message.value = "请填写品牌／银行与账户昵称。"; return }
        if (account.lastFour.isNotBlank() && !Regex("\\d{4}").matches(account.lastFour)) { message.value = "卡片末四位必须是 4 个数字。"; return }
        savingAccount.value = true
        work {
            try {
                dao.putAccount(account.copy(provider = account.provider.trim(), nickname = account.nickname.trim()))
                onSaved()
            } finally { savingAccount.value = false }
        }
    }

    fun exportCsv(uri: Uri) {
        val records = expenses.value.filter { it.status == "SAVED" }
        val accountLookup = accounts.value.associateBy { it.id }
        work {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    writer.write("\uFEFF")
                    writer.appendLine("date,amount,currency,category,merchant,payment_method,payment_account,note,source")
                    records.forEach { record ->
                        writer.appendLine(listOf(record.date, Money.display(record.amountMinor!!, record.currency), record.currency,
                            record.category, record.merchant, record.method, accountLookup[record.accountId]?.label().orEmpty(), record.note, record.source)
                            .joinToString(",", transform = Csv::cell))
                    }
                } ?: error("无法写入所选文件。")
            }
            message.value = "已导出 ${records.size} 笔记录（CSV 不包含原始凭证）。"
        }
    }
}
