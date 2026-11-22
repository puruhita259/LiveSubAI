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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class GeminiTranslationClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // Menyimpan model yang tidak mendukung field thinkingConfig agar tidak retry berulang
    private val modelsWithoutThinkingConfig = ConcurrentHashMap.newKeySet<String>()

    sealed class TranslationResult {
        data class Success(val translatedText: String, val usedModel: String) : TranslationResult()
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
            append("Terjemahkan ucapan di audio ke ")
            append(targetLanguage)
            if (sourceLanguage != "Deteksi otomatis") {
                append(" (dari ")
                append(sourceLanguage)
                append(")")
            }
            append(". Balas HANYA teks subtitle ringkas maks 2 baris. Jika tanpa ucapan, balas kosong.")
        }

        val candidates = buildModelFallbackChain(model)
        var lastError = "Gagal menerjemahkan."

        for (candidateModel in candidates) {
            val includeThinkingZero = !modelsWithoutThinkingConfig.contains(candidateModel)
            when (
                val attempt = executeSingleRequest(
                    audioBase64 = audioBase64,
                    prompt = prompt,
                    apiKey = apiKey,
                    modelId = candidateModel,
                    includeThinkingZero = includeThinkingZero
                )
            ) {
                is AttemptResult.Ok -> {
                    return@withContext TranslationResult.Success(attempt.text, candidateModel)
                }
                is AttemptResult.RetryWithoutThinking -> {
                    modelsWithoutThinkingConfig.add(candidateModel)
                    when (
                        val retry = executeSingleRequest(
                            audioBase64 = audioBase64,
                            prompt = prompt,
                            apiKey = apiKey,
                            modelId = candidateModel,
                            includeThinkingZero = false
                        )
                    ) {
                        is AttemptResult.Ok -> {
                            return@withContext TranslationResult.Success(retry.text, candidateModel)
                        }
                        is AttemptResult.SwitchModel -> {
                            lastError = retry.message
                        }
                        is AttemptResult.Fatal -> {
                            return@withContext TranslationResult.Failure(retry.message, retry.isNetworkError)
                        }
                        is AttemptResult.RetryWithoutThinking -> {
                            lastError = "Model tidak mendukung parameter permintaan."
                        }
                    }
                }
                is AttemptResult.SwitchModel -> {
                    lastError = attempt.message
                }
                is AttemptResult.Fatal -> {
                    return@withContext TranslationResult.Failure(attempt.message, attempt.isNetworkError)
                }
            }
        }

        TranslationResult.Failure(lastError)
    }

    private sealed class AttemptResult {
        data class Ok(val text: String) : AttemptResult()
        data object RetryWithoutThinking : AttemptResult()
        data class SwitchModel(val message: String) : AttemptResult()
        data class Fatal(val message: String, val isNetworkError: Boolean = false) : AttemptResult()
    }

    private fun executeSingleRequest(
        audioBase64: String,
        prompt: String,
        apiKey: String,
        modelId: String,
        includeThinkingZero: Boolean
    ): AttemptResult {
        val generationConfig = JSONObject()
            .put("temperature", 0.1)
            .put("maxOutputTokens", 90)

        if (includeThinkingZero) {
            generationConfig.put(
                "thinkingConfig",
                JSONObject().put("thinkingBudget", 0)
            )
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
            put("generationConfig", generationConfig)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    return AttemptResult.Ok(parseCandidateText(bodyText))
                }

                val errorMsg = parseErrorMessage(bodyText) ?: "HTTP ${response.code}"
                if (includeThinkingZero && response.code == 400 &&
                    (errorMsg.contains("thinking", ignoreCase = true) ||
                        errorMsg.contains("Unknown name", ignoreCase = true))
                ) {
                    return AttemptResult.RetryWithoutThinking
                }

                // Auto-fallback saat high demand (503/429) atau model tidak tersedia (404)
                if (response.code == 503 || response.code == 429 || response.code == 404 ||
                    errorMsg.contains("high demand", ignoreCase = true) ||
                    errorMsg.contains("no longer available", ignoreCase = true)
                ) {
                    return AttemptResult.SwitchModel("Gagal menerjemahkan ($errorMsg).")
                }

                AttemptResult.Fatal("Gagal menerjemahkan ($errorMsg).")
            }
        } catch (_: IOException) {
            AttemptResult.Fatal(
                message = "Koneksi terputus. Periksa internet Anda.",
                isNetworkError = true
            )
        } catch (e: Exception) {
            AttemptResult.Fatal(e.localizedMessage ?: "Kesalahan tidak terduga saat menerjemahkan.")
        }
    }

    private fun buildModelFallbackChain(configuredModel: String): List<String> {
        val list = linkedSetOf<String>()
        val primary = configuredModel.trim().removePrefix("models/")
        if (primary.isNotEmpty()) {
            list.add(primary)
        }
        list.add("gemini-flash-lite-latest")
        list.add("gemini-flash-latest")
        return list.toList()
    }

    private fun parseCandidateText(jsonString: String): String {
        val root = JSONObject(jsonString)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val partText = part.optString("text").orEmpty()
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
