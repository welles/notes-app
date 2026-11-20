package dev.welles.notes.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.welles.notes.app.NotesApp

class MainActivity : ComponentActivity() {
    private val app get() = application as NotesApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            NotesApp(startupError = app.startupError, captureRequests = app.captureRequests, navigationRequests = app.navigationRequests)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CAPTURE, false) == true) app.captureRequests.tryEmit(Unit)
        intent?.getStringExtra(EXTRA_OPEN_STICKY)?.let { app.navigationRequests.tryEmit("stickies/edit?id=$it") }
    }

    companion object {
        const val EXTRA_CAPTURE = "dev.welles.notes.CAPTURE"
        const val EXTRA_OPEN_STICKY = "dev.welles.notes.OPEN_STICKY"
    }
}
