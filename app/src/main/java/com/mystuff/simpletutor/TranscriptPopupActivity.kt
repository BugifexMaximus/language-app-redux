package com.mystuff.simpletutor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TranscriptPopupActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcript_popup)
        window.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        updateText(intent?.getStringExtra(EXTRA_TEXT))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        updateText(intent.getStringExtra(EXTRA_TEXT))
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(autoDismiss)
        super.onPause()
    }

    private fun updateText(text: String?) {
        val view = findViewById<TextView>(R.id.transcriptText)
        val incoming = text?.trim().orEmpty()
        if (incoming.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val combined = if (now - lastUpdateMs < APPEND_WINDOW_MS && lastText.isNotEmpty()) {
                if (lastText.endsWith(incoming)) lastText else "$lastText\n$incoming"
            } else {
                incoming
            }
            lastText = combined
            lastUpdateMs = now
            view.text = combined
        }
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
    }

    companion object {
        const val EXTRA_TEXT = "transcript_text"
        private const val AUTO_DISMISS_MS = 5000L
        private const val APPEND_WINDOW_MS = 2000L
        private var lastText: String = ""
        private var lastUpdateMs: Long = 0L
    }
}
