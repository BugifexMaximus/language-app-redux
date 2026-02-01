package com.mystuff.simpletutor.audio

import java.io.ByteArrayOutputStream

class UtteranceSegmenter(
    private val frameMs: Int = 20,
    private val nOn: Int = 5,
    private val nOff: Int = 25,
    private val minUtterMs: Int = 700,
    private val minSpeechRatio: Float = 0.6f,
    private val mergeGapMs: Int = 250,
    private val allowShortUtterances: Boolean = false,
    private val captureAllFrames: Boolean = false,
    private val allowEndWithoutSpeech: Boolean = false,
    private val deferFinalizeOnEnd: Boolean = false
) {
    private var inSpeech = false
    private var speechStreak = 0
    private var silenceStreak = 0
    private var speechFrames = 0
    private var totalFrames = 0
    private var gapFrames = 0
    private var pending: ByteArray? = null
    private var pendingSpeechFrames = 0
    private var pendingTotalFrames = 0
    private val buffer = ByteArrayOutputStream()
    private var endDetected = false
    private var hasSpeech = false

    fun forceStart() {
        if (!inSpeech) {
            startSpeech()
        }
    }

    fun forceStop(): ByteArray? {
        return forceEmitPending()
    }

    fun processFrame(isSpeech: Boolean, frame: ByteArray): ByteArray? {
        val pendingEmit = maybeEmitPendingOnGap(isSpeech)
        if (pendingEmit != null) return pendingEmit

        if (isSpeech) {
            speechStreak++
            silenceStreak = 0
            if (!inSpeech && speechStreak >= nOn) {
                startSpeech()
            }
        } else {
            speechStreak = 0
            if (inSpeech) {
                if (hasSpeech || allowEndWithoutSpeech) {
                    silenceStreak++
                    if (silenceStreak >= nOff) {
                        endDetected = true
                        if (deferFinalizeOnEnd) {
                            inSpeech = false
                            speechStreak = 0
                            silenceStreak = 0
                        } else {
                            val result = finalizeUtterance()
                            if (result != null) {
                                pending = result.first
                                pendingSpeechFrames = result.second
                                pendingTotalFrames = result.third
                                gapFrames = 0
                            }
                        }
                        return null
                    }
                }
            }
        }

        if (inSpeech) {
            totalFrames++
            if (isSpeech) {
                speechFrames++
                hasSpeech = true
            }
            if (captureAllFrames || hasSpeech || isSpeech) {
                buffer.write(frame)
            }
        }
        return null
    }

    private fun maybeEmitPendingOnGap(isSpeech: Boolean): ByteArray? {
        if (pending == null) return null
        if (isSpeech && gapFrames * frameMs < mergeGapMs) {
            // merge by continuing; move pending into buffer
            buffer.reset()
            buffer.write(pending)
            speechFrames = pendingSpeechFrames
            totalFrames = pendingTotalFrames
            pending = null
            pendingSpeechFrames = 0
            pendingTotalFrames = 0
            inSpeech = true
            speechStreak = nOn
            silenceStreak = 0
            gapFrames = 0
            return null
        }
        gapFrames++
        if (gapFrames * frameMs >= mergeGapMs) {
            val output = pending
            pending = null
            pendingSpeechFrames = 0
            pendingTotalFrames = 0
            gapFrames = 0
            return output
        }
        return null
    }

    private fun startSpeech() {
        inSpeech = true
        buffer.reset()
        speechFrames = 0
        totalFrames = 0
        silenceStreak = 0
        hasSpeech = false
    }

    private fun finalizeUtterance(): Triple<ByteArray, Int, Int>? {
        val utterMs = totalFrames * frameMs
        val ratio = if (totalFrames > 0) speechFrames.toFloat() / totalFrames else 0f
        val bytes = buffer.toByteArray()
        reset()
        if (!allowShortUtterances) {
            if (utterMs < minUtterMs) return null
            if (ratio < minSpeechRatio) return null
        }
        if (bytes.isEmpty()) return null
        return Triple(bytes, speechFrames, totalFrames)
    }

    private fun reset() {
        inSpeech = false
        speechStreak = 0
        silenceStreak = 0
        speechFrames = 0
        totalFrames = 0
        buffer.reset()
        hasSpeech = false
    }

    fun consumeEndDetected(): Boolean {
        val value = endDetected
        endDetected = false
        return value
    }

    fun forceEmitPending(): ByteArray? {
        val output = when {
            pending != null -> pending
            buffer.size() > 0 && (hasSpeech || captureAllFrames || allowEndWithoutSpeech) -> buffer.toByteArray()
            else -> null
        }
        pending = null
        pendingSpeechFrames = 0
        pendingTotalFrames = 0
        gapFrames = 0
        reset()
        return output
    }

    fun forceEmitBuffer(): ByteArray? {
        val output = if (buffer.size() > 0) buffer.toByteArray() else null
        pending = null
        pendingSpeechFrames = 0
        pendingTotalFrames = 0
        gapFrames = 0
        reset()
        return output
    }
}
