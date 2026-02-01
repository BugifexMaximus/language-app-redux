package com.mystuff.simpletutor.audio

import android.content.Context
import com.konovalov.vad.webrtc.config.Mode

object VadConfigStore {
    private const val PREFS = "vad_config"
    private const val KEY_MODE = "mode"
    private const val KEY_MIN_SPEECH = "min_speech"
    private const val KEY_END_SILENCE = "end_silence"
    private const val KEY_AMP_ENABLED = "amp_enabled"
    private const val KEY_MIN_AMP = "min_amp"
    private const val KEY_PEAK_MIN = "peak_min"
    private const val KEY_RMS_MIN = "rms_min"
    private const val KEY_NOISE_MARGIN = "noise_margin"
    private const val KEY_N_ON = "n_on"
    private const val KEY_N_OFF = "n_off"
    private const val KEY_MIN_UTTER = "min_utter"
    private const val KEY_MIN_RATIO = "min_ratio"
    private const val KEY_MERGE_GAP = "merge_gap"
    private const val KEY_ALWAYS_ON = "always_on"
    private const val KEY_AUTO_END = "auto_end"
    private const val KEY_MANUAL_LISTEN = "manual_listen"
    private const val KEY_SHOW_POPUP = "show_popup"

    fun load(context: Context): VadUserConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modeName = prefs.getString(KEY_MODE, Mode.VERY_AGGRESSIVE.name) ?: Mode.VERY_AGGRESSIVE.name
        val mode = runCatching { Mode.valueOf(modeName) }.getOrDefault(Mode.VERY_AGGRESSIVE)
        return VadUserConfig(
            mode = mode,
            minSpeechMs = prefs.getInt(KEY_MIN_SPEECH, 200),
            endSilenceMs = prefs.getInt(KEY_END_SILENCE, 400),
            amplitudeGateEnabled = prefs.getBoolean(KEY_AMP_ENABLED, false),
            minAmplitude = prefs.getInt(KEY_MIN_AMP, 500),
            peakMin = prefs.getInt(KEY_PEAK_MIN, 800),
            rmsMinDbfs = prefs.getFloat(KEY_RMS_MIN, -42f),
            noiseMarginDb = prefs.getFloat(KEY_NOISE_MARGIN, 8f),
            nOn = prefs.getInt(KEY_N_ON, 5),
            nOff = prefs.getInt(KEY_N_OFF, 75),
            minUtterMs = prefs.getInt(KEY_MIN_UTTER, 700),
            minSpeechRatio = prefs.getFloat(KEY_MIN_RATIO, 0.35f),
            mergeGapMs = prefs.getInt(KEY_MERGE_GAP, 250),
            alwaysOn = prefs.getBoolean(KEY_ALWAYS_ON, true),
            autoEndDetect = prefs.getBoolean(KEY_AUTO_END, true),
            manualListening = prefs.getBoolean(KEY_MANUAL_LISTEN, false),
            showTranscriptPopup = prefs.getBoolean(KEY_SHOW_POPUP, true)
        )
    }

    fun save(context: Context, config: VadUserConfig) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_MODE, config.mode.name)
            .putInt(KEY_MIN_SPEECH, config.minSpeechMs)
            .putInt(KEY_END_SILENCE, config.endSilenceMs)
            .putBoolean(KEY_AMP_ENABLED, config.amplitudeGateEnabled)
            .putInt(KEY_MIN_AMP, config.minAmplitude)
            .putInt(KEY_PEAK_MIN, config.peakMin)
            .putFloat(KEY_RMS_MIN, config.rmsMinDbfs)
            .putFloat(KEY_NOISE_MARGIN, config.noiseMarginDb)
            .putInt(KEY_N_ON, config.nOn)
            .putInt(KEY_N_OFF, config.nOff)
            .putInt(KEY_MIN_UTTER, config.minUtterMs)
            .putFloat(KEY_MIN_RATIO, config.minSpeechRatio)
            .putInt(KEY_MERGE_GAP, config.mergeGapMs)
            .putBoolean(KEY_ALWAYS_ON, config.alwaysOn)
            .putBoolean(KEY_AUTO_END, config.autoEndDetect)
            .putBoolean(KEY_MANUAL_LISTEN, config.manualListening)
            .putBoolean(KEY_SHOW_POPUP, config.showTranscriptPopup)
            .apply()
    }
}
