package io.github.michalych647.textlift

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class OcrEngineSheet(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit,
) {
    private val dialog = BottomSheetDialog(activity)
    private val list: LinearLayout

    init {
        val view = activity.layoutInflater.inflate(R.layout.sheet_ocr_engines, null)
        list = view.findViewById(R.id.ocrEngineList)
        dialog.setContentView(view)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        render()
    }

    fun show() {
        dialog.show()
    }

    private fun render() {
        list.removeAllViews()
        val selected = OcrSettings.selectedId(activity)
        for (info in OcrSettings.engines) {
            val row = activity.layoutInflater.inflate(R.layout.item_ocr_engine, list, false)
            row.findViewById<TextView>(R.id.ocrEngineTitle).setText(info.title)
            row.findViewById<TextView>(R.id.ocrEngineSummary).setText(info.summary)
            row.findViewById<View>(R.id.ocrEngineCheck).isVisible = info.id == selected
            row.setOnClickListener {
                OcrSettings.select(activity, info.id)
                onChanged()
                dialog.dismiss()
            }
            list.addView(row)
        }
    }
}
