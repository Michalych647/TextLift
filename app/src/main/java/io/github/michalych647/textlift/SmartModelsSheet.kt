package io.github.michalych647.textlift

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SmartModelsSheet(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit,
) {
    private val dialog = BottomSheetDialog(activity)
    private val list: LinearLayout
    private var installed: Set<String> = emptySet()
    private var query = ""
    private var showInstalled = true

    private fun fold(text: String): String {
        val d = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        return d.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    }

    init {
        val view = activity.layoutInflater.inflate(R.layout.sheet_languages, null)
        list = view.findViewById(R.id.languageList)
        view.minimumHeight = (activity.resources.displayMetrics.heightPixels * 0.85f).toInt()
        view.findViewById<TextView>(R.id.languageSheetTitle).setText(R.string.smart_models)
        view.findViewById<TextView>(R.id.languageSheetHint).setText(R.string.smart_models_hint)
        val tabs = view.findViewById<TabLayout>(R.id.languageTabs)
        tabs.addTab(tabs.newTab().setText(R.string.ocr_models_installed_header))
        tabs.addTab(tabs.newTab().setText(R.string.ocr_models_available_header))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showInstalled = tab.position == 0
                render()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        view.findViewById<android.widget.EditText>(R.id.languageSearchInput).doAfterTextChanged {
            query = fold(it?.toString().orEmpty().trim())
            render()
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setContentView(view)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun show() {
        dialog.show()
        refresh()
    }

    private fun refresh() {
        activity.lifecycleScope.launch {
            installed = SmartModels.installed()
            render()
        }
    }

    private fun render() {
        val codes = SmartModels.languages.filter { code ->
            ((code in installed) == showInstalled || (code in SmartModels.busy && !showInstalled)) &&
                (query.isEmpty() || fold("${SmartModels.displayName(code)} $code").contains(query))
        }.sortedBy { SmartModels.displayName(it) }
        list.removeAllViews()
        if (codes.isEmpty()) {
            list.addView(TextView(activity).apply {
                setText(
                    when {
                        query.isNotEmpty() -> R.string.ocr_models_nothing_found
                        showInstalled -> R.string.models_none_installed
                        else -> R.string.models_none_available
                    }
                )
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
            })
            return
        }
        for (code in codes) list.addView(row(code))
    }

    private fun row(code: String): View {
        val row = activity.layoutInflater.inflate(R.layout.item_language, list, false) as ViewGroup
        val status = row.findViewById<TextView>(R.id.langStatus)
        val action = row.findViewById<MaterialButton>(R.id.langAction)
        val progress = row.findViewById<LoadingIndicator>(R.id.langProgress)
        row.findViewById<ImageView>(R.id.langCheck).isInvisible = true
        row.findViewById<TextView>(R.id.langName).text = SmartModels.displayName(code)

        val busy = code in SmartModels.busy
        progress.isVisible = busy
        action.isInvisible = busy
        when {
            busy -> status.setText(R.string.language_downloading)
            code in installed -> {
                status.setText(R.string.language_downloaded)
                action.setIconResource(R.drawable.ic_delete)
                action.contentDescription = activity.getString(R.string.language_delete)
                action.setOnClickListener { delete(code) }
                row.setOnClickListener { delete(code) }
            }
            else -> {
                status.setText(R.string.smart_model_not_installed)
                action.setIconResource(R.drawable.ic_download)
                action.contentDescription = activity.getString(R.string.language_download)
                action.setOnClickListener { download(code) }
                row.setOnClickListener { download(code) }
            }
        }
        return row
    }

    private fun delete(code: String) {
        activity.lifecycleScope.launch {
            runCatching { SmartModels.delete(code) }
            onChanged()
            refresh()
        }
    }

    private fun download(code: String) {
        SmartModels.busy.add(code)
        render()
        activity.lifecycleScope.launch {
            try {
                SmartModels.download(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Snackbar.make(
                    dialog.window?.decorView ?: list,
                    activity.getString(R.string.language_download_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                SmartModels.busy.remove(code)
            }
            onChanged()
            refresh()
        }
    }
}
