package io.github.michalych647.textlift

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.snackbar.Snackbar
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TYPE_HEADER = 0
private const val TYPE_ROW = 1

private sealed interface OcrItem {
    data class Header(val title: Int) : OcrItem

    data class Row(
        val code: String,
        val name: String,
        val size: Long,
        val installed: Boolean,
        val busy: Boolean,
    ) : OcrItem
}

private class OcrEntry(
    val code: String,
    val name: String,
    val size: Long,
    val haystack: String,
)

private object OcrDiff : DiffUtil.ItemCallback<OcrItem>() {
    override fun areItemsTheSame(a: OcrItem, b: OcrItem): Boolean = when {
        a is OcrItem.Header && b is OcrItem.Header -> a.title == b.title
        a is OcrItem.Row && b is OcrItem.Row -> a.code == b.code
        else -> false
    }

    override fun areContentsTheSame(a: OcrItem, b: OcrItem): Boolean = a == b
}

private class OcrRowHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val name = view.findViewById<TextView>(R.id.ocrName)
    private val status = view.findViewById<TextView>(R.id.ocrStatus)
    private val action = view.findViewById<MaterialButton>(R.id.ocrAction)
    private val progress = view.findViewById<LoadingIndicator>(R.id.ocrProgress)

    fun bind(item: OcrItem.Row, onAction: (OcrItem.Row) -> Unit) {
        val context = itemView.context
        name.text = item.name
        progress.isVisible = item.busy
        action.isInvisible = item.busy
        if (item.busy) {
            status.setText(R.string.language_downloading)
            itemView.setOnClickListener(null)
            itemView.isClickable = false
            action.setOnClickListener(null)
            return
        }
        val click = View.OnClickListener { onAction(item) }
        itemView.setOnClickListener(click)
        action.setOnClickListener(click)
        if (item.installed) {
            status.setText(R.string.language_downloaded)
            action.setIconResource(R.drawable.ic_delete)
            action.contentDescription = context.getString(R.string.language_delete)
        } else {
            status.text = when {
                item.code == PaddleModels.UNIVERSAL -> context.getString(
                    R.string.ocr_universal_summary,
                    Formatter.formatShortFileSize(context, item.size)
                )
                item.size > 0L -> context.getString(
                    R.string.ocr_model_not_installed,
                    Formatter.formatShortFileSize(context, item.size)
                )
                else -> context.getString(R.string.ocr_model_not_installed_plain)
            }
            action.setIconResource(R.drawable.ic_download)
            action.contentDescription = context.getString(R.string.language_download)
        }
    }
}

private class OcrAdapter(
    private val onAction: (OcrItem.Row) -> Unit,
) : ListAdapter<OcrItem, RecyclerView.ViewHolder>(OcrDiff) {

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is OcrItem.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_ocr_header, parent, false)) {}
        } else {
            OcrRowHolder(inflater.inflate(R.layout.item_ocr_model, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is OcrItem.Header -> (holder.itemView as TextView).setText(item.title)
            is OcrItem.Row -> (holder as OcrRowHolder).bind(item, onAction)
        }
    }
}

private fun foldText(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
    return decomposed.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
}

class OcrModelsSheet(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit,
) {
    private val paddle = OcrSettings.selectedId(activity) == OcrSettings.PADDLE
    private val dialog = BottomSheetDialog(activity)
    private val loading: View
    private val error: TextView
    private val recycler: RecyclerView
    private val searchInput: EditText
    private val adapter = OcrAdapter { toggle(it) }
    private var entries: List<OcrEntry> = emptyList()
    private var terms: List<String> = emptyList()
    private var refreshJob: Job? = null
    private var buildJob: Job? = null

    init {
        val view = activity.layoutInflater.inflate(R.layout.sheet_ocr_models, null)
        view.minimumHeight = (activity.resources.displayMetrics.heightPixels * 0.85f).toInt()
        loading = view.findViewById(R.id.ocrModelsLoading)
        error = view.findViewById(R.id.ocrModelsError)
        recycler = view.findViewById(R.id.ocrModelList)
        searchInput = view.findViewById(R.id.ocrSearchInput)
        view.findViewById<TextView>(R.id.ocrModelsHint).setText(
            if (paddle) R.string.ocr_models_hint_paddle else R.string.ocr_models_hint
        )
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        dialog.setContentView(view)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        searchInput.doAfterTextChanged {
            terms = foldText(it?.toString().orEmpty()).split(' ').filter { s -> s.isNotEmpty() }
            refresh(120L, true)
        }
        searchInput.setOnEditorActionListener { v, _, _ ->
            searchInput.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
    }

    fun show() {
        dialog.show()
        rebuild(emptyList())
        if (!paddle) load()
    }

    private fun load() {
        error.isVisible = false
        loading.isVisible = true
        activity.lifecycleScope.launch {
            try {
                rebuild(TessModels.fetchList(activity))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.text = activity.getString(R.string.ocr_models_failed, e.localizedMessage ?: "")
                error.isVisible = true
            } finally {
                loading.isVisible = false
            }
        }
    }

    private fun rebuild(models: List<TessModels.Model>) {
        buildJob?.cancel()
        buildJob = activity.lifecycleScope.launch {
            entries = withContext(Dispatchers.Default) { buildEntries(models) }
            refresh()
        }
    }

    private fun buildEntries(models: List<TessModels.Model>): List<OcrEntry> {
        val collator = Collator.getInstance()
        if (paddle) {
            val languages = PaddleModels.languages.map { lang ->
                val name = PaddleModels.displayName(activity, lang.code)
                OcrEntry(
                    lang.code, name, lang.size,
                    foldText("$name ${lang.code} ${PaddleModels.keywords(lang.code)}")
                )
            }.sortedWith { a, b -> collator.compare(a.name, b.name) }
            val universalName = PaddleModels.displayName(activity, PaddleModels.UNIVERSAL)
            val universal = OcrEntry(
                PaddleModels.UNIVERSAL, universalName, PaddleModels.UNIVERSAL_SIZE,
                foldText("$universalName detection classification orientation")
            )
            return listOf(universal) + languages
        }
        val known = models.associateBy { it.code }
        val installed = TessModels.installed(activity)
        return (known.keys + installed).map { code ->
            val model = known[code] ?: TessModels.Model(
                code, TessModels.displayName(code), TessModels.fileSize(activity, code)
            )
            val english = Locale.forLanguageTag(code.substringBefore('_'))
                .getDisplayLanguage(Locale.ENGLISH)
            OcrEntry(code, model.name, model.size, foldText("${model.name} $code $english"))
        }.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    private fun refresh(delayMs: Long = 0L, toTop: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = activity.lifecycleScope.launch {
            if (delayMs > 0L) delay(delayMs)
            val query = terms
            val source = entries
            val busy = (if (paddle) PaddleModels.busy else TessModels.busy).toSet()
            val items = withContext(Dispatchers.Default) {
                val installed = if (paddle) {
                    PaddleModels.installed(activity).toSet()
                } else {
                    TessModels.installed(activity).toSet()
                }
                val shown = if (query.isEmpty()) {
                    source
                } else {
                    source.filter { e -> query.all { e.haystack.contains(it) } }
                }
                fun row(e: OcrEntry, isInstalled: Boolean) =
                    OcrItem.Row(e.code, e.name, e.size, isInstalled, e.code in busy)

                val out = ArrayList<OcrItem>()
                if (paddle) {
                    val universal = shown.firstOrNull { it.code == PaddleModels.UNIVERSAL }
                    if (universal != null && universal.code !in installed) {
                        out.add(OcrItem.Header(R.string.ocr_models_universal_header))
                        out.add(row(universal, false))
                    }
                }
                val have = shown.filter { it.code in installed }
                    .sortedBy { if (it.code == PaddleModels.UNIVERSAL) 0 else 1 }
                val rest = shown.filter { it.code !in installed && it.code != PaddleModels.UNIVERSAL }
                if (have.isNotEmpty()) {
                    out.add(OcrItem.Header(R.string.ocr_models_installed_header))
                    have.mapTo(out) { row(it, true) }
                }
                if (rest.isNotEmpty()) {
                    out.add(OcrItem.Header(R.string.ocr_models_available_header))
                    rest.mapTo(out) { row(it, false) }
                }
                if (out.isEmpty() && query.isNotEmpty()) {
                    out.add(OcrItem.Header(R.string.ocr_models_nothing_found))
                }
                out
            }
            adapter.submitList(items) { if (toTop) recycler.scrollToPosition(0) }
        }
    }

    private fun toggle(row: OcrItem.Row) {
        if (row.installed) delete(row.code) else download(row.code)
    }

    private fun delete(code: String) {
        if (paddle) PaddleModels.delete(activity, code) else TessModels.delete(activity, code)
        onChanged()
        refresh()
    }

    private fun download(code: String) {
        val busySet = if (paddle) PaddleModels.busy else TessModels.busy
        busySet.add(code)
        refresh()
        activity.lifecycleScope.launch {
            try {
                if (paddle) PaddleModels.download(activity, code) else TessModels.download(activity, code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Snackbar.make(
                    dialog.window?.decorView ?: recycler,
                    activity.getString(R.string.language_download_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                busySet.remove(code)
            }
            onChanged()
            refresh()
        }
    }
}
