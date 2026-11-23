package com.livesub.ai.ai

import android.content.Context
import com.livesub.ai.model.LanguageCatalog
import com.livesub.ai.model.OfflineSttModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Pengelola unduhan & mesin Streaming Speech-to-Text 100% Offline berbasis Vosk.
 * Menerima frame PCM 50ms secara langsung dan menghasilkan kata demi kata secara real-time.
 */
class OfflineSttManager(context: Context) {

    private val modelsRootDir = File(context.applicationContext.filesDir, "vosk_models").apply {
        mkdirs()
    }

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getDownloadedModelIds(): Set<String> {
        return LanguageCatalog.OFFLINE_STT_MODELS
            .filter { resolveModelDir(it.id) != null }
            .map { it.id }
            .toSet()
    }

    fun resolveModelDir(modelId: String): File? {
        val base = File(modelsRootDir, modelId)
        if (!base.exists() || !base.isDirectory) return null
        if (isValidVoskModelDir(base)) return base
        val nested = base.listFiles()?.firstOrNull { it.isDirectory && isValidVoskModelDir(it) }
        return nested
    }

    private fun isValidVoskModelDir(dir: File): Boolean {
        return File(dir, "am").exists() || File(dir, "conf").exists() || File(dir, "final.mdl").exists()
    }

    suspend fun downloadModel(
        spec: OfflineSttModelSpec,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDir = File(modelsRootDir, spec.id)
            val tempZip = File(modelsRootDir, "${spec.id}.zip.part")
            if (tempZip.exists()) tempZip.delete()

            val request = Request.Builder().url(spec.downloadUrl).get().build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Gagal mengunduh model (HTTP ${response.code}).")
                }
                val body = response.body ?: throw IOException("Respons unduhan kosong.")
                val totalBytes = body.contentLength()
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempZip).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                onProgress(((downloaded.toFloat() / totalBytes.toFloat()) * 0.85f).coerceIn(0f, 0.85f))
                            }
                        }
                    }
                }
            }

            // Ekstrak ZIP dengan aman (Zip Slip guard)
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            val canonicalTarget = targetDir.canonicalPath
            ZipInputStream(BufferedInputStream(tempZip.inputStream())).use { zis ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val outFile = File(targetDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(canonicalTarget)) {
                        throw SecurityException("Entri ZIP tidak valid.")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            while (true) {
                                val count = zis.read(buf)
                                if (count == -1) break
                                fos.write(buf, 0, count)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
            tempZip.delete()
            onProgress(1f)

            resolveModelDir(spec.id)
                ?: throw IOException("Struktur model Vosk tidak valid setelah ekstrak.")
        }
    }

    fun deleteModel(modelId: String): Boolean {
        val dir = File(modelsRootDir, modelId)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    class StreamingSession(modelDir: File) {
        private val model = Model(modelDir.absolutePath)
        private val recognizer = Recognizer(model, 16000.0f)

        data class TranscriptEvent(
            val text: String,
            val isFinal: Boolean
        )

        fun feedPcm(pcm16kMono: ByteArray): TranscriptEvent? {
            if (pcm16kMono.isEmpty()) return null
            val finalized = recognizer.acceptWaveForm(pcm16kMono, pcm16kMono.size)
            return if (finalized) {
                val txt = parseJsonField(recognizer.result, "text")
                if (txt.isNotEmpty()) TranscriptEvent(txt, isFinal = true) else null
            } else {
                val partial = parseJsonField(recognizer.partialResult, "partial")
                if (partial.isNotEmpty()) TranscriptEvent(partial, isFinal = false) else null
            }
        }

        fun close() {
            try {
                recognizer.close()
            } catch (_: Exception) {
            }
            try {
                model.close()
            } catch (_: Exception) {
            }
        }

        private fun parseJsonField(jsonStr: String?, key: String): String {
            if (jsonStr.isNullOrBlank()) return ""
            return try {
                JSONObject(jsonStr).optString(key).trim()
            } catch (_: Exception) {
                ""
            }
        }
    }
}
