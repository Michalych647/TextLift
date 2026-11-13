package io.github.michalych647.textlift

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.nl.translate.TranslateLanguage
import io.github.michalych647.textlift.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

open class MainActivity : AppCompatActivity(), SelectableOcrView.Listener {

    companion object {
        const val EXTRA_CAPTURED_SCREEN = "io.github.michalych647.textlift.CAPTURED_SCREEN"
        const val ACTION_CAMERA_PHOTO = "io.github.michalych647.textlift.action.CAMERA_PHOTO"

        private const val STATE_LAUNCHED_FOR_CAMERA = "launched_for_camera"
        private const val STATE_CAMERA_OPEN = "camera_open"

        private const val SPLASH_FADE_MS = 150L

        private const val TOOLBAR_DELAY = 0.35f
        private const val TOOLBAR_DURATION = 0.5f
        private const val TOOLBAR_SLIDE = 0.4f

        private const val TOOLBAR_SLIDE_FALLBACK_PX = 96

        private const val COUNTER_HOLD_MS = 1400L

        private const val PAGE_SLIDE = 0.28f
        private const val PAGE_OUT_MS = 150L
        private const val PAGE_IN_MS = 280L

        private const val BARS_AUTO_HIDE_MS = 3000L
        private const val BARS_DIMMED_ALPHA = 0.35f
    }

    private lateinit var binding: ActivityMainBinding
    private var currentBitmap: Bitmap? = null
    private var currentResult: OcrEngine.Result? = null
    private var launchedWithImage = false
    private var translateJob: Job? = null
    private var ocrJob: Job? = null

    private var contentReady = false

    private val translationMemory = TranslationMemory()

    /** Recognized text per page, so going back to a page does not run OCR again. */
    private val pageCache = HashMap<Uri, OcrEngine.Result>()
    private var pageCacheSignature = ""

    private fun ocrSignature(): String =
        OcrSettings.selectedId(this) + "|" + TessModels.installed(this).sorted() + "|" +
            PaddleModels.installed(this).sorted()
    private var searchActive = false
    private var imeWasVisible = false
    private var keepSearchOpen = false

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) saveImage()
            else Snackbar.make(binding.root, R.string.save_permission_denied, Snackbar.LENGTH_SHORT).show()
        }

    private val pages = ArrayList<Uri>()
    private var pageIndex = 0
    private var appendPicked = false
    private var speaker: Speaker? = null
    private val counterHide = Runnable { hideCounter() }

    private var barsShown = true
    private var barsDimmed = false
    private var topInsetPx = 0
    private var textSheetOpen = false
    private val barsAutoHide = Runnable {
        if (!searchActive && !textSheetOpen && !binding.ocrView.hasSelection()) setBars(false)
    }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) onImagesPicked(uris)
        }

    private val cameraFile by lazy { CameraActivity.photoFile(this) }

    /** Opened by the camera shortcut's photo: the home screen is never part of this session. */
    private var launchedForCamera = false
    private var cameraOpen = false
    private var viewingCameraPhoto = false

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            cameraOpen = false
            when {
                result.resultCode == RESULT_OK -> openImage(Uri.fromFile(cameraFile), fromCamera = true)
                launchedForCamera -> finish()
                else -> showHome()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val capturing = intent?.getBooleanExtra(EXTRA_CAPTURED_SCREEN, false) == true
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        splash.setKeepOnScreenCondition { !contentReady }
        splash.setOnExitAnimationListener { provider ->
            if (capturing) {
                provider.remove()
            } else {
                provider.view.animate()
                    .alpha(0f)
                    .setDuration(SPLASH_FADE_MS)
                    .withEndAction { provider.remove() }
            }
        }
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val density = resources.displayMetrics.density
            topInsetPx = bars.top
            placeCounter()
            val base = (24 * density).toInt()
            binding.homeGroup.updatePadding(top = base + bars.top, bottom = base + bars.bottom)
            val imeNow = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (searchActive && imeWasVisible && !imeNow) {
                if (keepSearchOpen) keepSearchOpen = false else closeSearch()
            }
            if (imeNow) keepSearchOpen = false
            imeWasVisible = imeNow
            insets
        }

        binding.ocrView.listener = this
        forwardTouchesWhenDimmed(binding.topBar)
        forwardTouchesWhenDimmed(binding.bottomBar)

        binding.topBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            binding.ocrView.topInset = v.bottom.toFloat()
            binding.ocrProgress.translationY = v.bottom.toFloat()
            placeCounter()
        }

        binding.btnPick.setOnClickListener {
            appendPicked = false
            pickImages.launch("image/*")
        }
        binding.btnAddImage.setOnClickListener {
            appendPicked = true
            pickImages.launch("image/*")
        }
        binding.btnBoxes.setOnClickListener { binding.ocrView.showBoxes = binding.btnBoxes.isChecked }
        binding.btnSpeak.setOnClickListener { toggleSpeak() }
        binding.btnCamera.isVisible =
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
        binding.btnCamera.setOnClickListener { launchCamera() }
        OcrEngine.warmUp()

        binding.btnAccessibility.setOnClickListener {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        binding.btnSetAssistant.setOnClickListener {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }.onFailure {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            }
        }

        binding.rowTargetLanguage.setOnClickListener { chooseTargetLanguage() }
        binding.rowLanguagePacks.setOnClickListener {
            LanguagePacksSheet(this) { refreshTranslationRows() }.show()
        }
        binding.rowEngine.setOnClickListener {
            TranslationEngineSheet(this) { refreshTranslationRows() }.show()
        }
        binding.rowOcrEngine.setOnClickListener {
            OcrEngineSheet(this) { refreshTranslationRows() }.show()
        }
        binding.rowOcrModels.setOnClickListener {
            OcrModelsSheet(this) { refreshTranslationRows() }.show()
        }
        binding.rowSmartModels.setOnClickListener {
            SmartModelsSheet(this) { refreshTranslationRows() }.show()
        }

        binding.btnBack.setOnClickListener { onBackFromViewer() }
        binding.btnSelectAll.setOnClickListener { binding.ocrView.selectAll() }
        binding.btnSearch.setOnClickListener { openSearch() }
        binding.btnSave.setOnClickListener { requestSave() }
        binding.btnSearchClose.setOnClickListener { closeSearch() }
        binding.searchInput.doAfterTextChanged { binding.ocrView.searchQuery = it?.toString() }
        binding.searchInput.setOnEditorActionListener { _, _, _ ->
            keepSearchOpen = imeWasVisible
            binding.searchInput.clearFocus()
            WindowCompat.getInsetsController(window, binding.searchInput).hide(WindowInsetsCompat.Type.ime())
            true
        }

        binding.btnTextMode.setOnClickListener { showTextSheet() }
        binding.btnTranslateAll.setOnClickListener {
            val lines = binding.ocrView.selectedLines()
            when {
                lines != null -> translateScreen(lines)
                binding.ocrView.translations != null -> resetTranslation()
                else -> translateScreen(null)
            }
        }
        binding.btnTranslateAll.setOnLongClickListener {
            chooseTargetLanguage()
            true
        }

        binding.toolbarCopy.setOnClickListener {
            binding.ocrView.selectedText?.let {
                copyToClipboard(it)
                binding.ocrView.clearSelection()
            }
        }
        binding.toolbarSelectAll.setOnClickListener { binding.ocrView.selectAll() }
        binding.toolbarShare.setOnClickListener { binding.ocrView.selectedText?.let { shareText(it) } }
        binding.toolbarSearch.setOnClickListener { binding.ocrView.selectedText?.let { searchWeb(it) } }

        onBackPressedDispatcher.addCallback(this) {
            when {
                searchActive -> closeSearch()
                binding.ocrView.hasSelection() -> binding.ocrView.clearSelection()
                binding.viewerGroup.isVisible -> onBackFromViewer()
                else -> finish()
            }
        }

        if (savedInstanceState != null) {
            if (intent.action == ACTION_CAMERA_PHOTO) intent.action = Intent.ACTION_MAIN
            cameraOpen = savedInstanceState.getBoolean(STATE_CAMERA_OPEN)
            launchedForCamera = cameraOpen && savedInstanceState.getBoolean(STATE_LAUNCHED_FOR_CAMERA)
        }
        handleIntent(intent)

        binding.root.post {
            contentReady = true
            if (savedInstanceState == null) askOcrModels()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED_FOR_CAMERA, launchedForCamera)
        outState.putBoolean(STATE_CAMERA_OPEN, cameraOpen)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshTranslationRows()
    }

    override fun onStop() {
        super.onStop()
        if (ocrJob?.isActive == true || translateJob?.isActive == true) return
        OcrEngine.release()
        Translator.release()
    }

    private fun refreshSetup() {
        binding.btnAccessibility.isVisible = ScreenCapture.supported && !ScreenCapture.isEnabled(this)
        binding.btnSetAssistant.isVisible = !isAssistantApp()
        val needsSetup = binding.btnAccessibility.isVisible || binding.btnSetAssistant.isVisible
        binding.setupHeader.isVisible = needsSetup
        binding.setupCard.isVisible = needsSetup
        applySegmentShapes(binding.btnAccessibility, binding.btnSetAssistant)
    }

    private fun applySegmentShapes(vararg rows: MaterialCardView) {
        val shown = rows.filter { it.isVisible }
        shown.forEachIndexed { index, row ->
            val shape = when {
                shown.size == 1 -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Single
                index == 0 -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_First
                index == shown.lastIndex -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Last
                else -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Middle
            }
            row.shapeAppearanceModel = ShapeAppearanceModel.builder(this, shape, 0).build()
        }
    }

    private fun refreshTranslationRows() {
        binding.textTargetLanguage.text = Translator.displayName(Translator.defaultTarget(this))
        val engine = EngineSettings.selected(this)
        binding.textEngine.text = getString(engine.title).let {
            if (engine.needsKey) getString(R.string.engine_row_summary_cloud, it) else it
        }

        binding.rowLanguagePacks.isVisible = !engine.isCloud
        binding.textOcrEngine.text = getString(OcrSettings.selected(this).title)
        binding.rowOcrModels.isVisible = OcrSettings.selectedId(this) != OcrSettings.MLKIT
        binding.textOcrModels.text = if (OcrSettings.selectedId(this) == OcrSettings.PADDLE) {
            getString(R.string.ocr_models_summary_paddle, PaddleModels.installed(this).size)
        } else {
            getString(R.string.ocr_models_summary, TessModels.installed(this).size)
        }
        lifecycleScope.launch {
            binding.textSmartModels.text =
                getString(R.string.smart_models_summary, SmartModels.installed().size)
        }
        lifecycleScope.launch {
            val count = runCatching { Translator.downloadedLanguages().size }.getOrNull() ?: return@launch
            binding.textLanguagePacks.text = getString(R.string.language_packs_summary, count)
        }
    }

    private fun isAssistantApp(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val roles = getSystemService(android.app.role.RoleManager::class.java) ?: return false
        return roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
    }

    override fun onDestroy() {
        speaker?.shutdown()
        speaker = null
        binding.pageCounter.removeCallbacks(counterHide)
        binding.pageCounter.removeCallbacks(barsAutoHide)
        clearViewerContent()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }

        val photoTaken = intent.action == ACTION_CAMERA_PHOTO
        val captureRequested = intent.getBooleanExtra(EXTRA_CAPTURED_SCREEN, false)
        intent.removeExtra(EXTRA_CAPTURED_SCREEN)
        intent.action = Intent.ACTION_MAIN
        intent.data = null

        when {
            uri != null -> {
                launchedWithImage = true
                openImage(uri)
            }
            captureRequested -> {
                val captured = CaptureHolder.take()
                if (captured != null) {
                    launchedWithImage = true
                    openBitmap(captured)
                } else if (this is CaptureViewerActivity) finish() else showHome()
            }
            else -> {
                CaptureHolder.take()
                when {
                    photoTaken -> {
                        launchedForCamera = true
                        openImage(Uri.fromFile(cameraFile), fromCamera = true)
                    }
                    cameraOpen -> showCameraPending()
                    else -> showHome()
                }
            }
        }
    }

    private fun launchCamera() {
        cameraOpen = true
        showCameraPending()
        takePhoto.launch(Intent(this, CameraActivity::class.java))
    }

    private fun showCameraPending() {
        clearViewerContent()
        viewingCameraPhoto = false
        binding.homeGroup.isVisible = false
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
    }

    private fun onImagesPicked(uris: List<Uri>) {
        val fresh = uris.distinct()
        if (appendPicked && pages.isNotEmpty()) {
            val known = pages.toHashSet()
            pages.addAll(fresh.filter { it !in known })
            flashCounter()
        } else {
            pages.clear(); pageCache.clear()
            pages.addAll(fresh)
            pageIndex = 0
            launchedWithImage = false
            openPage(0)
        }
    }

    private fun openPage(index: Int) {
        pageIndex = index
        openImage(pages[index], keepPages = true)
    }

    /** Slides the current page away, loads the next one underneath, and slides it in from the other side. */
    private fun openPageAnimated(index: Int, delta: Int) {
        pageIndex = index
        viewingCameraPhoto = false
        binding.ocrView.capturePreview = false
        val view = binding.ocrView
        val distance = view.width * PAGE_SLIDE
        val uri = pages[index]
        speaker?.stop()
        closeSearch()
        startOcr {
            try {
                val bitmap = coroutineScope {
                    val loading = async { ImageLoader.load(this@MainActivity, uri) }
                    view.animate().cancel()
                    view.animate().translationX(-delta * distance).alpha(0f)
                        .setDuration(PAGE_OUT_MS).setInterpolator(SelectableOcrView.EMPHASIZED_ACCELERATE)
                    delay(PAGE_OUT_MS)
                    loading.await()
                }
                showImage(bitmap)
                view.translationX = delta * distance
                view.alpha = 0f
                view.animate().translationX(0f).alpha(1f)
                    .setDuration(PAGE_IN_MS).setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
                recognize(bitmap, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Failed to open $uri", e)
                resetPageTransform()
                showHome()
                Snackbar.make(
                    binding.root,
                    getString(R.string.load_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun resetPageTransform() {
        binding.ocrView.animate().cancel()
        binding.ocrView.translationX = 0f
        binding.ocrView.alpha = 1f
    }

    override fun onSwipePage(delta: Int) {
        val next = pageIndex + delta
        if (pages.size < 2 || next !in pages.indices) return
        openPageAnimated(next, delta)
        flashCounter()
    }

    private fun flashCounter() {
        if (pages.size < 2) return
        val counter = binding.pageCounter
        counter.removeCallbacks(counterHide)
        counter.text = getString(R.string.page_counter, pageIndex + 1, pages.size)
        counter.animate().cancel()
        if (!counter.isVisible || counter.alpha == 0f) {
            counter.translationY = 8f * resources.displayMetrics.density
        }
        counter.isVisible = true
        counter.animate().alpha(1f).translationY(0f).setDuration(160L)
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
        counter.postDelayed(counterHide, COUNTER_HOLD_MS)
    }

    private fun hideCounter() {
        val counter = binding.pageCounter
        counter.animate().cancel()
        counter.animate().alpha(0f).translationY(8f * resources.displayMetrics.density)
            .setDuration(260L).setInterpolator(SelectableOcrView.EMPHASIZED_ACCELERATE)
            .withEndAction { counter.isVisible = false }
    }

    private fun hideCounterNow() {
        val counter = binding.pageCounter
        counter.removeCallbacks(counterHide)
        counter.animate().cancel()
        counter.alpha = 0f
        counter.isVisible = false
    }

    private fun toggleSpeak() {
        val voice = speaker ?: Speaker(
            this,
            onStateChanged = { binding.btnSpeak.isChecked = it },
            onProblem = { Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show() },
        ).also { speaker = it }
        if (voice.isSpeaking) {
            voice.stop()
            binding.btnSpeak.isChecked = false
            return
        }
        binding.btnSpeak.isChecked = false
        val text = (binding.ocrView.selectedText ?: binding.ocrView.displayedText).trim()
        if (text.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }
        val translated = binding.ocrView.translations != null
        lifecycleScope.launch {
            val locale = if (translated) {
                Locale.forLanguageTag(Translator.defaultTarget(this@MainActivity))
            } else {
                Translator.identifyTag(text)?.let { Locale.forLanguageTag(it) }
            }
            voice.speak(text, locale)
        }
    }

    private fun openImage(uri: Uri, fromCamera: Boolean = false, keepPages: Boolean = false) {
        if (!keepPages) {
            pages.clear(); pageCache.clear()
            pageIndex = 0
            if (!fromCamera) pages.add(uri)
            hideCounterNow()
        }
        viewingCameraPhoto = fromCamera
        binding.ocrView.capturePreview = false
        resetPageTransform()

        showViewer(loading = true)
        startOcr {
            try {
                val bitmap = try {
                    ImageLoader.load(this@MainActivity, uri)
                } finally {
                    if (fromCamera) cameraFile.delete()
                }
                processBitmap(bitmap, if (keepPages) uri else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Failed to open $uri", e)
                showHome()
                Snackbar.make(
                    binding.root,
                    getString(R.string.load_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openBitmap(bitmap: Bitmap) {
        pages.clear(); pageCache.clear()
        pageIndex = 0
        hideCounterNow()
        viewingCameraPhoto = false
        binding.ocrView.capturePreview = true
        clearOpenTransition()

        showImage(bitmap)
        binding.ocrView.playCaptureEntry()
        playToolbarEntry()
        startOcr { recognize(bitmap) }
    }

    private fun clearOpenTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun finishWithoutTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            finish()
        } else {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun playToolbarEntry() {
        val bar = binding.topBar
        bar.alpha = 0f
        bar.translationY = -bar.height.coerceAtLeast(TOOLBAR_SLIDE_FALLBACK_PX) * TOOLBAR_SLIDE
        bar.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DELAY).toLong())
            .setDuration((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong())
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)

        binding.bottomBar.alpha = 0f
        binding.bottomBar.animate()
            .alpha(1f)
            .setStartDelay((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DELAY).toLong())
            .setDuration((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong())
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)

        binding.ocrProgress.alpha = 0f
        binding.ocrProgress.animate()
            .alpha(1f)
            .setStartDelay((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DELAY).toLong())
            .setDuration((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong())
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
    }

    private fun startOcr(block: suspend () -> Unit) {
        ocrJob?.cancel()
        ocrJob = lifecycleScope.launch { block() }
    }

    private suspend fun processBitmap(bitmap: Bitmap, key: Uri? = null) {
        showImage(bitmap)
        recognize(bitmap, key)
    }

    private fun showImage(bitmap: Bitmap) {
        translationMemory.clear()
        resetTranslation()
        binding.selectionToolbar.isVisible = false
        for (v in arrayOf(binding.topBar, binding.bottomBar, binding.ocrProgress, binding.selectionToolbar)) {
            v.animate().cancel()
            v.alpha = 1f
        }

        binding.topBar.translationY = 0f
        binding.bottomBar.translationY = 0f
        binding.topBar.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        barsShown = true
        barsDimmed = false
        binding.pageCounter.removeCallbacks(barsAutoHide)
        currentResult = null
        currentBitmap = bitmap
        binding.ocrView.setImage(bitmap)
        showViewer(loading = false)
    }

    private suspend fun recognize(bitmap: Bitmap, key: Uri? = null) {
        val signature = ocrSignature()
        if (signature != pageCacheSignature) {
            pageCache.clear()
            pageCacheSignature = signature
        }
        val cached = key?.let { pageCache[it] }
        if (cached != null) {
            currentResult = cached
            binding.ocrView.setContent(bitmap, cached)
            scheduleBarsHide()
            return
        }
        binding.ocrProgress.show()
        try {
            val result = SmartActions.annotate(OcrEngine.recognize(this@MainActivity, bitmap))
            if (key != null) pageCache[key] = result
            currentResult = result
            binding.ocrView.setContent(bitmap, result)
            if (result.isEmpty) {
                Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_LONG).show()
            }
        } finally {
            binding.ocrProgress.hide()
        }
        scheduleBarsHide()
    }

    private fun showHome() {
        pages.clear(); pageCache.clear()
        pageIndex = 0
        hideCounterNow()
        clearViewerContent()
        binding.homeGroup.isVisible = true
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
        launchedWithImage = false
        viewingCameraPhoto = false
    }

    private fun showViewer(loading: Boolean) {
        binding.homeGroup.isVisible = false
        binding.viewerGroup.isVisible = true
        binding.progressGroup.isVisible = loading
        if (loading) {
            binding.selectionToolbar.isVisible = false
            clearViewerContent()
        }
    }

    private fun clearViewerContent() {
        resetPageTransform()
        speaker?.stop()
        closeSearch()
        translationMemory.clear()
        ocrJob?.cancel()
        ocrJob = null
        resetTranslation()
        binding.selectionToolbar.isVisible = false
        binding.ocrProgress.hide()
        binding.ocrView.clear()
        currentBitmap = null
        currentResult = null
    }

    private fun onBackFromViewer() {
        if (viewingCameraPhoto) {
            launchCamera()
            return
        }

        if (launchedWithImage && binding.ocrView.canPlayCaptureExit) {
            val fadeOut = (SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong()
            for (v in arrayOf(binding.topBar, binding.bottomBar, binding.ocrProgress, binding.selectionToolbar)) {
                v.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(fadeOut)
                    .setInterpolator(SelectableOcrView.EMPHASIZED_ACCELERATE)
            }
            ocrJob?.cancel()
            binding.ocrProgress.hide()
            binding.ocrView.playCaptureExit { finishWithoutTransition() }
            return
        }

        clearViewerContent()
        if (launchedWithImage) finish() else showHome()
    }

    override fun onSelectionChanged(text: String?, anchor: RectF?) {
        if (text == null || anchor == null) {
            binding.selectionToolbar.isVisible = false
            dimBars(false)
            return
        }
        dimBars(true)
        if (!barsShown) setBars(true)
        binding.selectionToolbar.isVisible = true
        binding.selectionToolbar.post { positionToolbar(anchor) }
    }

    private fun positionToolbar(anchor: RectF) {
        val toolbar = binding.selectionToolbar
        val container = binding.viewerGroup
        val margin = resources.displayMetrics.density * 12f

        var x = anchor.centerX() - toolbar.width / 2f
        x = max(margin, min(x, container.width - toolbar.width - margin))

        var y = anchor.top - toolbar.height - margin * 1.5f
        if (y < container.height * 0.12f) y = anchor.bottom + margin * 1.5f
        y = max(margin, min(y, container.height - toolbar.height - margin))

        toolbar.translationX = x
        toolbar.translationY = y
    }

    override fun onLinkClick(link: OcrEngine.Link) {
        val intent = when (link.type) {
            OcrEngine.LinkType.URL -> Intent(Intent.ACTION_VIEW, Uri.parse(link.value))
            OcrEngine.LinkType.PHONE -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + link.value))
            OcrEngine.LinkType.ADDRESS -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(link.value))
            )
        }
        runCatching { startActivity(intent) }.onFailure {
            Snackbar.make(binding.root, R.string.smart_open_failed, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onBackgroundTap() {
        setBars(!barsShown)
    }

    /** Gaps in a translucent toolbar pass touches to the image, so text can be selected through it. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun forwardTouchesWhenDimmed(bar: View) {
        bar.setOnTouchListener { v, ev ->
            if (!barsDimmed) return@setOnTouchListener false
            val copy = android.view.MotionEvent.obtain(ev)
            copy.offsetLocation(v.left + v.translationX, v.top + v.translationY)
            val handled = binding.ocrView.dispatchTouchEvent(copy)
            copy.recycle()
            handled
        }
    }

    private fun placeCounter() {
        val density = resources.displayMetrics.density
        val top = if (barsShown && binding.topBar.isVisible && !searchActive) {
            binding.topBar.bottom.coerceAtLeast(topInsetPx)
        } else {
            topInsetPx
        }
        binding.pageCounter.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = top + (8 * density).toInt()
        }
    }

    private fun setBars(show: Boolean) {
        if (show == barsShown || searchActive || !binding.viewerGroup.isVisible) return
        barsShown = show
        binding.pageCounter.removeCallbacks(barsAutoHide)
        val density = resources.displayMetrics.density
        val top = binding.topBar
        val bottom = binding.bottomBar
        val topShift = -(top.height + 48 * density)
        val bottomShift = bottom.height + 160 * density
        val target = if (show) (if (barsDimmed) BARS_DIMMED_ALPHA else 1f) else 0f
        if (show) {
            top.visibility = View.VISIBLE
            bottom.visibility = View.VISIBLE
        }
        top.animate().cancel()
        bottom.animate().cancel()
        top.animate().alpha(target).translationY(if (show) 0f else topShift).setStartDelay(0)
            .setDuration(220L).setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
            .withEndAction { if (!barsShown) top.visibility = View.INVISIBLE }
        bottom.animate().alpha(target).translationY(if (show) 0f else bottomShift).setStartDelay(0)
            .setDuration(220L).setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
            .withEndAction { if (!barsShown) bottom.visibility = View.INVISIBLE }
        placeCounter()
        if (show) scheduleBarsHide()
    }

    private fun scheduleBarsHide() {
        binding.pageCounter.removeCallbacks(barsAutoHide)
        if (barsShown && !textSheetOpen && binding.viewerGroup.isVisible) {
            binding.pageCounter.postDelayed(barsAutoHide, BARS_AUTO_HIDE_MS)
        }
    }

    /** While text is selected the toolbars turn translucent so they do not cover the image. */
    private fun dimBars(dim: Boolean) {
        if (barsDimmed == dim) return
        barsDimmed = dim
        if (!barsShown) return
        val alpha = if (dim) BARS_DIMMED_ALPHA else 1f
        for (bar in arrayOf(binding.topBar, binding.bottomBar)) {
            bar.animate().cancel()
            bar.animate().alpha(alpha).setStartDelay(0).setDuration(160L)
        }
        if (!dim) scheduleBarsHide()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))

        if (Build.VERSION.SDK_INT < 33) {
            Snackbar.make(binding.root, R.string.copied, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun searchWeb(text: String) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, text)
        }
        if (search.resolveActivity(packageManager) != null) {
            startActivity(search)
        } else {
            val browse = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://duckduckgo.com/?q=${Uri.encode(text)}")
            )
            runCatching { startActivity(browse) }
        }
    }

    private fun showTextSheet() {
        val text = currentResult?.fullText
        if (text.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_text, null)
        view.findViewById<TextView>(R.id.sheetText).text = text
        view.findViewById<View>(R.id.sheetCopy).setOnClickListener {
            copyToClipboard(text)
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.sheetShare).setOnClickListener { shareText(text) }
        textSheetOpen = true
        binding.pageCounter.removeCallbacks(barsAutoHide)
        if (!barsShown) setBars(true)
        sheet.setOnDismissListener {
            textSheetOpen = false
            scheduleBarsHide()
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun translateScreen(only: Set<Int>? = null) {
        val result = currentResult ?: return
        if (result.isEmpty) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }
        val target = Translator.defaultTarget(this)
        translateJob?.cancel()
        translateJob = lifecycleScope.launch {
            binding.ocrProgress.show()
            var downloadNote: Snackbar? = null
            try {
                val outcome = Translator.translate(
                    this@MainActivity, result, target, translationMemory, only
                ) {
                    downloadNote = Snackbar.make(
                        binding.root, R.string.downloading_models, Snackbar.LENGTH_INDEFINITE
                    ).also { it.show() }
                }
                if (currentResult !== result) return@launch
                if (outcome.sources.isEmpty()) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.already_in_language, Translator.displayName(target)),
                        Snackbar.LENGTH_LONG
                    ).setAction(R.string.change_language) { chooseTargetLanguage() }.show()
                    return@launch
                }
                val shown = binding.ocrView.translations
                binding.ocrView.translations = if (only != null && shown != null) {
                    List(outcome.lines.size) { outcome.lines[it] ?: shown.getOrNull(it) }
                } else {
                    outcome.lines
                }
                setTranslateActive(true)
                Snackbar.make(
                    binding.root,
                    getString(
                        R.string.translated_from,
                        outcome.sources.joinToString(", ") { Translator.displayName(it) },
                        Translator.displayName(target)
                    ),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.change_language) { chooseTargetLanguage() }.show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Translation failed", e)
                Snackbar.make(
                    binding.root,
                    getString(R.string.translation_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                downloadNote?.dismiss()
                binding.ocrProgress.hide()
            }
        }
    }

    private fun resetTranslation() {
        translateJob?.cancel()
        translateJob = null
        binding.ocrView.translations = null
        setTranslateActive(false)
    }

    private fun setTranslateActive(active: Boolean) {
        binding.btnTranslateAll.isChecked = active
        binding.btnTranslateAll.contentDescription =
            getString(if (active) R.string.show_original else R.string.translate)
    }

    private fun chooseTargetLanguage() {
        val codes = Translator.languages.sortedBy { Translator.displayName(it) }
        val names = codes.map { Translator.displayName(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.translate_to)
            .setSingleChoiceItems(names, codes.indexOf(Translator.defaultTarget(this))) { dialog, which ->
                dialog.dismiss()
                Translator.saveTarget(this, codes[which])
                refreshTranslationRows()
                if (binding.viewerGroup.isVisible) translateScreen()
            }
            .show()
    }

    private fun openSearch() {
        if (currentResult?.isEmpty != false) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }
        searchActive = true
        imeWasVisible = false
        binding.ocrView.clearSelection()
        binding.topBar.isVisible = false
        binding.searchBar.isVisible = true
        binding.searchInput.setText("")
        binding.searchInput.requestFocus()
        WindowCompat.getInsetsController(window, binding.searchInput).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeSearch() {
        if (!searchActive) return
        searchActive = false
        imeWasVisible = false
        keepSearchOpen = false
        binding.ocrView.searchQuery = null
        binding.searchBar.isVisible = false
        binding.topBar.isVisible = true
        binding.searchInput.clearFocus()
        WindowCompat.getInsetsController(window, binding.searchInput).hide(WindowInsetsCompat.Type.ime())
    }

    private fun requestSave() {
        if (currentBitmap == null) return
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        saveImage()
    }

    private fun saveImage() {
        val source = currentBitmap ?: return
        val bitmap = if (binding.btnTranslateAll.isChecked) {
            binding.ocrView.renderTranslated() ?: source
        } else {
            source
        }
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { writeToGallery(bitmap) }
            if (bitmap !== source) bitmap.recycle()
            Snackbar.make(
                binding.root,
                if (saved) R.string.image_saved else R.string.image_save_failed,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun writeToGallery(bitmap: Bitmap): Boolean {
        val name = "TextGrab_${System.currentTimeMillis()}"
        if (Build.VERSION.SDK_INT < 29) {
            @Suppress("DEPRECATION")
            val url = MediaStore.Images.Media.insertImage(contentResolver, bitmap, name, null)
            return url != null
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TextGrab")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val written = resolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: false
            if (!written) throw java.io.IOException("compress failed")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun askOcrModels() {
        if (this is CaptureViewerActivity || TessModels.isSetupDone(this)) return
        val view = layoutInflater.inflate(R.layout.dialog_ocr_setup, null)
        val progress = view.findViewById<View>(R.id.ocrSetupProgress)
        val language = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault())
        view.findViewById<TextView>(R.id.ocrSetupMessage).text =
            getString(R.string.ocr_setup_message, language)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ocr_setup_title)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.ocr_setup_yes, null)
            .setNegativeButton(R.string.ocr_setup_no, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        val yes = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val no = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        no.setOnClickListener {
            TessModels.markSetupDone(this)
            dialog.dismiss()
        }
        yes.setOnClickListener {
            yes.isEnabled = false
            no.isEnabled = false
            progress.isVisible = true
            lifecycleScope.launch {
                try {
                    val available = TessModels.fetchList(this@MainActivity).map { it.code }.toSet()
                    for (code in TessModels.preferredCodes(available)) {
                        if (!TessModels.isInstalled(this@MainActivity, code)) {
                            TessModels.download(this@MainActivity, code)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.language_download_failed, e.localizedMessage ?: ""),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
                TessModels.markSetupDone(this@MainActivity)
                refreshTranslationRows()
                dialog.dismiss()
            }
        }
    }
}
