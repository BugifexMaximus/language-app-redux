package com.mystuff.simpletutor

import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.konovalov.vad.webrtc.config.Mode
import com.mystuff.simpletutor.audio.VadConfigStore
import com.mystuff.simpletutor.audio.VadUserConfig

class DebugVadSettingsActivity : AppCompatActivity() {
    private val tag = "DebugVadSettings"
    private lateinit var config: VadUserConfig
    private val levelReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action != MicrophoneForegroundService.ACTION_LEVEL) return
            val level = intent.getIntExtra("level", 0)
            val label = findViewById<TextView>(R.id.vadLevelLabel)
            val bar = findViewById<ProgressBar>(R.id.vadLevelBar)
            label.text = "Level: $level"
            bar.progress = level
        }
    }

    private val listeningReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action != MicrophoneForegroundService.ACTION_LISTENING_STATUS) return
            val listening = intent.getBooleanExtra("listening", false)
            // Keep local config in sync so the toggle doesn't need two clicks.
            config = VadConfigStore.load(this@DebugVadSettingsActivity)
            updateListeningUi(listening)
        }
    }

    private lateinit var vadListenToggleBtn: Button
    private lateinit var vadListeningIndicator: android.view.View
    private lateinit var vadListeningLabel: TextView
    private var alwaysOn = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vad_settings)

        val root = findViewById<View>(R.id.vad_settings_root)
        val scroll = findViewById<View>(R.id.vad_settings_scroll)

        val vadAlwaysOnSwitch = findViewById<SwitchCompat>(R.id.vadAlwaysOnSwitch)
        val vadAutoEndSwitch = findViewById<SwitchCompat>(R.id.vadAutoEndSwitch)
        val vadShowPopupSwitch = findViewById<SwitchCompat>(R.id.vadShowPopupSwitch)
        vadListenToggleBtn = findViewById(R.id.vadListenToggleBtn)
        vadListeningIndicator = findViewById(R.id.vadListeningIndicator)
        vadListeningLabel = findViewById(R.id.vadListeningLabel)
        val vadModeSpinner = findViewById<Spinner>(R.id.vadModeSpinner)
        val vadMinSpeechEdit = findViewById<EditText>(R.id.vadMinSpeechEdit)
        val vadAmpGateCheckbox = findViewById<CheckBox>(R.id.vadAmpGateCheckbox)
        val vadMinAmplitudeEdit = findViewById<EditText>(R.id.vadMinAmplitudeEdit)
        val vadPeakMinEdit = findViewById<EditText>(R.id.vadPeakMinEdit)
        val vadRmsMinEdit = findViewById<EditText>(R.id.vadRmsMinEdit)
        val vadNoiseMarginEdit = findViewById<EditText>(R.id.vadNoiseMarginEdit)
        val vadNOnEdit = findViewById<EditText>(R.id.vadNOnEdit)
        val vadNOffEdit = findViewById<EditText>(R.id.vadNOffEdit)
        val vadMinUtterEdit = findViewById<EditText>(R.id.vadMinUtterEdit)
        val vadMinSpeechRatioEdit = findViewById<EditText>(R.id.vadMinSpeechRatioEdit)
        val vadMergeGapEdit = findViewById<EditText>(R.id.vadMergeGapEdit)
        val vadApplyBtn = findViewById<Button>(R.id.vadApplyBtn)

        val vadModes = listOf(
            Mode.NORMAL,
            Mode.LOW_BITRATE,
            Mode.AGGRESSIVE,
            Mode.VERY_AGGRESSIVE
        )
        vadModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            vadModes.map { it.name }
        )

        config = VadConfigStore.load(this)
        vadAlwaysOnSwitch.isChecked = config.alwaysOn
        alwaysOn = config.alwaysOn
        vadAutoEndSwitch.isChecked = config.autoEndDetect
        vadShowPopupSwitch.isChecked = config.showTranscriptPopup
        vadModeSpinner.setSelection(vadModes.indexOf(config.mode).coerceAtLeast(0))
        vadMinSpeechEdit.setText(config.minSpeechMs.toString())
        vadAmpGateCheckbox.isChecked = config.amplitudeGateEnabled
        vadMinAmplitudeEdit.setText(config.minAmplitude.toString())
        vadPeakMinEdit.setText(config.peakMin.toString())
        vadRmsMinEdit.setText(config.rmsMinDbfs.toString())
        vadNoiseMarginEdit.setText(config.noiseMarginDb.toString())
        vadNOnEdit.setText(config.nOn.toString())
        vadNOffEdit.setText(config.nOff.toString())
        vadMinUtterEdit.setText(config.minUtterMs.toString())
        vadMinSpeechRatioEdit.setText(config.minSpeechRatio.toString())
        vadMergeGapEdit.setText(config.mergeGapMs.toString())

        updateListeningUi(config.alwaysOn || config.manualListening)

        vadAlwaysOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            config = config.copy(alwaysOn = isChecked, manualListening = false)
            VadConfigStore.save(this, config)
            sendBroadcast(android.content.Intent(MicrophoneForegroundService.ACTION_CONFIG_CHANGED).setPackage(packageName))
            alwaysOn = isChecked
            updateListeningUi(config.alwaysOn || config.manualListening)
        }

        vadAutoEndSwitch.setOnCheckedChangeListener { _, isChecked ->
            config = config.copy(autoEndDetect = isChecked)
            VadConfigStore.save(this, config)
            sendBroadcast(android.content.Intent(MicrophoneForegroundService.ACTION_CONFIG_CHANGED).setPackage(packageName))
        }

        vadShowPopupSwitch.setOnCheckedChangeListener { _, isChecked ->
            config = config.copy(showTranscriptPopup = isChecked)
            VadConfigStore.save(this, config)
            sendBroadcast(android.content.Intent(MicrophoneForegroundService.ACTION_CONFIG_CHANGED).setPackage(packageName))
        }

        vadListenToggleBtn.setOnClickListener {
            if (config.alwaysOn) return@setOnClickListener
            val newState = !config.manualListening
            config = config.copy(manualListening = newState)
            VadConfigStore.save(this, config)
            sendBroadcast(android.content.Intent(MicrophoneForegroundService.ACTION_CONFIG_CHANGED).setPackage(packageName))
            val intent = android.content.Intent(this, MicrophoneForegroundService::class.java).apply {
                action = MicrophoneForegroundService.ACTION_SET_MANUAL_LISTEN
                putExtra(MicrophoneForegroundService.EXTRA_ENABLED, newState)
            }
            ContextCompat.startForegroundService(this, intent)
            updateListeningUi(config.alwaysOn || config.manualListening)
            Log.d(tag, "Manual listen toggled to $newState")
        }

        vadApplyBtn.setOnClickListener {
            val mode = vadModes.getOrNull(vadModeSpinner.selectedItemPosition) ?: Mode.VERY_AGGRESSIVE
            val minSpeechMs = vadMinSpeechEdit.text.toString().toIntOrNull() ?: 200
            val minAmp = vadMinAmplitudeEdit.text.toString().toIntOrNull() ?: 500
            val peakMin = vadPeakMinEdit.text.toString().toIntOrNull() ?: 800
            val rmsMin = vadRmsMinEdit.text.toString().toFloatOrNull() ?: -42f
            val margin = vadNoiseMarginEdit.text.toString().toFloatOrNull() ?: 8f
            val nOn = vadNOnEdit.text.toString().toIntOrNull() ?: 5
            val nOff = vadNOffEdit.text.toString().toIntOrNull() ?: 75
            val minUtterMs = vadMinUtterEdit.text.toString().toIntOrNull() ?: 700
            val minRatio = vadMinSpeechRatioEdit.text.toString().toFloatOrNull() ?: 0.35f
            val mergeGap = vadMergeGapEdit.text.toString().toIntOrNull() ?: 250
            config = VadUserConfig(
                mode = mode,
                minSpeechMs = minSpeechMs.coerceAtLeast(0),
                endSilenceMs = config.endSilenceMs,
                amplitudeGateEnabled = vadAmpGateCheckbox.isChecked,
                minAmplitude = minAmp.coerceIn(0, 32767),
                peakMin = peakMin.coerceIn(0, 32767),
                rmsMinDbfs = rmsMin.coerceIn(-100f, 0f),
                noiseMarginDb = margin.coerceIn(0f, 30f),
                nOn = nOn.coerceAtLeast(1),
                nOff = nOff.coerceAtLeast(1),
                minUtterMs = minUtterMs.coerceAtLeast(0),
                minSpeechRatio = minRatio.coerceIn(0f, 1f),
                mergeGapMs = mergeGap.coerceAtLeast(0),
                alwaysOn = vadAlwaysOnSwitch.isChecked,
                autoEndDetect = vadAutoEndSwitch.isChecked,
                manualListening = config.manualListening,
                showTranscriptPopup = vadShowPopupSwitch.isChecked
            )
            VadConfigStore.save(this, config)
            sendBroadcast(android.content.Intent(MicrophoneForegroundService.ACTION_CONFIG_CHANGED).setPackage(packageName))
            updateListeningUi(config.alwaysOn || config.manualListening)
        }

        val hideKeyboard = View.OnTouchListener { _, _ ->
            currentFocus?.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(root.windowToken, 0)
            false
        }
        root.setOnTouchListener(hideKeyboard)
        scroll.setOnTouchListener(hideKeyboard)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            levelReceiver,
            IntentFilter(MicrophoneForegroundService.ACTION_LEVEL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            listeningReceiver,
            IntentFilter(MicrophoneForegroundService.ACTION_LISTENING_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val config = VadConfigStore.load(this)
        alwaysOn = config.alwaysOn
        updateListeningUi(config.alwaysOn || config.manualListening)
    }

    override fun onStop() {
        unregisterReceiver(levelReceiver)
        unregisterReceiver(listeningReceiver)
        super.onStop()
    }

    private fun updateListeningUi(listening: Boolean) {
        val alwaysOnFlag = alwaysOn
        vadListenToggleBtn.isEnabled = !alwaysOnFlag
        vadListenToggleBtn.text = if (listening) "Stop listening" else "Start listening"
        val color = if (listening) android.graphics.Color.GREEN else android.graphics.Color.DKGRAY
        vadListeningIndicator.setBackgroundColor(color)
        vadListeningLabel.text = if (listening) "Listening: on" else "Listening: off"
    }
}
