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

object TessModels {

    class Model(val code: String, val name: String, val size: Long)

    private const val PREFS = "ocr_models"
    private const val KEY_LIST = "list"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val EXT = ".traineddata"
    private const val LIST_URL = "https://api.github.com/repos/tesseract-ocr/tessdata_fast/contents/"
    private const val FILE_URL = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/%s.traineddata"

    val busy: MutableSet<String> = HashSet()

    private val SPECIAL = mapOf(
        "chi_sim" to "Chinese (Simplified)",
        "chi_sim_vert" to "Chinese (Simplified, vertical)",
        "chi_tra" to "Chinese (Traditional)",
        "chi_tra_vert" to "Chinese (Traditional, vertical)",
        "frk" to "German (Fraktur)",
        "enm" to "English (Middle)",
        "grc" to "Greek (Ancient)",
        "equ" to "Math / equations",
    )

    fun dataPath(context: Context): String = context.filesDir.absolutePath

    private fun dir(context: Context): File =
        File(context.filesDir, "tessdata").apply { mkdirs() }

    fun installed(context: Context): List<String> =
        dir(context).listFiles { f -> f.name.endsWith(EXT) }
            ?.map { it.name.removeSuffix(EXT) }.orEmpty()

    fun isInstalled(context: Context, code: String): Boolean =
        File(dir(context), "$code$EXT").exists()

    fun fileSize(context: Context, code: String): Long =
        File(dir(context), "$code$EXT").length()

    fun delete(context: Context, code: String) {
        File(dir(context), "$code$EXT").delete()
    }

    fun isSetupDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SETUP_DONE, false)

    fun markSetupDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SETUP_DONE, true).apply()
    }

    fun displayName(code: String): String {
        SPECIAL[code]?.let { return it }
        val parts = code.split('_')
        val language = Locale.forLanguageTag(parts[0]).getDisplayLanguage(Locale.getDefault())
        val base = if (language.isBlank() || language.equals(parts[0], true)) parts[0] else language
        val extra = parts.drop(1).joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
        return (if (extra.isEmpty()) base else "$base ($extra)").replaceFirstChar { it.titlecase() }
    }

    fun preferredCodes(available: Set<String>): List<String> {
        val locale = Locale.getDefault()
        val system = when (locale.language) {
            "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "chi_tra" else "chi_sim"
            "nb", "nn", "no" -> "nor"
            else -> runCatching { locale.getISO3Language() }.getOrDefault("")
        }
        return listOf(system, "eng").filter { it.isNotEmpty() && it in available }.distinct()
    }

    suspend fun fetchList(context: Context): List<Model> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = try {
            httpGet(LIST_URL).also { prefs.edit().putString(KEY_LIST, it).apply() }
        } catch (e: IOException) {
            prefs.getString(KEY_LIST, null) ?: throw e
        }
        parse(json)
    }

    private fun parse(json: String): List<Model> {
        val array = JSONArray(json)
        val out = ArrayList<Model>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val name = o.optString("name")
            if (o.optString("type") != "file" || !name.endsWith(EXT) || name == "osd$EXT") continue
            val code = name.removeSuffix(EXT)
            out.add(Model(code, displayName(code), o.optLong("size")))
        }
        return out
    }

    suspend fun download(context: Context, code: String) = withContext(Dispatchers.IO) {
        val target = File(dir(context), "$code$EXT")
        val temp = File(dir(context), "$code$EXT.part")
        val connection = URL(FILE_URL.format(code)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "TextGrab")
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

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "TextGrab")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
