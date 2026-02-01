package com.mystuff.simpletutor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private val notificationPermissionRequestCode = 1001
    private val recordAudioPermissionRequestCode = 1002
    private val userOptions = listOf("alexei-1", "alexei-2")
    private val languageOptions = listOf(
        LanguageOption("Spanish", "es-ES"),
        LanguageOption("Japanese", "ja-JP"),
        LanguageOption("Korean (South)", "ko-KR")
    )
    private val modeOptions = listOf("chat", "practice", "quiz")

    private lateinit var userLanguagePanel: android.view.View
    private lateinit var modePanel: android.view.View
    private lateinit var modeSpecificPanel: android.view.View
    private lateinit var userSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var modeContext: TextView
    private lateinit var modeSpecificContext: TextView
    private var debugContainer: android.view.View? = null
    private lateinit var voiceStatus: TextView
    private lateinit var voiceActionButton: Button
    private lateinit var chatLog: LinearLayout
    private lateinit var chatLogScroll: android.widget.ScrollView
    private var isListening = false
    private var isAlwaysOn = false
    private var currentPipelineState = "Offline"
    private val pipelineReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                MicrophoneForegroundService.ACTION_LISTENING_STATUS -> {
                    isListening = intent.getBooleanExtra("listening", false)
                    isAlwaysOn = intent.getBooleanExtra("alwaysOn", false)
                    updateVoiceUi()
                }
                MicrophoneForegroundService.ACTION_PIPELINE_STATE -> {
                    currentPipelineState = intent.getStringExtra(MicrophoneForegroundService.EXTRA_STATE) ?: "Offline"
                    updateVoiceUi()
                }
                MicrophoneForegroundService.ACTION_CHAT_MESSAGE -> {
                    val role = intent.getStringExtra(MicrophoneForegroundService.EXTRA_ROLE) ?: "tutor"
                    val text = intent.getStringExtra(MicrophoneForegroundService.EXTRA_TEXT).orEmpty()
                    if (text.isNotBlank()) {
                        appendChatLine(role, text)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        userLanguagePanel = findViewById(R.id.panel_user_language)
        modePanel = findViewById(R.id.panel_mode)
        modeSpecificPanel = findViewById(R.id.panel_mode_specific)
        userSpinner = findViewById(R.id.user_spinner)
        languageSpinner = findViewById(R.id.language_spinner)
        modeSpinner = findViewById(R.id.mode_spinner)
        modeContext = findViewById(R.id.mode_context)
        modeSpecificContext = findViewById(R.id.mode_specific_context)
        debugContainer = findViewById(R.id.debug_container)
        voiceStatus = findViewById(R.id.voice_status)
        voiceActionButton = findViewById(R.id.voice_action_button)
        chatLog = findViewById(R.id.chat_log)
        chatLogScroll = findViewById(R.id.chat_log_scroll)

        userSpinner.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            userOptions
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        languageSpinner.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languageOptions.map { it.label }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        languageSpinner.setSelection(languageOptions.indexOfFirst { it.code == "ja-JP" }.coerceAtLeast(0))

        modeSpinner.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            modeOptions
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        findViewById<Button>(R.id.continue_button).setOnClickListener {
            val userId = userSpinner.selectedItem?.toString() ?: "-"
            val language = selectedLanguageOption()
            modeContext.text = "User: $userId | Language: ${language.label} (${language.code})"
            showPanel(modePanel)
        }

        findViewById<Button>(R.id.start_button).setOnClickListener {
            val userId = userSpinner.selectedItem?.toString() ?: "-"
            val language = selectedLanguageOption()
            val mode = modeSpinner.selectedItem?.toString() ?: "-"
            modeSpecificContext.text =
                "User: $userId | Language: ${language.label} (${language.code}) | Mode: $mode"
            showPanel(modeSpecificPanel)
            sendTutorContext(userId, language, mode)
        }

        voiceActionButton.setOnClickListener {
            if (isAlwaysOn) return@setOnClickListener
            val intent = Intent(this, MicrophoneForegroundService::class.java).apply {
                action = MicrophoneForegroundService.ACTION_SET_MANUAL_LISTEN
                putExtra(MicrophoneForegroundService.EXTRA_ENABLED, !isListening)
            }
            ContextCompat.startForegroundService(this, intent)
        }

        findViewById<Button?>(R.id.debug_open_panel)?.setOnClickListener {
            val intent = Intent().setClassName(
                this,
                "com.mystuff.simpletutor.DebugPanelActivity"
            )
            startActivity(intent)
        }

        findViewById<Button?>(R.id.debug_open_vad)?.setOnClickListener {
            val intent = Intent().setClassName(
                this,
                "com.mystuff.simpletutor.DebugVadSettingsActivity"
            )
            startActivity(intent)
        }

        ensureNotificationPermissionAndStartService()
        setPipelineRoute("test_stt")
        updateVoiceUi()
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter().apply {
            addAction(MicrophoneForegroundService.ACTION_LISTENING_STATUS)
            addAction(MicrophoneForegroundService.ACTION_PIPELINE_STATE)
            addAction(MicrophoneForegroundService.ACTION_CHAT_MESSAGE)
        }
        ContextCompat.registerReceiver(
            this,
            pipelineReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(pipelineReceiver)
        setPipelineRoute("test_stt")
        super.onStop()
    }

    private fun showPanel(panelToShow: android.view.View) {
        val panels = listOf(userLanguagePanel, modePanel, modeSpecificPanel)
        panels.forEach { panel ->
            panel.visibility = if (panel == panelToShow) android.view.View.VISIBLE else android.view.View.GONE
        }
        debugContainer?.visibility =
            if (panelToShow == userLanguagePanel) android.view.View.VISIBLE else android.view.View.GONE
        setPipelineRoute(if (panelToShow == modeSpecificPanel) "tutor" else "test_stt")
    }

    private fun updateVoiceUi() {
        val displayState = when {
            currentPipelineState.equals("Thinking", ignoreCase = true) -> "Thinking"
            currentPipelineState.equals("Speaking", ignoreCase = true) -> "Speaking"
            currentPipelineState.equals("Listening", ignoreCase = true) -> "Listening"
            isListening -> "Listening"
            else -> "Offline"
        }
        voiceStatus.text = "Status: $displayState"
        voiceActionButton.isEnabled = !isAlwaysOn
        voiceActionButton.text = if (isListening) "Stop listening" else "Start listening"
        if (isAlwaysOn) {
            voiceActionButton.text = "Listening (always on)"
        }
    }

    private fun appendChatLine(role: String, text: String) {
        val isUser = role.equals("user", ignoreCase = true)
        val view = TextView(this).apply {
            this.text = if (isUser) "You: $text" else "Tutor: $text"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(if (isUser) android.graphics.Color.DKGRAY else android.graphics.Color.BLACK)
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(8)
        }
        chatLog.addView(view, params)
        chatLogScroll.post { chatLogScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun sendTutorContext(userId: String, language: LanguageOption, mode: String) {
        val intent = Intent(this, MicrophoneForegroundService::class.java).apply {
            action = MicrophoneForegroundService.ACTION_SET_TUTOR_CONTEXT
            putExtra(MicrophoneForegroundService.EXTRA_USER_ID, userId)
            putExtra(MicrophoneForegroundService.EXTRA_LANG_LABEL, language.label)
            putExtra(MicrophoneForegroundService.EXTRA_LANG_CODE, language.code)
            putExtra(MicrophoneForegroundService.EXTRA_MODE, mode)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun setPipelineRoute(route: String) {
        val intent = Intent(this, MicrophoneForegroundService::class.java).apply {
            action = MicrophoneForegroundService.ACTION_SET_PIPELINE_ROUTE
            putExtra(MicrophoneForegroundService.EXTRA_ROUTE, route)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun selectedLanguageOption(): LanguageOption {
        val index = languageSpinner.selectedItemPosition
        return languageOptions.getOrNull(index) ?: languageOptions.first()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private data class LanguageOption(val label: String, val code: String)

    private fun ensureNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    ensureRecordAudioPermissionAndStartService()
                }
                ActivityCompat.shouldShowRequestPermissionRationale(this, permission) -> {
                    AlertDialog.Builder(this)
                        .setTitle("Notification permission")
                        .setMessage("Allow notifications so the microphone service can show its foreground notification.")
                        .setPositiveButton("Allow") { _, _ ->
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(permission),
                                notificationPermissionRequestCode
                            )
                        }
                        .setNegativeButton("Not now", null)
                        .show()
                }
                else -> {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(permission),
                        notificationPermissionRequestCode
                    )
                }
            }
        } else {
            ensureRecordAudioPermissionAndStartService()
        }
    }

    private fun ensureRecordAudioPermissionAndStartService() {
        val permission = Manifest.permission.RECORD_AUDIO
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                startMicrophoneService()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, permission) -> {
                AlertDialog.Builder(this)
                    .setTitle("Microphone permission")
                    .setMessage("Allow microphone access so the foreground service can start.")
                    .setPositiveButton("Allow") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(permission),
                            recordAudioPermissionRequestCode
                        )
                    }
                    .setNegativeButton("Not now", null)
                    .show()
            }
            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(permission),
                    recordAudioPermissionRequestCode
                )
            }
        }
    }

    private fun startMicrophoneService() {
        val intent = Intent(this, MicrophoneForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationPermissionRequestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            ensureRecordAudioPermissionAndStartService()
        }

        if (requestCode == recordAudioPermissionRequestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startMicrophoneService()
        }
    }
}
