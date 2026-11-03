package io.github.michalych647.textlift

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class HttpFailure(val status: Int, val body: String) : Exception("HTTP $status")

internal object FreeHttp {

    private const val AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    suspend fun call(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): String = withContext(Dispatchers.IO) {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw TranslationException("Invalid URL", e)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", AGENT)
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw HttpFailure(status, text)
            text
        } catch (e: HttpFailure) {
            throw e
        } catch (e: IOException) {
            throw TranslationException("Network error: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            connection.disconnect()
        }
    }
}

private fun splitText(text: String, maxChars: Int): List<String> {
    if (text.length <= maxChars) return listOf(text)
    val out = ArrayList<String>()
    val sb = StringBuilder()
    for (word in text.split(' ')) {
        if (sb.isNotEmpty() && sb.length + word.length + 1 > maxChars) {
            out.add(sb.toString())
            sb.setLength(0)
        }
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(word)
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}

class YandexFreeEngine : TranslationEngine {

    private val endpoint = "https://translate.yandex.net/api/v1/tr.json/translate"
    private val maxChars = 1800
    private val maxParallel = 4

    override suspend fun translate(request: TranslationRequest): List<String> {
        val gate = Semaphore(maxParallel)
        return coroutineScope {
            request.texts.map { text ->
                async { gate.withPermit { translateOne(text, request.target) } }
            }.awaitAll()
        }
    }

    private suspend fun translateOne(text: String, target: String): String {
        val out = StringBuilder()
        for (chunk in splitText(text, maxChars)) {
            val ucid = UUID.randomUUID().toString().replace("-", "")
            val url = "$endpoint?ucid=$ucid&srv=android&format=text"
            val body = "text=" + URLEncoder.encode(chunk, "UTF-8") +
                "&lang=" + URLEncoder.encode(target, "UTF-8")
            val response = try {
                FreeHttp.call(
                    url, "POST",
                    mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    body
                )
            } catch (e: HttpFailure) {
                throw TranslationException("Yandex: HTTP ${e.status}")
            }
            val array = JSONObject(response).optJSONArray("text")
                ?: throw TranslationException("Yandex returned no translations")
            if (out.isNotEmpty()) out.append(' ')
            out.append(array.optString(0))
        }
        return out.toString().ifBlank { text }
    }
}

class MicrosoftFreeEngine : TranslationEngine {

    private val authUrl = "https://edge.microsoft.com/translate/auth"
    private val endpoint = "https://api-edge.cognitive.microsofttranslator.com/translate"
    private val maxBatchChars = 20_000
    private val maxBatchItems = 100

    override suspend fun translate(request: TranslationRequest): List<String> {
        val target = when (request.target) {
            "zh" -> "zh-Hans"
            "no" -> "nb"
            else -> request.target
        }
        val results = arrayOfNulls<String>(request.texts.size)
        for (batch in batches(request.texts)) {
            val translated = send(batch.map { request.texts[it] }, target)
            batch.forEachIndexed { i, index -> results[index] = translated.getOrNull(i) }
        }
        return request.texts.mapIndexed { i, text ->
            results[i]?.takeIf { it.isNotBlank() } ?: text
        }
    }

    private fun batches(texts: List<String>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var chars = 0
        texts.forEachIndexed { index, text ->
            if (current.isNotEmpty() &&
                (chars + text.length > maxBatchChars || current.size >= maxBatchItems)
            ) {
                out.add(current)
                current = ArrayList()
                chars = 0
            }
            current.add(index)
            chars += text.length
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private suspend fun send(texts: List<String>, target: String): List<String> {
        val body = JSONArray().apply {
            texts.forEach { put(JSONObject().put("Text", it)) }
        }.toString()
        val url = "$endpoint?to=${URLEncoder.encode(target, "UTF-8")}" +
            "&api-version=3.0&includeSentenceLength=true"
        for (attempt in 0..1) {
            val token = token(attempt > 0)
            try {
                val response = FreeHttp.call(
                    url, "POST",
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "Content-Type" to "application/json",
                    ),
                    body
                )
                val array = JSONArray(response)
                return List(array.length()) {
                    array.getJSONObject(it).optJSONArray("translations")
                        ?.optJSONObject(0)?.optString("text").orEmpty()
                }
            } catch (e: HttpFailure) {
                if (e.status == 401 && attempt == 0) continue
                throw TranslationException("Microsoft: HTTP ${e.status}")
            }
        }
        throw TranslationException("Microsoft: authorization failed")
    }

    private suspend fun token(force: Boolean): String = lock.withLock {
        val cached = cachedToken
        if (!force && cached != null && System.currentTimeMillis() - issuedAt < TOKEN_LIFETIME_MS) {
            return@withLock cached
        }
        val fresh = try {
            FreeHttp.call(authUrl, "GET", emptyMap(), null).trim()
        } catch (e: HttpFailure) {
            throw TranslationException("Microsoft auth: HTTP ${e.status}")
        }
        if (fresh.isEmpty()) throw TranslationException("Microsoft auth: empty token")
        cachedToken = fresh
        issuedAt = System.currentTimeMillis()
        fresh
    }

    private companion object {
        const val TOKEN_LIFETIME_MS = 8 * 60_000L
        val lock = kotlinx.coroutines.sync.Mutex()
        var cachedToken: String? = null
        var issuedAt = 0L
    }
}
