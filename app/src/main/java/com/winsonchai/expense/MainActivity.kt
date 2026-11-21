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
        catch (_: RuntimeException) { model.message.value = "分享资料无法读取，请重新分享图片、文字或 PDF。" }
    }
    private fun readSharedIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            model.message.value = "本版每次只接收一份收据，请选择单个文件。"
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        val mime = intent.type.orEmpty()
        val clip = intent.clipData
        if ((clip?.itemCount ?: 0) > 1) { model.message.value = "请一次分享一份收据。"; return }
        val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val uri = stream ?: clip?.getItemAt(0)?.uri
        when {
            mime.startsWith("image/") || mime == "application/pdf" -> {
                if (uri == null) model.message.value = "没有收到收据文件，请重新分享。" else model.receive(uri = uri)
            }
            mime == "text/plain" -> model.receive(text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: clip?.getItemAt(0)?.text?.toString())
            else -> model.message.value = "请选择图片、文字或 PDF 收据。"
        }
    }
}
