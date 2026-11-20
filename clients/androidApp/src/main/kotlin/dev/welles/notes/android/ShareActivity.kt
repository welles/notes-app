package dev.welles.notes.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.welles.notes.core.data.ContextRepository
import dev.welles.notes.core.data.EntityActions
import dev.welles.notes.core.model.CaptureContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/** "Teilen mit Notes": creates a post-it from shared text in the active context, without opening the app. */
class ShareActivity : Activity() {
    private val actions: EntityActions by inject()
    private val contexts: ContextRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (text.isNullOrEmpty()) {
            finish()
            return
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() && it != text }
        val appContext = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = runCatching {
                contexts.ensureDefaultContext()
                actions.capture(CaptureContent(subject, text))
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, if (result.isSuccess) "Post-it angelegt" else "Konnte nicht gespeichert werden", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
