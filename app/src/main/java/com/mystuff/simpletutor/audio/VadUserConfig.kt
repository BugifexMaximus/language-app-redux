package com.mystuff.simpletutor.audio

import com.konovalov.vad.webrtc.config.Mode

data class VadUserConfig(
    val mode: Mode = VadConfig.mode,
    val minSpeechMs: Int = 200,
    val endSilenceMs: Int = 400,
    val amplitudeGateEnabled: Boolean = false,
    val minAmplitude: Int = 500,
    val peakMin: Int = 800,
    val rmsMinDbfs: Float = -42f,
    val noiseMarginDb: Float = 8f,
    val nOn: Int = 5,
    val nOff: Int = 75,
    val minUtterMs: Int = 700,
    val minSpeechRatio: Float = 0.35f,
    val mergeGapMs: Int = 250,
    val alwaysOn: Boolean = true,
    val autoEndDetect: Boolean = true,
    val manualListening: Boolean = false,
    val showTranscriptPopup: Boolean = true
)
