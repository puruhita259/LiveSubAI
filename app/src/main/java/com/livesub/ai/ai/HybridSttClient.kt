package com.livesub.ai.ai

import android.util.Base64
import com.livesub.ai.model.HybridSttProvider
import com.livesub.ai.model.OverlayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Klien STT Cloud kecepatan tinggi untuk Mode Hybrid:
 * 1) Groq Console (Whisper Large v3 Turbo via OpenAI-compatible multipart API)
 * 2) Cloudflare Workers AI (@cf/openai/whisper-large-v3-turbo / @cf/openai/whisper)
 * Mendukung auto-failover jika kedua kredensial diisi.
 */
class HybridSttClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()

    sealed class SttResult {
        data class Success(
            val transcript: String,
            val detectedLanguage: String,
            val provider: HybridSttProvider
        ) : SttResult()

        data class Failure(
            val message: String,
            val isNetworkError: Boolean = false,
            val isRateLimited: Boolean = false
        ) : SttResult()
    }

    suspend fun transcribe(
        wavBytes: ByteArray,
        config: OverlayConfig
    ): SttResult = withContext(Dispatchers.IO) {
        val providersToTry = buildProviderChain(config)
        if (providersToTry.isEmpty()) {
            return@withContext SttResult.Failure(
                message = "Isi API Key Groq atau Account ID + Token Cloudflare di tab Tampilan & AI."
            )
        }

        var lastFailure = SttResult.Failure("Gagal transkripsi STT.")
        for (provider in providersToTry) {
            val res = when (provider) {
                HybridSttProvider.GROQ -> transcribeWithGroq(wavBytes, config)
                HybridSttProvider.CLOUDFLARE -> transcribeWithCloudflare(wavBytes, config)
            }
            if (res is SttResult.Success) {
                return@withContext res
            }
            if (res is SttResult.Failure) {
                lastFailure = res
            }
        }
        lastFailure
    }

    private fun buildProviderChain(config: OverlayConfig): List<HybridSttProvider> {
        val hasGroq = config.groqApiKey.isNotBlank()
        val hasCf = config.cloudflareAccountId.isNotBlank() && config.cloudflareApiToken.isNotBlank()
        val list = mutableListOf<HybridSttProvider>()
        when (config.hybridProvider) {
            HybridSttProvider.GROQ -> {
                if (hasGroq) list.add(HybridSttProvider.GROQ)
                if (hasCf) list.add(HybridSttProvider.CLOUDFLARE)
            }
            HybridSttProvider.CLOUDFLARE -> {
                if (hasCf) list.add(HybridSttProvider.CLOUDFLARE)
                if (hasGroq) list.add(HybridSttProvider.GROQ)
            }
        }
        return list
    }

    private fun transcribeWithGroq(wavBytes: ByteArray, config: OverlayConfig): SttResult {
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "chunk.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", config.groqModel.trim().ifEmpty { "whisper-large-v3-turbo" })
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")

        if (config.sourceLangCode != "auto" && config.sourceLangCode.isNotBlank()) {
            bodyBuilder.addFormDataPart("language", config.sourceLangCode)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${config.groqApiKey.trim()}")
            .post(bodyBuilder.build())
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = parseGroqError(raw) ?: "HTTP ${response.code}"
                    return SttResult.Failure(
                        message = "Groq STT gagal ($err).",
                        isRateLimited = response.code == 429 || response.code == 503
                    )
                }
                val json = JSONObject(raw)
                val text = sanitizeWhisperHallucination(json.optString("text").trim())
                val lang = normalizeWhisperLanguage(json.optString("language").ifBlank { config.sourceLangCode })
                SttResult.Success(
                    transcript = text,
                    detectedLanguage = lang,
                    provider = HybridSttProvider.GROQ
                )
            }
        } catch (_: IOException) {
            SttResult.Failure("Koneksi ke Groq terputus atau timeout.", isNetworkError = true)
        } catch (e: Exception) {
            SttResult.Failure(e.localizedMessage ?: "Kesalahan Groq STT.")
        }
    }

    private fun transcribeWithCloudflare(wavBytes: ByteArray, config: OverlayConfig): SttResult {
        val accountId = config.cloudflareAccountId.trim()
        val token = config.cloudflareApiToken.trim()
        val model = config.cloudflareModel.trim().ifEmpty { "@cf/openai/whisper-large-v3-turbo" }
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"

        val preferBase64Json = model.contains("v3-turbo", ignoreCase = true)
        val firstAttempt = executeCloudflareCall(url, token, wavBytes, config.sourceLangCode, useBase64Json = preferBase64Json)
        if (firstAttempt is SttResult.Success) return firstAttempt

        // Jika HTTP 400 (format body berbeda antar varian model Whisper di Cloudflare), retry dengan format satunya
        if (firstAttempt is SttResult.Failure && firstAttempt.message.contains("400")) {
            val retryAttempt = executeCloudflareCall(url, token, wavBytes, config.sourceLangCode, useBase64Json = !preferBase64Json)
            if (retryAttempt is SttResult.Success) return retryAttempt
        }
        return firstAttempt
    }

    private fun executeCloudflareCall(
        url: String,
        token: String,
        wavBytes: ByteArray,
        sourceLangCode: String,
        useBase64Json: Boolean
    ): SttResult {
        val requestBody = if (useBase64Json) {
            val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            val json = JSONObject()
                .put("audio", b64)
                .put("task", "transcribe")
            if (sourceLangCode != "auto" && sourceLangCode.isNotBlank()) {
                json.put("language", sourceLangCode)
            }
            json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        } else {
            wavBytes.toRequestBody("application/octet-stream".toMediaType())
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = parseCloudflareError(raw) ?: "HTTP ${response.code}"
                    return SttResult.Failure(
                        message = "Cloudflare STT gagal ($err).",
                        isRateLimited = response.code == 429 || response.code == 503
                    )
                }
                val root = JSONObject(raw)
                val resultObj = root.optJSONObject("result")
                val rawText = resultObj?.optString("text")
                    ?: root.optString("text")
                val cleaned = sanitizeWhisperHallucination(rawText.trim())
                val lang = normalizeWhisperLanguage(
                    resultObj?.optString("language").orEmpty().ifBlank { sourceLangCode }
                )
                SttResult.Success(
                    transcript = cleaned,
                    detectedLanguage = lang,
                    provider = HybridSttProvider.CLOUDFLARE
                )
            }
        } catch (_: IOException) {
            SttResult.Failure("Koneksi ke Cloudflare terputus atau timeout.", isNetworkError = true)
        } catch (e: Exception) {
            SttResult.Failure(e.localizedMessage ?: "Kesalahan Cloudflare STT.")
        }
    }

    private fun parseGroqError(raw: String): String? {
        return try {
            JSONObject(raw).optJSONObject("error")?.optString("message")?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCloudflareError(raw: String): String? {
        return try {
            val arr = JSONObject(raw).optJSONArray("errors")
            if (arr != null && arr.length() > 0) {
                arr.optJSONObject(0)?.optString("message")?.ifBlank { null }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Saring halusinasi umum Whisper saat audio hanya musik/desis pendek.
     */
    private fun sanitizeWhisperHallucination(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= 1) return ""
        val lower = trimmed.lowercase()
        val noiseSet = setOf(
            "thank you.",
            "thank you",
            "thanks for watching.",
            "thanks for watching!",
            "you",
            "...",
            "mbc",
            "amara.org",
            "subtitles by"
        )
        if (lower in noiseSet) return ""
        return trimmed
    }

    private fun normalizeWhisperLanguage(lang: String): String {
        val l = lang.trim().lowercase()
        return when (l) {
            "", "auto" -> "auto"
            "indonesian", "id" -> "id"
            "english", "en" -> "en"
            "japanese", "ja" -> "ja"
            "korean", "ko" -> "ko"
            "chinese", "mandarin", "zh" -> "zh"
            "thai", "th" -> "th"
            "arabic", "ar" -> "ar"
            "malay", "ms" -> "ms"
            "vietnamese", "vi" -> "vi"
            "hindi", "hi" -> "hi"
            "spanish", "es" -> "es"
            "french", "fr" -> "fr"
            "german", "de" -> "de"
            "russian", "ru" -> "ru"
            "italian", "it" -> "it"
            "portuguese", "pt" -> "pt"
            "turkish", "tr" -> "tr"
            "dutch", "nl" -> "nl"
            "polish", "pl" -> "pl"
            "tagalog", "filipino", "tl" -> "tl"
            else -> l.take(2)
        }
    }
}
