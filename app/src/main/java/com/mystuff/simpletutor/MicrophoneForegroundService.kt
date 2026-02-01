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
import okhttp3.Call
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.suspendCoroutine
import com.mystuff.simpletutor.learning.ItemSource
import com.mystuff.simpletutor.learning.LearnerScope
import com.mystuff.simpletutor.learning.LearningItem
import com.mystuff.simpletutor.learning.LearningItemId
import com.mystuff.simpletutor.learning.LearningItemType
import com.mystuff.simpletutor.learning.LearningRepository
import com.mystuff.simpletutor.learning.Observation
import com.mystuff.simpletutor.learning.ObservationOutcome

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
    private var tutorLevel: String = "Beginner"
    private var learningRepo: LearningRepository? = null
    private val interruptToken = AtomicLong(0)
    private var ttsPlayer: MediaPlayer? = null
    @Volatile private var currentCall: Call? = null
    @Volatile private var ttsContinuation: CancellableContinuation<Unit>? = null
    private var pipelineRoute: PipelineRoute = PipelineRoute.TEST_STT
    private var lastConfigCheckMs: Long = 0L
    private var lastLevelBroadcastMs: Long = 0L
    private var lastFrameLogMs: Long = 0L
    private var framesSinceLog: Int = 0
    private var speechFramesSinceLog: Int = 0
    private var noiseDbfs: Float = -60f
    private var currentPipelineState: String = "Offline"
    private var lastUiBroadcastMs: Long = 0L
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
        learningRepo = LearningRepository(this)
        currentConfig = VadConfigStore.load(this)
        pipelineRoute = loadPipelineRoute()
        loadTutorContext()
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
                tutorLevel = intent.getStringExtra(EXTRA_LEVEL) ?: tutorLevel
                saveTutorContext()
                Log.d(tag, "Tutor context: user=$tutorUserId, lang=$tutorLanguageLabel ($tutorLanguageCode), mode=$tutorMode, level=$tutorLevel")
            }
            ACTION_INTERRUPT -> {
                interruptToken.incrementAndGet()
                currentCall?.cancel()
                stopTtsPlayback()
                cancelTtsContinuation()
                broadcastPipelineState(if (isListeningEnabled()) "Listening" else "Offline")
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
                val localToken = interruptToken.get()
                val wavFile = File(cacheDir, "utt_${System.currentTimeMillis()}.wav")
                WavWriter.writePcm16Mono(wavFile, bytes, VadConfig.sampleRateHz)
                val durationMs = (bytes.size / 2.0 / VadConfig.sampleRateHz * 1000).toInt()
                if (pipelineRoute == PipelineRoute.TEST_STT) {
                    showStatusBanner("Sending snippet (${durationMs}ms, ${bytes.size} bytes)...")
                }
                broadcastPipelineState("Thinking")
                val transcript = transcribe(wavFile)
                if (interruptToken.get() != localToken) {
                    broadcastPipelineState(if (isListeningEnabled()) "Listening" else "Offline")
                    if (pipelineRoute == PipelineRoute.TEST_STT) {
                        hideStatusBanner()
                    }
                    return
                }
                if (transcript.isNotBlank() && transcript != lastTranscript) {
                    lastTranscript = transcript
                    if (pipelineRoute == PipelineRoute.TEST_STT && configSnapshot.showTranscriptPopup) {
                        showTranscriptPopup(transcript)
                    }
                    if (pipelineRoute == PipelineRoute.TUTOR) {
                        broadcastChatMessage("user", transcript)
                        val response = generateTutorReply(transcript)
                        if (interruptToken.get() != localToken) {
                            broadcastPipelineState(if (isListeningEnabled()) "Listening" else "Offline")
                            if (pipelineRoute == PipelineRoute.TEST_STT) {
                                hideStatusBanner()
                            }
                            return
                        }
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
                maybeBroadcastUiState()
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
        val sttPrompt = "English or $tutorLanguageLabel"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", STT_MODEL)
            .addFormDataPart("prompt", sttPrompt)
            .addFormDataPart("file", file.name, file.asRequestBody(octetStream))
            .build()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .post(body)
            .addHeader("Authorization", authHeader())
            .build()

        return try {
            val call = client.newCall(req)
            currentCall = call
            call.execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return ""
                JSONObject(respBody).optString("text")
            }
        } catch (_: Exception) {
            ""
        } finally {
            currentCall = null
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
            val call = client.newCall(req)
            currentCall = call
            call.execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val body = resp.body?.string().orEmpty()
                val output = extractOutputText(body).trim()
                parseTutorOutput(output, text)
            }
        } catch (_: Exception) {
            ""
        } finally {
            currentCall = null
        }
    }

    private fun buildTutorPrompt(text: String): String {
        val scope = LearnerScope(tutorUserId, tutorLanguageCode)
        val packet = learningRepo?.buildContextPacket(
            scope = scope,
            level = tutorLevel,
            preferences = "Short responses. Correct lightly. Ask a follow-up question only when it feels natural.",
            weakLimit = 6,
            recentLimit = 6,
            errorLimit = 4,
            errorWindow = 60,
            recentTurns = 4
        )
        val knownCount = learningRepo?.load(scope)?.items?.size ?: 0
        val memoryBlock = packet?.let { formatMemorySlice(it.memorySlice) } ?: "Weak: -\nRecent: -\nErrors: -"
        val turnsBlock = packet?.recentTurns?.joinToString("\n") { turn ->
            "User: ${turn.userText}\nTutor: ${turn.tutorText}"
        }.orEmpty()
        val progressHint = when {
            knownCount <= 0 -> "brand new"
            knownCount < 10 -> "very early"
            knownCount < 40 -> "early"
            knownCount < 120 -> "building"
            else -> "established"
        }
        val languageMixHint = when (tutorLevel.lowercase()) {
            "beginner" -> when {
                knownCount < 10 -> "Mostly English. Use short target-language phrases when helpful."
                knownCount < 40 -> "English-led, with more target-language phrases/sentences."
                else -> "Balanced mix; lean more into the target language when it feels natural."
            }
            "intermediate" -> "Mostly target language, with brief English clarifications as needed."
            "advanced" -> "Target language almost entirely; English only if truly needed."
            "grammar" -> "Explain in English, show short target-language examples."
            else -> "Balanced mix."
        }
        val coreGuidance = when (tutorLevel.lowercase()) {
            "beginner" -> """
Keep the pace gentle. Be responsive to what the user actually asked.
Avoid overwhelming the learner. If you give a longer example, keep it simple and do not pack it with many new words/grammar at once.
When you introduce something new, use it in a natural sentence and then continue the conversation with one relevant question.
Avoid scripted drills and repetitive coaching phrasing.
            """.trimIndent()
            "intermediate" -> "Be conversational and helpful; focus on fluency and natural phrasing."
            "advanced" -> "Be concise and natural; prioritize nuance and idiomatic usage."
            "grammar" -> "Be clear and practical; focus on one grammar point with examples."
            else -> "Be conversational and helpful."
        }
        return """
You are a language tutor speaking through TTS (audio). The learner speaks English and is practicing $tutorLanguageLabel.
Level: $tutorLevel. Progress: $progressHint ($knownCount tracked items).
Language mix: $languageMixHint

Goal: produce a helpful, natural next tutor turn that matches the user's intent.
Decision rule: answer the user's request first; then (if it fits) continue with a short prompt that helps the learner practice something real.

Practice loop (what "good tutoring" looks like here):
- If you introduce a word/phrase, immediately place it in a tiny real-life situation and invite the learner to respond using it.
- Do not "menu" the curriculum (avoid questions like "do you want to learn X or Y next?") unless the user explicitly asks you to pick topics.

Examples:
- Bad: "We learned 'hola'. Do you want 'good morning' or 'good night' next?"
- Good: "Nice. 'Hola' is 'hi'. You see your friend at a cafe - what do you say?"

$coreGuidance

TTS formatting constraints (so it sounds natural):
- Do not use "A:"/"B:" or "Tutor:"/"User:" labels.
- Do not include English phonetic spellings for target-language text.
- Sample conversations are rare; only include them if they genuinely help, and use real names (vary them).
- Avoid meta-instructions like "repeat after me" or "type". If you invite practice, do it as a normal question.

Meta learning questions: If the user asks about their learning status ("what words do I know? what am I weak at?"), answer directly using the memory slice. You can add a brief next-step suggestion if it fits.

Output JSON only:
- assistant_text: what you will say (this is spoken by TTS)
- follow_up_question: optional, only if it feels natural; when present, prefer a practice question tied to the user's message or the just-introduced item
- memory_suggestions: optional list, only if you are confident; keep it small and high-signal (prefer 0-2 items)
  - {type: WORD|PHRASE|GRAMMAR_POINT, display: string, gloss?: string, tags?: [string], outcome?: INTRODUCED|USED|CORRECT|PARTIAL|INCORRECT, error_tags?: [string]}

If you include follow_up_question, make it a practice question (situational, answerable with what the learner knows / just learned), not a meta preference question.

Memory slice:
$memoryBlock

Recent turns:
$turnsBlock

User spoke: $text
        """.trimIndent()
    }

    private fun parseTutorOutput(output: String, userText: String): String {
        val obj = extractJsonObject(output)
        if (obj == null) {
            recordTurn(userText, output)
            return output
        }
        val assistantText = obj.optString("assistant_text").trim()
        val followUp = obj.optString("follow_up_question").trim()
        val combined = buildString {
            if (assistantText.isNotBlank()) append(assistantText)
            if (followUp.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(followUp)
            }
        }.ifBlank { output }
        handleMemorySuggestions(obj.optJSONArray("memory_suggestions"), userText, combined)
        recordTurn(userText, combined)
        return combined
    }

    private fun extractJsonObject(output: String): JSONObject? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return null
        val noFence = if (trimmed.startsWith("```")) {
            trimmed.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```$"), "").trim()
        } else {
            trimmed
        }
        if (noFence.startsWith("{") && noFence.endsWith("}")) {
            return runCatching { JSONObject(noFence) }.getOrNull()
        }
        val start = noFence.indexOf('{')
        val end = noFence.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val slice = noFence.substring(start, end + 1)
            return runCatching { JSONObject(slice) }.getOrNull()
        }
        return null
    }

    private fun handleMemorySuggestions(suggestions: JSONArray?, userText: String, tutorText: String) {
        if (suggestions == null) return
        val repo = learningRepo ?: return
        val scope = LearnerScope(tutorUserId, tutorLanguageCode)
        val now = System.currentTimeMillis()
        // Hard cap to prevent a long example response from exploding memory.
        val n = minOf(suggestions.length(), MAX_MEMORY_SUGGESTIONS_PER_TURN)
        for (i in 0 until n) {
            val obj = suggestions.optJSONObject(i) ?: continue
            val typeRaw = obj.optString("type").uppercase()
            val type = runCatching { LearningItemType.valueOf(typeRaw) }.getOrNull() ?: continue
            val display = obj.optString("display").trim()
            if (display.isBlank()) continue
            val gloss = obj.optString("gloss").trim().ifBlank { null }
            val tags = obj.optJSONArray("tags")?.let { array ->
                val list = mutableListOf<String>()
                for (j in 0 until array.length()) {
                    val value = array.optString(j)
                    if (value.isNotBlank()) list.add(value)
                }
                list
            } ?: emptyList()
            val outcomeRaw = obj.optString("outcome").uppercase()
            val outcome = runCatching { ObservationOutcome.valueOf(outcomeRaw) }.getOrNull()
            val errors = obj.optJSONArray("error_tags")?.let { array ->
                val list = mutableListOf<String>()
                for (j in 0 until array.length()) {
                    val value = array.optString(j)
                    if (value.isNotBlank()) list.add(value)
                }
                list
            } ?: emptyList()
            val id = LearningItemId.from(type, tutorLanguageCode, display)
            val item = LearningItem(
                id = id,
                type = type,
                language = tutorLanguageCode,
                display = display,
                gloss = gloss,
                tags = tags,
                firstSeen = now,
                lastSeen = now,
                source = ItemSource.INTRODUCED
            )
            repo.upsertItem(scope, item)
            if (outcome != null) {
                val observation = Observation(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    userText = userText,
                    tutorText = tutorText,
                    itemIds = listOf(id),
                    outcome = outcome,
                    errorTags = errors
                )
                repo.recordObservation(scope, observation)
            }
        }
    }

    private fun recordTurn(userText: String, tutorText: String) {
        val repo = learningRepo ?: return
        val scope = LearnerScope(tutorUserId, tutorLanguageCode)
        repo.recordTurn(scope, userText, tutorText, limit = 6)
    }

    private fun formatMemorySlice(slice: com.mystuff.simpletutor.learning.MemorySlice): String {
        val weak = slice.weakItems.joinToString(", ") { it.display }.ifBlank { "-" }
        val recent = slice.recentItems.joinToString(", ") { it.display }.ifBlank { "-" }
        val errors = slice.frequentErrors.joinToString(", ") { it.tag }.ifBlank { "-" }
        return "Weak: $weak\nRecent: $recent\nErrors: $errors"
    }

    private suspend fun playTts(text: String) {
        val voice = loadTtsVoice()
        val payload = """
            {
              "model": "$TTS_MODEL",
              "voice": "$voice",
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
            val call = client.newCall(req)
            currentCall = call
            call.execute().use { resp ->
                if (!resp.isSuccessful) return
                resp.body?.bytes() ?: return
            }
        } catch (_: Exception) {
            return
        } finally {
            currentCall = null
        }

        val outFile = File(cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        outFile.writeBytes(audioBytes)
        try {
            playAudioFile(outFile)
        } catch (_: Exception) {
        }
    }

    private suspend fun playAudioFile(file: File) {
        if (!file.exists()) return
        suspendCancellableCoroutine<Unit> { cont ->
            stopTtsPlayback()
            ttsContinuation = cont
            cont.invokeOnCancellation {
                stopTtsPlayback()
            }
            val player = MediaPlayer()
            ttsPlayer = player
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener {
                it.release()
                if (ttsPlayer === it) {
                    ttsPlayer = null
                }
                if (ttsContinuation === cont) {
                    ttsContinuation = null
                }
                cont.resume(Unit)
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (ttsPlayer === mp) {
                    ttsPlayer = null
                }
                if (ttsContinuation === cont) {
                    ttsContinuation = null
                }
                cont.resume(Unit)
                true
            }
            try {
                player.setDataSource(file.absolutePath)
                player.prepareAsync()
            } catch (e: Exception) {
                player.release()
                if (ttsPlayer === player) {
                    ttsPlayer = null
                }
                if (ttsContinuation === cont) {
                    ttsContinuation = null
                }
                cont.resume(Unit)
            }
        }
    }

    private fun stopTtsPlayback() {
        val player = ttsPlayer ?: return
        try {
            if (player.isPlaying) {
                player.stop()
            }
        } catch (_: Exception) {
        } finally {
            player.release()
            if (ttsPlayer === player) {
                ttsPlayer = null
            }
        }
    }

    private fun cancelTtsContinuation() {
        val cont = ttsContinuation ?: return
        if (cont.isActive) {
            cont.cancel()
        }
        if (ttsContinuation === cont) {
            ttsContinuation = null
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
        currentPipelineState = state
        val intent = Intent(ACTION_PIPELINE_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
        sendBroadcast(intent)
    }

    private fun maybeBroadcastUiState() {
        val now = System.currentTimeMillis()
        if (now - lastUiBroadcastMs < UI_BROADCAST_MS) return
        lastUiBroadcastMs = now
        broadcastListeningStatus()
        broadcastPipelineState(currentPipelineState)
    }

    private fun loadPipelineRoute(): PipelineRoute {
        val prefs = getSharedPreferences(PipelinePrefs.NAME, Context.MODE_PRIVATE)
        return PipelineRoute.fromValue(
            prefs.getString(PipelinePrefs.KEY_PIPELINE_ROUTE, PipelineRoute.TEST_STT.value)
        )
    }

    private fun savePipelineRoute(route: PipelineRoute) {
        val prefs = getSharedPreferences(PipelinePrefs.NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PipelinePrefs.KEY_PIPELINE_ROUTE, route.value).apply()
    }

    private fun loadTtsVoice(): String {
        val prefs = getSharedPreferences(PipelinePrefs.NAME, Context.MODE_PRIVATE)
        return TtsVoiceCatalog.resolve(prefs.getString(PipelinePrefs.KEY_TTS_VOICE, null))
    }

    private fun loadTutorContext() {
        val prefs = getSharedPreferences(TUTOR_PREFS, Context.MODE_PRIVATE)
        tutorUserId = prefs.getString(KEY_TUTOR_USER, tutorUserId) ?: tutorUserId
        tutorLanguageLabel = prefs.getString(KEY_TUTOR_LANG_LABEL, tutorLanguageLabel) ?: tutorLanguageLabel
        tutorLanguageCode = prefs.getString(KEY_TUTOR_LANG_CODE, tutorLanguageCode) ?: tutorLanguageCode
        tutorMode = prefs.getString(KEY_TUTOR_MODE, tutorMode) ?: tutorMode
        tutorLevel = prefs.getString(KEY_TUTOR_LEVEL, tutorLevel) ?: tutorLevel
    }

    private fun saveTutorContext() {
        val prefs = getSharedPreferences(TUTOR_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TUTOR_USER, tutorUserId)
            .putString(KEY_TUTOR_LANG_LABEL, tutorLanguageLabel)
            .putString(KEY_TUTOR_LANG_CODE, tutorLanguageCode)
            .putString(KEY_TUTOR_MODE, tutorMode)
            .putString(KEY_TUTOR_LEVEL, tutorLevel)
            .apply()
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
        private const val CLASSIFIER_MODEL = "gpt-5.2"
        private const val ACTION_COOLDOWN_MS = 4000L
        private const val FRAME_MS = 20
        private const val CONFIG_REFRESH_MS = 1000L
        private const val LEVEL_BROADCAST_MS = 200L
        private const val UI_BROADCAST_MS = 1500L
        const val ACTION_LEVEL = "com.mystuff.simpletutor.VAD_LEVEL"
        const val ACTION_CONFIG_CHANGED = "com.mystuff.simpletutor.VAD_CONFIG_CHANGED"
        const val ACTION_LISTENING_STATUS = "com.mystuff.simpletutor.VAD_LISTENING_STATUS"
        const val ACTION_PIPELINE_STATE = "com.mystuff.simpletutor.PIPELINE_STATE"
        const val ACTION_CHAT_MESSAGE = "com.mystuff.simpletutor.CHAT_MESSAGE"
        const val ACTION_SET_MANUAL_LISTEN = "com.mystuff.simpletutor.SET_MANUAL_LISTEN"
        const val ACTION_SET_PIPELINE_ROUTE = "com.mystuff.simpletutor.SET_PIPELINE_ROUTE"
        const val ACTION_SET_TUTOR_CONTEXT = "com.mystuff.simpletutor.SET_TUTOR_CONTEXT"
        const val ACTION_INTERRUPT = "com.mystuff.simpletutor.INTERRUPT"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_STATE = "state"
        const val EXTRA_ROLE = "role"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_LANG_LABEL = "langLabel"
        const val EXTRA_LANG_CODE = "langCode"
        const val EXTRA_MODE = "mode"
        const val EXTRA_LEVEL = "level"
        private const val NOISE_ALPHA = 0.1f
        private const val MAX_MEMORY_SUGGESTIONS_PER_TURN = 2
        private const val TUTOR_PREFS = "tutor_context"
        private const val KEY_TUTOR_USER = "userId"
        private const val KEY_TUTOR_LANG_LABEL = "langLabel"
        private const val KEY_TUTOR_LANG_CODE = "langCode"
        private const val KEY_TUTOR_MODE = "mode"
        private const val KEY_TUTOR_LEVEL = "level"
    }
}
