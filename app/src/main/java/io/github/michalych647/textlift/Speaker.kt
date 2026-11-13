package io.github.michalych647.textlift

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class Speaker(
    context: Context,
    private val onStateChanged: (speaking: Boolean) -> Unit,
    private val onProblem: (messageRes: Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: Pair<String, Locale?>? = null
    private var lastId = ""
    private var session = 0

    var isSpeaking = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setOnUtteranceProgressListener(listener)
                    pending?.let { (text, locale) -> start(text, locale) }
                } else {
                    failed = true
                    onProblem(R.string.tts_unavailable)
                    setSpeaking(false)
                }
                pending = null
            }
        }
    }

    fun speak(text: String, locale: Locale?) {
        if (failed) {
            onProblem(R.string.tts_unavailable)
            return
        }
        setSpeaking(true)
        if (ready) start(text, locale) else pending = text to locale
    }

    fun stop() {
        pending = null
        session++
        tts?.stop()
        setSpeaking(false)
    }

    fun shutdown() {
        pending = null
        session++
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        setSpeaking(false)
    }

    private fun start(text: String, locale: Locale?) {
        val engine = tts ?: return
        if (locale != null) {
            if (!usable(engine.setLanguage(locale))) {
                // Warn only when the fallback cannot speak either; otherwise the text is read anyway.
                if (!usable(engine.setLanguage(Locale.getDefault()))) {
                    onProblem(R.string.tts_language_missing)
                }
            }
        }
        val limit = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        val parts = split(text, limit)
        if (parts.isEmpty()) {
            setSpeaking(false)
            return
        }
        session++
        val mine = session
        engine.stop()
        parts.forEachIndexed { i, part ->
            val id = "$mine:$i"
            if (i == parts.lastIndex) lastId = id
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(part, mode, null, id) == TextToSpeech.ERROR) {
                main.post { if (session == mine) setSpeaking(false) }
                return
            }
        }
    }

    private fun usable(code: Int) =
        code != TextToSpeech.LANG_MISSING_DATA && code != TextToSpeech.LANG_NOT_SUPPORTED &&
            code != TextToSpeech.ERROR

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            if (utteranceId == lastId) main.post { if (lastId == utteranceId) setSpeaking(false) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { setSpeaking(false) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            main.post { setSpeaking(false) }
        }
    }

    private fun setSpeaking(value: Boolean) {
        if (isSpeaking == value) return
        isSpeaking = value
        onStateChanged(value)
    }

    private fun split(text: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            var rest = trimmed
            while (rest.length > limit) {
                var cut = rest.lastIndexOf(' ', limit)
                if (cut < limit / 2) cut = limit
                if (sb.isNotEmpty()) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                out.add(rest.substring(0, cut))
                rest = rest.substring(cut).trim()
            }
            if (sb.isNotEmpty() && sb.length + 1 + rest.length > limit) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(rest)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
