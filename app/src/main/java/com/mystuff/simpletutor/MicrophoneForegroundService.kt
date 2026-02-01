package com.mystuff.simpletutor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.util.Log
import com.mystuff.simpletutor.audio.UtteranceSegmenter
import com.mystuff.simpletutor.audio.VadConfig
import com.mystuff.simpletutor.audio.VadProcessor
import com.mystuff.simpletutor.audio.VadConfigStore
import com.mystuff.simpletutor.audio.VadUserConfig
import com.mystuff.simpletutor.audio.WavWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MicrophoneForegroundService : Service() {
    private enum class Action { OPEN_DEBUG, GO_HOME, NONE }
    private enum class PipelineRoute(val value: String) {
        TEST_STT("test_stt"),
        TUTOR("tutor");

        companion object {
            fun fromValue(value: String?): PipelineRoute =
                values().firstOrNull { it.value == value } ?: TEST_STT
        }
    }
    private val tag = "MicrophoneForegroundService"

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private val json = "application/json".toMediaType()
    private val octetStream = "application/octet-stream".toMediaType()
    private var loopJob: Job? = null
    private var lastTranscript: String = ""
    private var lastActionAtMs: Long = 0L
    private var audioRecord: AudioRecord? = null
    private lateinit var currentConfig: VadUserConfig
    private var tutorUserId: String = "alexei-1"
    private var tutorLanguageLabel: String = "Japanese"
    private var tutorLanguageCode: String = "ja-JP"
    private var tutorMode: String = "chat"
    private var pipelineRoute: PipelineRoute = PipelineRoute.TEST_STT
    private var lastConfigCheckMs: Long = 0L
    private var lastLevelBroadcastMs: Long = 0L
    private var lastFrameLogMs: Long = 0L
    private var framesSinceLog: Int = 0
    private var speechFramesSinceLog: Int = 0
    private var noiseDbfs: Float = -60f
    @Volatile private var pendingManualStop: Boolean = false
    @Volatile private var configDirty: Boolean = false
    private val sendMutex = Mutex()
    private val configReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CONFIG_CHANGED) return
            val beforeManual = currentConfig.manualListening
            currentConfig = VadConfigStore.load(this@MicrophoneForegroundService)
            Log.d(tag, "Config changed: alwaysOn=${currentConfig.alwaysOn}, manual=${currentConfig.manualListening}, autoEnd=${currentConfig.autoEndDetect}")
            if (beforeManual && !currentConfig.manualListening) {
                pendingManualStop = true
            }
            if (!isListeningEnabled()) {
                stopAudioRecord()
            }
            broadcastListeningStatus()
            broadcastPipelineState(if (isListeningEnabled()) "Listening" else "Offline")
            configDirty = true
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        currentConfig = VadConfigStore.load(this)
        pipelineRoute = loadPipelineRoute()
        Log.d(tag, "Service created: alwaysOn=${currentConfig.alwaysOn}, manual=${currentConfig.manualListening}")
        ContextCompat.registerReceiver(
            this,
            configReceiver,
            IntentFilter(ACTION_CONFIG_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        broadcastListeningStatus()
        broadcastPipelineState(if (isListeningEnabled()) "Listening" else "Offline")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_SET_MANUAL_LISTEN -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                if (currentConfig.manualListening != enabled) {
                    if (!enabled) {
                        pendingManualStop = true
                    }
                    setManualListening(enabled)
                }
            }
            ACTION_SET_PIPELINE_ROUTE -> {
                val route = PipelineRoute.fromValue(intent.getStringExtra(EXTRA_ROUTE))
                pipelineRoute = route
                savePipelineRoute(route)
                Log.d(tag, "Pipeline route: ${route.value}")
            }
            ACTION_SET_TUTOR_CONTEXT -> {
                tutorUserId = intent.getStringExtra(EXTRA_USER_ID) ?: tutorUserId
                tutorLanguageLabel = intent.getStringExtra(EXTRA_LANG_LABEL) ?: tutorLanguageLabel
                tutorLanguageCode = intent.getStringExtra(EXTRA_LANG_CODE) ?: tutorLanguageCode
                tutorMode = intent.getStringExtra(EXTRA_MODE) ?: tutorMode
                Log.d(tag, "Tutor context: user=$tutorUserId, lang=$tutorLanguageLabel ($tutorLanguageCode), mode=$tutorMode")
            }
        }
        startListeningLoop()
        Log.d(tag, "Service start command")
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        stopAudioRecord()
        unregisterReceiver(configReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Microphone service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows that SimpleTutor is listening in the foreground."
        }

        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SimpleTutor is listening")
            .setContentText("Tap to return to the app.")
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showTranscriptPopup(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val intent = Intent(this, TranscriptPopupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(TranscriptPopupActivity.EXTRA_TEXT, trimmed)
        startActivity(intent)
    }

    private fun startListeningLoop() {
        if (loopJob != null) return
        loopJob = serviceScope.launch {
            var vad = VadProcessor(mode = currentConfig.mode)
            var lastManual = currentConfig.manualListening && !currentConfig.alwaysOn
            var manualSessionStarted = false
            var manualSilenceFrames = 0
            var segmenter = UtteranceSegmenter(
                frameMs = FRAME_MS,
                nOn = if (lastManual) 1 else currentConfig.nOn,
                nOff = currentConfig.nOff,
                minUtterMs = if (lastManual) 0 else currentConfig.minUtterMs,
                minSpeechRatio = if (lastManual) 0f else currentConfig.minSpeechRatio,
                mergeGapMs = currentConfig.mergeGapMs,
                allowShortUtterances = lastManual,
                captureAllFrames = lastManual,
                allowEndWithoutSpeech = false,
                deferFinalizeOnEnd = lastManual
            )
            var record: AudioRecord? = null
            val frameBuffer = ShortArray(VadConfig.frameSizeSamples)
            val byteBuffer = ByteArray(VadConfig.frameSizeSamples * 2)

            suspend fun sendUtterance(bytes: ByteArray, configSnapshot: VadUserConfig) {
                val wavFile = File(cacheDir, "utt_${System.currentTimeMillis()}.wav")
                WavWriter.writePcm16Mono(wavFile, bytes, VadConfig.sampleRateHz)
                val durationMs = (bytes.size / 2.0 / VadConfig.sampleRateHz * 1000).toInt()
                if (pipelineRoute == PipelineRoute.TEST_STT) {
                    showStatusBanner("Sending snippet (${durationMs}ms, ${bytes.size} bytes)...")
                }
                broadcastPipelineState("Thinking")
                val transcript = transcribe(wavFile)
                if (transcript.isNotBlank() && transcript != lastTranscript) {
                    lastTranscript = transcript
                    if (pipelineRoute == PipelineRoute.TEST_STT && configSnapshot.showTranscriptPopup) {
                        showTranscriptPopup(transcript)
                    }
                    if (pipelineRoute == PipelineRoute.TUTOR) {
                        broadcastChatMessage("user", transcript)
                        val response = generateTutorReply(transcript)
                        if (response.isNotBlank()) {
                            broadcastChatMessage("tutor", response)
                            broadcastPipelineState("Speaking")
                            playTts(response)
                        }
                    }
                    val action = classify(transcript)
                    if (action != Action.NONE && shouldFireAction()) {
                        handleAction(action)
                    }
                }
                broadcastPipelineState(if (isListeningEnabled()) "Listening" else "Offline")
                if (pipelineRoute == PipelineRoute.TEST_STT) {
                    hideStatusBanner()
                }
            }

            fun handleUtterance(bytes: ByteArray) {
                val configSnapshot = currentConfig
                serviceScope.launch {
                    sendMutex.withLock {
                        sendUtterance(bytes, configSnapshot)
                    }
                }
            }

            while (isActive) {
                if (pendingManualStop) {
                    pendingManualStop = false
                    val forced = segmenter.forceEmitBuffer()
                    if (forced != null) handleUtterance(forced)
                    Log.d(tag, "Manual stop pending: forcedBytes=${forced?.size ?: 0}")
                }
                maybeReloadConfig { updated ->
                    currentConfig = updated
                    val manualNow = currentConfig.manualListening && !currentConfig.alwaysOn
                    vad = VadProcessor(mode = updated.mode)
                    segmenter = UtteranceSegmenter(
                        frameMs = FRAME_MS,
                        nOn = if (manualNow) 1 else updated.nOn,
                        nOff = updated.nOff,
                        minUtterMs = if (manualNow) 0 else updated.minUtterMs,
                        minSpeechRatio = if (manualNow) 0f else updated.minSpeechRatio,
                        mergeGapMs = updated.mergeGapMs,
                        allowShortUtterances = manualNow,
                        captureAllFrames = manualNow,
                        allowEndWithoutSpeech = false,
                        deferFinalizeOnEnd = manualNow
                    )
                    if (!manualNow) {
                        manualSessionStarted = false
                        manualSilenceFrames = 0
                    }
                    lastManual = manualNow
                    Log.d(tag, "Config reloaded: alwaysOn=${updated.alwaysOn}, manual=${updated.manualListening}, autoEnd=${updated.autoEndDetect}")
                }
                if (!isListeningEnabled()) {
                    stopAudioRecord()
                    record = null
                    delay(200)
                    continue
                }
                if (record == null) {
                    record = startAudioRecord()
                    if (record == null) {
                        delay(200)
                        continue
                    }
                }
                val read = record.read(frameBuffer, 0, frameBuffer.size)
                if (read <= 0) {
                    delay(10)
                    continue
                }
                val peakAbs = maxAbs(frameBuffer, read)
                val rmsDbfs = rmsDbfs(frameBuffer, read)
                val level = peakAbs
                maybeBroadcastLevel(level)
                shortsToBytes(frameBuffer, byteBuffer, read)
                val frameBytes = if (read == VadConfig.frameSizeSamples) {
                    byteBuffer
                } else {
                    byteBuffer.copyOf(read * 2)
                }
                val manualListening = currentConfig.manualListening && !currentConfig.alwaysOn
                if (manualListening && !manualSessionStarted) {
                    segmenter.forceStart()
                    manualSessionStarted = true
                    manualSilenceFrames = 0
                    Log.d(tag, "Manual session started")
                }
                val isSpeech = if (manualListening) {
                    true // manual mode records everything; end detection uses silence only
                } else {
                    val ampGatePass = peakAbs >= currentConfig.peakMin && rmsDbfs >= currentConfig.rmsMinDbfs
                    if (!ampGatePass) {
                        noiseDbfs = ema(noiseDbfs, rmsDbfs, NOISE_ALPHA)
                    }
                    val noiseGatePass = rmsDbfs >= noiseDbfs + currentConfig.noiseMarginDb
                    if (currentConfig.amplitudeGateEnabled && level < currentConfig.minAmplitude) {
                        false
                    } else {
                        ampGatePass && noiseGatePass && vad.isSpeech(frameBytes)
                    }
                }
                framesSinceLog++
                if (isSpeech) speechFramesSinceLog++
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastFrameLogMs >= 1000L) {
                    Log.d(
                        tag,
                        "Frames/sec=${framesSinceLog}, speech=${speechFramesSinceLog}, manual=$manualListening, peak=$peakAbs, rms=${"%.1f".format(rmsDbfs)}, noise=${"%.1f".format(noiseDbfs)}"
                    )
                    framesSinceLog = 0
                    speechFramesSinceLog = 0
                    lastFrameLogMs = nowMs
                }
                val utterance = segmenter.processFrame(isSpeech, frameBytes)
                if (manualListening && currentConfig.autoEndDetect) {
                    val isSilent = peakAbs < currentConfig.peakMin && rmsDbfs < currentConfig.rmsMinDbfs
                    manualSilenceFrames = if (isSilent) manualSilenceFrames + 1 else 0
                    if (manualSilenceFrames >= currentConfig.nOff) {
                        pendingManualStop = true
                        setManualListening(false)
                        manualSessionStarted = false
                        manualSilenceFrames = 0
                        Log.d(tag, "Manual silence end; auto stop (queued)")
                    }
                } else {
                    val endDetected = segmenter.consumeEndDetected()
                    if (endDetected && shouldAutoStopAfterUtterance()) {
                        pendingManualStop = true
                        setManualListening(false)
                        manualSessionStarted = false
                        Log.d(tag, "End detected; auto stop (queued)")
                    }
                }
                if (utterance != null) {
                    handleUtterance(utterance)
                    Log.d(tag, "Utterance ready: bytes=${utterance.size}")
                }
            }
        }
    }

    private fun startAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            VadConfig.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val bufferSize = maxOf(minBuffer, VadConfig.frameSizeSamples * 2 * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            VadConfig.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.w(tag, "AudioRecord init failed")
            return null
        }
        record.startRecording()
        audioRecord = record
        Log.d(tag, "AudioRecord started")
        return record
    }

    private fun stopAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        } finally {
            audioRecord?.release()
            audioRecord = null
            Log.d(tag, "AudioRecord stopped")
        }
    }

    private fun transcribe(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        Log.d(tag, "Transcribing ${file.name} (${file.length()} bytes)")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", STT_MODEL)
            .addFormDataPart("file", file.name, file.asRequestBody(octetStream))
            .build()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .post(body)
            .addHeader("Authorization", authHeader())
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return ""
                JSONObject(respBody).optString("text")
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun generateTutorReply(text: String): String {
        val prompt = escapeJson(buildTutorPrompt(text))
        val payload = """
            {
              "model": "$TUTOR_MODEL",
              "input": "$prompt"
            }
        """.trimIndent()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .post(payload.toRequestBody(json))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", authHeader())
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val body = resp.body?.string().orEmpty()
                extractOutputText(body).trim()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun buildTutorPrompt(text: String): String {
        return "You are a friendly language tutor. The learner speaks English and is practicing $tutorLanguageLabel. Reply in $tutorLanguageLabel with short, natural responses, and include brief English help only if needed. User said: $text"
    }

    private suspend fun playTts(text: String) {
        val payload = """
            {
              "model": "$TTS_MODEL",
              "voice": "$TTS_VOICE",
              "input": "${escapeJson(text)}"
            }
        """.trimIndent()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .post(payload.toRequestBody(json))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", authHeader())
            .build()

        val audioBytes = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                resp.body?.bytes() ?: return
            }
        } catch (_: Exception) {
            return
        }

        val outFile = File(cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        outFile.writeBytes(audioBytes)
        playAudioFile(outFile)
    }

    private suspend fun playAudioFile(file: File) {
        if (!file.exists()) return
        suspendCoroutine<Unit> { cont ->
            val player = MediaPlayer()
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener {
                it.release()
                cont.resume(Unit)
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                cont.resume(Unit)
                true
            }
            try {
                player.setDataSource(file.absolutePath)
                player.prepareAsync()
            } catch (e: Exception) {
                player.release()
                cont.resume(Unit)
            }
        }
    }

    private fun classify(text: String): Action {
        val payload = """
            {
              "model": "$CLASSIFIER_MODEL",
              "input": "Classify the user's intent. If they want to open the debug panel, action=open_debug. If they want to go back to the main screen, action=go_home. Otherwise action=none. Reply ONLY as JSON: {\"action\":\"open_debug|go_home|none\"}. User said: ${escapeJson(text)}"
            }
        """.trimIndent()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .post(payload.toRequestBody(json))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", authHeader())
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Action.NONE
                val body = resp.body?.string().orEmpty()
                val outputText = extractOutputText(body)
                val obj = JSONObject(outputText)
                when (obj.optString("action")) {
                    "open_debug" -> Action.OPEN_DEBUG
                    "go_home" -> Action.GO_HOME
                    else -> Action.NONE
                }
            }
        } catch (_: Exception) {
            Action.NONE
        }
    }

    private fun extractOutputText(body: String): String {
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

    private fun handleAction(action: Action) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            when (action) {
                Action.OPEN_DEBUG -> {
                    val intent = Intent().setClassName(
                        this,
                        "com.mystuff.simpletutor.DebugPanelActivity"
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                Action.GO_HOME -> {
                    val intent = Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                else -> Unit
            }
            Log.d(tag, "Action handled: $action")
        }
    }

    private fun authHeader(): String = "Bearer ${BuildConfig.OPENAI_API_KEY}"

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun shortsToBytes(src: ShortArray, dst: ByteArray, count: Int) {
        var i = 0
        var j = 0
        while (i < count) {
            val v = src[i].toInt()
            dst[j] = (v and 0xFF).toByte()
            dst[j + 1] = ((v shr 8) and 0xFF).toByte()
            i++
            j += 2
        }
    }

    private fun shouldFireAction(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastActionAtMs > ACTION_COOLDOWN_MS) {
            lastActionAtMs = now
            true
        } else {
            false
        }
    }

    private fun isListeningEnabled(): Boolean =
        currentConfig.alwaysOn || currentConfig.manualListening

    private fun shouldAutoStopAfterUtterance(): Boolean =
        !currentConfig.alwaysOn && currentConfig.manualListening && currentConfig.autoEndDetect

    private fun setManualListening(enabled: Boolean) {
        if (currentConfig.manualListening == enabled) return
        currentConfig = currentConfig.copy(manualListening = enabled)
        VadConfigStore.save(this, currentConfig)
        if (!enabled) {
            stopAudioRecord()
        }
        broadcastListeningStatus()
        broadcastPipelineState(if (isListeningEnabled()) "Listening" else "Offline")
    }

    private fun broadcastListeningStatus() {
        val intent = Intent(ACTION_LISTENING_STATUS)
            .setPackage(packageName)
            .putExtra("listening", isListeningEnabled())
            .putExtra("alwaysOn", currentConfig.alwaysOn)
            .putExtra("manualListening", currentConfig.manualListening)
        sendBroadcast(intent)
    }

    private fun broadcastChatMessage(role: String, text: String) {
        val intent = Intent(ACTION_CHAT_MESSAGE)
            .setPackage(packageName)
            .putExtra(EXTRA_ROLE, role)
            .putExtra(EXTRA_TEXT, text)
        sendBroadcast(intent)
    }

    private fun broadcastPipelineState(state: String) {
        val intent = Intent(ACTION_PIPELINE_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
        sendBroadcast(intent)
    }

    private fun loadPipelineRoute(): PipelineRoute {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PipelineRoute.fromValue(prefs.getString(PREF_PIPELINE_ROUTE, PipelineRoute.TEST_STT.value))
    }

    private fun savePipelineRoute(route: PipelineRoute) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_PIPELINE_ROUTE, route.value).apply()
    }

    private fun maxAbs(samples: ShortArray, count: Int): Int {
        var max = 0
        var i = 0
        while (i < count) {
            val v = kotlin.math.abs(samples[i].toInt())
            if (v > max) max = v
            i++
        }
        return max
    }

    private fun rmsDbfs(samples: ShortArray, count: Int): Float {
        if (count <= 0) return -120f
        var sum = 0.0
        var i = 0
        while (i < count) {
            val v = samples[i].toDouble()
            sum += v * v
            i++
        }
        val mean = sum / count
        val rms = kotlin.math.sqrt(mean)
        if (rms <= 0.0) return -120f
        val db = 20.0 * kotlin.math.log10(rms / 32768.0)
        return db.toFloat()
    }

    private fun ema(prev: Float, next: Float, alpha: Float): Float {
        return (alpha * next) + (1f - alpha) * prev
    }

    private fun maybeReloadConfig(onChanged: (VadUserConfig) -> Unit) {
        val now = System.currentTimeMillis()
        if (!configDirty && now - lastConfigCheckMs < CONFIG_REFRESH_MS) return
        lastConfigCheckMs = now
        val updated = VadConfigStore.load(this)
        if (updated != currentConfig) {
            onChanged(updated)
        }
        configDirty = false
    }

    private fun maybeBroadcastLevel(level: Int) {
        val now = System.currentTimeMillis()
        if (now - lastLevelBroadcastMs < LEVEL_BROADCAST_MS) return
        lastLevelBroadcastMs = now
        val intent = Intent(ACTION_LEVEL)
            .setPackage(packageName)
            .putExtra("level", level)
        sendBroadcast(intent)
    }

    private fun showStatusBanner(text: String) {
        val intent = Intent(this, StatusBannerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(StatusBannerActivity.EXTRA_TEXT, text)
        startActivity(intent)
    }

    private fun hideStatusBanner() {
        sendBroadcast(Intent(StatusBannerActivity.ACTION_HIDE))
    }

    companion object {
        private const val CHANNEL_ID = "microphone_service"
        private const val NOTIFICATION_ID = 100
        private const val STT_MODEL = "gpt-4o-transcribe"
        private const val TUTOR_MODEL = "gpt-5.2"
        private const val TTS_MODEL = "gpt-4o-mini-tts"
        private const val TTS_VOICE = "alloy"
        private const val CLASSIFIER_MODEL = "gpt-5.2"
        private const val ACTION_COOLDOWN_MS = 4000L
        private const val FRAME_MS = 20
        private const val CONFIG_REFRESH_MS = 1000L
        private const val LEVEL_BROADCAST_MS = 200L
        const val ACTION_LEVEL = "com.mystuff.simpletutor.VAD_LEVEL"
        const val ACTION_CONFIG_CHANGED = "com.mystuff.simpletutor.VAD_CONFIG_CHANGED"
        const val ACTION_LISTENING_STATUS = "com.mystuff.simpletutor.VAD_LISTENING_STATUS"
        const val ACTION_PIPELINE_STATE = "com.mystuff.simpletutor.PIPELINE_STATE"
        const val ACTION_CHAT_MESSAGE = "com.mystuff.simpletutor.CHAT_MESSAGE"
        const val ACTION_SET_MANUAL_LISTEN = "com.mystuff.simpletutor.SET_MANUAL_LISTEN"
        const val ACTION_SET_PIPELINE_ROUTE = "com.mystuff.simpletutor.SET_PIPELINE_ROUTE"
        const val ACTION_SET_TUTOR_CONTEXT = "com.mystuff.simpletutor.SET_TUTOR_CONTEXT"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_STATE = "state"
        const val EXTRA_ROLE = "role"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_LANG_LABEL = "langLabel"
        const val EXTRA_LANG_CODE = "langCode"
        const val EXTRA_MODE = "mode"
        private const val NOISE_ALPHA = 0.1f
        private const val PREFS_NAME = "pipeline_prefs"
        private const val PREF_PIPELINE_ROUTE = "pipeline_route"
    }
}
