package com.mystuff.simpletutor.audio

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.SampleRate
import com.konovalov.vad.webrtc.config.Mode

object VadConfig {
    const val sampleRateHz = 16000
    const val frameSizeSamples = 320 // 20 ms at 16 kHz
    const val silenceDurationMs = 300
    const val speechDurationMs = 50
    val sampleRate: SampleRate = SampleRate.SAMPLE_RATE_16K
    val frameSize: FrameSize = FrameSize.FRAME_SIZE_320
    val mode: Mode = Mode.VERY_AGGRESSIVE
}
