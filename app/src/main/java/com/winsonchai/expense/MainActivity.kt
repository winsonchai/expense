package com.winsonchai.expense

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.winsonchai.expense.ui.ExpenseApp
import com.winsonchai.expense.ui.ExpenseViewModel

class MainActivity : ComponentActivity() {
    private val model: ExpenseViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ExpenseApp(model) }
        if (savedInstanceState == null) accept(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }
    private fun accept(intent: Intent) {
        try { readSharedIntent(intent) }
        catch (_: RuntimeException) { model.message.value = "Unable to read shared content. Share an image, text receipt or PDF again." }
    }
    private fun readSharedIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            model.message.value = "Select one receipt file at a time."
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        val mime = intent.type.orEmpty()
        val clip = intent.clipData
        if ((clip?.itemCount ?: 0) > 1) { model.message.value = "Share one receipt at a time."; return }
        val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val uri = stream ?: clip?.getItemAt(0)?.uri
        when {
            mime.startsWith("image/") || mime == "application/pdf" -> {
                if (uri == null) model.message.value = "No receipt file was received. Please share it again." else model.receive(uri = uri)
            }
            mime == "text/plain" -> model.receive(text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: clip?.getItemAt(0)?.text?.toString())
            else -> model.message.value = "Select an image, text receipt or PDF."
        }
    }
}
