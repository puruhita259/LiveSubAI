package com.livesub.ai.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioCaptureManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    data class PcmFrame(
        val pcmBytes: ByteArray,
        val rmsLevel: Double,
        val isSpeech: Boolean
    )

    data class AudioChunk(
        val wavBytes: ByteArray,
        val rmsLevel: Double
    )

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Streaming frame PCM mentah 100ms (16kHz, 16-bit, mono) untuk Gemini Live WebSocket.
     * Hanya menandai DRM jika perangkat benar-benar memutar media (isMusicActive)
     * namun buffer PCM bernilai nol absolut selama >= 6 detik.
     */
    @SuppressLint("MissingPermission")
    suspend fun streamInternalPcmFrames(
        mediaProjection: MediaProjection,
        frameDurationMs: Long = 100L,
        onFrameReady: (PcmFrame) -> Unit,
        onAudioActive: () -> Unit,
        onIdleSilence: () -> Unit,
        onDrmBlockedDetected: () -> Unit
    ) = withContext(Dispatchers.IO) {
        val recorder = buildInternalRecorder(mediaProjection)
        runPcmFrameLoop(
            recorder = recorder,
            frameDurationMs = frameDurationMs,
            detectDrm = true,
            onFrameReady = onFrameReady,
            onAudioActive = onAudioActive,
            onIdleSilence = onIdleSilence,
            onDrmBlockedDetected = onDrmBlockedDetected
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun streamMicrophonePcmFrames(
        frameDurationMs: Long = 100L,
        onFrameReady: (PcmFrame) -> Unit,
        onAudioActive: () -> Unit
    ) = withContext(Dispatchers.IO) {
        val recorder = buildMicRecorder()
        runPcmFrameLoop(
            recorder = recorder,
            frameDurationMs = frameDurationMs,
            detectDrm = false,
            onFrameReady = onFrameReady,
            onAudioActive = onAudioActive,
            onIdleSilence = {},
            onDrmBlockedDetected = {}
        )
    }

    /**
     * Streaming potongan WAV cerdas (max 2,4 detik + early VAD flush saat pembicara berhenti >= 300ms)
     * untuk mode REST Cepat Paralel.
     */
    @SuppressLint("MissingPermission")
    suspend fun streamInternalAudio(
        mediaProjection: MediaProjection,
        chunkDurationMs: Long = 2400L,
        onChunkReady: (AudioChunk) -> Unit,
        onAudioActive: () -> Unit,
        onIdleSilence: () -> Unit,
        onDrmBlockedDetected: () -> Unit
    ) = withContext(Dispatchers.IO) {
        val recorder = buildInternalRecorder(mediaProjection)
        runVadChunkLoop(
            recorder = recorder,
            maxChunkDurationMs = chunkDurationMs,
            detectDrm = true,
            onChunkReady = onChunkReady,
            onAudioActive = onAudioActive,
            onIdleSilence = onIdleSilence,
            onDrmBlockedDetected = onDrmBlockedDetected
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun streamMicrophoneAudio(
        chunkDurationMs: Long = 2400L,
        onChunkReady: (AudioChunk) -> Unit,
        onAudioActive: () -> Unit
    ) = withContext(Dispatchers.IO) {
        val recorder = buildMicRecorder()
        runVadChunkLoop(
            recorder = recorder,
            maxChunkDurationMs = chunkDurationMs,
            detectDrm = false,
            onChunkReady = onChunkReady,
            onAudioActive = onAudioActive,
            onIdleSilence = {},
            onDrmBlockedDetected = {}
        )
    }

    @SuppressLint("MissingPermission")
    private fun buildInternalRecorder(mediaProjection: MediaProjection): AudioRecord {
        if (!hasRecordAudioPermission()) {
            throw SecurityException("Izin RECORD_AUDIO belum diberikan.")
        }

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE)

        return AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun buildMicRecorder(): AudioRecord {
        if (!hasRecordAudioPermission()) {
            throw SecurityException("Izin RECORD_AUDIO belum diberikan.")
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE)

        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
    }

    private suspend fun runPcmFrameLoop(
        recorder: AudioRecord,
        frameDurationMs: Long,
        detectDrm: Boolean,
        onFrameReady: (PcmFrame) -> Unit,
        onAudioActive: () -> Unit,
        onIdleSilence: () -> Unit,
        onDrmBlockedDetected: () -> Unit
    ) {
        val frameBytes = ((SAMPLE_RATE * 2L * frameDurationMs) / 1000L).toInt().coerceAtLeast(1600)
        val readBuffer = ByteArray(frameBytes)
        val accumulator = ByteArrayOutputStream(frameBytes)

        var consecutiveDrmZeroFrames = 0
        var consecutiveIdleFrames = 0
        var wasBlockedOrIdle = false
        var consecutiveReadErrors = 0

        val drmThresholdFrames = (6000L / frameDurationMs.coerceAtLeast(50L)).toInt()
        val idleThresholdFrames = (4500L / frameDurationMs.coerceAtLeast(50L)).toInt()

        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val readCount = recorder.read(readBuffer, 0, readBuffer.size)
                if (readCount < 0) {
                    consecutiveReadErrors++
                    if (consecutiveReadErrors >= 5) {
                        throw IllegalStateException("Stream audio terputus oleh sistem (kode $readCount).")
                    }
                    delay(120L)
                    continue
                }
                if (readCount == 0) {
                    delay(20L)
                    continue
                }
                consecutiveReadErrors = 0

                accumulator.write(readBuffer, 0, readCount)
                if (accumulator.size() >= frameBytes) {
                    val pcm = accumulator.toByteArray()
                    accumulator.reset()
                    val rms = computeRms(pcm)
                    val isSpeech = rms >= SPEECH_RMS_THRESHOLD

                    if (isSpeech) {
                        consecutiveDrmZeroFrames = 0
                        consecutiveIdleFrames = 0
                        if (wasBlockedOrIdle) {
                            wasBlockedOrIdle = false
                            onAudioActive()
                        }
                    } else if (detectDrm) {
                        val mediaPlaying = audioManager.isMusicActive
                        if (mediaPlaying && rms < DIGITAL_ZERO_RMS) {
                            consecutiveDrmZeroFrames++
                            consecutiveIdleFrames = 0
                            if (consecutiveDrmZeroFrames == drmThresholdFrames) {
                                wasBlockedOrIdle = true
                                onDrmBlockedDetected()
                            }
                        } else {
                            consecutiveDrmZeroFrames = 0
                            consecutiveIdleFrames++
                            if (consecutiveIdleFrames == idleThresholdFrames && !wasBlockedOrIdle) {
                                wasBlockedOrIdle = true
                                onIdleSilence()
                            }
                        }
                    }

                    onFrameReady(PcmFrame(pcmBytes = pcm, rmsLevel = rms, isSpeech = isSpeech))
                }
            }
        } finally {
            safeRelease(recorder)
        }
    }

    private suspend fun runVadChunkLoop(
        recorder: AudioRecord,
        maxChunkDurationMs: Long,
        detectDrm: Boolean,
        onChunkReady: (AudioChunk) -> Unit,
        onAudioActive: () -> Unit,
        onIdleSilence: () -> Unit,
        onDrmBlockedDetected: () -> Unit
    ) {
        val frameBytes = ((SAMPLE_RATE * 2L * 100L) / 1000L).toInt() // 100ms
        val maxChunkBytes = ((SAMPLE_RATE * 2L * maxChunkDurationMs) / 1000L).toInt()
        val minEarlyFlushBytes = ((SAMPLE_RATE * 2L * 1000L) / 1000L).toInt()
        val readBuffer = ByteArray(frameBytes)
        val pcmAccumulator = ByteArrayOutputStream(maxChunkBytes)

        var hadSpeechInChunk = false
        var trailingSilentFrames = 0
        var consecutiveDrmZeroFrames = 0
        var consecutiveIdleFrames = 0
        var wasBlockedOrIdle = false
        var consecutiveReadErrors = 0

        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val readCount = recorder.read(readBuffer, 0, readBuffer.size)
                if (readCount < 0) {
                    consecutiveReadErrors++
                    if (consecutiveReadErrors >= 5) {
                        throw IllegalStateException("Stream audio terputus oleh sistem (kode $readCount).")
                    }
                    delay(120L)
                    continue
                }
                if (readCount == 0) {
                    delay(20L)
                    continue
                }
                consecutiveReadErrors = 0

                val slice = if (readCount == readBuffer.size) readBuffer else readBuffer.copyOf(readCount)
                val frameRms = computeRms(slice)
                val isSpeech = frameRms >= SPEECH_RMS_THRESHOLD

                if (isSpeech) {
                    hadSpeechInChunk = true
                    trailingSilentFrames = 0
                    consecutiveDrmZeroFrames = 0
                    consecutiveIdleFrames = 0
                    if (wasBlockedOrIdle) {
                        wasBlockedOrIdle = false
                        onAudioActive()
                    }
                } else {
                    trailingSilentFrames++
                    if (detectDrm) {
                        val mediaPlaying = audioManager.isMusicActive
                        if (mediaPlaying && frameRms < DIGITAL_ZERO_RMS) {
                            consecutiveDrmZeroFrames++
                            consecutiveIdleFrames = 0
                            if (consecutiveDrmZeroFrames == 60) {
                                wasBlockedOrIdle = true
                                onDrmBlockedDetected()
                            }
                        } else {
                            consecutiveDrmZeroFrames = 0
                            consecutiveIdleFrames++
                            if (consecutiveIdleFrames == 45 && !wasBlockedOrIdle) {
                                wasBlockedOrIdle = true
                                onIdleSilence()
                            }
                        }
                    }
                }

                if (hadSpeechInChunk) {
                    pcmAccumulator.write(slice, 0, slice.size)
                    val reachedMax = pcmAccumulator.size() >= maxChunkBytes
                    val earlyPauseFlush = pcmAccumulator.size() >= minEarlyFlushBytes && trailingSilentFrames >= 3

                    if (reachedMax || earlyPauseFlush) {
                        val pcm = pcmAccumulator.toByteArray()
                        pcmAccumulator.reset()
                        hadSpeechInChunk = false
                        trailingSilentFrames = 0
                        val chunkRms = computeRms(pcm)
                        if (chunkRms >= SPEECH_RMS_THRESHOLD * 0.7) {
                            val wav = wrapPcmAsWav(pcm, SAMPLE_RATE, 1, 16)
                            onChunkReady(AudioChunk(wavBytes = wav, rmsLevel = chunkRms))
                        }
                    }
                }
            }
        } finally {
            safeRelease(recorder)
        }
    }

    private fun safeRelease(recorder: AudioRecord) {
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (_: IllegalStateException) {
        }
        recorder.release()
    }

    private fun computeRms(pcm16Bit: ByteArray): Double {
        if (pcm16Bit.size < 2) return 0.0
        val sampleCount = pcm16Bit.size / 2
        val bb = ByteBuffer.wrap(pcm16Bit).order(ByteOrder.LITTLE_ENDIAN)
        var sumSquares = 0.0
        for (i in 0 until sampleCount) {
            val sample = bb.short.toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / sampleCount)
    }

    private fun wrapPcmAsWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        return ByteArrayOutputStream(44 + dataSize).apply {
            write(header.array())
            write(pcmData)
        }.toByteArray()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val SPEECH_RMS_THRESHOLD = 30.0
        private const val DIGITAL_ZERO_RMS = 1.5
    }
}
