package com.livesub.ai.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioCaptureManager(private val context: Context) {

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

    @SuppressLint("MissingPermission")
    suspend fun streamInternalAudio(
        mediaProjection: MediaProjection,
        chunkDurationMs: Long = 3200L,
        onChunkReady: suspend (AudioChunk) -> Unit,
        onDrmSilenceDetected: suspend () -> Unit
    ) = withContext(Dispatchers.IO) {
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

        val recorder = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        runCaptureLoop(
            recorder = recorder,
            chunkDurationMs = chunkDurationMs,
            detectDrmSilence = true,
            onChunkReady = onChunkReady,
            onDrmSilenceDetected = onDrmSilenceDetected
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun streamMicrophoneAudio(
        chunkDurationMs: Long = 3200L,
        onChunkReady: suspend (AudioChunk) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!hasRecordAudioPermission()) {
            throw SecurityException("Izin RECORD_AUDIO belum diberikan.")
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )

        runCaptureLoop(
            recorder = recorder,
            chunkDurationMs = chunkDurationMs,
            detectDrmSilence = false,
            onChunkReady = onChunkReady,
            onDrmSilenceDetected = {}
        )
    }

    private suspend fun runCaptureLoop(
        recorder: AudioRecord,
        chunkDurationMs: Long,
        detectDrmSilence: Boolean,
        onChunkReady: suspend (AudioChunk) -> Unit,
        onDrmSilenceDetected: suspend () -> Unit
    ) {
        val targetBytes = ((SAMPLE_RATE * 2L * chunkDurationMs) / 1000L).toInt()
        val readBuffer = ByteArray(4096)
        val pcmAccumulator = ByteArrayOutputStream(targetBytes)
        var consecutiveSilentChunks = 0

        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val readCount = recorder.read(readBuffer, 0, readBuffer.size)
                if (readCount > 0) {
                    pcmAccumulator.write(readBuffer, 0, readCount)
                    if (pcmAccumulator.size() >= targetBytes) {
                        val pcm = pcmAccumulator.toByteArray()
                        pcmAccumulator.reset()
                        val rms = computeRms(pcm)

                        if (rms < SILENCE_RMS_THRESHOLD) {
                            consecutiveSilentChunks++
                            if (detectDrmSilence && consecutiveSilentChunks >= 3) {
                                onDrmSilenceDetected()
                            }
                        } else {
                            consecutiveSilentChunks = 0
                            val wav = wrapPcmAsWav(pcm, SAMPLE_RATE, 1, 16)
                            onChunkReady(AudioChunk(wavBytes = wav, rmsLevel = rms))
                        }
                    }
                }
            }
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: IllegalStateException) {
            }
            recorder.release()
        }
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
        private const val SILENCE_RMS_THRESHOLD = 45.0
    }
}
