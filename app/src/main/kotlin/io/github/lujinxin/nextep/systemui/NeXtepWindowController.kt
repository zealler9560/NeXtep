package io.github.lujinxin.nextep.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.display.SlotState
import io.github.lujinxin.nextep.display.SlotTaskCoordinator
import io.github.lujinxin.nextep.display.SlotTaskDrag
import io.github.lujinxin.nextep.display.VirtualDisplaySlot
import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.safety.FeatureGate
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.lujinxin.nextep.workspace.WorkbenchScene
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry

class NeXtepWindowController(
    private val context: Context,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
    private val onSidebarSideRequested: (SidebarSide) -> Boolean,
    private val onSettingsRequested: () -> Unit,
    private val onExitRequested: () -> Unit,
    private val onViewportChanged: () -> Unit,
) {
    private data class WindowRecord(val view: View, val title: String)

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val viewport = WorkspaceViewport(context, onViewportChanged)
    private val records = mutableListOf<WindowRecord>()
    private var initialized = false
    private var slotCoordinator: SlotTaskCoordinator? = null
    private var topAppStrip: TopAppStripView? = null
    private var sidebarView: FrameLayout? = null
    private var contentHighlightView: View? = null
    private var slotWindows: List<VirtualDisplaySlot> = emptyList()
    private var slotBackgroundSwipeEnabled = true
    private var frostStrength = 50
    private val swapAnimator = WorkspaceSwapAnimator(context, backdrop = { panelBackdrop(0, 0) }) { appearance }
    private var contentTarget: SlotTaskCoordinator.ContentExchange? = null
    private var contentOriginSide = SidebarSide.RIGHT
    private var contentLayoutChanging = false
    private var appliedGeometry: WorkspaceGeometry? = null
    private val contentDrag = io.github.lujinxin.nextep.workspace.WorkspaceContentDragSession(
        ::expandContentTarget, ::restoreContentTarget, { contentTarget = null },
    )
    private val internalDrag = WorkspaceInternalDragController(
        context, ::geometry, { slotWindows.map { it.view } },
        ::highlightInternalDragTarget, ::dropInternalPayload,
        { topAppStrip?.finishInternalAppDrag() }, ::onSlotHeld,
    )
    private val backdropGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val backdropWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-Backdrop")
    }
    private val backdropHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var wallpaperBitmap: android.graphics.Bitmap? = null
    private var sidebarSide = SystemServerWorkspaceBridge.sidebarSide(context)
    private var suspended = false
    private var workspaceVisible = false
    private var wideSlotIndex: Int? = null
    private var wideTaskId: Int? = null
    /** The Activity shown in the wide slot while its task's landscape request was fresh. */
    private var wideComponent: android.content.ComponentName? = null
    private val wideAspectSampler = WideAspectSampler(backdropHandler,
        { wideSlotIndex?.let { slotWindows.getOrNull(it)?.view?.textureView } }) { aspect ->
        val index = wideSlotIndex ?: return@WideAspectSampler
        viewport.setWideSlot(index, aspect)
        val geometry = geometry()
        applyWindowGeometry(geometry)
        sidebarView?.let { applySlotLayout(it, geometry) }
        // The shared canvas follows the picture's height; the main task is laid out at it again, as the
        // pane displays are, so a swap under the picture keeps the app's layout.
        mainTaskPresenter.currentTaskId()?.let { taskId ->
            mainTaskPresenter.presentTask(taskId, geometry).onFailure {
                NeXtepLog.warn("wide_layout", "Main task not laid out at the new canvas taskId=$taskId", it)
            }
        }
    }
    private var pinPending = false
    /** Bumped by each wide pin or return; a newer one stops the older one's frame polling. */
    private var wideGeneration = 0
    private var wideLayoutChanging = false
    private var wideReturnPending = false
    /** The wide pane's last picture with content: a player drops its video before it asks for portrait. */
    private var wideSnapshot: android.graphics.Bitmap? = null
    private val wideSnapshotTick = object : Runnable {
        override fun run() {
            val index = wideSlotIndex ?: return
            val task = wideTaskId ?: return
            val crop = slotWindows.getOrNull(index)?.geometry()?.let { android.graphics.Rect(0, 0, it.width, it.height) }
            io.github.lujinxin.nextep.framework.TaskSurfaceCompat.capturePreview(task, crop, scale = 0.25f)?.let { shot ->
                if (shot.isMostlyBlack()) shot.recycle() else { wideSnapshot?.recycle(); wideSnapshot = shot }
            }
            backdropHandler.postDelayed(this, WIDE_SNAPSHOT_INTERVAL_MS)
        }
    }

    private fun android.graphics.Bitmap.isMostlyBlack(): Boolean =
        io.github.lujinxin.nextep.framework.mostlyBlack(IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) })
    private var seenSceneTime = -1L
    private var observedTasks: List<io.github.lujinxin.nextep.task.TaskRepository.TaskSnapshot> = emptyList()
    private var observedStates: List<SlotState> = emptyList()
    // A fullscreen request can arrive between task observations; re-evaluate the pin as soon as
    // system_server publishes it instead of waiting for the next task change.
    private val sceneObserver = object : android.database.ContentObserver(backdropHandler) {
        override fun onChange(selfChange: Boolean) {
            NeXtepLog.info("workbench_scene", "Scene changed visible=$workspaceVisible suspended=$suspended " +
                "scene=${SystemServerWorkspaceBridge.scene(context)}")
            if (workspaceVisible && !suspended) observeTasks(observedTasks, observedStates)
        }
    }
    private val workbenchPreferences = runCatching {
        context.createDeviceProtectedStorageContext().getSharedPreferences("nextep_workbench_v1", Context.MODE_PRIVATE)
    }.getOrNull()
    /** LIGHT, DARK, or SYSTEM (follow the device's night mode). */
    private var appearanceMode = workbenchPreferences?.getString("appearance", APPEARANCE_SYSTEM) ?: APPEARANCE_SYSTEM
    private var appearance = resolveAppearance()
    private fun resolveAppearance(config: android.content.res.Configuration = context.resources.configuration): WorkbenchAppearance =
        WorkbenchAppearance.entries.firstOrNull { it.name == appearanceMode }
            ?: if (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) WorkbenchAppearance.DARK else WorkbenchAppearance.LIGHT
    private fun followSystemAppearance(config: android.content.res.Configuration = context.resources.configuration) {
        val resolved = resolveAppearance(config)
        if (resolved == appearance) return
        appearance = resolved
        applyAppearance()
        NeXtepLog.info("workspace", "Appearance follows the system: ${resolved.name}")
    }
    private val systemThemeCallbacks = object : android.content.ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = followSystemAppearance(newConfig)
        override fun onLowMemory() = Unit
    }
    private var recentsHidden = workbenchPreferences?.getBoolean("hide_recents", false) ?: false
    private var controlAtBottom = workbenchPreferences?.getBoolean("control_bottom", false) ?: false
    /** Packages that pause after two idle seconds in a side pane; chosen in settings or by a long press. */
    private val pausedApps = workbenchPreferences?.getStringSet("paused_apps", null).orEmpty().toMutableSet()
    private val pauseRules = io.github.lujinxin.nextep.display.SlotPauseRules(context, { it in pausedApps }, { wideSlotIndex })
    private val usageProbe = io.github.lujinxin.nextep.display.SlotUsageProbe(context)
    private val tools = WorkbenchToolsController(context, { appearance },
        { intent -> slotCoordinator?.openExternalInMain(intent)
            ?: Result.failure(IllegalStateException("Workspace unavailable")) },
        { intents, done -> slotCoordinator?.openSearches(intents, done)
            ?: Result.failure(IllegalStateException("Workspace unavailable")) },
        { slotCoordinator?.cancelApplicationLaunch() })

    /** Settings-page view of the SystemUI-owned workbench preferences and session state. */
    fun workbenchState(): android.os.Bundle = android.os.Bundle().apply {
        putString(TriggerBroadcastContract.EXTRA_APPEARANCE, appearanceMode)
        putString(TriggerBroadcastContract.EXTRA_SIDEBAR_SIDE, sidebarSide.name)
        putBoolean(TriggerBroadcastContract.EXTRA_HIDE_RECENTS, recentsHidden)
        putBoolean(TriggerBroadcastContract.EXTRA_CONTROL_BOTTOM, controlAtBottom)
        putString(TriggerBroadcastContract.EXTRA_SCENES, WorkbenchScene.encode(scenes))
        putString(TriggerBroadcastContract.EXTRA_PAUSED_APPS, pausedApps.sorted().joinToString(","))
    }

    /** "<package>=true|false" from settings, or a long-press choice on a pane. */
    private fun setAppPaused(value: String, source: String): Boolean {
        val packageName = value.substringBefore('=')
        val pause = value.substringAfter('=', "").toBooleanStrictOrNull() ?: return false
        if (!PACKAGE_NAME.matches(packageName)) return false
        if (pause) pausedApps += packageName else pausedApps -= packageName
        workbenchPreferences?.edit()?.putStringSet("paused_apps", pausedApps.toSet())?.apply()
        NeXtepLog.info("slot_pause", "Choice package=$packageName pause=$pause source=$source chosen=${pausedApps.size}")
        slotCoordinator?.refreshPause()
        return true
    }

    private fun onSlotHeld(payload: WorkspaceInternalDragController.Payload) {
        val drag = (payload as? WorkspaceInternalDragController.Payload.Slot)?.drag ?: return
        if (!workspaceVisible || suspended) return
        val packageName = slotCoordinator?.slotComponent(drag.slotIndex)?.packageName ?: return
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        val label = info?.let { context.packageManager.getApplicationLabel(it).toString() } ?: packageName
        tools.showPauseChoice(label, packageName in pausedApps,
            info?.let(io.github.lujinxin.nextep.workspace.PauseRecommendations::isGame) == true) { pause ->
            setAppPaused("$packageName=$pause", "pane")
        }
    }

    // Price comparison and payment become the first two scenes once; deleting them later sticks.
    private var scenes = WorkbenchScene.decode(workbenchPreferences?.getString("scenes", null)).let { saved ->
        val preferences = workbenchPreferences
        if (preferences == null || preferences.getBoolean("scenes_seeded", false)) saved
        else WorkbenchScene.seeded(saved).also { seeded ->
            preferences.edit().putString("scenes", WorkbenchScene.encode(seeded)).putBoolean("scenes_seeded", true).apply()
        }
    }
    private var activeSceneName: String? = null
    private fun storeScenes(updated: List<WorkbenchScene>) {
        scenes = updated
        workbenchPreferences?.edit()?.putString("scenes", WorkbenchScene.encode(updated))?.apply()
        refreshScenes()
    }

    /** Shows the scenes, highlighting the one the workspace shows, from the last observed tasks. */
    private fun refreshScenes() {
        val home = TriggerBroadcastContract.resolveHomePackage(context)
        val shownMain = observedTasks.firstOrNull { it.displayId == android.view.Display.DEFAULT_DISPLAY }?.component?.packageName
            ?.takeIf { it != home && it != TriggerBroadcastContract.SYSTEM_UI_PACKAGE && it != TriggerBroadcastContract.MODULE_PACKAGE }
        activeSceneName = WorkbenchScene.active(scenes, shownMain,
            observedStates.map { (it as? SlotState.Occupied)?.component?.packageName })?.name
        topAppStrip?.setScenes(scenes, activeSceneName)
    }

    private fun onSceneTapped(scene: WorkbenchScene) {
        when (scene.builtin) {
            WorkbenchScene.BUILTIN_COMPARE -> tools.showSearch()
            WorkbenchScene.BUILTIN_PAYMENT -> tools.showPayment()
            else -> sceneCommand(TriggerBroadcastContract.COMMAND_SCENE_APPLY, scene.name)
        }
    }

    private fun onAddScene() {
        val coordinator = slotCoordinator?.takeIf { workspaceVisible && !suspended } ?: return
        if (scenes.size >= WorkbenchScene.MAX_SCENES) { tools.message(AppLanguage.string(context, R.string.scene_full)); return }
        // The layout at the tap is what gets saved, whatever happens while the picker is open.
        val (main, slots) = coordinator.scenePackages()
        if (main == null && slots.all { it == null }) {
            tools.message(AppLanguage.string(context, R.string.scene_needs_apps)); return
        }
        tools.showScenePicker(AppLanguage.string(context, R.string.scene_add_action), null, null) { icon, text ->
            val base = text ?: AppLanguage.string(context, WorkbenchToolsController.presetLabel(checkNotNull(icon)))
            val saved = WorkbenchScene.save(scenes, WorkbenchScene(WorkbenchScene.uniqueName(scenes, base), main, slots, icon))
            if (saved === scenes) tools.message(AppLanguage.string(context, R.string.scene_full)) else storeScenes(saved)
        }
    }

    private fun onSceneLongPressed(scene: WorkbenchScene) {
        val label = topAppStrip?.sceneLabel(scene) ?: scene.name
        tools.showSceneMenu(label, canChangeIcon = scene.builtin == null, changeIcon = {
            tools.showScenePicker(AppLanguage.string(context, R.string.scene_change_icon), scene.icon,
                scene.name.takeIf { scene.icon == null }) { icon, text ->
                // An icon keeps the name (desktop shortcuts use it); text is shown, so it becomes the name.
                val typed = text.orEmpty()
                val updated = if (icon != null) scene.copy(icon = icon)
                else scene.copy(name = if (typed == scene.name) typed else WorkbenchScene.uniqueName(scenes, typed), icon = null)
                storeScenes(scenes.map { if (it.name == scene.name) updated else it })
            }
        }, delete = { storeScenes(scenes.filterNot { it.name == scene.name }) })
    }

    private fun sceneCommand(key: String, name: String): Boolean = when (key) {
        TriggerBroadcastContract.COMMAND_SCENE_SAVE -> {
            val (main, slots) = slotCoordinator?.takeIf { workspaceVisible }?.scenePackages() ?: return false
            val saved = WorkbenchScene.save(scenes, WorkbenchScene(name, main, slots))
            if (saved !== scenes) storeScenes(saved)
            saved !== scenes
        }
        TriggerBroadcastContract.COMMAND_SCENE_DELETE -> {
            storeScenes(scenes.filterNot { it.name == name }); true
        }
        TriggerBroadcastContract.COMMAND_SCENE_RESTORE_DEFAULTS -> {
            storeScenes(WorkbenchScene.restoreBuiltins(scenes))
            WorkbenchScene.builtins().all { builtin -> scenes.any { it.builtin == builtin.builtin } }
        }
        else -> {
            val scene = scenes.firstOrNull { it.name == name && it.builtin == null } ?: return false
            val coordinator = slotCoordinator?.takeIf { workspaceVisible } ?: return false
            val previous = mainTaskPresenter.currentTaskId()
            val previousPackage = observedTasks.firstOrNull { it.taskId == previous }?.component?.packageName
            if (scene.main != null && scene.main != previousPackage) holdMainUntilReplaced(previous)
            coordinator.applyScene(scene.main, scene.slots) { result ->
                result.onFailure {
                    NeXtepLog.warn("workspace", "Scene '$name' did not fully apply", it)
                    swapAnimator.dismissFrozen()
                }
            }.onFailure { swapAnimator.dismissFrozen() }.isSuccess
        }
    }

    /** "<slot index or -1>|<intent URI>": a link or search handed over from the share sheet. */
    private fun openInPane(value: String): Boolean = runCatching {
        val coordinator = slotCoordinator?.takeIf { workspaceVisible } ?: return false
        val index = value.substringBefore('|').toInt()
        val intent = Intent.parseUri(value.substringAfter('|'), Intent.URI_INTENT_SCHEME)
        (if (index < 0) coordinator.openExternalInMain(intent) else coordinator.openExternalInSlot(intent, index)).getOrThrow()
        true
    }.onFailure { NeXtepLog.warn("workspace", "Open in pane failed", it) }.getOrDefault(false)

    /** Commands arrive only through the signature-protected control receiver. */
    fun applyWorkbenchCommand(key: String, value: String?): Boolean = when (key) {
        TriggerBroadcastContract.COMMAND_APPEARANCE ->
            if (value != null && (value == APPEARANCE_SYSTEM || WorkbenchAppearance.entries.any { it.name == value })) {
                appearanceMode = value
                workbenchPreferences?.edit()?.putString("appearance", value)?.apply()
                appearance = resolveAppearance()
                applyAppearance()
                true
            } else false
        TriggerBroadcastContract.COMMAND_SIDEBAR_SIDE -> runCatching { SidebarSide.valueOf(checkNotNull(value)) }
            .map { side -> if (workspaceVisible && !suspended) onSidebarSideRequested(side) else { sidebarSide = side; true } }
            .getOrDefault(false)
        TriggerBroadcastContract.COMMAND_HIDE_RECENTS -> {
            recentsHidden = value.toBoolean()
            workbenchPreferences?.edit()?.putBoolean("hide_recents", recentsHidden)?.apply()
            publishPrivacy()
            true
        }
        TriggerBroadcastContract.COMMAND_CONTROL_BOTTOM -> {
            setControlAtBottom(value.toBoolean())
            true
        }
        TriggerBroadcastContract.COMMAND_PAUSE_APP -> value?.let { setAppPaused(it, "settings") } ?: false
        TriggerBroadcastContract.COMMAND_EXPORT_DIAGNOSTICS -> {
            slotCoordinator?.logSnapshot()
            tools.exportDiagnostics()
        }
        TriggerBroadcastContract.COMMAND_SCENE_SAVE, TriggerBroadcastContract.COMMAND_SCENE_APPLY,
        TriggerBroadcastContract.COMMAND_SCENE_DELETE, TriggerBroadcastContract.COMMAND_SCENE_RESTORE_DEFAULTS ->
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { sceneCommand(key, it) } ?: false
        TriggerBroadcastContract.COMMAND_OPEN_IN_PANE -> value?.let(::openInPane) ?: false
        else -> false
    }

    private fun setControlAtBottom(bottom: Boolean) {
        if (controlAtBottom == bottom) return
        controlAtBottom = bottom
        workbenchPreferences?.edit()?.putBoolean("control_bottom", bottom)?.apply()
        viewport.setControlAtBottom(bottom)
        onViewportChanged()
    }

    private val wallpaperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Sent by the system on every configuration change, including night mode.
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    NeXtepLog.info("workspace", "Configuration changed uiMode=${context.resources.configuration.uiMode}")
                    followSystemAppearance()
                    // SystemUI's Resources still carry the previous night mode when this arrives
                    // (observed: updated within 300 ms), and its Application never forwards
                    // ComponentCallbacks, so re-check shortly after.
                    listOf(300L, 1500L).forEach { backdropHandler.postDelayed({ followSystemAppearance() }, it) }
                    return
                }
                Intent.ACTION_WALLPAPER_CHANGED -> {
                    // Retain the displayed frame while the new wallpaper starts rendering.
                    // OEM live services may change content without changing component/id.
                    backdropGeneration.incrementAndGet()
                    // Closed, the kept frame shows the old wallpaper; the next entry captures afresh.
                    if (!workspaceVisible) releaseWallpaperAfterExit()
                }
                Intent.ACTION_USER_PRESENT -> Unit
                Intent.ACTION_SCREEN_OFF -> { tools.dismiss(); return }
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    if (intent.getStringExtra("reason") in setOf("homekey", "recentapps")) tools.dismiss()
                    return
                }
                else -> return
            }
            if (!suspended && workspaceVisible) refreshWallpaperBackdrop()
        }
    }

    init {
        // Window/display contexts may not forward configuration changes; the application does.
        runCatching { (context.applicationContext ?: context).registerComponentCallbacks(systemThemeCallbacks) }
        // Nothing is pinned when SystemUI starts; drop whatever the previous process left behind.
        SystemServerWorkspaceBridge.publishWideDisplay(context, null)
        // Warm the cache before the first workspace entry, including after unlocking.
        // Window creation is deliberately lazy; wallpaper observation must not be.
        runCatching {
            context.registerReceiver(
                wallpaperReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_WALLPAPER_CHANGED)
                    addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onFailure { NeXtepLog.warn("wallpaper_backdrop", "Wallpaper change listener unavailable", it) }
        runCatching {
            context.contentResolver.registerContentObserver(SystemServerWorkspaceBridge.sceneUri(), false, sceneObserver)
        }.onFailure { NeXtepLog.warn("workbench_scene", "Scene observer unavailable", it) }
        mainTaskPresenter.onEntryPresented = { backdropHandler.post { iconLaunch.finish() } }
        mainTaskPresenter.onHomePresented = {
            backdropHandler.post {
                if (!suspended && workspaceVisible) refreshWallpaperBackdrop()
            }
        }
        mainTaskPresenter.statusBarHidden = ::statusBarHidden
    }

    fun suspendForLock() {
        runPendingDeactivate()
        tools.dismiss()
        suspended = true
        publishPrivacy()
        cancelDragVisuals()
        backdropGeneration.incrementAndGet()
        slotCoordinator?.setSuspended(true)
        topAppStrip?.setWorkspaceVisible(false)
        // Keep TextureViews attached: GONE/removeView would destroy their virtual displays.
        records.forEach { record ->
            record.view.animate().cancel()
            record.view.alpha = 0f
            val params = record.view.layoutParams as WindowManager.LayoutParams
            params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
            windowManager.updateViewLayout(record.view, params)
        }
    }

    fun resumeAfterLock(): WorkspaceGeometry {
        check(records.size == EXPECTED_WINDOW_COUNT) { "Retained SystemUI windows are unavailable" }
        suspended = false
        publishPrivacy()
        // Wake reuses attached windows and the recorded layout. Re-running show()
        // starts a fresh alpha=0 entry animation and repeats all display setup.
        val geometry = geometry()
        applyWindowGeometry(geometry)
        sidebarView?.let { applySlotLayout(it, geometry) }
        records.forEach { record ->
            record.view.animate().cancel()
            record.view.visibility = View.VISIBLE
            record.view.alpha = 1f
            record.view.translationX = 0f
            record.view.translationY = 0f
        }
        topAppStrip?.setWorkspaceVisible(true)
        slotCoordinator?.setSuspended(false)
        return geometry
    }

    private var pendingDeactivate: Runnable? = null
    private var panelGeneration = 0

    private fun runPendingDeactivate() {
        pendingDeactivate?.let { backdropHandler.removeCallbacks(it); it.run() }
    }

    fun show(): WorkspaceGeometry {
        runPendingDeactivate()
        initializeIfNeeded()
        // system_server reads the wide display id from Settings; a session that ended without
        // clearing it (or a reboot) would otherwise hold every landscape request without pinning.
        SystemServerWorkspaceBridge.publishWideDisplay(context, wideSlotIndex?.let { slotWindows.getOrNull(it)?.displayId() })
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        followSystemAppearance()
        workspaceVisible = true
        backdropHandler.removeCallbacks(releaseParkedWindows)
        publishPrivacy()
        // Restores parked windows too: configureWindow makes them opaque and touchable again.
        val geometry = reconfigure().getOrThrow()
        // Windows released after a long hide recreate their surfaces in the first frame (~60 ms).
        // Draw that frame at the start positions, where nothing shows, and slide from the next
        // one; otherwise the animation's clock skipped a third of the slide.
        val coldWindows = records.any { it.view.visibility != View.VISIBLE }
        val enterAnimations = mutableListOf<() -> Unit>()
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            if (record.title == "NeXtepSidebar") {
                // Fading the rail builds a hardware layer, and SurfaceView holes inside a layer
                // show through as black. Slide the rail in from its edge at full alpha instead.
                val (dx, dy) = sidebarEdgeOffset(geometry)
                view.alpha = 1f
                view.translationX = dx
                view.translationY = dy
                enterAnimations += {
                    view.animate().translationX(0f).translationY(0f)
                        .setDuration(WORKSPACE_ENTER_DURATION_MS).setInterpolator(transitionInterpolator).start()
                }
                return@forEach
            }
            view.translationX = 0f
            view.alpha = 0f
            view.translationY = when (record.title) {
                "NeXtepTopBar" -> if (controlAtBottom) dp(ENTER_OFFSET_DP) else -dp(ENTER_OFFSET_DP)
                else -> dp(ENTER_OFFSET_DP)
            }
            enterAnimations += {
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(WORKSPACE_ENTER_DURATION_MS)
                    .setInterpolator(transitionInterpolator)
                    .start()
            }
        }
        val animationGeneration = panelGeneration + 1
        fun startEnterAnimations() {
            if (animationGeneration != panelGeneration || !workspaceVisible) return
            enterAnimations.forEach { it() }
            // Retained apps come back after the slide, never in its last frames.
            slotCoordinator?.onEntrySlideStarted(WORKSPACE_ENTER_DURATION_MS)
        }
        if (coldWindows) {
            // A callback posted now runs in the expensive first frame; the one it posts, in the next.
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { startEnterAnimations() }
            }
        }
        topAppStrip?.setWorkspaceVisible(true)
        topAppStrip?.refresh()
        if (FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            slotCoordinator?.activate()
        }
        usageProbe.start { slotCoordinator?.takeIf { workspaceVisible && !suspended }?.usagePanes() }
        // Rapid exit/enter cycles have left the main task scaled with every panel hidden. Whatever
        // cancels the entry animation, the panels must end visible while the workspace is active.
        val generation = ++panelGeneration
        if (!coldWindows) startEnterAnimations()
        backdropHandler.postDelayed({
            if (generation != panelGeneration || !workspaceVisible || suspended) return@postDelayed
            records.forEach { record ->
                val view = record.view
                if (view.visibility != View.VISIBLE || view.alpha < 1f || view.translationX != 0f || view.translationY != 0f) {
                    NeXtepLog.warn("workspace", "Panel recovered ${record.title} visibility=${view.visibility} alpha=${view.alpha}")
                    view.animate().cancel()
                    view.visibility = View.VISIBLE
                    view.alpha = 1f
                    view.translationX = 0f
                    view.translationY = 0f
                }
            }
        }, WORKSPACE_ENTER_DURATION_MS + 80 + if (coldWindows) COLD_ENTRY_ALLOWANCE_MS else 0L)
        return geometry()
    }

    fun hide() {
        workspaceVisible = false
        usageProbe.stop()
        panelGeneration++
        // The main app is growing back; a task-change reconcile during the fade re-shrank it.
        slotCoordinator?.pauseReconciliation()
        // The frame stays for the next entry: the translucent panels showed black until a new capture.
        tools.dismiss()
        publishPrivacy()
        cancelDragVisuals()
        suspended = false
        backdropGeneration.incrementAndGet()
        // Keep the live slots on screen while the panels fade; parking the tasks and
        // releasing their displays first blanked every slot a frame before the fade began.
        runPendingDeactivate()
        pendingDeactivate = Runnable {
            pendingDeactivate = null
            runCatching { slotCoordinator?.deactivate() }
                .onFailure { NeXtepLog.warn("workspace", "Deferred slot deactivation failed; displays retained", it) }
        }.also { backdropHandler.postDelayed(it, WORKSPACE_EXIT_DURATION_MS) }
        setWideLayout(null, null)
        SystemServerWorkspaceBridge.clearScene(context)
        topAppStrip?.setWorkspaceVisible(false)
        val exitGeometry = geometry()
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            val params = view.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
            windowManager.updateViewLayout(view, params)
            val slideOut = record.title == "NeXtepSidebar"
            val (dx, dy) = if (slideOut) sidebarEdgeOffset(exitGeometry) else 0f to 0f
            view.animate()
                .alpha(if (slideOut) 1f else 0f)
                .translationX(dx)
                .translationY(
                    if (slideOut) dy
                    else if (record.title == "NeXtepTopBar" && !controlAtBottom) -dp(EXIT_OFFSET_DP)
                    else dp(EXIT_OFFSET_DP),
                )
                .setDuration(WORKSPACE_EXIT_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .withEndAction { parkWindow(record) }
                .start()
        }
        backdropHandler.removeCallbacks(releaseParkedWindows)
        backdropHandler.postDelayed(releaseParkedWindows, WORKSPACE_EXIT_DURATION_MS + PARKED_WINDOW_RELEASE_MS)
    }

    /**
     * Keep a hidden panel's window and surfaces for the next entry: recreating them (GONE ->
     * VISIBLE) cost the first entry frame ~50 ms. The window becomes transparent, untouchable
     * and hidden from accessibility; its views stay faded or off screen where the exit left them.
     */
    private fun parkWindow(record: WindowRecord) {
        if (workspaceVisible) return
        val view = record.view
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.alpha = 0f
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { NeXtepLog.warn("workspace", "Could not park ${record.title}", it) }
    }

    /** A workspace hidden for a while gives its window surfaces back. */
    private val releaseParkedWindows = Runnable {
        if (workspaceVisible) return@Runnable
        records.forEach { record ->
            record.view.visibility = View.GONE
            record.view.translationX = 0f
            record.view.translationY = 0f
        }
    }

    fun launchInSlot(intent: Intent): Result<Unit> {
        if (!FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            return Result.failure(IllegalStateException("VirtualDisplay slot feature disabled"))
        }
        return slotCoordinator?.launchInSlot(intent)
            ?: Result.failure(IllegalStateException("VirtualDisplay slot unavailable"))
    }

    fun openFromLauncher(intent: Intent): Result<Unit> = slotCoordinator
        ?.openFromLauncher(intent)
        ?: Result.failure(IllegalStateException("Slot coordinator unavailable"))

    fun currentGeometry(refreshViewport: Boolean = false): WorkspaceGeometry =
        viewport.geometry(sidebarSide, controlAtBottom, refreshViewport)

    fun setNavigationButtonsEnabled(enabled: Boolean) {
        viewport.setNavigationButtonsEnabled(enabled)
    }

    fun setSlotBackgroundSwipeEnabled(enabled: Boolean) {
        if (slotBackgroundSwipeEnabled == enabled) return
        slotBackgroundSwipeEnabled = enabled
        slotWindows.forEach { it.view.setBackgroundSwipeEnabled(enabled) }
    }

    fun reconfigure(): Result<WorkspaceGeometry> = runCatching {
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        val geometry = geometry()
        applyWindowGeometry(geometry)
        sidebarView?.let { applySlotLayout(it, geometry) }
        refreshWallpaperBackdrop()
        NeXtepLog.info("workspace_configuration", "Reconfigured geometry=$geometry")
        geometry
    }

    fun setSidebarSide(side: SidebarSide): Result<WorkspaceGeometry> = runCatching {
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        sidebarSide = side
        val geometry = geometry()
        applyWindowGeometry(geometry)
        sidebarView?.let { panel -> applySlotLayout(panel, geometry) }
        applySidebarBackdrop(geometry)
        records.firstOrNull { it.title == "NeXtepSidebar" }?.view?.takeUnless { contentLayoutChanging }?.let { sidebar ->
            sidebar.animate().cancel()
            sidebar.translationY = 0f
            sidebar.alpha = 0f
            sidebar.translationX = if (side == SidebarSide.LEFT) {
                -dp(SIDEBAR_ENTER_OFFSET_DP)
            } else {
                dp(SIDEBAR_ENTER_OFFSET_DP)
            }
            sidebar.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(SIDEBAR_SWITCH_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        }
        NeXtepLog.info("sidebar_side", "Applied side=$side geometry=$geometry")
        geometry
    }

    fun slotStatesDescription(): String = slotCoordinator
        ?.slotStates()
        ?.mapIndexed { index, state -> "${index + 1}:$state" }
        ?.joinToString(" | ")
        ?: "unavailable"

    private fun initializeIfNeeded() {
        if (initialized) return
        initialized = true
        val windowType = SystemUiWindowTypeResolver.resolve() ?: run {
            initialized = false
            return
        }
        val geometry = geometry()

        addWindow(
            title = "NeXtepTopBar",
            view = TopAppStripView(
                context = context,
                onAppClicked = { intent ->
                    val result = if (slotCoordinator?.selectingDesktopApp == true) slotCoordinator?.openFromLauncher(intent)
                    else slotCoordinator?.openInMain(
                        intent,
                        autoMinimizeCurrent = SystemUiRuntime.shouldAutoMinimizeMainOnTopAppSwitch(),
                    )
                    result?.onFailure { error ->
                        NeXtepLog.warn("top_apps", "Unable to open ${intent.component} in main", error)
                    }
                },
                onFrostStrengthChanged = { strength ->
                    if (frostStrength != strength) {
                        frostStrength = strength
                        applyWallpaperBackdrop(geometry())
                    }
                },
                onAppDragStarting = { source, intent ->
                    workspaceVisible && !suspended && internalDrag.start(
                        source, WorkspaceInternalDragController.Payload.App(intent),
                    )
                },
                onAppDragTouch = internalDrag::touch,
                onSceneTapped = ::onSceneTapped,
                onSceneLongPressed = ::onSceneLongPressed,
                onAddScene = ::onAddScene,
            ).also { strip -> topAppStrip = strip; strip.setScenes(scenes, activeSceneName) },
            width = geometry.screenWidth,
            height = geometry.topHeight,
            gravity = Gravity.TOP,
            touchable = true,
            windowType = windowType,
        )
        addWindow(
            title = "NeXtepSidebar",
            view = sidebarPanel(geometry),
            width = geometry.sidebarWidth,
            height = geometry.contentHeight,
            gravity = Gravity.TOP or if (sidebarSide == SidebarSide.LEFT) {
                Gravity.START
            } else {
                Gravity.END
            },
            y = geometry.topHeight,
            touchable = FeatureGate.TASK_SWAP.defaultEnabled,
            windowType = windowType,
        )
        addWindow(
            title = "NeXtepContentPanel",
            view = FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                addView(View(context).also { contentHighlightView = it })
            },
            width = geometry.contentWidth,
            height = geometry.contentHeight,
            gravity = Gravity.TOP or if (sidebarSide == SidebarSide.LEFT) {
                Gravity.END
            } else {
                Gravity.START
            },
            y = geometry.topHeight,
            windowType = windowType,
        )
        if (records.size == EXPECTED_WINDOW_COUNT) iconLaunch.prepare()
        if (records.size != EXPECTED_WINDOW_COUNT) {
            iconLaunch.release()
            records.forEach { record ->
                try {
                    windowManager.removeView(record.view)
                } catch (error: Throwable) {
                    NeXtepLog.warn("systemui_window", "Cleanup failed for ${record.title}", error)
                }
            }
            records.clear()
            slotCoordinator = null
            topAppStrip = null
            sidebarView = null
            contentHighlightView = null
            slotWindows = emptyList()
            initialized = false
        }
        applyAppearance()
    }

    private fun addWindow(
        title: String,
        view: View,
        width: Int,
        height: Int,
        gravity: Int,
        y: Int = 0,
        touchable: Boolean = false,
        windowType: Int,
    ) {
        val interactionFlags = if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams(
            width,
            height,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                interactionFlags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.y = y
            this.title = title
        }
        val hostedView = if (title == "NeXtepContentPanel") view else WorkspacePanelHost(context, view)
        val record = WindowRecord(hostedView, title)
        configureWindow(record, params, geometry())
        hostedView.visibility = View.GONE
        try {
            windowManager.addView(hostedView, params)
            records += record
            NeXtepLog.info("systemui_window", "Added $title")
        } catch (error: Throwable) {
            NeXtepLog.error("systemui_window", "Could not add $title", error)
        }
    }

    private fun applyWindowGeometry(geometry: WorkspaceGeometry) {
        // A re-application of the same geometry (the viewport callback after a wide
        // layout change) must not abort the slot exchange that change just started.
        // A wide layout change keeps the frames that cover it (see pinTask/returnWideToMain).
        if (!contentLayoutChanging && geometry != appliedGeometry) cancelDragVisuals(keepOverlay = wideLayoutChanging)
        appliedGeometry = geometry
        records.forEach { record ->
            val params = record.view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            configureWindow(record, params, geometry)
            windowManager.updateViewLayout(record.view, params)
        }
        refreshMainFrame(geometry)
        topAppStrip?.setLandscape(geometry.isLandscape, geometry.isControlOnLeft, geometry.controlAtBottom,
            geometry.controlStatusBand)
    }

    private fun configureWindow(
        record: WindowRecord,
        params: WindowManager.LayoutParams,
        geometry: WorkspaceGeometry,
    ) {
        // A hidden workspace keeps its windows parked: transparent and untouchable (see parkWindow).
        val touchable = workspaceVisible && !suspended && (record.title == "NeXtepTopBar" ||
            (record.title == "NeXtepSidebar" && FeatureGate.TASK_SWAP.defaultEnabled))
        params.flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.alpha = if (workspaceVisible) 1f else 0f
        // A foreground app normally hides live wallpaper, so screenshotWallpaper()
        // has no surface to read on entry. Keep that surface available behind the
        // workspace until exit/lock, without sending control gestures to it.
        params.flags = if (record.title == "NeXtepTopBar" && workspaceVisible && !suspended) {
            params.flags or WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        } else params.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
        params.setWallpaperTouchEventsEnabled(false)
        (record.view as? WorkspacePanelHost)?.panelRotationDegrees = geometry.panelRotationDegrees
        // WorkspaceGeometry explicitly reserves navigation space in display coordinates.
        // FLAG_LAYOUT_IN_SCREEN alone does not disable inset fitting: a bottom navigation
        // inset can make WindowManager shift the entire full-height sidebar upward.
        // Apply this on both creation and reconfiguration, for either sidebar and rotation.
        params.setFitInsetsTypes(0)
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.gravity = Gravity.TOP or Gravity.LEFT
        when (record.title) {
            "NeXtepTopBar" -> {
                params.width = geometry.controlWidth
                // The status band above the strip belongs to the strip's window, not to any pane.
                params.height = geometry.controlHeight + geometry.controlStatusBand
                params.x = geometry.controlLeft
                params.y = geometry.controlTop - geometry.controlStatusBand
            }
            "NeXtepSidebar" -> {
                params.width = geometry.sidebarPhysicalWidth
                params.height = geometry.sidebarPhysicalHeight
                params.x = geometry.sidebarLeft
                params.y = geometry.sidebarTop
            }
            "NeXtepContentPanel" -> {
                // The transparent, non-touchable root observes full-display insets.
                // Its drag highlight child and visible tasks use the safe bounds.
                params.width = geometry.screenWidth
                params.height = geometry.screenHeight
                params.x = 0
                params.y = 0
                // The main pane's rectangle; under a wide picture the app fills it width-fit.
                contentHighlightView?.layoutParams = FrameLayout.LayoutParams(
                    geometry.contentWidth, geometry.contentHeight, Gravity.TOP or Gravity.LEFT,
                ).apply {
                    leftMargin = geometry.contentLeft
                    topMargin = geometry.contentTop
                }
            }
        }
    }

    private fun sidebarPanel(geometry: WorkspaceGeometry): View = FrameLayout(context).apply {
        sidebarView = this
        setBackgroundColor(Color.TRANSPARENT)
        if (FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            slotWindows = List(WORKSPACE_SLOT_COUNT) { index -> VirtualDisplaySlot(context, index) }
            slotWindows.forEach { slot ->
                slot.view.setBackgroundSwipeEnabled(slotBackgroundSwipeEnabled)
                addView(slot.view)
            }
            applySlotLayout(this, geometry)
            slotCoordinator = SlotTaskCoordinator(
                context = context,
                slots = slotWindows,
                mainTaskPresenter = mainTaskPresenter,
                geometryProvider = ::geometry,
                onSwapAnimation = ::animateDragSwap,
                onInternalSlotDrag = { source, drag ->
                    internalDrag.start(source, WorkspaceInternalDragController.Payload.Slot(drag))
                },
                onInternalDragTouch = internalDrag::touch,
                onTasksObserved = ::observeTasks,
                onContentDragStarted = { if (workspaceVisible && !suspended) contentDrag.begin() },
                onContentDragEntered = { index ->
                    // Defer reparent until ViewGroup has finished dispatching ENTERED.
                    backdropHandler.post {
                        runCatching { contentDrag.enter(index) }.onFailure {
                            NeXtepLog.warn("content_drag", "Content expansion unavailable", it)
                            swapAnimator.cancel()
                            contentDrag.abandon()
                        }
                    }
                },
                onContentDragEnded = { accepted -> backdropHandler.post {
                    runCatching { contentDrag.end(accepted) }.onFailure {
                        NeXtepLog.warn("content_drag", "Content drag recovery unavailable", it)
                        contentDrag.abandon()
                    }
                } },
                pauseRules = pauseRules,
                onIconLaunch = ::flyIcon,
                autoMinimizeMain = SystemUiRuntime::shouldAutoMinimizeMainOnTopAppSwitch,
            )
        }
    }

    private val iconLaunch = IconLaunchOverlay(context) { SystemUiWindowTypeResolver.resolve() }

    private val iconLaunchStyle = IconLaunchStyle.forDevice()

    /**
     * The tapped icon flies from [source] to the main pane, covering the task's black first frames.
     * The desktop's own rendering of the icon is used when it came along. The plate is a neutral
     * surface in the system's light or dark tone: an icon- or splash-coloured block over the whole
     * pane looked like a paint swatch, not a window opening.
     */
    private fun flyIcon(component: android.content.ComponentName, source: android.graphics.Rect, desktopIcon: android.graphics.Bitmap?) {
        if (source.isEmpty) return
        val icon = desktopIcon?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) }
            ?: runCatching { context.packageManager.getActivityIcon(component) }
                .recoverCatching { context.packageManager.getApplicationIcon(component.packageName) }
                .getOrNull() ?: return
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val plate = if (night) PLATE_DARK else PLATE_LIGHT
        val geometry = geometry()
        iconLaunch.start(icon, plate, source,
            android.graphics.Rect(geometry.contentLeft, geometry.contentTop, geometry.contentRight, geometry.contentBottom), iconLaunchStyle)
    }

    private fun cancelDragVisuals(keepOverlay: Boolean = false) {
        contentDrag.abandon()
        internalDrag.cancel()
        slotWindows.forEach { it.view.cancelBackgroundSwipe() }
        slotCoordinator?.onSlotDragEnded()
        slotCoordinator?.cancelPendingDragExchange()
        if (!keepOverlay) swapAnimator.cancel()
    }

    private fun contentSide(side: SidebarSide): Boolean {
        contentLayoutChanging = true
        return try { onSidebarSideRequested(side) } finally { contentLayoutChanging = false }
    }

    private fun expandContentTarget(index: Int, complete: (Boolean) -> Unit): Boolean {
        if (!workspaceVisible || suspended || wideSlotIndex != null) return false
        val coordinator = slotCoordinator ?: return false
        val target = coordinator.contentExchangeTarget(index) ?: return false
        contentOriginSide = sidebarSide
        contentTarget = target
        return animateSlotExpansion(index, target.sourceTask, { contentDrag.active },
            { complete(false) }) { reveal ->
            var reported = false
            fun settled(success: Boolean) {
                if (reported) return
                reported = true
                if (!success) contentSide(contentOriginSide)
                reveal()
                complete(success)
                NeXtepLog.info("content_drag", "Expansion settled success=$success")
            }
            if (!coordinator.exchangeContent(target, restoring = false) { settled(it.isSuccess) }) settled(false)
        }
    }

    private fun animateSlotExpansion(index: Int, sourceTask: Int?, valid: () -> Boolean,
                                     rejected: () -> Unit, move: (() -> Unit) -> Unit): Boolean {
        val old = geometry()
        val destinationSide = if (sidebarSide == SidebarSide.RIGHT) SidebarSide.LEFT else SidebarSide.RIGHT
        val next = old.copy(sidebarSide = destinationSide)
        val bounds = slotWindows.map { slot -> android.graphics.Rect().also {
            if (!slot.view.getScreenVisibleRect(it)) return false
        } }
        val main = android.graphics.RectF(old.contentLeft.toFloat(), old.contentTop.toFloat(),
            old.contentRight.toFloat(), old.contentBottom.toFloat())
        val frames = mutableListOf<WorkspaceSwapAnimator.Frame>()
        val states = slotCoordinator?.slotStates().orEmpty()
        bounds.forEachIndexed { i, rect ->
            val from = android.graphics.RectF(rect)
            val to = android.graphics.RectF(rect).apply {
                offset((next.sidebarLeft - old.sidebarLeft).toFloat(),
                    (next.sidebarTop - old.sidebarTop).toFloat())
            }
            if (i == index) frames.add(WorkspaceSwapAnimator.Frame(
                sourceTask?.let { mainPanePreview(it, old) }, main, to))
            else frames.add(WorkspaceSwapAnimator.Frame(slotWindows[i].view.captureProtectedPreview(), from, to,
                empty = states.getOrNull(i) !is SlotState.Occupied))
        }
        // Target expands on its own side while the other tasks cross to the opposite rail.
        frames.add(WorkspaceSwapAnimator.Frame(slotWindows[index].view.captureProtectedPreview(),
            android.graphics.RectF(bounds[index]), android.graphics.RectF(next.contentLeft.toFloat(),
                next.contentTop.toFloat(), next.contentRight.toFloat(), next.contentBottom.toFloat())))
        swapAnimator.animateFrames(frames, listOf(main) + bounds.map { android.graphics.RectF(it) },
            old.screenWidth, old.screenHeight) { reveal ->
            if (!valid() || !workspaceVisible || suspended || !contentSide(destinationSide)) {
                reveal(); rejected()
            } else move(reveal)
        }
        return true
    }

    private fun restoreContentTarget(index: Int, complete: () -> Unit) {
        val target = contentTarget?.takeIf { it.slot == index }
        if (target == null || !workspaceVisible || suspended) { complete(); return }
        var reported = false
        fun restored() {
            if (reported) return
            reported = true
            contentSide(contentOriginSide)
            complete()
        }
        if (slotCoordinator?.exchangeContent(target, restoring = true) {
                if (it.isFailure) NeXtepLog.warn("content_drag", "Cancellation task restore unavailable", it.exceptionOrNull())
                restored()
            } != true) restored()
    }

    private fun highlightInternalDragTarget(target: WorkspaceInternalDragController.Target?) {
        slotWindows.forEachIndexed { index, slot ->
            slot.view.setDropHighlighted(target == WorkspaceInternalDragController.Target.Slot(index))
        }
        topAppStrip?.setDismissTargetHighlighted(target == WorkspaceInternalDragController.Target.Background)
        contentHighlightView?.background =
            if (target == WorkspaceInternalDragController.Target.Main) {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor((appearance.accent and 0x00FFFFFF) or 0x19000000)
                    setStroke(dp(2).toInt(), appearance.accent)
                    cornerRadius = dp(14)
                }
            } else mainFrame(geometry())
    }

    private fun dropInternalPayload(
        payload: WorkspaceInternalDragController.Payload,
        target: WorkspaceInternalDragController.Target,
    ): Boolean {
        val coordinator = slotCoordinator ?: return false
        return when (target) {
            is WorkspaceInternalDragController.Target.Slot -> when (payload) {
                is WorkspaceInternalDragController.Payload.Slot -> coordinator.onSlotDropped(target.index, payload.drag)
                is WorkspaceInternalDragController.Payload.App -> coordinator.onIntentDropped(target.index, payload.intent)
            }
            WorkspaceInternalDragController.Target.Main ->
                payload is WorkspaceInternalDragController.Payload.Slot && coordinator.dropSlotIntoMain(payload.drag)
            WorkspaceInternalDragController.Target.Background ->
                payload is WorkspaceInternalDragController.Payload.Slot && coordinator.dismissDraggedSlot(payload.drag)
        }
    }

    private fun animateDragSwap(drag: SlotTaskDrag, targetIndex: Int?, move: (() -> Unit) -> Unit) {
        // A tap swaps the slot with the main window in place; the rail keeps its side.
        // (Content drag still expands the target on its own side, see expandContentTarget.)
        val geometry = geometry()
        val sourceView = slotWindows.getOrNull(drag.slotIndex)?.view
        val sourceRect = android.graphics.Rect()
        if (sourceView == null || !sourceView.getScreenVisibleRect(sourceRect)) {
            move {}
            return
        }
        val targetRect = android.graphics.Rect()
        if (targetIndex != null) {
            val targetView = slotWindows.getOrNull(targetIndex)?.view
            if (targetView == null || !targetView.getScreenVisibleRect(targetRect)) {
                move {}
                return
            }
        } else targetRect.set(geometry.contentLeft, geometry.contentTop, geometry.contentRight, geometry.contentBottom)
        val point = drag.dropPoint
        val halfWidth = sourceRect.width() * 0.45f
        val halfHeight = sourceRect.height() * 0.45f
        val startRect = if (point == null) android.graphics.RectF(sourceRect) else
            android.graphics.RectF(point.x - halfWidth, point.y - halfHeight,
                point.x + halfWidth, point.y + halfHeight)
        val sourcePreview = sourceView.captureProtectedPreview()
        // The desktop in the main window does not move into the pane: the card flying back is an empty one.
        val targetOccupied = if (targetIndex == null) mainTaskPresenter.currentTaskId() != null
            else slotCoordinator?.slotStates()?.getOrNull(targetIndex) is SlotState.Occupied
        val targetPreview = if (targetIndex != null) {
            if (targetOccupied) slotWindows[targetIndex].view.captureProtectedPreview() else null
        } else mainTaskPresenter.currentTaskId()?.let { mainPanePreview(it, geometry) }
        // An app lays out again when it changes window, and some pad their top for a status bar even in a
        // side pane: just before the card fades, it takes the app as the main window now shows it.
        val reveal: (() -> Unit) -> Unit = if (targetIndex != null) move else { show ->
            move { refreshArrivedMain(show, swapAnimator.current, attempt = 0, previous = null) }
        }
        swapAnimator.animate(sourcePreview, targetPreview, !targetOccupied, startRect,
            android.graphics.RectF(sourceRect), android.graphics.RectF(targetRect),
            geometry.screenWidth, geometry.screenHeight, reveal)
    }

    /**
     * The app lays out again in the main window (it has status and gesture bars there, none in a pane), so
     * the swap card dissolves into the app as the main window shows it, then [show]s the live app. It waits
     * for that layout to settle: two like pictures in a row that are not its bare (flat) window. A picture
     * that keeps changing (a video) is taken after about 300 ms.
     */
    private fun refreshArrivedMain(show: () -> Unit, animation: Int, attempt: Int, previous: IntArray?) {
        if (swapAnimator.current != animation) return show()
        val shot = mainTaskPresenter.currentTaskId()?.let { mainPanePreview(it, geometry()) }
        val luma = shot?.let(::lumaGrid)
        val settled = luma != null && luma.max() - luma.min() >= 24 && previous != null &&
            luma.indices.all { kotlin.math.abs(luma[it] - previous[it]) < 6 }
        if (!settled && attempt < ARRIVAL_ATTEMPTS) {
            shot?.recycle()
            backdropHandler.postDelayed({ refreshArrivedMain(show, animation, attempt + 1, luma) }, ARRIVAL_RETRY_MS)
            return
        }
        swapAnimator.refresh(1, shot, animation, show)
    }

    private fun lumaGrid(bitmap: android.graphics.Bitmap): IntArray = IntArray(9 * 16) { i ->
        val c = bitmap.getPixel(i % 9 * (bitmap.width - 1) / 8, i / 9 * (bitmap.height - 1) / 15)
        (android.graphics.Color.red(c) * 3 + android.graphics.Color.green(c) * 6 + android.graphics.Color.blue(c)) / 10
    }

    /** The status bar as it is now: a report from the app goes stale (a splash screen hid it, the page that
     * followed showed it without reporting), so the strip's own insets decide. */
    private fun statusBarHidden(): Boolean =
        topAppStrip?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.statusBars()) == false

    /** The main app cut to what its pane shows (the capture is of the whole screen). */
    private fun mainPanePreview(taskId: Int, base: WorkspaceGeometry): android.graphics.Bitmap? {
        val shot = io.github.lujinxin.nextep.framework.TaskSurfaceCompat.capturePreview(taskId) ?: return null
        val g = if (statusBarHidden()) base.uncropped() else base
        val scale = shot.height / g.screenHeight.toFloat()
        val top = (g.availableTop * scale).toInt().coerceIn(0, shot.height - 1)
        val bottom = (g.availableBottom * scale).toInt().coerceIn(top + 1, shot.height)
        if (top == 0 && bottom == shot.height) return shot
        return android.graphics.Bitmap.createBitmap(shot, 0, top, shot.width, bottom - top).also { shot.recycle() }
    }

    private fun applySlotLayout(panel: FrameLayout, geometry: WorkspaceGeometry) {
        panel.invalidate()
        val profile = viewport.layout()
        val bounds = profile.slots
        topAppStrip?.setWideSlot(profile.wideSlotIndex?.let { slotWindows[it].view })
        slotWindows.forEachIndexed { index, slot ->
            slot.setInteractive(index == profile.wideSlotIndex)
            slot.setLayoutProfile(profile)
            slot.view.setBackgroundSwipeEnabled(slotBackgroundSwipeEnabled && index != profile.wideSlotIndex)
            if (index == profile.wideSlotIndex) return@forEachIndexed
            if (slot.view.parent !== panel) {
                (slot.view.parent as? android.view.ViewGroup)?.removeView(slot.view)
                panel.addView(slot.view)
            }
            slot.view.setBackgroundSwipeSide(logicalSidebarSide(geometry))
            slot.view.layoutParams = FrameLayout.LayoutParams(
                bounds[index].width,
                bounds[index].height,
                Gravity.TOP,
            ).apply {
                // The rail's gap splits evenly: the screen edge and the main window each get half,
                // the same 4dp that separates the slots, on either side.
                val slack = (geometry.sidebarWidth - profile.gapPixels - bounds[index].width).coerceAtLeast(0) / 2
                marginStart = slack + profile.gapPixels / 2
                marginEnd = slack + profile.gapPixels - profile.gapPixels / 2
                topMargin = bounds[index].top
            }
        }
    }

    private fun refreshWallpaperBackdrop(attempt: Int = 0) {
        val geometry = geometry()
        // Use the last frame immediately; surface capture runs off the UI thread.
        applyWallpaperBackdrop(geometry)
        val previous = wallpaperBitmap
        val generation = backdropGeneration.incrementAndGet()
        backdropWorker.execute {
            if (generation != backdropGeneration.get()) return@execute
            val identity = WorkspaceWallpaperBackdrop.identity(context)
            val captured = WorkspaceWallpaperBackdrop.capture(
                context, geometry.screenWidth, geometry.screenHeight,
            )
            val currentIdentity = WorkspaceWallpaperBackdrop.identity(context)
            // Do not associate a frame taken during a wallpaper change with the
            // new wallpaper. The next retry will capture the settled surface.
            val bitmap = captured?.takeUnless {
                identity != null && currentIdentity != null && identity != currentIdentity
            }
            if (bitmap !== captured) captured?.recycle()
            val fitted = if (bitmap == null && previous != null &&
                (previous.width != geometry.screenWidth || previous.height != geometry.screenHeight)
            ) WorkspaceWallpaperBackdrop.fit(previous, geometry.screenWidth, geometry.screenHeight)
            else null
            backdropHandler.post {
                if (generation != backdropGeneration.get() || !WorkspaceWallpaperBackdrop.canCapture(context)) {
                    bitmap?.recycle()
                    fitted?.recycle()
                    return@post
                }
                val replaced = wallpaperBitmap
                if (bitmap != null) {
                    wallpaperBitmap = bitmap
                } else if (fitted != null) {
                    wallpaperBitmap = fitted
                }
                // A failed capture or identity query must not erase a valid frame.
                applyWallpaperBackdrop(geometry)
                if (replaced != null && replaced !== wallpaperBitmap) replaced.recycle()
                // A waking live engine can briefly expose its initial frame even
                // when capture succeeds. Refresh again after it has started drawing.
                if (attempt < BACKDROP_SETTLE_REFRESHES ||
                    (bitmap == null && attempt < BACKDROP_MAX_RETRIES)
                ) {
                    backdropHandler.postDelayed({
                        if (generation == backdropGeneration.get() && !suspended &&
                            workspaceVisible
                        ) refreshWallpaperBackdrop(attempt + 1)
                    }, minOf(1_000L, 200L * (attempt + 1)))
                }
            }
        }
    }

    /** The panels fade for 180 ms; afterwards the frame is dropped until the next entry. */
    private fun releaseWallpaperAfterExit() {
        backdropGeneration.incrementAndGet()
        backdropHandler.postDelayed({
            if (workspaceVisible) return@postDelayed
            val released = wallpaperBitmap ?: return@postDelayed
            wallpaperBitmap = null
            applyWallpaperBackdrop(geometry())
            released.recycle()
        }, WORKSPACE_EXIT_DURATION_MS + 50)
    }

    private fun applyWallpaperBackdrop(geometry: WorkspaceGeometry) {
        records.firstOrNull { it.title == "NeXtepTopBar" }?.view?.let { view ->
            val background = WorkspaceWallpaperBackdrop.crop(wallpaperBitmap,
                geometry.controlLeft, geometry.controlTop - geometry.controlStatusBand, frostStrength, view.background,
                appearance.opaquePanel)
            if (view.background !== background) view.background = background
        }
        applySidebarBackdrop(geometry)
        refreshMainFrame(geometry)
    }

    private fun applySidebarBackdrop(geometry: WorkspaceGeometry) {
        records.firstOrNull { it.title == "NeXtepSidebar" }?.view?.let { view ->
            val background = WorkspaceWallpaperBackdrop.crop(wallpaperBitmap,
                geometry.sidebarLeft, geometry.sidebarTop, frostStrength, view.background, appearance.opaquePanel)
            if (view.background !== background) view.background = background
        }
    }

    private fun logicalSidebarSide(geometry: WorkspaceGeometry): SidebarSide =
        geometry.panelSidebarSide

    /** The panels' frosted wallpaper and tone as seen at [left], [top] on screen; null before a capture. */
    private fun panelBackdrop(left: Int, top: Int): android.graphics.drawable.Drawable? = wallpaperBitmap?.let {
        android.graphics.drawable.LayerDrawable(arrayOf(
            WorkspaceWallpaperBackdrop.crop(it, left, top, frostStrength),
            android.graphics.drawable.ColorDrawable(appearance.panelEnd),
        ))
    }

    // No resting outline: on the screen edge it read as a gap the slots, flush on their side, do not have.
    private fun mainFrame(geometry: WorkspaceGeometry) = WorkbenchFrameDrawable(appearance.opaquePanel,
        Color.TRANSPARENT, dp(14), dp(1).coerceAtLeast(1f), panelBackdrop(geometry.contentLeft, geometry.contentTop))

    /** Recut the main pane's corners after the wallpaper frame or the pane itself changed. */
    private fun refreshMainFrame(geometry: WorkspaceGeometry) {
        contentHighlightView?.takeIf { it.background is WorkbenchFrameDrawable }?.background = mainFrame(geometry)
    }

    private fun applyAppearance() {
        topAppStrip?.setAppearance(appearance)
        sidebarView?.background = appearance.background()
        slotWindows.forEach { it.view.setAppearance(appearance) }
        contentHighlightView?.background = mainFrame(geometry())
        applyWallpaperBackdrop(geometry())
    }

    private fun publishPrivacy() {
        val privateIds = if (workspaceVisible && !suspended && recentsHidden) {
            val displays = slotWindows.mapNotNull { it.displayId() }.toSet()
            observedTasks.filter { it.displayId in displays }.mapTo(mutableSetOf()) { it.taskId }.apply {
                observedTasks.firstOrNull { it.displayId == android.view.Display.DEFAULT_DISPLAY }
                    ?.takeIf { it.component.packageName != context.packageName }
                    ?.let { add(it.taskId) }
            }
        } else emptySet()
        SystemServerWorkspaceBridge.publishPrivateTasks(context, privateIds)
    }

    private fun observeTasks(tasks: List<io.github.lujinxin.nextep.task.TaskRepository.TaskSnapshot>, states: List<SlotState>) {
        observedTasks = tasks
        observedStates = states
        publishPrivacy()
        refreshScenes()
        if (!workspaceVisible || suspended || contentDrag.active || slotCoordinator?.launchingApps == true ||
            slotCoordinator?.movingTasks == true) return
        val scene = SystemServerWorkspaceBridge.scene(context)
        if (scene != null && scene.time > seenSceneTime && scene.taskId == wideTaskId && scene.releasesWide()) {
            seenSceneTime = scene.time
            returnWideToMain()
            return
        }
        wideSlotIndex?.let { index ->
            val current = states.getOrNull(index)
            // A task pulled back to the main display (or gone) leaves the wide slot empty;
            // keeping the wide layout then shows a black top with the app squeezed below it.
            val lost = current !is SlotState.Occupied || current.taskId != wideTaskId
            if (!pinPending && lost || wideTaskId != null && tasks.none { it.taskId == wideTaskId }) {
                NeXtepLog.info("wide_layout", "Wide slot lost task=$wideTaskId state=$current pending=$pinPending")
                setWideLayout(null, null)
            } else if (!pinPending && current is SlotState.Occupied) {
                // Leaving fullscreen: the task now shows another Activity and no fresh landscape
                // request stands. system_server does not always publish the portrait request
                // when the landscape Activity finishes, so this returns the task on its own.
                val landscapeFresh = scene != null && scene.taskId == wideTaskId &&
                    scene.shouldPin(android.os.SystemClock.uptimeMillis())
                val known = wideComponent
                if (known == null || landscapeFresh) {
                    wideComponent = current.component
                } else if (current.component != known) {
                    NeXtepLog.info("wide_layout", "Wide slot left landscape ${known.className} -> ${current.component.className}")
                    returnWideToMain()
                    return
                }
            }
        }
        val main = tasks.firstOrNull { it.displayId == android.view.Display.DEFAULT_DISPLAY } ?: return
        if (scene != null && scene.time > seenSceneTime && scene.taskId == main.taskId &&
            wideSlotIndex == null && scene.fresh(android.os.SystemClock.uptimeMillis())) {
            val pin = scene.shouldPin(android.os.SystemClock.uptimeMillis())
            NeXtepLog.info("workbench_scene", "Scene task=${main.taskId} orientation=${scene.orientation} " +
                "fullscreen=${scene.fullscreen} pin=$pin")
            if (pin && pinTask(main.taskId)) seenSceneTime = scene.time
        } else if (scene != null && scene.time > seenSceneTime && scene.taskId != main.taskId) {
            NeXtepLog.info("workbench_scene", "Scene ignored task=${scene.taskId} main=${main.taskId} wide=$wideSlotIndex")
        }
    }

    private fun pinCurrentLandscape(): Boolean {
        if (wideSlotIndex != null) { returnWideToMain(); return true }
        val main = observedTasks.firstOrNull { it.displayId == android.view.Display.DEFAULT_DISPLAY } ?: return false
        val scene = SystemServerWorkspaceBridge.scene(context)
        if (scene?.taskId != main.taskId || !scene.shouldPin(android.os.SystemClock.uptimeMillis())) return false
        return pinTask(main.taskId)
    }

    private fun returnWideToMain() {
        val index = wideSlotIndex ?: return
        if (wideReturnPending) return
        val task = wideTaskId
        val generation = ++wideGeneration
        // Freeze the wide pane and the main app as they are; the apps trade places behind the
        // frames, which travel once both have drawn at their new size. A player leaving
        // fullscreen otherwise shows a blank page under its header for most of a second.
        val strip = slotRect(index)
        val mainFrom = mainRect()
        val mainShot = mainPreview()
        // The player has usually dropped its video by now; hold its last picture instead of black.
        val fresh = slotWindows[index].view.captureProtectedPreview()
        val stripShot = if (wideSnapshot != null && (fresh == null || fresh.isMostlyBlack())) {
            fresh?.recycle()
            wideSnapshot.also { wideSnapshot = null }
        } else fresh
        val frozen = strip != null && swapAnimator.freeze(listOf(
            WorkspaceSwapAnimator.Frame(stripShot, strip, strip, topFill = true),
            WorkspaceSwapAnimator.Frame(mainShot, mainFrom, mainFrom),
        ), listOf(strip, mainFrom), geometry().screenWidth, geometry().screenHeight)
        if (frozen) backdropHandler.postDelayed({
            if (generation == wideGeneration) swapAnimator.dismissFrozen()
        }, WIDE_REVEAL_FALLBACK_MS)
        wideReturnPending = true
        // Nothing on screen changes before the frozen frames cover it.
        swapAnimator.whenShown {
            wideReturnPending = false
            if (generation != wideGeneration || wideSlotIndex != index) return@whenShown
            setWideLayout(null, null)
            // The slot view is back in the rail but laid out only on the next traversal; swap
            // once its output layer is bound.
            slotWindows[index].whenOutputBound {
                val coordinator = slotCoordinator
                if (task == null || coordinator == null ||
                    (coordinator.slotStates().getOrNull(index) as? SlotState.Occupied)?.taskId != task) {
                    swapAnimator.dismissFrozen()
                    return@whenOutputBound
                }
                // The card that takes the main app back shows it until that app draws there.
                slotRect(index)?.let { swapAnimator.holdAlso(WorkspaceSwapAnimator.Frame(mainShot, it, it)) }
                var attempted = false
                coordinator.returnFromWide(index, task) { settled ->
                    attempted = true
                    if (settled.isFailure) swapAnimator.dismissFrozen()
                    else if (frozen) whenWideDrawn(index, generation) {
                        val card = slotRect(index)
                        // The strip's frame grows into the main pane showing the page as now laid out.
                        if (card == null) swapAnimator.dismissFrozen()
                        else swapAnimator.play(listOf(mainRect(), card), listOf(card, mainRect()),
                            replace = mapOf(0 to mainPreview()))
                    }
                }.onFailure {
                    swapAnimator.dismissFrozen()
                    // Turned away while another move settles: the tap path queues the exchange.
                    if (!attempted) coordinator.onSlotClicked(index)
                }
            }
        }
    }

    private fun pinTask(taskId: Int): Boolean {
        if (pinPending) return false
        val coordinator = slotCoordinator ?: return false
        val states = coordinator.slotStates()
        val index = wideSlotIndex ?: states.indexOfFirst { it is SlotState.Ready }.takeIf { it >= 0 }
            ?: states.indexOfFirst { it is SlotState.Occupied }.takeIf { it >= 0 } ?: return false
        val previousIndex = wideSlotIndex
        val previousTask = wideTaskId
        val generation = ++wideGeneration
        // Freeze the page that becomes the wide pane and the card whose app takes the main pane;
        // after the layout change they travel to their new places and hold there until both
        // apps have drawn at their new size.
        val mainFrom = mainRect()
        val cardFrom = slotRect(index).takeIf { previousIndex == null }
        // The top bar turns into the wide pane, black until its output arrives; its still covers that.
        val topBar = cardFrom?.let { topBarFrame() }
        val frozen = cardFrom != null && swapAnimator.freeze(listOfNotNull(
            topBar,
            WorkspaceSwapAnimator.Frame(mainPreview(), mainFrom, mainFrom, topFill = true),
            WorkspaceSwapAnimator.Frame(slotWindows[index].view.captureProtectedPreview(), cardFrom, cardFrom,
                empty = states.getOrNull(index) !is SlotState.Occupied),
        ), listOfNotNull(topBar?.from, mainFrom, cardFrom), geometry().screenWidth, geometry().screenHeight)
        var reveal: (() -> Unit)? = null
        var live = false
        fun revealWhenLive() {
            if (live) reveal?.let { reveal = null; it() }
        }
        pinPending = true
        // Nothing on screen changes before the frozen frames cover it.
        swapAnimator.whenShown {
            if (generation != wideGeneration) {
                pinPending = false
                return@whenShown
            }
            setWideLayout(index, taskId)
            pinPending = true
            if (frozen) afterNextLayout {
                val strip = slotRect(index)
                if (strip == null || generation != wideGeneration) {
                    swapAnimator.dismissFrozen()
                    return@afterNextLayout
                }
                val main = mainRect()
                swapAnimator.play(listOfNotNull(topBar?.from, strip, main), listOf(strip, main)) { done ->
                    reveal = done
                    revealWhenLive()
                }
            }
            backdropHandler.postDelayed({
                live = true
                revealWhenLive()
                if (generation == wideGeneration) swapAnimator.dismissFrozen()
            }, WIDE_REVEAL_FALLBACK_MS)
            // The slot's output layer moves into the top bar and is recreated there on the next
            // traversal. Park the task once the display feeds that layer, so the move never lands
            // on a sleeping display that still has the rail canvas.
            slotWindows[index].whenOutputBound {
                pinPending = false
                if (wideSlotIndex != index || wideTaskId != taskId) return@whenOutputBound
                val result = coordinator.parkMainForWide(taskId, index) { settled ->
                    if (settled.isSuccess) whenWideDrawn(index, generation) {
                        live = true
                        revealWhenLive()
                    } else swapAnimator.cancel()
                }
                result.onFailure {
                    NeXtepLog.warn("wide_layout", "Pin failed task=$taskId slot=$index", it)
                    swapAnimator.cancel()
                    setWideLayout(previousIndex, previousTask)
                }
            }
        }
        return true
    }

    private fun mainRect(): android.graphics.RectF = geometry().let {
        android.graphics.RectF(it.contentLeft.toFloat(), it.contentTop.toFloat(),
            it.contentRight.toFloat(), it.contentBottom.toFloat())
    }

    private fun slotRect(index: Int): android.graphics.RectF? = android.graphics.Rect()
        .takeIf { slotWindows.getOrNull(index)?.view?.getScreenVisibleRect(it) == true }
        ?.let { android.graphics.RectF(it) }

    /** The top bar as drawn now, at its place on screen. */
    private fun topBarFrame(): WorkspaceSwapAnimator.Frame? {
        val view = records.firstOrNull { it.title == "NeXtepTopBar" }?.view?.takeIf { it.width > 0 && it.height > 0 }
            ?: return null
        val bitmap = runCatching {
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                .also { view.draw(android.graphics.Canvas(it)) }
        }.getOrNull() ?: return null
        val at = IntArray(2).also(view::getLocationOnScreen)
        val rect = android.graphics.RectF(at[0].toFloat(), at[1].toFloat(),
            (at[0] + view.width).toFloat(), (at[1] + view.height).toFloat())
        return WorkspaceSwapAnimator.Frame(bitmap, rect, rect)
    }

    private fun mainPreview(): android.graphics.Bitmap? = mainTaskPresenter.currentTaskId()?.let {
        io.github.lujinxin.nextep.framework.TaskSurfaceCompat.capturePreview(it)
    }

    private fun afterNextLayout(action: () -> Unit) {
        val observer = topAppStrip?.viewTreeObserver?.takeIf { it.isAlive } ?: return action()
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                action()
                return true
            }
        })
    }

    /** Polls until the main pane's app and slot [index]'s app show frames laid out for their new size. */
    /**
     * Holds the main pane's last picture while a scene replaces its app: the new app's first frames
     * are a blank page (or, with the open animation, the wallpaper behind the old one).
     */
    private fun holdMainUntilReplaced(previous: Int?) {
        val rect = mainRect()
        if (!swapAnimator.freeze(listOf(WorkspaceSwapAnimator.Frame(mainPreview(), rect, rect)), listOf(rect),
                geometry().screenWidth, geometry().screenHeight)) return
        val generation = ++wideGeneration
        backdropHandler.postDelayed({ if (generation == wideGeneration) swapAnimator.dismissFrozen() }, WIDE_REVEAL_FALLBACK_MS)
        whenWideDrawn(null, generation, replacing = previous) { swapAnimator.fadeFrozen() }
    }

    /** Polls until the main pane's app (another than [replacing]) and slot [index]'s app show settled frames. */
    private fun whenWideDrawn(index: Int?, generation: Int, replacing: Int? = null, action: () -> Unit) {
        val deadline = android.os.SystemClock.uptimeMillis() + WIDE_DRAW_TIMEOUT_MS
        val slotCrop = index?.let { slotWindows.getOrNull(it) }?.geometry()?.let { android.graphics.Rect(0, 0, it.width, it.height) }
        var lastMain: IntArray? = null
        var mainStableSince = 0L
        // The main pane's page must also hold still: a stale buffer or a video surface left from
        // fullscreen has detail too, but it changes until the page has really laid out.
        fun mainSettled(task: Int?): Boolean {
            if (replacing != null && (task == null || task == replacing)) return false
            task ?: return true
            val grid = io.github.lujinxin.nextep.framework.TaskSurfaceCompat.laidOutLowerHalf(task) ?: return true
            val now = android.os.SystemClock.uptimeMillis()
            val previous = lastMain
            lastMain = grid
            if (grid.isEmpty() || previous == null ||
                !io.github.lujinxin.nextep.framework.sameFrame(previous, grid)) {
                mainStableSince = now
                return false
            }
            return now - mainStableSince >= WIDE_STABLE_MS
        }
        fun poll() {
            if (generation != wideGeneration || !workspaceVisible) return
            val main = mainTaskPresenter.currentTaskId()
            val slot = index?.let { (slotCoordinator?.slotStates()?.getOrNull(it) as? SlotState.Occupied)?.taskId }
            val drawn = mainSettled(main) && (slot == null ||
                io.github.lujinxin.nextep.framework.TaskSurfaceCompat.showsLaidOutFrame(slot, slotCrop) != false)
            if (drawn || android.os.SystemClock.uptimeMillis() >= deadline) {
                NeXtepLog.info("wide_layout", "Live frames ${if (drawn) "drawn" else "timed out"} slot=$index")
                // The slot's output moved between windows and was once left without a picture;
                // the frames still cover it while that is checked.
                val slot = index?.let { slotWindows.getOrNull(it) }
                if (slot == null) action()
                else slot.ensureOutputShowing { if (generation == wideGeneration) action() }
            } else backdropHandler.postDelayed({ poll() }, WIDE_DRAW_POLL_MS)
        }
        poll()
    }

    private fun setWideLayout(index: Int?, taskId: Int?) {
        if (wideSlotIndex == index && wideTaskId == taskId) return
        NeXtepLog.info("wide_layout", "Wide layout index=$index task=$taskId display=${index?.let { slotWindows.getOrNull(it)?.displayId() }}")
        if (index == null) pinPending = false
        wideComponent = null
        wideSlotIndex = index
        wideTaskId = taskId
        // Each pin starts at 16:9; the sampler narrows the slot once the picture shows its bars.
        viewport.setWideSlot(index)
        if (index == null) wideAspectSampler.stop() else wideAspectSampler.start()
        backdropHandler.removeCallbacks(wideSnapshotTick)
        wideSnapshot?.recycle()
        wideSnapshot = null
        if (index != null) backdropHandler.postDelayed(wideSnapshotTick, WIDE_SNAPSHOT_INTERVAL_MS)
        SystemServerWorkspaceBridge.publishWideDisplay(context, index?.let { slotWindows.getOrNull(it)?.displayId() })
        val geometry = geometry()
        wideLayoutChanging = true
        try { applyWindowGeometry(geometry) } finally { wideLayoutChanging = false }
        sidebarView?.let { applySlotLayout(it, geometry) }
    }

    private fun dp(value: Int): Float = value * context.resources.displayMetrics.density

    /** Translation that parks the rail just outside the display edge it hangs from. */
    private fun sidebarEdgeOffset(geometry: WorkspaceGeometry): Pair<Float, Float> =
        if (geometry.isLandscape) 0f to (if (geometry.isSidebarAtTop) -1f else 1f) * geometry.sidebarPhysicalHeight
        else (if (geometry.sidebarSide == SidebarSide.LEFT) -1f else 1f) * geometry.sidebarWidth to 0f

    private fun geometry(): WorkspaceGeometry = currentGeometry()


    private val transitionInterpolator = DecelerateInterpolator(1.6f)

    private companion object {
        /** The icon-launch plate: the tone of a blank window in light and dark mode. */
        private const val PLATE_LIGHT = 0xFFF5F6F8.toInt()
        private const val PLATE_DARK = 0xFF1E1F22.toInt()
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        const val EXPECTED_WINDOW_COUNT = 3
        const val ARRIVAL_ATTEMPTS = 8
        const val ARRIVAL_RETRY_MS = 40L
        const val BACKDROP_SETTLE_REFRESHES = 2
        const val BACKDROP_MAX_RETRIES = 8
        const val WORKSPACE_ENTER_DURATION_MS = 240L
        const val WORKSPACE_EXIT_DURATION_MS = 180L
        const val PARKED_WINDOW_RELEASE_MS = 60_000L
        /** A player leaving fullscreen took ~1.7 s to lay out its page on the test phone; past this the frames give way. */
        const val WIDE_DRAW_TIMEOUT_MS = 2_000L
        const val WIDE_DRAW_POLL_MS = 40L
        const val WIDE_STABLE_MS = 200L
        const val WIDE_SNAPSHOT_INTERVAL_MS = 1_000L
        const val WIDE_REVEAL_FALLBACK_MS = 4_000L
        /** Upper bound of the surface-recreating first frame that a cold entry waits out. */
        const val COLD_ENTRY_ALLOWANCE_MS = 250L
        const val APPEARANCE_SYSTEM = "SYSTEM"
        const val SIDEBAR_SWITCH_DURATION_MS = 220L
        const val ENTER_OFFSET_DP = 12
        const val EXIT_OFFSET_DP = 8
        const val SIDEBAR_ENTER_OFFSET_DP = 28
    }
}
