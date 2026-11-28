package com.winsonchai.expense.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CategorySettings(model: ExpenseViewModel, names: List<String>, busy: Boolean) {
    var managing by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var original by rememberSaveable { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    fun edit(name: String?) {
        original = name
        input = name.orEmpty()
        editing = true
    }

    OutlinedButton(onClick = { managing = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Manage categories")
    }

    if (managing && !editing && deleting == null) AlertDialog(
        onDismissRequest = { managing = false },
        title = { Text("Categories") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(names.filterNot { it == "Uncategorized" }, key = { it }) { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f))
                        TextButton(onClick = { edit(name) }, enabled = !busy) { Text("Rename") }
                        TextButton(onClick = { deleting = name }, enabled = !busy) { Text("Delete") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { edit(null) }, enabled = !busy) { Text("Add category") } },
        dismissButton = { TextButton(onClick = { managing = false }) { Text("Done") } }
    )

    val cleaned = input.trim()
    val validName = cleaned.isNotEmpty() && cleaned.length <= 80 &&
        !cleaned.equals("Uncategorized", ignoreCase = true) &&
        names.none { it != original && it.equals(cleaned, ignoreCase = true) }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(if (original == null) "Add category" else "Rename category") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Category name") },
                singleLine = true,
                isError = cleaned.isNotEmpty() && !validName,
                supportingText = { Text("Use a unique name, up to 80 characters.") }
            )
        },
        confirmButton = {
            TextButton(enabled = validName && !busy, onClick = {
                model.saveCategory(original, cleaned)
                editing = false
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
    )

    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete category?") },
            text = { Text("Expenses in \"$name\" will move to Uncategorized.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    model.deleteCategory(name)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}
