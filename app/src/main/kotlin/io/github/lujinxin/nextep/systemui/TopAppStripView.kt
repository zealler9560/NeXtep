package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Region
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.config.WorkspaceConfigClient
import io.github.lujinxin.nextep.config.WorkspaceTopConfig
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkbenchScene
import io.github.lujinxin.nextep.workspace.WorkspaceControlMetrics
import java.lang.reflect.Proxy
import java.util.Date

class TopAppStripView(
    context: Context,
    private val onAppClicked: (Intent) -> Unit,
    private val onFrostStrengthChanged: (Int) -> Unit,
    private val onAppDragStarting: (View, Intent) -> Boolean,
    private val onAppDragTouch: (View, android.view.MotionEvent) -> Boolean,
    private val onSceneTapped: (WorkbenchScene) -> Unit = {},
    private val onSceneLongPressed: (WorkbenchScene) -> Unit = {},
    private val onAddScene: () -> Unit = {},
) : FrameLayout(context) {
    private var landscape = false
    private var controlOnLeft = false
    private var controlAtBottom = false

    /** The status/gesture reserve and pass-through band sit on the display's outer edge. */
    fun setLandscape(value: Boolean, controlOnLeft: Boolean, controlAtBottom: Boolean = false, statusBand: Int = 0) {
        landscape = value
        this.controlOnLeft = controlOnLeft
        if (this.controlAtBottom != controlAtBottom || this.statusBand != statusBand) {
            this.controlAtBottom = controlAtBottom
            this.statusBand = statusBand
            listOf(controls, dismissHint, mediaHost).forEach { view ->
                (view.layoutParams as LayoutParams).apply {
                    topMargin = if (controlAtBottom) 0 else statusReserve()
                    bottomMargin = if (controlAtBottom) dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP) else 0
                }
                view.requestLayout()
            }
        }
    }

    private var statusBand = 0

    /** The measured status band the window covers above the strip, else the fixed reserve inside it. */
    private fun statusReserve(): Int = if (statusBand > 0) statusBand else dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)

    private val repository = TopAppRepository(context)
    private val mediaHost = FrameLayout(context).apply { visibility = GONE }
    private var wideSlot: View? = null
    private lateinit var controls: LinearLayout

    internal fun setWideSlot(view: View?) {
        if (wideSlot === view) return
        NeXtepLog.info("wide_layout", "Wide slot host ${if (view == null) "cleared" else "set"} strip=${width}x$height")
        wideSlot?.let { mediaHost.removeView(it) }
        wideSlot = view
        controls.visibility = if (view == null) VISIBLE else GONE
        mediaHost.visibility = if (view == null) GONE else VISIBLE
        view?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            mediaHost.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        requestLayout()
    }

    private val titleView = TextView(context).apply {
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val heading = TextView(context).apply {
        text = AppLanguage.string(context, R.string.workbench_title)
        textSize = 20f
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private val appRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val appScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        // The row scrolls; a fade at the trailing edge says so instead of a hard clip.
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(24))
        addView(appRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    /** Saved scenes left to right, then the add button while there is room. */
    private val sceneBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }
    private var scenes = emptyList<WorkbenchScene>()
    private var activeScene: String? = null
    private var appearance = WorkbenchAppearance.DARK
    private var loading = false
    private var refreshPending = false
    private var lastRefreshAt = 0L
    private var refreshGeneration = 0
    private var refreshRetryCount = 0
    private val refreshRetry = Runnable { refresh(force = true) }
    private var touchableInsetsListener: Any? = null
    private var internalAppDragActive = false
    private var deferredApps: List<TopAppRepository.AppEntry>? = null
    private val appTiles = linkedMapOf<String, AppTile>()
    private val dismissHint = TextView(context).apply {
        text = AppLanguage.string(context, R.string.window_drop_background)
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(230, 28, 77, 90))
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(
            LinearLayout(context).apply {
                controls = this
                orientation = LinearLayout.VERTICAL
                // Title row and app row sit as one block, centred in the space below the status band.
                gravity = Gravity.CENTER_VERTICAL
                // Title, first icon and the scene buttons' visible edge share one EDGE_INSET_DP line;
                // the buttons carry a touch inset, so the end padding gives that much back.
                setPadding(dp(EDGE_INSET_DP), 0, dp(EDGE_INSET_DP - SCENE_BUTTON_INSET_DP),
                    dp(WorkspaceControlMetrics.CONTENT_BOTTOM_PADDING_DP))
                addView(
                    actionRow(),
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(WorkspaceControlMetrics.ACTION_HEIGHT_DP)),
                )
                addView(
                    appScroll,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        dp(WorkspaceControlMetrics.APP_ICON_SIZE_DP + WorkspaceControlMetrics.APP_BOTTOM_PADDING_DP))
                        .apply {
                            topMargin = dp(WorkspaceControlMetrics.APP_ROW_GAP_DP)
                            marginEnd = dp(SCENE_BUTTON_INSET_DP)
                        },
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
            },
        )
        // Video keeps the status/camera strip free like the controls; its player chrome
        // (back button, title) must stay tappable under the system bar.
        addView(mediaHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
        })
        addView(dismissHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
        })
        installTouchableStatusBarPassThrough()
    }

    private val configChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Read configuration through the explicit module receiver; do not trust broadcast extras.
            if (intent.action == io.github.lujinxin.nextep.config.WorkspaceConfigContract.ACTION_CHANGED ||
                intent.action == android.content.Intent.ACTION_LOCALE_CHANGED) {
                refreshRetryCount = 0
                refresh(force = true)
            } else updateTitle()
        }
    }
    private var topConfig = WorkspaceTopConfig()
    private var workspaceVisible = false
    private val titleTick = object : Runnable {
        override fun run() {
            renderTitle()
            if (isAttachedToWindow && workspaceVisible) {
                postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }

    private fun updateTitle() {
        heading.text = AppLanguage.string(context, R.string.workbench_title)
        renderScenes()
        dismissHint.text = AppLanguage.string(context, R.string.window_drop_background)
        removeCallbacks(titleTick)
        titleTick.run()
    }

    private fun renderTitle() {
        val locale = AppLanguage.locale(context)
        val date = java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMEd"), locale).format(Date())
        if (!android.text.TextUtils.equals(titleView.text, date)) titleView.text = date
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(configChangedReceiver,
            android.content.IntentFilter(io.github.lujinxin.nextep.config.WorkspaceConfigContract.ACTION_CHANGED).apply {
                addAction(android.content.Intent.ACTION_LOCALE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            Context.RECEIVER_EXPORTED)
        updateTitle()
        if (workspaceVisible && appRow.childCount == 0) refresh(force = true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(titleTick)
        removeCallbacks(refreshRetry)
        context.unregisterReceiver(configChangedReceiver)
        super.onDetachedFromWindow()
    }

    fun setWorkspaceVisible(visible: Boolean) {
        if (visible && !workspaceVisible) refreshRetryCount = 0
        workspaceVisible = visible
        if (!visible) removeCallbacks(refreshRetry)
        else if (appRow.childCount == 0) refresh(force = true)
        updateTitle()
    }

    fun setDismissTargetHighlighted(highlighted: Boolean) {
        dismissHint.visibility = if (highlighted) VISIBLE else GONE
    }

    fun finishInternalAppDrag() {
        internalAppDragActive = false
        deferredApps?.let { entries -> deferredApps = null; renderApps(entries) }
    }

    fun refresh(force: Boolean = false) {
        removeCallbacks(refreshRetry)
        val now = System.currentTimeMillis()
        if (loading) {
            refreshPending = refreshPending || force
            return
        }
        if (!force && now - lastRefreshAt < REFRESH_INTERVAL_MS) return
        loading = true
        val generation = ++refreshGeneration
        Thread({
            val config = WorkspaceConfigClient.queryOrNull(context)
            if (config != null) AppLanguage.updateHostLanguage(config.languageTag)
            val result = config?.let {
                runCatching {
                    repository.load(config.appComponents).map { it.copy(icon = rasterized(it.icon)) }
                }
            }
            post {
                if (generation != refreshGeneration) return@post
                loading = false
                if (config != null) {
                    onFrostStrengthChanged(config.frostStrength)
                    topConfig = config
                    updateTitle()
                    result?.onSuccess { entries ->
                        refreshRetryCount = 0
                        lastRefreshAt = System.currentTimeMillis()
                        renderApps(entries)
                    }?.onFailure { error ->
                        NeXtepLog.error("top_apps", "Unable to load App strip", error)
                        // A temporary catalogue failure must not erase the
                        // last successfully rendered user selection.
                        scheduleRefreshRetry()
                    }
                } else {
                    // A failed IPC query says nothing about the user's preferences.
                    // Retain both the title mode and the already rendered app order.
                    NeXtepLog.warn("top_apps", "Config unavailable; retaining previous top content and App order")
                    scheduleRefreshRetry()
                }
                if (refreshPending) {
                    refreshPending = false
                    refresh(force = true)
                }
            }
        }, "NeXtep-TopApps").start()
    }

    private fun scheduleRefreshRetry() {
        if (!isAttachedToWindow || !workspaceVisible) return
        removeCallbacks(refreshRetry)
        // A cold or temporarily frozen module can outlast the initial retries.
        // Keep the last successful selection and retry while the panel is visible.
        val delay = minOf(1_000L * (1L shl refreshRetryCount), REFRESH_INTERVAL_MS)
        refreshRetryCount = (refreshRetryCount + 1).coerceAtMost(4)
        postDelayed(refreshRetry, delay)
    }

    internal fun setAppearance(value: WorkbenchAppearance) {
        appearance = value
        background = value.background()
        titleView.setTextColor(value.text)
        heading.setTextColor(value.text)
        renderScenes()
        invalidate()
    }

    /** The saved scenes and the one the workspace currently shows (highlighted). */
    fun setScenes(value: List<WorkbenchScene>, active: String?) {
        if (scenes == value && activeScene == active) return
        scenes = value
        activeScene = active
        renderScenes()
    }

    private fun renderScenes() {
        sceneBar.removeAllViews()
        val shown = scenes.take(WorkbenchScene.MAX_SCENES)
        // 48dp touch targets; the visible 36dp card sits inside, so neighbours show a 12dp gap.
        val size = dp(SCENE_BUTTON_SIZE_DP + 2 * SCENE_BUTTON_INSET_DP)
        fun add(view: View) = sceneBar.addView(view, LinearLayout.LayoutParams(size, size))
        shown.forEach { scene -> add(sceneButton(scene, scene.name == activeScene)) }
        if (shown.size < WorkbenchScene.MAX_SCENES) {
            add(iconButton(WorkbenchIcon(WorkbenchIcon.Kind.SCENE_ADD, appearance.accent), selected = false).apply {
                contentDescription = AppLanguage.string(context, R.string.scene_add_action)
                setOnClickListener { onAddScene() }
            })
        }
    }

    private fun sceneButton(scene: WorkbenchScene, selected: Boolean): View {
        val kind = when (scene.builtin) {
            WorkbenchScene.BUILTIN_COMPARE -> WorkbenchIcon.Kind.COMPARE
            WorkbenchScene.BUILTIN_PAYMENT -> WorkbenchIcon.Kind.PAYMENT
            else -> WorkbenchIcon.preset(scene.icon)
        }
        val view = if (kind != null) iconButton(WorkbenchIcon(kind, appearance.accent), selected) else TextView(context).apply {
            // A text scene shows (the start of) its name where an icon would be.
            text = WorkbenchScene.textLabel(scene.name)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setTextColor(appearance.accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            // Before the padding: an inset background replaces the view's padding with its insets.
            background = buttonBackground(selected)
            setPadding(dp(3 + SCENE_BUTTON_INSET_DP), 0, dp(3 + SCENE_BUTTON_INSET_DP), 0)
        }
        return view.apply {
            contentDescription = sceneLabel(scene)
            isFocusable = true
            setOnClickListener { onSceneTapped(scene) }
            setOnLongClickListener { onSceneLongPressed(scene); true }
        }
    }

    internal fun sceneLabel(scene: WorkbenchScene): String = when (scene.builtin) {
        WorkbenchScene.BUILTIN_COMPARE -> AppLanguage.string(context, R.string.workbench_compare)
        WorkbenchScene.BUILTIN_PAYMENT -> AppLanguage.string(context, R.string.workbench_payment)
        else -> scene.name
    }

    private fun iconButton(icon: WorkbenchIcon, selected: Boolean) = android.widget.ImageButton(context).apply {
        setImageDrawable(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isFocusable = true
        // Before the padding: an inset background replaces the view's padding with its insets.
        background = buttonBackground(selected)
        dp(9 + SCENE_BUTTON_INSET_DP).let { setPadding(it, it, it, it) }
    }

    /** Buttons share the empty-slot card tone; the current scene carries an accent ring.
     * The card is inset from the view, which stays the full touch target. */
    private fun buttonBackground(selected: Boolean) = android.graphics.drawable.InsetDrawable(
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(appearance.accent and 0x33ffffff),
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (selected) (appearance.accent and 0x00ffffff) or 0x26000000 else appearance.slotCard)
                setStroke(dp(if (selected) 2 else 1), if (selected) appearance.accent else appearance.outline)
            }, null),
        dp(SCENE_BUTTON_INSET_DP))

    private fun actionRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(heading, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(27)))
                addView(titleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(10) },
        )
        // Up to six 48dp scene buttons; the title column narrows to make room.
        addView(sceneBar, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private fun renderApps(entries: List<TopAppRepository.AppEntry>) {
        // Keep the touch-owning tile attached until its drag finishes.
        if (internalAppDragActive) { deferredApps = entries; return }
        val scrollX = appScroll.scrollX
        var structureChanged = false
        val visible = entries
        val keys = visible.mapTo(hashSetOf()) { it.key }
        val obsolete = appTiles.entries.iterator()
        while (obsolete.hasNext()) {
            val tile = obsolete.next()
            if (tile.key !in keys) {
                appRow.removeView(tile.value)
                obsolete.remove()
                structureChanged = true
            }
        }
        if (visible.isEmpty()) {
            appRow.removeAllViews()
        } else {
            if (appTiles.isEmpty()) appRow.removeAllViews()
            visible.forEachIndexed { index, entry ->
                val tile = appTiles.getOrPut(entry.key) { AppTile() }
                tile.bind(entry)
                if (appRow.getChildAt(index) !== tile) {
                    appRow.removeView(tile)
                    appRow.addView(tile, index)
                    structureChanged = true
                }
            }
        }
        if (structureChanged) appScroll.post { appScroll.scrollTo(scrollX, 0) }
        NeXtepLog.info("top_apps", "Rendered ${entries.size} launcher activities")
    }

    private inner class AppTile : LinearLayout(context) {
        private lateinit var entry: TopAppRepository.AppEntry
        private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

        init {
            orientation = LinearLayout.VERTICAL
            // The icon sits on the tile's leading edge so the first glyph shares the title's line.
            gravity = Gravity.BOTTOM or Gravity.START
            isClickable = true
            isLongClickable = true
            setPadding(0, 0, 0, dp(WorkspaceControlMetrics.APP_BOTTOM_PADDING_DP))
            addView(
                icon,
                LinearLayout.LayoutParams(dp(WorkspaceControlMetrics.APP_ICON_SIZE_DP), dp(WorkspaceControlMetrics.APP_ICON_SIZE_DP)),
            )
            layoutParams = LinearLayout.LayoutParams(dp(APP_TILE_WIDTH_DP * rowScale), LayoutParams.MATCH_PARENT)
            setOnClickListener { onAppClicked(Intent(entry.launchIntent)) }
            setOnLongClickListener { tile ->
                val started = onAppDragStarting(tile, Intent(entry.launchIntent))
                internalAppDragActive = started
                NeXtepLog.info(
                    "top_apps",
                    "Internal drag started=$started component=${entry.launchIntent.component}",
                )
                started
            }
            setOnTouchListener { tile, event -> onAppDragTouch(tile, event) }
        }

        fun bind(value: TopAppRepository.AppEntry) {
            entry = value
            if (!android.text.TextUtils.equals(contentDescription, value.label)) contentDescription = value.label
            if (icon.drawable !== value.icon) icon.setImageDrawable(value.icon)
        }
    }

    private fun installTouchableStatusBarPassThrough() {
        runCatching {
            val listenerClass = Class.forName(
                "android.view.ViewTreeObserver\$OnComputeInternalInsetsListener",
            )
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, arguments ->
                if (method.name == "onComputeInternalInsets") {
                    val info = arguments?.firstOrNull() ?: return@newProxyInstance null
                    info.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                        .invoke(info, TOUCHABLE_INSETS_REGION)
                    val region = info.javaClass.getField("touchableRegion").get(info) as Region
                    if (landscape) {
                        // Exclude the physical-top status band in window-local axes.
                        val status = if (wideSlot != null) 0 else dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP).coerceIn(0, height)
                        if (controlOnLeft) region.set(status, 0, height, width)
                        else region.set(0, 0, height - status, width)
                    } else {
                        val status = if (wideSlot != null) 0 else if (controlAtBottom)
                            dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP) else statusReserve()
                        if (controlAtBottom) region.set(0, 0, width, height - status)
                        else region.set(0, status, width, height)
                    }
                }
                null
            }
            viewTreeObserver.javaClass
                .getMethod("addOnComputeInternalInsetsListener", listenerClass)
                .invoke(viewTreeObserver, listener)
            touchableInsetsListener = listener
        }.onFailure { error ->
            NeXtepLog.warn(
                "top_apps",
                "Unable to pass the status band through to SystemUI",
                error,
            )
        }
    }

    private fun messageView(message: String) = TextView(context).apply {
        text = message
        setTextColor(Color.LTGRAY)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * A larger display size leaves fewer dp across the screen (the standard size here: 347 dp), and
     * fixed 60 dp tiles then left wide gaps. Narrower screens narrow the tiles with the screen width,
     * so as many icons fit across as on the reference; the glyph keeps its 38 dp like the buttons
     * beside it (shrinking it too made the icons smaller than the buttons).
     */
    private val rowScale: Float
        get() = with(resources.displayMetrics) {
            (minOf(widthPixels, heightPixels) / density / REFERENCE_WIDTH_DP).coerceIn(0.75f, 1f)
        }

    /**
     * Each entry re-records the strip (its window is released on exit), and an adaptive or
     * icon-pack drawable rendered itself in software for ~6 ms per icon on the main thread.
     * Draw it once here, off the main thread. The transparent margin around the glyph differs
     * (a quarter of the canvas on ColorOS 17, almost none on ColorOS 16 or in icon packs), so
     * the visible glyph, not the canvas, is fitted to [APP_GLYPH_SIZE_DP] inside the icon box.
     */
    private fun rasterized(icon: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable = runCatching {
        val box = dp(WorkspaceControlMetrics.APP_ICON_SIZE_DP).coerceAtLeast(1)
        // The drawable is freshly loaded for this refresh and not attached anywhere yet.
        val bitmap = android.graphics.Bitmap.createBitmap(box, box, android.graphics.Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, box, box)
        icon.draw(android.graphics.Canvas(bitmap))
        visibleGlyphBounds(bitmap)?.let { glyph ->
            // Draw again, scaled about the glyph's centre, rather than resample the first pass.
            val scale = dp(APP_GLYPH_SIZE_DP).toFloat() / maxOf(glyph.width(), glyph.height())
            val left = box / 2f - glyph.exactCenterX() * scale
            val top = box / 2f - glyph.exactCenterY() * scale
            bitmap.eraseColor(Color.TRANSPARENT)
            icon.setBounds(left.toInt(), top.toInt(), (left + box * scale).toInt(), (top + box * scale).toInt())
            icon.draw(android.graphics.Canvas(bitmap))
        }
        android.graphics.drawable.BitmapDrawable(resources,
            bitmap.copy(android.graphics.Bitmap.Config.HARDWARE, false) ?: bitmap)
    }.getOrDefault(icon)

    private companion object {
        const val APP_TILE_WIDTH_DP = 66
        const val APP_GLYPH_SIZE_DP = 43
        // The Find X9s Pro the row was tuned on: 1216 px, about 410 dp across.
        const val REFERENCE_WIDTH_DP = 410f
        const val SCENE_BUTTON_SIZE_DP = 40
        const val SCENE_BUTTON_INSET_DP = 6
        const val EDGE_INSET_DP = 16
        const val REFRESH_INTERVAL_MS = 15_000L
        const val TOUCHABLE_INSETS_REGION = 3
    }
}

/** The box around the pixels an icon visibly paints (faint shadow ignored); null when it paints nothing. */
internal fun visibleGlyphBounds(bitmap: android.graphics.Bitmap): android.graphics.Rect? {
    val width = bitmap.width
    val pixels = IntArray(width * bitmap.height).also { bitmap.getPixels(it, 0, width, 0, 0, width, bitmap.height) }
    var left = width
    var top = bitmap.height
    var right = -1
    var bottom = -1
    pixels.forEachIndexed { index, pixel ->
        if (pixel ushr 24 < 0x20) return@forEachIndexed
        val x = index % width
        val y = index / width
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
    }
    return if (right < 0) null else android.graphics.Rect(left, top, right + 1, bottom + 1)
}
