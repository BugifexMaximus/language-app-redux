package com.mystuff.simpletutor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity

class StatusBannerActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() }

    private val hideReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_HIDE) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status_banner)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply { gravity = Gravity.TOP }
        updateText(intent?.getStringExtra(EXTRA_TEXT))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        updateText(intent.getStringExtra(EXTRA_TEXT))
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            hideReceiver,
            IntentFilter(ACTION_HIDE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
    }

    override fun onPause() {
        unregisterReceiver(hideReceiver)
        handler.removeCallbacks(autoDismiss)
        super.onPause()
    }

    private fun updateText(text: String?) {
        val view = findViewById<TextView>(R.id.statusBannerText)
        view.text = text?.trim().orEmpty()
        view.isSelected = true
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
    }

    companion object {
        const val EXTRA_TEXT = "status_text"
        const val ACTION_HIDE = "com.mystuff.simpletutor.STATUS_HIDE"
        private const val AUTO_DISMISS_MS = 2500L
    }
}
