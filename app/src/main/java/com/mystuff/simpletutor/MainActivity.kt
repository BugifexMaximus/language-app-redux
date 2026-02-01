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
import android.widget.AdapterView
import android.widget.CheckBox
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
    private val levelOptions = listOf("Beginner", "Intermediate", "Advanced", "Grammar")

    private lateinit var userLanguagePanel: android.view.View
    private lateinit var modePanel: android.view.View
    private lateinit var modeSpecificPanel: android.view.View
    private lateinit var userSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var levelSpinner: Spinner
    private lateinit var loadPreviousCheckbox: CheckBox
    private lateinit var modeContext: TextView
    private lateinit var modeSpecificContext: TextView
    private var debugContainer: android.view.View? = null
    private lateinit var voiceStatus: TextView
    private lateinit var voiceActionButton: Button
    private lateinit var ttsVoiceSpinner: Spinner
    private lateinit var learningStatusButton: Button
    private lateinit var learningStatusButtonMode: Button
    private lateinit var backHomeButton: Button
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
        levelSpinner = findViewById(R.id.level_spinner)
        loadPreviousCheckbox = findViewById(R.id.load_previous_checkbox)
        modeContext = findViewById(R.id.mode_context)
        modeSpecificContext = findViewById(R.id.mode_specific_context)
        debugContainer = findViewById(R.id.debug_container)
        voiceStatus = findViewById(R.id.voice_status)
        voiceActionButton = findViewById(R.id.voice_action_button)
        ttsVoiceSpinner = findViewById(R.id.tts_voice_spinner)
        learningStatusButton = findViewById(R.id.learning_status_button)
        learningStatusButtonMode = findViewById(R.id.learning_status_button_mode)
        backHomeButton = findViewById(R.id.back_home_button)
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

        levelSpinner.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            levelOptions
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val voiceAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            TtsVoiceCatalog.voices
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        ttsVoiceSpinner.adapter = voiceAdapter
        val voicePrefs = getSharedPreferences(PipelinePrefs.NAME, MODE_PRIVATE)
        val savedVoice = TtsVoiceCatalog.resolve(
            voicePrefs.getString(PipelinePrefs.KEY_TTS_VOICE, null)
        )
        val savedIndex = TtsVoiceCatalog.voices.indexOf(savedVoice).coerceAtLeast(0)
        ttsVoiceSpinner.setSelection(savedIndex)
        voicePrefs.edit().putString(PipelinePrefs.KEY_TTS_VOICE, savedVoice).apply()
        ttsVoiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val voice = TtsVoiceCatalog.voices.getOrNull(position) ?: TtsVoiceCatalog.defaultVoice
                voicePrefs.edit().putString(PipelinePrefs.KEY_TTS_VOICE, voice).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
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
            val level = levelSpinner.selectedItem?.toString() ?: "Beginner"
            modeSpecificContext.text =
                "User: $userId | Language: ${language.label} (${language.code}) | Mode: $mode | Level: $level"
            showPanel(modeSpecificPanel)
            sendTutorContext(userId, language, mode, level)
            if (!loadPreviousCheckbox.isChecked) {
                val scope = com.mystuff.simpletutor.learning.LearnerScope(userId, language.code)
                com.mystuff.simpletutor.learning.LearningRepository(this).clearTurns(scope)
                chatLog.removeAllViews()
            }
            LearningScopeStore.save(
                this,
                LearningScopeInfo(
                    userId = userId,
                    languageCode = language.code,
                    languageLabel = language.label,
                    mode = mode,
                    level = level
                )
            )
        }

        voiceActionButton.setOnClickListener {
            if (currentPipelineState.equals("Thinking", ignoreCase = true) ||
                currentPipelineState.equals("Speaking", ignoreCase = true)
            ) {
                val intent = Intent(this, MicrophoneForegroundService::class.java).apply {
                    action = MicrophoneForegroundService.ACTION_INTERRUPT
                }
                ContextCompat.startForegroundService(this, intent)
                return@setOnClickListener
            }
            if (isAlwaysOn) return@setOnClickListener
            val intent = Intent(this, MicrophoneForegroundService::class.java).apply {
                action = MicrophoneForegroundService.ACTION_SET_MANUAL_LISTEN
                putExtra(MicrophoneForegroundService.EXTRA_ENABLED, !isListening)
            }
            ContextCompat.startForegroundService(this, intent)
            setPipelineRoute("tutor")
        }

        learningStatusButton.setOnClickListener {
            startActivity(Intent(this, LearningStatusActivity::class.java))
        }
        learningStatusButtonMode.setOnClickListener {
            startActivity(Intent(this, LearningStatusActivity::class.java))
        }

        backHomeButton.setOnClickListener {
            showPanel(userLanguagePanel)
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
        setPipelineRoute(if (modeSpecificPanel.visibility == android.view.View.VISIBLE) "tutor" else "test_stt")
    }

    override fun onStop() {
        unregisterReceiver(pipelineReceiver)
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
        voiceActionButton.text = when {
            currentPipelineState.equals("Thinking", ignoreCase = true) ||
                currentPipelineState.equals("Speaking", ignoreCase = true) -> "Stop"
            isListening -> "Stop listening"
            else -> "Start listening"
        }
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

    private fun sendTutorContext(userId: String, language: LanguageOption, mode: String, level: String) {
        val intent = Intent(this, MicrophoneForegroundService::class.java).apply {
            action = MicrophoneForegroundService.ACTION_SET_TUTOR_CONTEXT
            putExtra(MicrophoneForegroundService.EXTRA_USER_ID, userId)
            putExtra(MicrophoneForegroundService.EXTRA_LANG_LABEL, language.label)
            putExtra(MicrophoneForegroundService.EXTRA_LANG_CODE, language.code)
            putExtra(MicrophoneForegroundService.EXTRA_MODE, mode)
            putExtra(MicrophoneForegroundService.EXTRA_LEVEL, level)
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
