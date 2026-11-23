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

    data class OverlappingChunk(
        val seqId: Long,
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
     * Streaming frame PCM mentah 50ms (16kHz, 16-bit, mono = 1600 bytes) untuk Mode Full Offline (Vosk).
     */
    @SuppressLint("MissingPermission")
    suspend fun streamInternalPcmFrames(
        mediaProjection: MediaProjection,
        frameDurationMs: Long = 50L,
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
        frameDurationMs: Long = 50L,
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
     * Streaming potongan WAV tumpang-tindih (1,6 dtk + 350 ms overlap dari ekor potongan sebelumnya)
     * agar kata cepat di batas potongan TIDAK PERNAH terpotong pada Mode Hybrid (Groq / Cloudflare).
     */
    @SuppressLint("MissingPermission")
    suspend fun streamInternalOverlappingChunks(
        mediaProjection: MediaProjection,
        chunkDurationMs: Long = 1600L,
        overlapMs: Long = 350L,
        onChunkReady: (OverlappingChunk) -> Unit,
        onAudioActive: () -> Unit,
        onIdleSilence: () -> Unit,
        onDrmBlockedDetected: () -> Unit
    ) = withContext(Dispatchers.IO) {
        val recorder = buildInternalRecorder(mediaProjection)
        runOverlappingVadLoop(
            recorder = recorder,
            maxChunkDurationMs = chunkDurationMs,
            overlapMs = overlapMs,
            detectDrm = true,
            onChunkReady = onChunkReady,
            onAudioActive = onAudioActive,
            onIdleSilence = onIdleSilence,
            onDrmBlockedDetected = onDrmBlockedDetected
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun streamMicrophoneOverlappingChunks(
        chunkDurationMs: Long = 1600L,
        overlapMs: Long = 350L,
        onChunkReady: (OverlappingChunk) -> Unit,
        onAudioActive: () -> Unit
    ) = withContext(Dispatchers.IO) {
        val recorder = buildMicRecorder()
        runOverlappingVadLoop(
            recorder = recorder,
            maxChunkDurationMs = chunkDurationMs,
            overlapMs = overlapMs,
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
        ).coerceAtLeast(SAMPLE_RATE / 2)

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
        ).coerceAtLeast(SAMPLE_RATE / 2)

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
        val safeFrameMs = frameDurationMs.coerceIn(40L, 200L)
        val frameBytes = ((SAMPLE_RATE * 2L * safeFrameMs) / 1000L).toInt().coerceAtLeast(1280)
        val readBuffer = ByteArray(frameBytes)
        val accumulator = ByteArrayOutputStream(frameBytes)

        var consecutiveDrmZeroFrames = 0
        var consecutiveIdleFrames = 0
        var wasBlockedOrIdle = false
        var consecutiveReadErrors = 0

        val drmThresholdFrames = (6000L / safeFrameMs).toInt()
        val idleThresholdFrames = (4000L / safeFrameMs).toInt()

        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val readCount = recorder.read(readBuffer, 0, readBuffer.size)
                if (readCount < 0) {
                    consecutiveReadErrors++
                    if (consecutiveReadErrors >= 5) {
                        throw IllegalStateException("Stream audio terputus oleh sistem (kode $readCount).")
                    }
                    delay(100L)
                    continue
                }
                if (readCount == 0) {
                    delay(15L)
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

    private suspend fun runOverlappingVadLoop(
        recorder: AudioRecord,
        maxChunkDurationMs: Long,
        overlapMs: Long,
        detectDrm: Boolean,
        onChunkReady: (OverlappingChunk) -> Unit,
        onAudioActive: () -> Unit,
        onIdleSilence: () -> Unit,
        onDrmBlockedDetected: () -> Unit
    ) {
        val frameBytes = ((SAMPLE_RATE * 2L * 80L) / 1000L).toInt() // 80ms = 2560 bytes
        val maxChunkBytes = ((SAMPLE_RATE * 2L * maxChunkDurationMs) / 1000L).toInt()
        val overlapBytes = ((SAMPLE_RATE * 2L * overlapMs) / 1000L).toInt()
        val minEarlyFlushBytes = ((SAMPLE_RATE * 2L * 750L) / 1000L).toInt()

        val readBuffer = ByteArray(frameBytes)
        val pcmAccumulator = ByteArrayOutputStream(maxChunkBytes + overlapBytes)
        var tailOverlap = ByteArray(0)
        var seqCounter = 0L

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
                    delay(100L)
                    continue
                }
                if (readCount == 0) {
                    delay(15L)
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
                            if (consecutiveDrmZeroFrames == 75) {
                                wasBlockedOrIdle = true
                                onDrmBlockedDetected()
                            }
                        } else {
                            consecutiveDrmZeroFrames = 0
                            consecutiveIdleFrames++
                            if (consecutiveIdleFrames == 50 && !wasBlockedOrIdle) {
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
                        val freshPcm = pcmAccumulator.toByteArray()
                        pcmAccumulator.reset()
                        hadSpeechInChunk = false
                        trailingSilentFrames = 0

                        // Gabungkan ekor 350ms dari potongan sebelumnya agar kata di batas tidak terpotong
                        val combinedPcm = if (tailOverlap.isNotEmpty()) {
                            ByteArrayOutputStream(tailOverlap.size + freshPcm.size).apply {
                                write(tailOverlap)
                                write(freshPcm)
                            }.toByteArray()
                        } else {
                            freshPcm
                        }

                        // Simpan ekor baru hanya jika masih ada bicara (reachedMax), kosongkan jika jeda alami
                        tailOverlap = if (reachedMax && freshPcm.size > overlapBytes) {
                            freshPcm.copyOfRange(freshPcm.size - overlapBytes, freshPcm.size)
                        } else {
                            ByteArray(0)
                        }

                        val chunkRms = computeRms(freshPcm)
                        if (chunkRms >= SPEECH_RMS_THRESHOLD * 0.7) {
                            seqCounter++
                            val wav = wrapPcmAsWav(combinedPcm, SAMPLE_RATE, 1, 16)
                            onChunkReady(OverlappingChunk(seqId = seqCounter, wavBytes = wav, rmsLevel = chunkRms))
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
        private const val SPEECH_RMS_THRESHOLD = 28.0
        private const val DIGITAL_ZERO_RMS = 1.5
    }
}
