package com.mystuff.simpletutor

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.ArrayAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.measureTimeMillis

class DebugPanelActivity : AppCompatActivity() {

    private data class TtsResult(val message: String, val filePath: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()
    private val octetStream = "application/octet-stream".toMediaType()
    private val llmModels = listOf("gpt-5.2", "gpt-5.2-2025-12-11")
    private val reasoningEfforts = listOf("none", "low", "medium", "high")
    private val sttModels = listOf("gpt-4o-transcribe")
    private val ttsModels = listOf("tts-1", "tts-1-hd", "gpt-4o-mini-tts")
    private val sttLanguages = listOf(
        "auto",
        "en", "es", "fr", "de", "it", "pt", "nl",
        "ru", "ja", "ko", "zh", "hi", "ar"
    )
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var ttsPlayer: MediaPlayer? = null

    companion object {
        private const val PREFS_NAME = "debug_panel_prefs"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_STT_MODEL = "stt_model"
        private const val KEY_STT_LANGUAGE = "stt_language"
        private const val KEY_STT_PROMPT = "stt_prompt"
        private const val KEY_TTS_MODEL = "tts_model"
        private const val KEY_TTS_VOICE = "tts_voice"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_panel)

        val llmModelSpinner = findViewById<Spinner>(R.id.llmModelSpinner)
        val reasoningEffortSpinner = findViewById<Spinner>(R.id.reasoningEffortSpinner)
        val sttModelSpinner = findViewById<Spinner>(R.id.sttModelSpinner)
        val ttsModelSpinner = findViewById<Spinner>(R.id.ttsModelSpinner)
        val sttLanguageSpinner = findViewById<Spinner>(R.id.sttLanguageSpinner)
        val sttPromptEdit = findViewById<EditText>(R.id.sttPromptEdit)
        val voiceSpinner = findViewById<Spinner>(R.id.voiceSpinner)
        val voiceLabel = findViewById<TextView>(R.id.voiceLabel)
        val audioPathEdit = findViewById<EditText>(R.id.audioPathEdit)
        val inputEdit = findViewById<EditText>(R.id.inputEdit)
        val status = findViewById<TextView>(R.id.statusText)
        val out = findViewById<TextView>(R.id.outputText)
        val recordSttBtn = findViewById<Button>(R.id.recordSttBtn)
        val playSttBtn = findViewById<Button>(R.id.playSttBtn)
        val root = findViewById<android.view.View>(R.id.debug_panel_root)

        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                hideKeyboard()
            }
            false
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        llmModelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            llmModels
        )

        reasoningEffortSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            reasoningEfforts
        )
        val savedModel = prefs.getString(KEY_LLM_MODEL, null)
        if (savedModel != null) {
            val idx = llmModels.indexOf(savedModel)
            if (idx >= 0) llmModelSpinner.setSelection(idx)
        }
        val savedEffort = prefs.getString(KEY_REASONING_EFFORT, null)
        if (savedEffort != null) {
            val idx = reasoningEfforts.indexOf(savedEffort)
            if (idx >= 0) reasoningEffortSpinner.setSelection(idx)
        } else {
            val idx = reasoningEfforts.indexOf("medium")
            reasoningEffortSpinner.setSelection(if (idx >= 0) idx else 0)
        }

        fun updateReasoningWarning() {
            val model = llmModelSpinner.selectedItem?.toString().orEmpty()
            val effort = reasoningEffortSpinner.selectedItem?.toString().orEmpty()
            val supports = model.startsWith("gpt-5") || model.startsWith("o-")
            if (effort.equals("none", ignoreCase = true)) {
                return
            }
            if (!supports && effort.isNotBlank()) {
                status.text = "Warning: reasoning.effort is only supported for gpt-5 / o-series models."
            }
        }

        llmModelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString(KEY_LLM_MODEL, llmModelSpinner.selectedItem?.toString()).apply()
                updateReasoningWarning()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        reasoningEffortSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString(KEY_REASONING_EFFORT, reasoningEffortSpinner.selectedItem?.toString()).apply()
                updateReasoningWarning()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        sttModelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sttModels
        )

        ttsModelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ttsModels
        )

        sttLanguageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sttLanguages
        )

        voiceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            TtsVoiceCatalog.voices
        )

        val savedSttModel = prefs.getString(KEY_STT_MODEL, null)
        if (savedSttModel != null) {
            val idx = sttModels.indexOf(savedSttModel)
            if (idx >= 0) sttModelSpinner.setSelection(idx)
        }
        val savedSttLang = prefs.getString(KEY_STT_LANGUAGE, null)
        if (savedSttLang != null) {
            val idx = sttLanguages.indexOf(savedSttLang)
            if (idx >= 0) sttLanguageSpinner.setSelection(idx)
        }
        val savedTtsModel = prefs.getString(KEY_TTS_MODEL, null)
        if (savedTtsModel != null) {
            val idx = ttsModels.indexOf(savedTtsModel)
            if (idx >= 0) ttsModelSpinner.setSelection(idx)
        }
        val savedVoice = prefs.getString(KEY_TTS_VOICE, null)
        if (savedVoice != null) {
            val idx = TtsVoiceCatalog.voices.indexOf(savedVoice)
            if (idx >= 0) voiceSpinner.setSelection(idx)
        }
        val savedPrompt = prefs.getString(KEY_STT_PROMPT, null)
        if (savedPrompt != null && savedPrompt != sttPromptEdit.text.toString()) {
            sttPromptEdit.setText(savedPrompt)
        }

        sttModelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString(KEY_STT_MODEL, sttModelSpinner.selectedItem?.toString()).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        sttLanguageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString(KEY_STT_LANGUAGE, sttLanguageSpinner.selectedItem?.toString()).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        ttsModelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString(KEY_TTS_MODEL, ttsModelSpinner.selectedItem?.toString()).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        voiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString(KEY_TTS_VOICE, voiceSpinner.selectedItem?.toString()).apply()
                getSharedPreferences(PipelinePrefs.NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PipelinePrefs.KEY_TTS_VOICE, voiceSpinner.selectedItem?.toString())
                    .apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        sttPromptEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString(KEY_STT_PROMPT, s?.toString().orEmpty()).apply()
            }
        })


        fun keySuffix(): String {
            val k = BuildConfig.OPENAI_API_KEY
            return if (k.length >= 8) "...${k.takeLast(4)}" else "(missing)"
        }

        findViewById<Button>(R.id.listModelsBtn).setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                status.text = "GET /v1/models (key ${keySuffix()})"
                val result = withContext(Dispatchers.IO) { listModels() }
                out.text = result
                status.text = "Done"
            }
        }

        recordSttBtn.setOnClickListener {
            if (!hasAudioPermission()) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    2001
                )
                status.text = "Requesting RECORD_AUDIO permission..."
                return@setOnClickListener
            }
            if (isRecording) {
                stopRecording()
                recordSttBtn.text = "Record STT (mic)"
                audioPathEdit.setText(recordingFile?.absolutePath.orEmpty())
                status.text = "Recorded: ${recordingFile?.name.orEmpty()}"
            } else {
                val started = startRecording()
                if (started) {
                    recordSttBtn.text = "Stop recording"
                    status.text = "Recording..."
                } else {
                    status.text = "Failed to start recording"
                }
            }
        }

        playSttBtn.setOnClickListener {
            val path = audioPathEdit.text.toString().trim()
            if (path.isBlank()) {
                status.text = "No recording to play."
            } else {
                val result = playAudio(path)
                status.text = result
            }
        }

        findViewById<Button>(R.id.testLlmBtn).setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                val input = inputEdit.text.toString()
                val model = llmModelSpinner.selectedItem?.toString().orEmpty()
                val effort = reasoningEffortSpinner.selectedItem?.toString().orEmpty()
                status.text = "POST /v1/responses (key ${keySuffix()})"
                val result = withContext(Dispatchers.IO) { testResponses(model, input, effort) }
                out.text = result
                status.text = "Done"
            }
        }

        findViewById<Button>(R.id.testSttBtn).setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                val model = sttModelSpinner.selectedItem?.toString().orEmpty()
                val path = audioPathEdit.text.toString().trim()
                val language = sttLanguageSpinner.selectedItem?.toString().orEmpty()
                val prompt = sttPromptEdit.text.toString().trim()
                status.text = "POST /v1/audio/transcriptions (key ${keySuffix()})"
                val result = withContext(Dispatchers.IO) { testTranscribe(model, path, language, prompt) }
                out.text = result
                status.text = "Done"
            }
        }

        findViewById<Button>(R.id.testTtsBtn).setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                val model = ttsModelSpinner.selectedItem?.toString().orEmpty()
                val voice = voiceSpinner.selectedItem?.toString().orEmpty()
                val input = inputEdit.text.toString()
                status.text = "POST /v1/audio/speech (key ${keySuffix()})"
                val result = withContext(Dispatchers.IO) { testTts(model, voice, input) }
                out.text = result.message
                result.filePath?.let { playAudio(it) }
                status.text = "Done"
            }
        }
    }


    private fun authHeader(): String = "Bearer ${BuildConfig.OPENAI_API_KEY}"

    private fun listModels(): String {
        val req = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .get()
            .addHeader("Authorization", authHeader())
            .build()

        var code = -1
        var body = ""
        return try {
            val ms = measureTimeMillis {
                client.newCall(req).execute().use { resp ->
                    code = resp.code
                    body = resp.body?.string().orEmpty()
                }
            }
            "HTTP $code (${ms}ms)\n\n$body"
        } catch (e: Exception) {
            "Request failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
        }
    }

    private fun testResponses(model: String, input: String, effort: String): String {
        val effortBlock = if (effort.isNotBlank() && !effort.equals("none", ignoreCase = true)) {
            "\"reasoning\": {\"effort\": \"${escapeJson(effort)}\"},"
        } else {
            ""
        }
        val payload = """
            {
              "model": "${escapeJson(model)}",
              $effortBlock
              "input": "${escapeJson(input)}"
            }
        """.trimIndent()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .post(payload.toRequestBody(json))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", authHeader())
            .build()

        var code = -1
        var body = ""
        return try {
            val ms = measureTimeMillis {
                client.newCall(req).execute().use { resp ->
                    code = resp.code
                    body = resp.body?.string().orEmpty()
                }
            }
            if (code >= 400) {
                "HTTP $code (${ms}ms)\n\nError:\n$body"
            } else {
                formatResponsesOutput(body)
            }
        } catch (e: Exception) {
            "Request failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}\n\nRequest:\n$payload"
        }
    }

    private fun testTts(model: String, voice: String, input: String): TtsResult {
        if (input.isBlank()) {
            return TtsResult("Missing input text for TTS.", null)
        }
        val payload = """
            {
              "model": "${escapeJson(model)}",
              "voice": "${escapeJson(voice)}",
              "input": "${escapeJson(input)}"
            }
        """.trimIndent()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .post(payload.toRequestBody(json))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", authHeader())
            .build()

        var code = -1
        var bytes = 0
        var filePath = ""
        var errorBody = ""
        return try {
            val ms = measureTimeMillis {
                client.newCall(req).execute().use { resp ->
                    code = resp.code
                    val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                    if (code >= 400) {
                        errorBody = bodyBytes.toString(Charsets.UTF_8)
                    } else {
                        bytes = bodyBytes.size
                        val outFile = File(cacheDir, "tts_${System.currentTimeMillis()}.mp3")
                        outFile.writeBytes(bodyBytes)
                        filePath = outFile.absolutePath
                    }
                }
            }
            if (code >= 400) {
                TtsResult("HTTP $code (${ms}ms)\n\nRequest:\n$payload\n\nError:\n$errorBody", null)
            } else {
                TtsResult("HTTP $code (${ms}ms)\nSaved: $filePath\nBytes: $bytes\n\nRequest:\n$payload", filePath)
            }
        } catch (e: Exception) {
            TtsResult("Request failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}\n\nRequest:\n$payload", null)
        }
    }

    private fun testTranscribe(model: String, audioPath: String, language: String, prompt: String): String {
        if (model.isBlank()) {
            return "Missing model for STT."
        }
        if (audioPath.isBlank()) {
            return "Missing audio file path for STT."
        }
        val file = File(audioPath)
        if (!file.exists()) {
            return "Audio file not found: $audioPath"
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .apply {
                if (language.isNotBlank() && language != "auto") {
                    addFormDataPart("language", language)
                }
                if (prompt.isNotBlank()) {
                    addFormDataPart("prompt", prompt)
                }
            }
            .addFormDataPart("file", file.name, file.asRequestBody(octetStream))
            .build()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .post(body)
            .addHeader("Authorization", authHeader())
            .build()

        var code = -1
        var respBody = ""
        return try {
            val ms = measureTimeMillis {
                client.newCall(req).execute().use { resp ->
                    code = resp.code
                    respBody = resp.body?.string().orEmpty()
                }
            }
            if (code >= 400) {
                "HTTP $code (${ms}ms)\n\nError:\n$respBody"
            } else {
                formatTranscribeResponse(respBody)
            }
        } catch (e: Exception) {
            "Request failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun formatResponsesOutput(body: String): String {
        return try {
            val obj = JSONObject(body)
            val direct = obj.optString("output_text")
            if (direct.isNotBlank()) {
                direct
            } else {
                extractTextFromOutput(obj.optJSONArray("output")).ifBlank { body }
            }
        } catch (_: Exception) {
            body
        }
    }

    private fun formatTranscribeResponse(body: String): String {
        return try {
            val obj = JSONObject(body)
            val text = obj.optString("text")
            if (text.isNotBlank()) text else body
        } catch (_: Exception) {
            body
        }
    }

    private fun extractTextFromOutput(output: JSONArray?): String {
        if (output == null) return ""
        val parts = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                val text = c.optString("text")
                if (text.isNotBlank()) {
                    if (parts.isNotEmpty()) parts.append('\n')
                    parts.append(text)
                }
            }
        }
        return parts.toString()
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecording(): Boolean {
        return try {
            val file = File(cacheDir, "stt_${System.currentTimeMillis()}.m4a")
            val mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            recordingFile = file
            isRecording = true
            true
        } catch (_: Exception) {
            recorder?.release()
            recorder = null
            isRecording = false
            false
        }
    }

    private fun stopRecording() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        } finally {
            recorder?.release()
            recorder = null
            isRecording = false
        }
    }

    private fun playAudio(path: String): String {
        val file = File(path)
        if (!file.exists()) {
            return "Audio file not found: $path"
        }
        if (file.length() == 0L) {
            return "Audio file is empty: $path"
        }
        return try {
            ttsPlayer?.release()
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(path)
                setOnPreparedListener { it.start() }
                setOnErrorListener { mp, what, extra ->
                    mp.release()
                    if (ttsPlayer === mp) {
                        ttsPlayer = null
                    }
                    false
                }
                prepareAsync()
            }
            player.setOnCompletionListener {
                it.release()
                if (ttsPlayer === it) {
                    ttsPlayer = null
                }
            }
            ttsPlayer = player
            "Playing recording (${file.length()} bytes)..."
        } catch (e: Exception) {
            ttsPlayer?.release()
            ttsPlayer = null
            "Playback failed: ${e.message ?: "unknown error"}"
        }
    }

    private fun hideKeyboard() {
        val view = currentFocus ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    override fun onStop() {
        if (isRecording) {
            stopRecording()
        }
        ttsPlayer?.release()
        ttsPlayer = null
        super.onStop()
    }
}
