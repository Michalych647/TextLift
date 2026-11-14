package io.github.michalych647.textlift

import android.graphics.RectF
import com.google.mlkit.nl.entityextraction.DateTimeEntity
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import kotlinx.coroutines.CancellationException

/** Finds links, phone numbers and addresses in recognized text. */
object SmartActions {

    private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
    private val PHONE = Regex("""(?<![\w+])\+\d[\d\s().\-]{5,18}\d""")
    private const val URL_TRAILING = ".,;:!?)]}'\""

    private class Joined(val text: String, val starts: IntArray, val ends: IntArray)

    private fun join(result: OcrEngine.Result): Joined {
        val sb = StringBuilder()
        val starts = IntArray(result.words.size)
        val ends = IntArray(result.words.size)
        result.words.forEachIndexed { i, w ->
            if (i > 0) {
                when {
                    result.words[i - 1].rowId != w.rowId -> sb.append('\n')
                    w.glued && result.words[i - 1].lineId == w.lineId -> Unit
                    else -> sb.append(' ')
                }
            }
            starts[i] = sb.length
            sb.append(w.text)
            ends[i] = sb.length
        }
        return Joined(sb.toString(), starts, ends)
    }

    private fun wordRange(j: Joined, start: Int, end: Int): IntRange? {
        var first = -1
        var last = -1
        for (i in j.starts.indices) {
            if (j.starts[i] < end && j.ends[i] > start) {
                if (first == -1) first = i
                last = i
            }
        }
        return if (first == -1) null else first..last
    }

    private const val SPLIT_AFTER = "/.-_?=&:#@%"
    private const val MIN_PIECE = 3

    /** Breaks the OCR words that make up a URL into parts, so a handle can stop inside the link. */
    private fun splitUrls(result: OcrEngine.Result): OcrEngine.Result {
        val j = join(result)
        val inUrl = BooleanArray(result.words.size)
        for (m in URL.findAll(j.text)) {
            val end = m.range.first + m.value.trimEnd { it in URL_TRAILING }.length
            for (i in result.words.indices) {
                if (j.starts[i] >= m.range.first && j.ends[i] <= end) inUrl[i] = true
            }
        }
        if (inUrl.none { it }) return result
        val out = ArrayList<OcrEngine.Word>()
        result.words.forEachIndexed { i, w ->
            if (inUrl[i] && !w.isBarcode) out.addAll(splitWord(w)) else out.add(w)
        }
        return result.copy(words = out)
    }

    private fun splitWord(w: OcrEngine.Word): List<OcrEngine.Word> {
        val t = w.text
        val cuts = ArrayList<Int>()
        var last = 0
        for (i in t.indices) {
            if (t[i] in SPLIT_AFTER && i + 1 - last >= MIN_PIECE && t.length - (i + 1) >= MIN_PIECE) {
                cuts.add(i + 1)
                last = i + 1
            }
        }
        if (cuts.isEmpty()) return listOf(w)
        val bounds = listOf(0) + cuts + listOf(t.length)
        return (0 until bounds.size - 1).map { k ->
            val a = bounds[k]
            val b = bounds[k + 1]
            val box = RectF(
                w.box.left + w.box.width() * a / t.length,
                w.box.top,
                w.box.left + w.box.width() * b / t.length,
                w.box.bottom,
            )
            OcrEngine.Word(t.substring(a, b), box, w.lineId, w.rowId, false, glued = k > 0)
        }
    }

    suspend fun annotate(input: OcrEngine.Result): OcrEngine.Result {
        if (input.isEmpty) return input
        val result = splitUrls(input)
        val j = join(result)
        val links = ArrayList<OcrEngine.Link>()

        fun add(
            type: OcrEngine.LinkType, value: String, start: Int, end: Int,
            time: Long = 0L, allDay: Boolean = false,
        ) {
            val range = wordRange(j, start, end) ?: return
            if (links.any { range.first <= it.last && range.last >= it.first }) return
            links.add(OcrEngine.Link(type, value, range.first, range.last, time, allDay))
        }

        // Decoded QR codes and barcodes first, so what they hold is never re-read as a link.
        result.words.forEachIndexed { i, w ->
            if (w.isBarcode) links.add(OcrEngine.Link(OcrEngine.LinkType.CODE, w.text, i, i))
        }

        for (m in URL.findAll(j.text)) {
            val raw = m.value.trimEnd { it in URL_TRAILING }
            if (raw.length < 5) continue
            val value = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
            add(OcrEngine.LinkType.URL, value, m.range.first, m.range.first + raw.length)
        }
        for (m in PHONE.findAll(j.text)) {
            val digits = m.value.count { it.isDigit() }
            if (digits !in 7..15) continue
            val value = "+" + m.value.filter { it.isDigit() }
            add(OcrEngine.LinkType.PHONE, value, m.range.first, m.range.last + 1)
        }
        entities(j.text).forEach { e ->
            add(e.type, j.text.substring(e.start, e.end).replace('\n', ' '), e.start, e.end, e.time, e.allDay)
        }
        links.sortBy { it.first }
        return result.copy(links = links)
    }

    private class Found(
        val type: OcrEngine.LinkType,
        val start: Int,
        val end: Int,
        val time: Long = 0L,
        val allDay: Boolean = false,
    )

    private val DAY_OR_COARSER = setOf(
        DateTimeEntity.GRANULARITY_YEAR, DateTimeEntity.GRANULARITY_MONTH,
        DateTimeEntity.GRANULARITY_WEEK, DateTimeEntity.GRANULARITY_DAY,
    )

    /** Addresses, dates and times via ML Kit Entity Extraction, if a model for the text's language is installed. */
    private suspend fun entities(text: String): List<Found> {
        val code = Translator.identifyTag(text)?.let { SmartModels.forTag(it) } ?: return emptyList()
        if (!SmartModels.isInstalled(code)) return emptyList()
        return try {
            EntityExtraction.getClient(EntityExtractorOptions.Builder(code).build()).use { extractor ->
                val params = EntityExtractionParams.Builder(text)
                    .setEntityTypesFilter(setOf(Entity.TYPE_ADDRESS, Entity.TYPE_DATE_TIME))
                    .build()
                extractor.annotate(params).await().mapNotNull { annotation ->
                    val entity = annotation.entities.firstOrNull() ?: return@mapNotNull null
                    when (entity.type) {
                        Entity.TYPE_ADDRESS ->
                            Found(OcrEngine.LinkType.ADDRESS, annotation.start, annotation.end)
                        Entity.TYPE_DATE_TIME -> {
                            val date = entity.asDateTimeEntity() ?: return@mapNotNull null
                            Found(
                                OcrEngine.LinkType.DATE, annotation.start, annotation.end,
                                date.timestampMillis, date.dateTimeGranularity in DAY_OR_COARSER,
                            )
                        }
                        else -> null
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("TextGrab", "Entity detection failed", e)
            emptyList()
        }
    }
}
