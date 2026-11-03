package io.github.michalych647.textlift

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

object PaddleModels {

    const val UNIVERSAL = "universal"
    const val UNIVERSAL_SIZE = 5_800_000L

    class Lang(val code: String, val repo: String, val size: Long)

    private const val BASE = "https://huggingface.co/PaddlePaddle/"
    private const val DET_REPO = "PP-OCRv5_mobile_det_onnx"
    private const val CLS_REPO = "PP-LCNet_x0_25_textline_ori_onnx"

    val busy: MutableSet<String> = HashSet()

    val languages: List<Lang> = listOf(
        Lang("ch", "PP-OCRv5_mobile_rec_onnx", 16_500_000L),
        Lang("en", "en_PP-OCRv5_mobile_rec_onnx", 0L),
        Lang("latin", "latin_PP-OCRv5_mobile_rec_onnx", 7_900_000L),
        Lang("eslav", "eslav_PP-OCRv5_mobile_rec_onnx", 7_900_000L),
        Lang("cyrillic", "cyrillic_PP-OCRv5_mobile_rec_onnx", 0L),
        Lang("korean", "korean_PP-OCRv5_mobile_rec_onnx", 13_400_000L),
        Lang("arabic", "arabic_PP-OCRv5_mobile_rec_onnx", 0L),
        Lang("devanagari", "devanagari_PP-OCRv5_mobile_rec_onnx", 0L),
        Lang("th", "th_PP-OCRv5_mobile_rec_onnx", 0L),
        Lang("el", "el_PP-OCRv5_mobile_rec_onnx", 0L),
        Lang("ta", "ta_PP-OCRv5_mobile_rec_onnx", 0L),
        Lang("te", "te_PP-OCRv5_mobile_rec_onnx", 0L),
    )

    private fun root(context: Context): File =
        File(context.filesDir, "paddle").apply { mkdirs() }

    fun detFile(context: Context): File = File(root(context), "det.onnx")

    fun clsFile(context: Context): File = File(root(context), "cls.onnx")

    fun recFile(context: Context, code: String): File = File(root(context), "rec_$code.onnx")

    fun dictFile(context: Context, code: String): File = File(root(context), "rec_$code.json")

    fun isUniversalInstalled(context: Context): Boolean = detFile(context).exists()

    fun installedLanguages(context: Context): List<String> =
        languages.map { it.code }.filter {
            recFile(context, it).exists() && dictFile(context, it).exists()
        }

    fun installed(context: Context): List<String> =
        (if (isUniversalInstalled(context)) listOf(UNIVERSAL) else emptyList()) +
            installedLanguages(context)

    fun delete(context: Context, id: String) {
        if (id == UNIVERSAL) {
            detFile(context).delete()
            clsFile(context).delete()
        } else {
            recFile(context, id).delete()
            dictFile(context, id).delete()
        }
    }

    fun readDict(context: Context, code: String): List<String> {
        val array = JSONArray(dictFile(context, code).readText(Charsets.UTF_8))
        return List(array.length()) { array.getString(it) }
    }

    fun displayName(context: Context, code: String): String = when (code) {
        UNIVERSAL -> context.getString(R.string.ocr_universal_name)
        "ch" -> context.getString(R.string.paddle_lang_ch)
        "latin" -> context.getString(R.string.paddle_lang_latin)
        "eslav" -> context.getString(R.string.paddle_lang_eslav)
        "cyrillic" -> context.getString(R.string.paddle_lang_cyrillic)
        "devanagari" -> context.getString(R.string.paddle_lang_devanagari)
        "korean" -> localeName("ko", Locale.getDefault())
        "arabic" -> localeName("ar", Locale.getDefault())
        else -> localeName(code, Locale.getDefault())
    }

    fun keywords(code: String): String = when (code) {
        "ch" -> "chinese japanese english simplified traditional"
        "latin" -> "latin french german spanish italian portuguese polish turkish dutch"
        "eslav" -> "russian ukrainian belarusian east slavic cyrillic"
        "cyrillic" -> "cyrillic serbian bulgarian macedonian mongolian kazakh russian"
        "devanagari" -> "devanagari hindi marathi nepali sanskrit"
        "korean" -> localeName("ko", Locale.ENGLISH)
        "arabic" -> localeName("ar", Locale.ENGLISH)
        else -> localeName(code, Locale.ENGLISH)
    }

    private fun localeName(tag: String, locale: Locale): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(locale).replaceFirstChar { it.titlecase() }

    suspend fun download(context: Context, id: String) = withContext(Dispatchers.IO) {
        if (id == UNIVERSAL) {
            fetchFile(url(DET_REPO, "inference.onnx"), detFile(context))
            runCatching { fetchFile(url(CLS_REPO, "inference.onnx"), clsFile(context)) }
        } else {
            val lang = languages.first { it.code == id }
            val dict = parseCharacterDict(fetchText(url(lang.repo, "inference.yml")))
            fetchFile(url(lang.repo, "inference.onnx"), recFile(context, id))
            dictFile(context, id).writeText(JSONArray(dict).toString(), Charsets.UTF_8)
        }
    }

    private fun url(repo: String, file: String): String = "$BASE$repo/resolve/main/$file"

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "TextGrab")
        return connection
    }

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchFile(url: String, target: File) {
        val temp = File(target.parentFile, "${target.name}.part")
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (!temp.renameTo(target)) throw IOException("Cannot save model")
        } catch (e: Exception) {
            temp.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseCharacterDict(yml: String): List<String> {
        val lines = yml.split('\n')
        var i = lines.indexOfFirst { it.trim() == "character_dict:" }
        if (i < 0) throw IOException("Dictionary not found")
        i++
        val out = ArrayList<String>()
        while (i < lines.size) {
            val raw = lines[i].removeSuffix("\r")
            i++
            var k = 0
            while (k < raw.length && raw[k] == ' ') k++
            if (k >= raw.length || raw[k] != '-') break
            if (k + 1 < raw.length && raw[k + 1] != ' ') break
            if (k + 1 >= raw.length) continue
            out.add(scalar(raw.substring(k + 2)))
        }
        if (out.isEmpty()) throw IOException("Dictionary is empty")
        return out
    }

    private fun scalar(s: String): String {
        if (s.isEmpty()) return s
        return when (s[0]) {
            '\'' -> singleQuoted(s)
            '"' -> doubleQuoted(s)
            else -> s
        }
    }

    private fun singleQuoted(s: String): String {
        val sb = StringBuilder()
        var i = 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\'') {
                if (i + 1 < s.length && s[i + 1] == '\'') {
                    sb.append('\'')
                    i += 2
                    continue
                }
                break
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun doubleQuoted(s: String): String {
        val sb = StringBuilder()
        var i = 1

        fun hex(count: Int) {
            val end = minOf(i + count, s.length)
            val code = s.substring(i, end).toIntOrNull(16)
            if (code != null) sb.appendCodePoint(code)
            i = end
        }

        while (i < s.length) {
            val c = s[i]
            if (c == '"') break
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                i += 2
                when (n) {
                    '0' -> sb.append('\u0000')
                    'a' -> sb.append('\u0007')
                    'b' -> sb.append('\b')
                    't', '\t' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'v' -> sb.append('\u000B')
                    'f' -> sb.append('\u000C')
                    'r' -> sb.append('\r')
                    'e' -> sb.append('\u001B')
                    'N' -> sb.append('\u0085')
                    '_' -> sb.append(' ')
                    'L' -> sb.append(' ')
                    'P' -> sb.append(' ')
                    'x' -> hex(2)
                    'u' -> hex(4)
                    'U' -> hex(8)
                    else -> sb.append(n)
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
