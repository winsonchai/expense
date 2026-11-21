package com.winsonchai.expense.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winsonchai.expense.core.Money
import com.winsonchai.expense.data.*
import java.math.BigInteger
import java.time.YearMonth
import java.util.Currency

private val Teal = Color(0xFF006A60)

@Composable
fun ExpenseApp(model: ExpenseViewModel) {
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
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); if (model.message.value == it) model.message.value = null } }
    BackHandler(editor != null) { model.close() }
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, secondary = Color(0xFF496563), background = Color(0xFFF7F8FA))) {
        if (addedAccount != null) AlertDialog(
            onDismissRequest = model::dismissAccountAdded,
            title = { Text("已新增账户") },
            text = { Text("付款账户「$addedAccount」已成功保存。") },
            confirmButton = { TextButton(onClick = model::dismissAccountAdded) { Text("确定") } }
        )
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (editor == null) NavigationBar {
                    listOf("首页", "记录", "统计", "账户").forEachIndexed { index, title ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text(listOf("⌂", "≡", "▥", "○")[index]) }, label = { Text(title) })
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
                            Text("分享到账本", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("个人开销 · 本机保存", style = MaterialTheme.typography.bodySmall)
                        }
                        if (tab != 3) TextButton(onClick = { export.launch("expense-${YearMonth.now()}.csv") }) { Text("导出 CSV") }
                    }
                    Column(Modifier.verticalScroll(rememberScrollState()).weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (tab) {
                            0 -> Home(expenses, accounts, model::open, model::newExpense,
                                { picker.launch(arrayOf("image/*", "application/pdf")) })
                            1 -> Records(expenses, accounts, model::open)
                            2 -> Statistics(expenses, accounts)
                            3 -> Accounts(accounts, model)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Home(expenses: List<Expense>, accounts: List<PaymentAccount>, open: (Expense) -> Unit, add: () -> Unit, import: () -> Unit) {
    val month = YearMonth.now().toString()
    Card(colors = CardDefaults.cardColors(containerColor = Teal), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$month · 本月已确认支出", color = Color.White)
            val records = expenses.filter { it.status == "SAVED" && it.date.startsWith(month) }
            if (records.isEmpty()) Text("MYR 0.00", color = Color.White, style = MaterialTheme.typography.headlineLarge)
            else records.groupBy { it.currency }.forEach { (currency, items) ->
                Text("$currency ${total(items, currency)}", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
            Text("草稿不计入统计；不同币种分别汇总", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = add, modifier = Modifier.weight(1f)) { Text("＋ 手动记账") }
        OutlinedButton(onClick = import, modifier = Modifier.weight(1f)) { Text("导入收据") }
    }
    Text("付款后，在钱包收据页点「分享」，选择「分享到账本」，再核对保存。", style = MaterialTheme.typography.bodyMedium)
    val drafts = expenses.filter { it.status == "DRAFT" }
    if (drafts.isNotEmpty()) {
        SectionTitle("待核对草稿 · ${drafts.size}")
        drafts.forEach { ExpenseRow(it, accounts, open) }
    }
    SectionTitle("近期记录")
    val saved = expenses.filter { it.status == "SAVED" }.take(8)
    if (saved.isEmpty()) EmptyState("还没有费用记录", "添加第一笔开销，或从钱包分享一份收据。")
    saved.forEach { ExpenseRow(it, accounts, open) }
}

@Composable
private fun Records(expenses: List<Expense>, accounts: List<PaymentAccount>, open: (Expense) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部分类") }
    var accountId by rememberSaveable { mutableStateOf("") }
    SectionTitle("费用记录")
    Field(query, "搜索商家、日期或备注", { query = it })
    Choice("分类", listOf("全部分类") + categories, category, { category = it })
    Choice("账户", listOf("" to "全部账户") + accounts.map { it.id to it.label() }, accountId, { accountId = it })
    val filtered = expenses.filter { expense ->
        (category == "全部分类" || expense.category == category) && (accountId.isEmpty() || expense.accountId == accountId) &&
            (query.isBlank() || "${expense.date} ${expense.merchant} ${expense.note}".contains(query, ignoreCase = true))
    }
    if (filtered.isEmpty()) EmptyState("暂无匹配记录", "试试调整搜索条件。")
    filtered.forEach { ExpenseRow(it, accounts, open) }
}

@Composable
private fun Statistics(expenses: List<Expense>, accounts: List<PaymentAccount>) {
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    SectionTitle("支出统计")
    Field(month, "月份 YYYY-MM", { month = it })
    val valid = runCatching { YearMonth.parse(month) }.isSuccess
    if (!valid) { Text("请输入有效月份，例如 2026-11。"); return }
    val records = expenses.filter { it.status == "SAVED" && it.date.startsWith("$month-") }
    if (records.isEmpty()) { EmptyState("本月暂无支出", "只有已确认保存的费用才计入统计。"); return }
    for ((currency, items) in records.groupBy { it.currency }) {
        SectionTitle("$currency ${total(items, currency)}")
        Text("按分类", fontWeight = FontWeight.Bold)
        items.groupBy { it.category }.forEach { (label, group) -> SummaryRow(label, "$currency ${total(group, currency)}") }
        Text("按付款方式", fontWeight = FontWeight.Bold)
        items.groupBy { it.method }.forEach { (label, group) -> SummaryRow(label, "$currency ${total(group, currency)}") }
        Text("按付款账户", fontWeight = FontWeight.Bold)
        items.groupBy { it.accountId }.forEach { (id, group) ->
            SummaryRow(accounts.find { it.id == id }?.label() ?: "未指定账户", "$currency ${total(group, currency)}")
        }
    }
}

@Composable
private fun ColumnScope.Editor(model: ExpenseViewModel, expense: Expense, expenses: List<Expense>, accounts: List<PaymentAccount>, busy: Boolean) {
    var reviewed by rememberSaveable(expense.id) { mutableStateOf(false) }
    var duplicateAccepted by rememberSaveable(expense.id) { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    val duplicate = expenses.any { expense.receiptHash != null && it.id != expense.id && it.receiptHash == expense.receiptHash }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = model::close, enabled = !busy) { Text("返回") }
        Text(if (expense.status == "DRAFT") "核对费用草稿" else "编辑费用", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = { deleteDialog = true }, enabled = !busy) { Text("删除") }
    }
    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理收据，请稍候…") }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (expense.receiptPath != null) ReceiptPreview(model, expense)
        if (expense.warnings.isNotBlank()) Notice(expense.warnings)
        if (duplicate) {
            Notice("这份凭证已导入过。请在首页／记录中核对已有记录，避免重复记账。")
            Check("已核对，仍要保留这笔记录", duplicateAccepted, { duplicateAccepted = it }, !busy)
        }
        Field(expense.amountInput, "金额（必填）", { value -> model.edit { it.copy(amountInput = value) } }, !busy, KeyboardType.Decimal)
        if (expense.candidates.isNotBlank()) {
            Text("收据中的金额（可能含余额／优惠）：${expense.candidates.replace("|", "、")}", style = MaterialTheme.typography.bodySmall)
        }
        Choice("币种", currencies, expense.currency, { value -> model.edit { it.copy(currency = value) } }, !busy)
        Field(expense.date, "交易日期 YYYY-MM-DD（必填）", { value -> model.edit { it.copy(date = value) } }, !busy)
        Choice("分类", categories, expense.category, { value -> model.edit { it.copy(category = value) } }, !busy)
        Field(expense.merchant, "商家", { value -> model.edit { it.copy(merchant = value) } }, !busy)
        Choice("付款方式", paymentMethods, expense.method, { value -> model.edit { it.copy(method = value, accountId = null) } }, !busy)
        val choices = accounts.filter { it.method == expense.method && (it.active || it.id == expense.accountId) }
        Choice("付款账户", listOf("" to "暂未指定") + choices.map { it.id to (it.label() + if (it.active) "" else "（已停用）") }, expense.accountId.orEmpty(),
            { value -> model.edit { it.copy(accountId = value.ifEmpty { null }) } }, !busy)
        Field(expense.note, "备注", { value -> model.edit { it.copy(note = value) } }, !busy)
        Text("来源：${expense.source}", style = MaterialTheme.typography.bodySmall)
        if (expense.rawText.isNotBlank()) {
            SectionTitle("原始／识别文字")
            Text(expense.rawText, style = MaterialTheme.typography.bodySmall)
        }
        if (expense.source != "手动") Check("我已核对金额、币种、日期和账户，确认这是已完成的消费支出（非充值、转账、还款或退款）。", reviewed, { reviewed = it }, !busy)
        if (expense.status == "DRAFT") Text("填写内容自动保留为草稿，保存后才计入统计。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { model.save(reviewed, duplicateAccepted) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("确认并保存费用") }
        Spacer(Modifier.height(20.dp))
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("删除这笔记录？") },
        text = { Text("记录及其原始凭证将从本机删除。") }, confirmButton = {
            TextButton(onClick = { deleteDialog = false; model.delete(expense) }) { Text("删除") }
        }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
}

@Composable
private fun ReceiptPreview(model: ExpenseViewModel, expense: Expense) {
    val bitmap by produceState<Bitmap?>(null, expense.receiptPath) {
        value = model.importer.preview(expense.receiptPath!!, expense.mimeType.orEmpty())
    }
    Text(if (expense.mimeType == "application/pdf") "原始凭证 · PDF 首页预览" else "原始凭证", fontWeight = FontWeight.Bold)
    bitmap?.let { Image(it.asImageBitmap(), "收据预览", Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit) }
    if (bitmap == null) Text("预览无法显示或正在载入，仍可核对下方文字及手动填写。", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Accounts(accounts: List<PaymentAccount>, model: ExpenseViewModel) {
    val savingAccount by model.savingAccount.collectAsStateWithLifecycle()
    val addedAccount by model.addedAccount.collectAsStateWithLifecycle()
    var method by rememberSaveable { mutableStateOf("电子钱包") }
    var provider by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var lastFour by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editProvider by rememberSaveable { mutableStateOf("") }
    var editName by rememberSaveable { mutableStateOf("") }
    var editLastFour by rememberSaveable { mutableStateOf("") }
    SectionTitle("付款账户")
    Text("记录钱包品牌、发卡银行及昵称。卡片只填末四位。", style = MaterialTheme.typography.bodySmall)
    accounts.forEach { account ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(account.label(), fontWeight = FontWeight.Bold)
                Text(account.method + if (account.active) "" else " · 已停用")
                Row {
                    TextButton(onClick = {
                        editingId = account.id
                        editProvider = account.provider
                        editName = account.nickname
                        editLastFour = account.lastFour
                    }, enabled = !savingAccount) { Text("编辑") }
                    TextButton(onClick = { model.updateAccount(account.copy(active = !account.active)) }, enabled = !savingAccount) { Text(if (account.active) "停用" else "启用") }
                }
            }
        }
    }
    SectionTitle("新增账户")
    Choice("付款方式", paymentMethods.filter { it != "未指定" }, method, { method = it })
    Field(provider, "品牌／银行，例如 TNG eWallet、CIMB", { provider = it })
    Field(nickname, "昵称，例如个人、日常消费", { nickname = it })
    if (method in listOf("信用卡", "借记卡", "预付／储值卡")) Field(lastFour, "卡片末四位（可选）", { lastFour = it.take(4) }, keyboardType = KeyboardType.Number)
    Button(onClick = {
        model.addAccount(method, provider, nickname, if (method in listOf("信用卡", "借记卡", "预付／储值卡")) lastFour else "")
    }, enabled = !savingAccount && addedAccount == null) { Text(if (savingAccount) "正在保存…" else "新增付款账户") }
    Text("停用账户会保留历史记录关联。卸载 App 会删除本机资料；CSV 仅供分析，完整备份恢复尚未提供。", style = MaterialTheme.typography.bodySmall)
    accounts.find { it.id == editingId }?.let { account ->
        val isCard = account.method in listOf("信用卡", "借记卡", "预付／储值卡")
        val error = when {
            editProvider.isBlank() || editName.isBlank() -> "请填写品牌／银行与账户昵称。"
            isCard && editLastFour.isNotBlank() && !Regex("\\d{4}").matches(editLastFour) -> "卡片末四位必须是 4 个数字，或留空。"
            else -> null
        }
        AlertDialog(
            onDismissRequest = { if (!savingAccount) editingId = null },
            title = { Text("编辑付款账户") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("付款方式：${account.method}")
                    Field(editProvider, "品牌／银行", { editProvider = it }, !savingAccount)
                    Field(editName, "账户昵称", { editName = it }, !savingAccount)
                    if (isCard) Field(editLastFour, "卡片末四位（可选）", { editLastFour = it.take(4) }, !savingAccount, KeyboardType.Number)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    model.updateAccount(account.copy(provider = editProvider, nickname = editName,
                        lastFour = if (isCard) editLastFour else "")) { editingId = null }
                }, enabled = !savingAccount && error == null) { Text(if (savingAccount) "正在保存…" else "保存") }
            },
            dismissButton = { TextButton(onClick = { editingId = null }, enabled = !savingAccount) { Text("取消") } }
        )
    }
}

@Composable
private fun ExpenseRow(expense: Expense, accounts: List<PaymentAccount>, open: (Expense) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { open(expense) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(expense.merchant.ifBlank { expense.category }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${expense.currency} ${expense.amountMinor?.let { Money.display(it, expense.currency) } ?: expense.amountInput.ifBlank { "待核对" }}", fontWeight = FontWeight.Bold)
            }
            Text("${expense.date.ifBlank { "日期待核对" }} · ${expense.category}${if (expense.status == "DRAFT") " · 草稿" else ""}", style = MaterialTheme.typography.bodySmall)
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
            Text("$label：${options.find { it.first == selected }?.second ?: selected}", Modifier.weight(1f)); Text("▾")
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
