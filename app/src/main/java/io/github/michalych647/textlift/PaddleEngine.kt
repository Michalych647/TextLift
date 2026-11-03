package io.github.michalych647.textlift

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PaddleEngine {

    class Line(
        val box: RectF,
        val words: List<Pair<String, RectF>>,
        val conf: Float,
        var block: Int = 0,
    )

    private class Rec(val session: OrtSession, val input: String, val dict: List<String>)

    private class Glyph(val text: String, val start: Int, val end: Int)

    private class Reading(
        val text: String,
        val conf: Float,
        val glyphs: List<Glyph>,
        val steps: Int,
        val inputWidth: Int,
        val resizedWidth: Int,
    )

    private const val DET_MAX_SIDE = 1536
    private const val DET_MIN_SIDE = 64
    private const val DET_THRESH = 0.3f
    private const val DET_BOX_THRESH = 0.6f
    private const val DET_UNCLIP = 1.5f
    private const val DET_MIN_KERNEL = 3
    private const val DET_MIN_BOX = 5
    private const val MAX_BOXES = 300
    private const val REC_HEIGHT = 48
    private const val REC_MIN_WIDTH = 320
    private const val REC_MAX_WIDTH = 3200
    private const val REC_MIN_CONF = 0.3f
    private const val REC_GOOD_CONF = 0.85f
    private const val CLS_FLIP_CONF = 0.9f

    private val mutex = Mutex()
    private var env: OrtEnvironment? = null
    private var det: OrtSession? = null
    private var cls: OrtSession? = null
    private var recs: Map<String, Rec> = emptyMap()
    private var key: String? = null

    fun release() {
        closeSessions()
    }

    suspend fun recognize(context: Context, bitmap: Bitmap): List<Line>? = mutex.withLock {
        withContext(Dispatchers.Default) {
            try {
                if (!ensure(context)) return@withContext null
                val soft = bitmap.takeIf { it.config != Bitmap.Config.HARDWARE }
                    ?: (bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap)
                try {
                    process(soft)
                } finally {
                    if (soft !== bitmap) soft.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("TextGrab", "PaddleOCR failed", e)
                null
            }
        }
    }

    private fun closeSessions() {
        recs.values.forEach { runCatching { it.session.close() } }
        recs = emptyMap()
        runCatching { cls?.close() }
        cls = null
        runCatching { det?.close() }
        det = null
        key = null
    }

    private fun ensure(context: Context): Boolean {
        if (!PaddleModels.isUniversalInstalled(context)) return false
        val codes = PaddleModels.installedLanguages(context)
        if (codes.isEmpty()) return false
        val hasCls = PaddleModels.clsFile(context).exists()
        val newKey = (listOf("det", if (hasCls) "cls" else "") + codes).joinToString("|")
        if (newKey == key && det != null) return true

        closeSessions()
        val environment = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
        }
        try {
            det = environment.createSession(PaddleModels.detFile(context).absolutePath, options)
            cls = if (hasCls) {
                runCatching {
                    environment.createSession(PaddleModels.clsFile(context).absolutePath, options)
                }.getOrNull()
            } else {
                null
            }
            val map = LinkedHashMap<String, Rec>()
            for (code in codes) {
                val session = environment.createSession(
                    PaddleModels.recFile(context, code).absolutePath, options
                )
                map[code] = Rec(session, session.inputNames.first(), PaddleModels.readDict(context, code))
            }
            recs = map
            key = newKey
            return true
        } catch (e: Exception) {
            closeSessions()
            throw e
        }
    }

    private suspend fun process(src: Bitmap): List<Line> {
        val boxes = detect(src)
        val lines = ArrayList<Line>()
        for (box in boxes) {
            currentCoroutineContext().ensureActive()
            readBox(src, box)?.let { lines.add(it) }
        }
        return assignBlocks(lines)
    }

    private fun tensorOf(environment: OrtEnvironment, data: FloatArray, shape: LongArray): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(data)
        buffer.rewind()
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    private fun detect(src: Bitmap): List<RectF> {
        val session = det ?: return emptyList()
        val environment = env ?: return emptyList()
        val w = src.width
        val h = src.height
        val longSide = max(w, h)
        val shortSide = min(w, h)
        var scale = 1f
        if (longSide > DET_MAX_SIDE) {
            scale = DET_MAX_SIDE.toFloat() / longSide
        } else if (shortSide < DET_MIN_SIDE) {
            scale = min(DET_MIN_SIDE.toFloat() / shortSide, 4000f / longSide)
        }
        val rw = max(32, (w * scale / 32f).roundToInt() * 32)
        val rh = max(32, (h * scale / 32f).roundToInt() * 32)

        val resized = if (rw == w && rh == h) src else Bitmap.createScaledBitmap(src, rw, rh, true)
        val px = IntArray(rw * rh)
        resized.getPixels(px, 0, rw, 0, 0, rw, rh)
        if (resized !== src) resized.recycle()

        val plane = rw * rh
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            data[i] = ((c and 0xFF) / 255f - 0.485f) / 0.229f
            data[plane + i] = (((c shr 8) and 0xFF) / 255f - 0.456f) / 0.224f
            data[2 * plane + i] = (((c shr 16) and 0xFF) / 255f - 0.406f) / 0.225f
        }

        var prob = FloatArray(0)
        tensorOf(environment, data, longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val buffer = out.floatBuffer
                prob = FloatArray(buffer.remaining())
                buffer.get(prob)
            }
        }
        return extractBoxes(prob, rw, rh, w.toFloat() / rw, h.toFloat() / rh)
    }

    private fun extractBoxes(prob: FloatArray, w: Int, h: Int, sx: Float, sy: Float): List<RectF> {
        val total = w * h
        if (prob.size < total) return emptyList()
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        val out = ArrayList<RectF>()
        for (start in 0 until total) {
            if (visited[start] || prob[start] <= DET_THRESH) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val q = ny * w + nx
                        if (!visited[q] && prob[q] > DET_THRESH) {
                            visited[q] = true
                            queue[tail++] = q
                        }
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (min(bw, bh) < DET_MIN_KERNEL) continue
            var sum = 0f
            for (y in minY..maxY) {
                val row = y * w
                for (x in minX..maxX) sum += prob[row + x]
            }
            if (sum / (bw * bh) < DET_BOX_THRESH) continue
            val d = bw.toFloat() * bh * DET_UNCLIP / (2f * (bw + bh))
            val left = (minX - d).coerceAtLeast(0f)
            val top = (minY - d).coerceAtLeast(0f)
            val right = (maxX + 1 + d).coerceAtMost(w.toFloat())
            val bottom = (maxY + 1 + d).coerceAtMost(h.toFloat())
            if (min(right - left, bottom - top) < DET_MIN_BOX) continue
            out.add(RectF(left * sx, top * sy, right * sx, bottom * sy))
            if (out.size >= MAX_BOXES) break
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun readBox(src: Bitmap, box: RectF): Line? {
        val left = floor(box.left).toInt().coerceIn(0, src.width - 1)
        val top = floor(box.top).toInt().coerceIn(0, src.height - 1)
        val right = ceil(box.right).toInt().coerceIn(left + 1, src.width)
        val bottom = ceil(box.bottom).toInt().coerceIn(top + 1, src.height)
        val w = right - left
        val h = bottom - top
        if (w < 4 || h < 4) return null

        val crop = Bitmap.createBitmap(src, left, top, w, h)
        try {
            val tall = h.toFloat() / w >= 1.5f
            var best = readAll(crop)
            var whole = false
            if (!tall) {
                val flip = cls?.let { flipProbability(it, crop) } ?: 0f
                if (flip >= CLS_FLIP_CONF) {
                    val turned = rotate(crop, 180f)
                    val alt = readAll(turned)
                    turned.recycle()
                    if (alt != null && (best == null || alt.conf > best.conf)) {
                        best = alt
                        whole = true
                    }
                }
            } else if (best == null || best.conf < REC_GOOD_CONF) {
                val turned = rotate(crop, -90f)
                val alt = readAll(turned)
                turned.recycle()
                if (alt != null && (best == null || alt.conf > best.conf)) {
                    best = alt
                    whole = true
                }
            }
            val reading = best ?: return null
            if (reading.text.isBlank() || reading.conf < REC_MIN_CONF) return null
            val rect = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            val words = if (whole) listOf(reading.text.trim() to rect) else splitWords(reading, rect)
            return Line(rect, words, reading.conf)
        } finally {
            if (crop !== src) crop.recycle()
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun readAll(bitmap: Bitmap): Reading? {
        var best: Reading? = null
        for (rec in recs.values) {
            val r = read(rec, bitmap) ?: continue
            val current = best
            if (current == null ||
                r.conf > current.conf + 0.03f ||
                (abs(r.conf - current.conf) <= 0.03f && r.text.length > current.text.length)
            ) best = r
        }
        return best
    }

    private fun read(rec: Rec, bitmap: Bitmap): Reading? {
        val environment = env ?: return null
        val ratio = bitmap.width.toFloat() / bitmap.height
        val resizedW = min(ceil(REC_HEIGHT * ratio).toInt(), REC_MAX_WIDTH).coerceAtLeast(8)
        val inputW = max(REC_MIN_WIDTH, resizedW)
        val scaled = Bitmap.createScaledBitmap(bitmap, resizedW, REC_HEIGHT, true)
        val px = IntArray(resizedW * REC_HEIGHT)
        scaled.getPixels(px, 0, resizedW, 0, 0, resizedW, REC_HEIGHT)
        if (scaled !== bitmap) scaled.recycle()

        val plane = REC_HEIGHT * inputW
        val data = FloatArray(3 * plane)
        for (y in 0 until REC_HEIGHT) {
            for (x in 0 until resizedW) {
                val c = px[y * resizedW + x]
                val i = y * inputW + x
                data[i] = ((c and 0xFF) / 255f - 0.5f) / 0.5f
                data[plane + i] = (((c shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                data[2 * plane + i] = (((c shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            }
        }

        var steps = 0
        var classes = 0
        var probs = FloatArray(0)
        tensorOf(environment, data, longArrayOf(1, 3, REC_HEIGHT.toLong(), inputW.toLong())).use { tensor ->
            rec.session.run(mapOf(rec.input to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape
                if (shape.size == 3) {
                    steps = shape[1].toInt()
                    classes = shape[2].toInt()
                }
                val buffer = out.floatBuffer
                probs = FloatArray(buffer.remaining())
                buffer.get(probs)
            }
        }
        if (steps <= 0 || classes <= 1 || probs.size < steps * classes) return null

        val ids = IntArray(steps)
        val best = FloatArray(steps)
        for (step in 0 until steps) {
            val base = step * classes
            var bi = 0
            var bv = probs[base]
            for (c in 1 until classes) {
                val v = probs[base + c]
                if (v > bv) {
                    bv = v
                    bi = c
                }
            }
            ids[step] = bi
            best[step] = bv
        }

        val glyphs = ArrayList<Glyph>()
        var confSum = 0f
        var step = 0
        while (step < steps) {
            val id = ids[step]
            if (id == 0) {
                step++
                continue
            }
            var end = step
            var peak = best[step]
            while (end + 1 < steps && ids[end + 1] == id) {
                end++
                if (best[end] > peak) peak = best[end]
            }
            val text = if (id - 1 < rec.dict.size) rec.dict[id - 1] else " "
            glyphs.add(Glyph(text, step, end))
            confSum += peak
            step = end + 1
        }
        if (glyphs.isEmpty()) return null
        return Reading(
            glyphs.joinToString("") { it.text },
            confSum / glyphs.size,
            glyphs,
            steps,
            inputW,
            resizedW,
        )
    }

    private fun splitWords(r: Reading, rect: RectF): List<Pair<String, RectF>> {
        if (r.glyphs.none { it.text.isBlank() }) return listOf(r.text.trim() to rect)
        val width = rect.width()

        fun xAt(step: Int): Float {
            val fraction = (step.toFloat() / r.steps) * r.inputWidth / r.resizedWidth
            return rect.left + width * fraction.coerceIn(0f, 1f)
        }

        val words = ArrayList<Pair<String, RectF>>()
        val sb = StringBuilder()
        var first = -1
        var last = -1

        fun flush() {
            if (sb.isEmpty()) return
            val l = xAt(first)
            val rr = max(xAt(last + 1), l + 1f)
            words.add(sb.toString() to RectF(l, rect.top, rr, rect.bottom))
            sb.setLength(0)
        }

        for (g in r.glyphs) {
            if (g.text.isBlank()) {
                flush()
                continue
            }
            if (sb.isEmpty()) first = g.start
            sb.append(g.text)
            last = g.end
        }
        flush()
        return words.ifEmpty { listOf(r.text.trim() to rect) }
    }

    private fun flipProbability(session: OrtSession, bitmap: Bitmap): Float {
        val environment = env ?: return 0f
        val name = session.inputNames.first()
        val shape = (session.inputInfo[name]?.info as? TensorInfo)?.shape
        val height = shape?.getOrNull(2)?.takeIf { it > 0 }?.toInt() ?: 80
        val width = shape?.getOrNull(3)?.takeIf { it > 0 }?.toInt() ?: 160
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val px = IntArray(width * height)
        scaled.getPixels(px, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()

        val plane = width * height
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            data[i] = (((c shr 16) and 0xFF) / 255f - 0.485f) / 0.229f
            data[plane + i] = (((c shr 8) and 0xFF) / 255f - 0.456f) / 0.224f
            data[2 * plane + i] = ((c and 0xFF) / 255f - 0.406f) / 0.225f
        }

        var out = FloatArray(0)
        tensorOf(environment, data, longArrayOf(1, 3, height.toLong(), width.toLong())).use { tensor ->
            session.run(mapOf(name to tensor)).use { result ->
                val buffer = (result.get(0) as OnnxTensor).floatBuffer
                out = FloatArray(buffer.remaining())
                buffer.get(out)
            }
        }
        if (out.size < 2) return 0f
        val isProbability = out[0] in 0f..1f && out[1] in 0f..1f && abs(out[0] + out[1] - 1f) < 0.02f
        if (isProbability) return out[1]
        val peak = max(out[0], out[1])
        val e0 = exp(out[0] - peak)
        val e1 = exp(out[1] - peak)
        return e1 / (e0 + e1)
    }

    private fun assignBlocks(lines: List<Line>): List<Line> {
        val sorted = lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        var block = -1
        var previous: Line? = null
        for (line in sorted) {
            val p = previous
            if (p == null || !sameBlock(p, line)) block++
            line.block = block
            previous = line
        }
        return sorted
    }

    private fun sameBlock(a: Line, b: Line): Boolean {
        val height = min(a.box.height(), b.box.height())
        if (b.box.top - a.box.bottom > height * 0.9f) return false
        val overlap = min(a.box.right, b.box.right) - max(a.box.left, b.box.left)
        if (overlap <= 0f) return false
        return abs(a.box.left - b.box.left) < height * 3f
    }
}
