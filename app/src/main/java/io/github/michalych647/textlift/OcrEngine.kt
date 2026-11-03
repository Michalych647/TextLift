package io.github.michalych647.textlift

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object OcrEngine {

    data class Word(
        val text: String,
        val box: RectF,
        val lineId: Int,
        val rowId: Int,
    )

    data class Result(
        val words: List<Word>,
        val lineBoxes: List<RectF>,
        val lineBlocks: List<Int>,
    ) {
        val isEmpty get() = words.isEmpty()

        fun textOf(start: Int, end: Int): String {
            val sb = StringBuilder()
            for (i in start..end) {
                if (i > start) {
                    sb.append(if (words[i - 1].rowId != words[i].rowId) "\n" else " ")
                }
                sb.append(words[i].text)
            }
            return sb.toString()
        }

        val fullText: String get() = if (isEmpty) "" else textOf(0, words.size - 1)

        val lineTexts: List<String>
            get() {
                val byLine = words.groupBy { it.lineId }
                return lineBoxes.indices.map { id ->
                    byLine[id]?.joinToString(" ") { it.text }.orEmpty()
                }
            }
    }

    private class Line(
        val box: RectF,
        val words: List<Pair<String, RectF>>,
        val blockId: Int,
        val conf: Float,
        val crop: RectF,
        val unsure: Boolean,
    )

    private class Scored(val lines: List<Line>, val score: Float, val meanConfidence: Float)

    private class RawWord(
        val line: Int,
        val block: Int,
        val text: String,
        val box: RectF,
        val conf: Float,
    )

    private const val MIN_CONFIDENCE = 0.45f
    private const val LATIN_TRUSTED_CONFIDENCE = 0.8f
    private const val TRUSTED_LINE_CONFIDENCE = 0.95f

    private const val TESS_WORD_MIN_CONFIDENCE = 0.3f
    private const val TESS_MIN_LINE_CONFIDENCE = 0.4f
    private const val TESS_GARBLE_CONFIDENCE = 0.5f
    private const val TESS_FOREIGN_CONFIDENCE = 0.55f
    private const val TESS_MORE_TEXT_CONFIDENCE = 0.55f
    private const val TESS_NEW_LINE_CONFIDENCE = 0.65f
    private const val TESS_REPLACE_MARGIN = 0.1f
    private const val OVERLAP_THRESHOLD = 0.3f
    private const val TESS_UPSCALE_BELOW = 1600
    private const val TARGET_HEIGHT = 56f
    private const val PAD = 12
    private const val MAX_REFINE = 80

    private val garbleRegex = Regex("\\p{Ll}\\p{Lu}{2,}\\p{Ll}")

    private val toLatin = mapOf(
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x',
        'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H', 'О' to 'O',
        'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'Х' to 'X', 'і' to 'i', 'І' to 'I',
    )
    private val toCyrillic = toLatin.entries.associate { (k, v) -> v to k }

    private val cjkOptions = listOf(
        ChineseTextRecognizerOptions.Builder().build(),
        JapaneseTextRecognizerOptions.Builder().build(),
        KoreanTextRecognizerOptions.Builder().build(),
    )

    private var latin: TextRecognizer? = null

    private val tessMutex = Mutex()
    private var tess: TessBaseAPI? = null
    private var tessLangs: String? = null

    private fun latin(): TextRecognizer =
        latin ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { latin = it }

    fun warmUp() {
        if (latin != null) return
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        latin().process(InputImage.fromBitmap(bmp, 0))
            .addOnCompleteListener { bmp.recycle() }
    }

    fun release() {
        latin?.close()
        latin = null
        tess?.recycle()
        tess = null
        tessLangs = null
        PaddleEngine.release()
    }

    suspend fun recognize(context: Context, bitmap: Bitmap): Result {
        if (OcrSettings.selectedId(context) == OcrSettings.PADDLE) {
            val paddle = PaddleEngine.recognize(context, bitmap)
            if (paddle != null) {
                return buildResult(
                    paddle.map { Line(it.box, it.words, it.block, it.conf, it.box, false) }
                )
            }
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        var best = score(latin().run(image))

        if (best.lines.isEmpty() || best.meanConfidence < LATIN_TRUSTED_CONFIDENCE) {
            for (options in cjkOptions) {
                val candidate = TextRecognition.getClient(options).use { score(it.run(image)) }
                if (candidate.score > best.score) best = candidate
            }
        }

        val langs = TessModels.installed(context).sorted().joinToString("+")
        val lines = if (langs.isEmpty()) best.lines else tessPasses(context, bitmap, langs, best.lines)
        return buildResult(lines.filter { it.words.isNotEmpty() })
    }

    private suspend fun TextRecognizer.run(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    private suspend fun tessPasses(
        context: Context,
        bitmap: Bitmap,
        langs: String,
        base: List<Line>,
    ): List<Line> = tessMutex.withLock {
        withContext(Dispatchers.Default) {
            try {
                val api = ensureApi(context, langs) ?: return@withContext base
                val soft = bitmap.takeIf { it.config != Bitmap.Config.HARDWARE }
                    ?: (bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap)
                try {
                    val refined = refine(api, soft, base)
                    val page = pagePass(api, soft)
                    addMissing(refined, page)
                } finally {
                    if (soft !== bitmap) soft.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Tesseract failed", e)
                base
            }
        }
    }

    private fun ensureApi(context: Context, langs: String): TessBaseAPI? {
        val cached = tess
        if (cached != null && tessLangs == langs) return cached
        cached?.recycle()
        tess = null
        tessLangs = null
        val fresh = TessBaseAPI()
        if (!fresh.init(TessModels.dataPath(context), langs, TessBaseAPI.OEM_LSTM_ONLY)) {
            fresh.recycle()
            return null
        }
        tess = fresh
        tessLangs = langs
        return fresh
    }

    private suspend fun refine(api: TessBaseAPI, src: Bitmap, base: List<Line>): List<Line> {
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
        val out = ArrayList<Line>()
        var done = 0
        for (line in base) {
            currentCoroutineContext().ensureActive()
            if (done >= MAX_REFINE || !needsRefine(line)) {
                if (line.words.isNotEmpty()) out.add(line)
                continue
            }
            done++
            choose(line, readCrop(api, src, line))?.let { out.add(it) }
        }
        return out
    }

    private suspend fun pagePass(api: TessBaseAPI, soft: Bitmap): List<Line> {
        currentCoroutineContext().ensureActive()
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        val factor = if (max(soft.width, soft.height) < TESS_UPSCALE_BELOW) 2 else 1
        val input = if (factor == 1) {
            soft
        } else {
            Bitmap.createScaledBitmap(soft, soft.width * factor, soft.height * factor, true)
        }
        try {
            api.setImage(input)
            val kept = readWords(api).mapNotNull { r ->
                val text = unmix(r.text)
                if (isNoise(text, r.conf.takeIf { it > 0f }, TESS_WORD_MIN_CONFIDENCE)) {
                    null
                } else {
                    RawWord(
                        r.line, r.block, text,
                        RectF(
                            r.box.left / factor, r.box.top / factor,
                            r.box.right / factor, r.box.bottom / factor
                        ),
                        r.conf
                    )
                }
            }
            return kept.groupBy { it.line }.values.map { ws ->
                val pairs = ws.map { it.text to it.box }
                val box = RectF(pairs[0].second).apply { pairs.forEach { union(it.second) } }
                Line(box, pairs, ws[0].block, ws.map { it.conf }.average().toFloat(), box, false)
            }
        } finally {
            if (input !== soft) input.recycle()
        }
    }

    private fun readWords(api: TessBaseAPI): List<RawWord> {
        api.getUTF8Text()
        val iterator = api.getResultIterator() ?: return emptyList()
        val out = ArrayList<RawWord>()
        var lineKey = -1
        var blockId = -1
        val word = TessBaseAPI.PageIteratorLevel.RIL_WORD
        iterator.begin()
        do {
            if (iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)) blockId++
            if (iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) lineKey++
            val text = iterator.getUTF8Text(word)?.trim().orEmpty()
            val conf = iterator.confidence(word) / 100f
            val rect = iterator.getBoundingRect(word)
            if (text.isNotEmpty() && rect != null) {
                out.add(RawWord(max(lineKey, 0), max(blockId, 0), text, RectF(rect), conf))
            }
        } while (iterator.next(word))
        iterator.delete()
        return out
    }

    private fun readCrop(api: TessBaseAPI, src: Bitmap, line: Line): Line? {
        val crop = line.crop
        val margin = max(2f, crop.height() * 0.12f)
        val left = floor(crop.left - margin).toInt().coerceIn(0, src.width - 1)
        val top = floor(crop.top - margin).toInt().coerceIn(0, src.height - 1)
        val right = ceil(crop.right + margin).toInt().coerceIn(left + 1, src.width)
        val bottom = ceil(crop.bottom + margin).toInt().coerceIn(top + 1, src.height)
        val w = right - left
        val h = bottom - top
        if (w < 4 || h < 4) return null

        val scale = (TARGET_HEIGHT / h).coerceIn(0.5f, 4f)
        val sw = max(1, (w * scale).roundToInt())
        val sh = max(1, (h * scale).roundToInt())
        val region = Bitmap.createBitmap(src, left, top, w, h)
        val scaled = if (sw == w && sh == h) region else Bitmap.createScaledBitmap(region, sw, sh, true)
        if (scaled !== region && region !== src) region.recycle()
        val prepared = prepare(scaled)
        if (scaled !== src) scaled.recycle()

        try {
            api.setImage(prepared)
            val raw = readWords(api)
            val sx = sw.toFloat() / w
            val sy = sh.toFloat() / h
            val words = ArrayList<Pair<String, RectF>>()
            var confSum = 0f
            for (r in raw) {
                val text = unmix(r.text)
                if (isNoise(text, r.conf.takeIf { it > 0f }, TESS_WORD_MIN_CONFIDENCE)) continue
                val box = RectF(
                    (r.box.left - PAD) / sx + left,
                    (r.box.top - PAD) / sy + top,
                    (r.box.right - PAD) / sx + left,
                    (r.box.bottom - PAD) / sy + top,
                )
                words.add(text to box)
                confSum += r.conf
            }
            if (words.isEmpty()) return null
            val union = RectF(words[0].second).apply { words.forEach { union(it.second) } }
            return Line(union, words, line.blockId, confSum / words.size, line.crop, false)
        } finally {
            prepared.recycle()
        }
    }

    private fun prepare(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val v = (r * 299 + g * 587 + b * 114) / 1000
            gray[i] = v
            hist[v]++
        }
        val total = w * h
        var sum = 0L
        for (t in 0..255) sum += t.toLong() * hist[t]
        var wB = 0
        var sumB = 0L
        var maxVar = -1.0
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > maxVar) {
                maxVar = v
                threshold = t
            }
        }
        var darkCount = 0
        var darkSum = 0L
        var brightCount = 0
        var brightSum = 0L
        for (t in 0..255) {
            if (t <= threshold) {
                darkCount += hist[t]
                darkSum += t.toLong() * hist[t]
            } else {
                brightCount += hist[t]
                brightSum += t.toLong() * hist[t]
            }
        }
        val darkMean = if (darkCount > 0) darkSum.toFloat() / darkCount else 0f
        val brightMean = if (brightCount > 0) brightSum.toFloat() / brightCount else 255f
        val invert = brightCount < darkCount
        val bg = if (invert) 255f - darkMean else brightMean
        val fg = if (invert) 255f - brightMean else darkMean
        val stretch = bg - fg >= 12f

        val ow = w + 2 * PAD
        val oh = h + 2 * PAD
        val out = IntArray(ow * oh) { -1 }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = if (invert) 255 - gray[y * w + x] else gray[y * w + x]
                if (stretch) v = (((v - fg) * 255f) / (bg - fg)).roundToInt().coerceIn(0, 255)
                out[(y + PAD) * ow + x + PAD] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, ow, 0, 0, ow, oh)
        return bmp
    }

    private fun needsRefine(line: Line): Boolean {
        val words = line.words.map { it.first }
        if (words.isNotEmpty() && cjkShare(words) >= 0.5f && words.none(::looksGarbled)) return false
        return line.unsure || line.conf < TRUSTED_LINE_CONFIDENCE || words.any(::looksGarbled)
    }

    private fun choose(old: Line, read: Line?): Line? {
        if (old.words.isEmpty()) {
            return read?.takeIf { it.conf >= TESS_NEW_LINE_CONFIDENCE }
        }
        if (read == null || read.conf < TESS_MIN_LINE_CONFIDENCE) return old

        val oldWords = old.words.map { it.first }
        val newWords = read.words.map { it.first }

        if (read.conf >= TESS_GARBLE_CONFIDENCE &&
            oldWords.any(::looksGarbled) &&
            newWords.none(::looksGarbled)
        ) return read

        if (read.conf >= TESS_FOREIGN_CONFIDENCE &&
            foreignShare(newWords) >= 0.5f &&
            foreignShare(oldWords) < 0.2f
        ) return read

        val oldLetters = letterCount(oldWords)
        val newLetters = letterCount(newWords)

        if (read.conf >= TESS_MORE_TEXT_CONFIDENCE && newLetters >= 1.25f * oldLetters) return read

        if (read.conf > old.conf + TESS_REPLACE_MARGIN && newLetters >= 0.7f * oldLetters) return read

        return old
    }

    private fun addMissing(base: List<Line>, extra: List<Line>): List<Line> {
        val out = ArrayList<Line>(base)
        for (e in extra) {
            if (e.conf < TESS_NEW_LINE_CONFIDENCE) continue
            if (out.any { overlap(it.box, e.box) >= OVERLAP_THRESHOLD }) continue
            out.add(e)
        }
        return out
    }

    private fun unmix(word: String): String {
        var latinCount = 0
        var cyrillicCount = 0
        for (c in word) {
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.LATIN -> latinCount++
                Character.UnicodeScript.CYRILLIC -> cyrillicCount++
                else -> Unit
            }
        }
        if (latinCount == 0 || cyrillicCount == 0) return word
        val map = if (cyrillicCount >= latinCount) toCyrillic else toLatin
        return buildString { for (c in word) append(map[c] ?: c) }
    }

    private fun looksGarbled(word: String): Boolean {
        if (word.length < 4) return false
        var switches = 0
        for (i in 1 until word.length) {
            if (word[i - 1].isLowerCase() && word[i].isUpperCase()) switches++
        }
        if (switches >= 2) return true
        return garbleRegex.containsMatchIn(word)
    }

    private fun letterCount(words: List<String>): Int =
        words.sumOf { w -> w.count { it.isLetterOrDigit() } }

    private fun overlap(a: RectF, b: RectF): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val smaller = min(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else (w * h) / smaller
    }

    private fun cjkShare(words: List<String>): Float {
        var letters = 0
        var cjk = 0
        for (word in words) for (c in word) {
            if (!c.isLetter()) continue
            letters++
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL -> cjk++
                else -> Unit
            }
        }
        return if (letters == 0) 0f else cjk.toFloat() / letters
    }

    private fun foreignShare(words: List<String>): Float {
        var letters = 0
        var foreign = 0
        for (word in words) for (c in word) {
            if (!c.isLetter()) continue
            letters++
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.LATIN,
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL,
                Character.UnicodeScript.COMMON,
                Character.UnicodeScript.INHERITED -> Unit
                else -> foreign++
            }
        }
        return if (letters == 0) 0f else foreign.toFloat() / letters
    }

    private fun score(text: Text): Scored {
        val lines = ArrayList<Line>()
        var score = 0f
        var confSum = 0f
        var confCount = 0
        for ((blockId, block) in text.textBlocks.withIndex()) {
            for (line in block.lines) {
                val words = ArrayList<Pair<String, RectF>>()
                var crop: RectF? = null
                var unsure = false
                var lineConf = 0f
                for (e in line.elements) {
                    val box = e.boundingBox ?: continue
                    val t = e.text.trim()
                    val conf = e.confidence.takeIf { it > 0f }
                    if (conf != null) {
                        confSum += conf
                        confCount++
                    }
                    val rect = RectF(box)
                    val current = crop
                    if (current == null) crop = RectF(rect) else current.union(rect)
                    if (isNoise(t, conf)) {
                        if (t.any { it.isLetterOrDigit() }) unsure = true
                        continue
                    }
                    words.add(t to rect)
                    score += t.count { it.isLetterOrDigit() } * (conf ?: 0.7f)
                    lineConf += conf ?: 0.7f
                }
                val cropBox = crop ?: continue
                if (words.isEmpty() && !unsure) continue
                val lineBox = if (words.isEmpty()) {
                    RectF(cropBox)
                } else {
                    RectF(words[0].second).apply { words.forEach { union(it.second) } }
                }
                val meanConf = if (words.isEmpty()) 0f else lineConf / words.size
                lines.add(Line(lineBox, words, blockId, meanConf, cropBox, unsure))
            }
        }
        val mean = if (confCount > 0) confSum / confCount else 1f
        return Scored(lines, score, mean)
    }

    private fun isNoise(t: String, conf: Float?, minConfidence: Float = MIN_CONFIDENCE): Boolean {
        if (t.isEmpty()) return true
        if (conf != null && conf < minConfidence) return true
        val letters = t.count { it.isLetterOrDigit() }
        if (letters == 0 && t.length <= 2) return true
        if (t.length == 1 && conf != null && conf < 0.7f) return true
        return false
    }

    private fun buildResult(lines: List<Line>): Result {
        val byTop = lines.sortedBy { it.box.centerY() }
        val rows = ArrayList<MutableList<Line>>()
        for (line in byTop) {
            val row = rows.lastOrNull()
            if (row != null && sameRow(row[0].box, line.box)) row.add(line) else rows.add(mutableListOf(line))
        }
        val words = ArrayList<Word>()
        val lineBoxes = ArrayList<RectF>()
        val lineBlocks = ArrayList<Int>()
        var lineId = 0
        rows.forEachIndexed { rowId, row ->
            for (line in row.sortedBy { it.box.left }) {
                lineBoxes.add(line.box)
                lineBlocks.add(line.blockId)
                for ((t, box) in line.words.sortedBy { it.second.left }) {
                    words.add(Word(t, box, lineId, rowId))
                }
                lineId++
            }
        }
        return Result(words, lineBoxes, lineBlocks)
    }

    private fun sameRow(a: RectF, b: RectF): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap > 0.5f * min(a.height(), b.height())
    }
}
