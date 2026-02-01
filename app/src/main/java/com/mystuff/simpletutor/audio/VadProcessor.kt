package com.mystuff.simpletutor.audio

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.SampleRate
import com.konovalov.vad.webrtc.config.Mode

class VadProcessor(
    sampleRate: SampleRate = VadConfig.sampleRate,
    frameSize: FrameSize = VadConfig.frameSize,
    mode: Mode = VadConfig.mode,
    silenceDurationMs: Int = VadConfig.silenceDurationMs,
    speechDurationMs: Int = VadConfig.speechDurationMs
) {
    private val vad = VadWebRTC(
        sampleRate = sampleRate,
        frameSize = frameSize,
        mode = mode,
        silenceDurationMs = silenceDurationMs,
        speechDurationMs = speechDurationMs
    )

    fun isSpeech(frameBytes: ByteArray): Boolean = vad.isSpeech(frameBytes)
}
