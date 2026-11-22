package com.winsonchai.expense.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winsonchai.expense.core.ExpenseCsv
import com.winsonchai.expense.core.Money

@Composable
fun CsvImportDialogs(model: ExpenseViewModel) {
    val preview by model.csvImport.collectAsStateWithLifecycle()
    val result by model.csvResult.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    result?.let { message ->
        AlertDialog(onDismissRequest = model::dismissCsvResult, title = { Text("CSV import complete") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = model::dismissCsvResult) { Text("OK") } })
    }
    preview?.let { session ->
        var skipDuplicates by rememberSaveable(session.token) { mutableStateOf(true) }
        var skipInvalid by rememberSaveable(session.token) { mutableStateOf(false) }
        val selection = remember(session, skipDuplicates) {
            ExpenseCsv.select(session.parsed.rows, session.existingIds, session.fingerprints, skipDuplicates)
        }
        val canImport = selection.rows.isNotEmpty() && (session.parsed.problems.isEmpty() || skipInvalid)
        AlertDialog(onDismissRequest = model::dismissCsvImport, title = { Text("Preview CSV import") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${session.parsed.totalRows} records · ${session.parsed.rows.size} valid · ${session.parsed.problems.size} invalid")
                    Text("Will import ${selection.rows.size} saved expenses. Skip ${selection.existingIds} existing IDs and ${selection.possibleDuplicates} possible duplicates.")
                    Text("CSV restores saved expense details and their payment accounts. It does not contain receipt photos/PDFs or unsaved drafts. Existing records are kept.", style = MaterialTheme.typography.bodySmall)
                    Row {
                        Checkbox(skipDuplicates, { skipDuplicates = it }, enabled = !busy)
                        Text("Skip possible duplicates (matching expense details)", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!skipDuplicates) Text("Identical details will be imported as separate expenses. Existing IDs are always skipped.", style = MaterialTheme.typography.bodySmall)
                    if (session.parsed.problems.isNotEmpty()) {
                        Text("Records needing attention", style = MaterialTheme.typography.titleSmall)
                        session.parsed.problems.take(10).forEach { problem ->
                            Text("Record ${problem.record}: ${problem.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (session.parsed.problems.size > 10) Text("${session.parsed.problems.size - 10} more invalid records. Fix the file and select it again to import every record.", style = MaterialTheme.typography.bodySmall)
                        Row {
                            Checkbox(skipInvalid, { skipInvalid = it }, enabled = !busy)
                            Text("Import valid records and skip ${session.parsed.problems.size} invalid records", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    selection.rows.take(5).forEach { row ->
                        val expense = row.expense
                        Text("${expense.date} · ${expense.currency} ${Money.display(expense.amountMinor, expense.currency)}\n${expense.merchant.ifBlank { expense.category }}${expense.account?.let { " · ${it.label()}" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (selection.rows.size > 5) Text("And ${selection.rows.size - 5} more…", style = MaterialTheme.typography.bodySmall)
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Importing. Please keep the app open…") }
                    else Text("No records are written until you confirm.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { model.confirmCsvImport(skipDuplicates, skipInvalid) }, enabled = !busy && canImport) {
                    Text(if (busy) "Importing…" else "Import ${selection.rows.size} records")
                }
            },
            dismissButton = { TextButton(onClick = model::dismissCsvImport, enabled = !busy) { Text("Cancel") } })
    }
}
