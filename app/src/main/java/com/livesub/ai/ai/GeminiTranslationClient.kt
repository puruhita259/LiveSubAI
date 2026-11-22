package com.livesub.ai.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiTranslationClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class TranslationResult {
        data class Success(val translatedText: String) : TranslationResult()
        data class Failure(val message: String, val isNetworkError: Boolean = false) : TranslationResult()
    }

    suspend fun translateAudioChunk(
        wavBytes: ByteArray,
        apiKey: String,
        model: String,
        sourceLanguage: String,
        targetLanguage: String
    ): TranslationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext TranslationResult.Failure(
                message = "API Key Gemini belum diisi di tab Tampilan & AI."
            )
        }

        val audioBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val prompt = buildString {
            append("Dengarkan klip audio video berikut dan terjemahkan ucapannya secara langsung ke dalam ")
            append(targetLanguage)
            append(". ")
            if (sourceLanguage != "Deteksi otomatis") {
                append("Bahasa sumber diperkirakan ")
                append(sourceLanguage)
                append(". ")
            }
            append("Aturan: balas HANYA teks subtitle hasil terjemahan yang alami dan ringkas (maksimal 2 baris). ")
            append("Jika hanya musik atau tidak ada ucapan manusia yang jelas, balas kosong.")
        }

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", prompt))
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject()
                                            .put("mimeType", "audio/wav")
                                            .put("data", audioBase64)
                                    )
                                )
                        )
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 120)
            )
        }

        val safeModel = model.trim().ifEmpty { "gemini-2.5-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errorMsg = parseErrorMessage(bodyText) ?: "HTTP ${response.code}"
                    return@withContext TranslationResult.Failure(
                        message = "Gagal menerjemahkan ($errorMsg)."
                    )
                }

                val translated = parseCandidateText(bodyText)
                TranslationResult.Success(translated)
            }
        } catch (ioe: IOException) {
            TranslationResult.Failure(
                message = "Koneksi terputus. Periksa internet Anda.",
                isNetworkError = true
            )
        } catch (e: Exception) {
            TranslationResult.Failure(
                message = e.localizedMessage ?: "Kesalahan tidak terduga saat menerjemahkan."
            )
        }
    }

    private fun parseCandidateText(jsonString: String): String {
        val root = JSONObject(jsonString)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            val partText = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (partText.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append(" ")
                builder.append(partText.trim())
            }
        }
        return builder.toString().trim()
    }

    private fun parseErrorMessage(jsonString: String): String? {
        return try {
            JSONObject(jsonString).optJSONObject("error")?.optString("message")
        } catch (_: Exception) {
            null
        }
    }
}
