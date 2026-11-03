package io.github.michalych647.textlift

import android.content.Context

object OcrSettings {

    const val TESSERACT = "tesseract"
    const val PADDLE = "paddle"

    private const val PREFS = "ocr_settings"
    private const val KEY_ENGINE = "engine"

    class Info(val id: String, val title: Int, val summary: Int)

    val engines: List<Info> = listOf(
        Info(TESSERACT, R.string.ocr_engine_tesseract, R.string.ocr_engine_tesseract_summary),
        Info(PADDLE, R.string.ocr_engine_paddle, R.string.ocr_engine_paddle_summary),
    )

    fun selectedId(context: Context): String {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE, TESSERACT)
        return engines.firstOrNull { it.id == id }?.id ?: TESSERACT
    }

    fun selected(context: Context): Info {
        val id = selectedId(context)
        return engines.first { it.id == id }
    }

    fun select(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENGINE, id).apply()
    }
}
