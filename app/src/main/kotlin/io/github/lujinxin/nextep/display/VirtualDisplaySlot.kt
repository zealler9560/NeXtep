package io.github.lujinxin.nextep.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.view.Surface
import android.view.SurfaceHolder
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.framework.UserTargetedActivityLauncher
import io.github.lujinxin.nextep.workspace.WorkspaceLayoutProfile

class VirtualDisplaySlot(
    context: Context,
    private val index: Int,
) : SurfaceHolder.Callback {
    private companion object {
        const val OUTPUT_BOUND_TIMEOUT_MS = 600L
        /** Two frames for a reconnected output to show the app. */
        const val OUTPUT_REBIND_SETTLE_MS = 40L
        const val PARKED_DISPLAY_RELEASE_MS = 60_000L
        const val STALE_FRAME_SPREAD = 24
        /** Above the output's own buffer layer inside the SurfaceView's container. */
        const val MIRROR_LAYER = 1_000
        val creator: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "NeXtep-VirtualDisplay") }
    }
    interface Listener {
        fun onDisplayReady(slotIndex: Int, displayId: Int)
        fun onDisplayReleased(slotIndex: Int, displayId: Int?, reason: String)
        fun onDisplayFailed(slotIndex: Int, message: String)
    }

    val view = TaskSwitcherView(context, index)

    private val applicationContext = context.applicationContext ?: context
    private val factory = VirtualDisplayFactory(applicationContext)
    private val statusInsets = SlotStatusBarInsets(applicationContext, index)
    private var active = false
    private var suspended = false
    private var surface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var activeGeometry: SlotGeometry? = null
    private var layoutProfile: WorkspaceLayoutProfile? = null
    private var pendingLaunch: Intent? = null
    private var surfaceGeneration = 1
    private var listener: Listener? = null
    private var creating = false
    private var creation = 0
    private var outputBoundAction: (() -> Unit)? = null
    private var boundWidth = 0
    private var boundHeight = 0
    private val outputBoundFallback = Runnable {
        NeXtepLog.warn("virtual_display", "Output not bound in time slot=$index display=${displayId()}")
        runOutputBound()
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val inputManager by lazy { applicationContext.getSystemService(android.hardware.input.InputManager::class.java) }
    private val injectInput by lazy { inputManager.javaClass.getMethod("injectInputEvent",
        android.view.InputEvent::class.java, Int::class.javaPrimitiveType) }
    private val inputDisplay by lazy { android.view.InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }
    private val input = SlotTouchForwarder { event ->
        val id = displayId()
        if (!active || suspended || id == null || id <= 0) false else runCatching {
            inputDisplay.invoke(event, id)
            injectInput.invoke(inputManager, event, 0) == true
        }.onFailure { NeXtepLog.warn("wide_input", "Player input unavailable", it) }.getOrDefault(false)
    }

    fun setInteractive(value: Boolean) { view.setContentInput(if (value) input else null) }

    /** One BACK key press delivered to this slot's own display, not the main one. */
    fun sendBack(): Boolean {
        val id = displayId()
        if (!active || suspended || id == null || id <= 0) return false
        return runCatching {
            val now = android.os.SystemClock.uptimeMillis()
            listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP).all { action ->
                val key = android.view.KeyEvent(
                    now, now, action, android.view.KeyEvent.KEYCODE_BACK, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, android.view.KeyEvent.FLAG_FROM_SYSTEM,
                    android.view.InputDevice.SOURCE_KEYBOARD,
                )
                inputDisplay.invoke(key, id)
                injectInput.invoke(inputManager, key, 0) == true
            }
        }.onFailure { NeXtepLog.warn("wide_back", "Back key unavailable", it) }.getOrDefault(false)
    }

    private var paused = false
    private var pausedAt = 0L
    /**
     * A screen wake lock on the pane's own display group, held while an output is connected. The
     * display sits in its own group with its own screen timeout. On HyperOS a display reused after
     * leaving the workbench came back off (state 1) with the lock held, so acquiring it also wakes the group.
     */
    private var wake: android.os.PowerManager.WakeLock? = null

    private fun keepAwake(on: Boolean) {
        wake?.let { held -> runCatching { if (held.isHeld) held.release() } }
        wake = null
        if (!on) return
        val displayId = virtualDisplay?.display?.displayId ?: return
        wake = runCatching {
            val power = checkNotNull(applicationContext.getSystemService(android.os.PowerManager::class.java))
            // Hidden overload: a wake lock without a display id only keeps the phone's own group awake.
            val lock = power.javaClass.getMethod("newWakeLock", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                .invoke(power, android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "NeXtep:slot$index", displayId) as android.os.PowerManager.WakeLock
            lock.setReferenceCounted(false)
            lock.acquire()
            lock
        }.onFailure { NeXtepLog.warn("virtual_display", "Display wake lock failed slot=$index displayId=$displayId", it) }.getOrNull()
    }

    fun isPaused(): Boolean = paused

    fun refreshRate(): Float? = virtualDisplay?.display?.refreshRate

    /**
     * Pauses the app: without an output the display turns off, and the system stops its activity
     * as it would a backgrounded app. The card keeps [cover] and the pause badge meanwhile.
     */
    fun pause(cover: android.graphics.Bitmap?): Boolean {
        val display = virtualDisplay
        // Something is about to move into this pane and waits for its output.
        if (paused || !active || suspended || parked || display == null || outputBoundAction != null) return false
        view.holdCover(cover)
        runCatching { display.setSurface(null) }.onFailure {
            view.releaseCover()
            NeXtepLog.warn("slot_pause", "Pause failed slot=$index", it)
            return false
        }
        releasePlaceholder()
        surface = null
        keepAwake(false)
        detachMirror()
        paused = true
        pausedAt = android.os.SystemClock.uptimeMillis()
        NeXtepLog.info("slot_pause", "Paused slot=$index displayId=${display.display.displayId} " +
            "displayState=${display.display.state} cover=${cover?.let { "${it.width}x${it.height}" }}")
        return true
    }

    /** Connects the output again; the app resumes and its frames replace the held cover. */
    fun resume(reason: String) {
        if (!paused) return
        paused = false
        val display = virtualDisplay
        val output = view.textureView.holder.surface.takeIf { it.isValid }
        if (display != null && active && !suspended && output != null) {
            runCatching { display.setSurface(output) }.onSuccess {
                surface = output
                boundWidth = view.textureView.holder.surfaceFrame.width()
                boundHeight = view.textureView.holder.surfaceFrame.height()
                keepAwake(true)
            }.onFailure { NeXtepLog.warn("slot_pause", "Output reconnect failed slot=$index", it) }
        }
        view.releaseCover()
        if (mirroredTask != null) attachMirror()
        NeXtepLog.info("slot_pause", "Resumed slot=$index reason=$reason " +
            "pausedMs=${android.os.SystemClock.uptimeMillis() - pausedAt} displayState=${display?.display?.state}")
        if (surface != null) runOutputBound()
    }

    init {
        view.textureView.holder.addCallback(this)
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /** A parked display (hidden workspace) is not offered: to callers the slot has no display. */
    fun displayId(): Int? = if (parked) null else virtualDisplay?.display?.displayId

    /** This pane's app, display and output for the diagnostics export; [taskId] is the occupant the coordinator expects. */
    fun describe(taskId: Int?): String = SlotDiagnostics.describe(applicationContext, index, virtualDisplay?.display, taskId,
        view.textureView, "active=$active parked=$parked suspended=$suspended paused=$paused awake=${wake?.isHeld} " +
            "mirror=${mirroredTask?.let { "$it:${mirror?.isValid == true}" }}")

    /** Task whose layers the pane shows over its output, and the copy that shows them. */
    private var mirroredTask: Int? = null
    private var mirror: android.view.SurfaceControl? = null

    fun mirrorsTask(taskId: Int): Boolean = mirroredTask == taskId && mirror?.isValid == true

    /**
     * Shows [taskId]'s own layers in the pane, over the output. On HyperOS a pane display often
     * composes no frame at all: the app drew, WM showed it on that display, the display was on, and
     * the output still never received a buffer, on three of four displays. A mirror of the task's
     * layer is composed with the pane itself and needs nothing from the display.
     */
    fun mirrorTask(taskId: Int): Boolean {
        mirroredTask = taskId
        return attachMirror()
    }

    fun clearMirror() {
        mirroredTask = null
        detachMirror()
    }

    private fun attachMirror(): Boolean {
        val taskId = mirroredTask ?: return false
        detachMirror()
        if (paused) return false
        val output = view.textureView
        val parent = output.surfaceControl?.takeIf { it.isValid } ?: return false
        val leash = TaskSurfaceCompat.taskLeash(taskId)?.takeIf { it.isValid } ?: return false
        return runCatching {
            val copy = android.view.SurfaceControl::class.java
                .getMethod("mirrorSurface", android.view.SurfaceControl::class.java)
                .invoke(null, leash) as android.view.SurfaceControl
            // The output layer already scales its display-sized buffer to the view, children included:
            // the mirror sits in display coordinates. Its own scale shrank it twice, into a corner.
            android.view.SurfaceControl.Transaction().reparent(copy, parent).setLayer(copy, MIRROR_LAYER)
                .setPosition(copy, 0f, 0f).setVisibility(copy, true).apply()
            mirror = copy
            NeXtepLog.info("slot_mirror", "Mirrored slot=$index taskId=$taskId")
            true
        }.onFailure { NeXtepLog.warn("slot_mirror", "Mirror failed slot=$index taskId=$taskId", it) }.getOrDefault(false)
    }

    private fun detachMirror() {
        val copy = mirror ?: return
        mirror = null
        runCatching {
            android.view.SurfaceControl.Transaction().reparent(copy, null).apply()
            copy.release()
        }
    }

    /**
     * What the user sees in the pane: the window's layers cut to the pane. The output layer's own
     * capture ignores its scale and showed a mirror shrunk into the corner as filling the pane.
     */
    fun captureShown(): String {
        val output = view.textureView
        return runCatching {
            val root = output.rootSurfaceControl ?: return "no window"
            val window = root.javaClass.getMethod("getSurfaceControl").invoke(root) as android.view.SurfaceControl
            val at = IntArray(2).also(output::getLocationInSurface)
            TaskSurfaceCompat.captureLayers(window, android.graphics.Rect(at[0], at[1], at[0] + output.width, at[1] + output.height), 0.25f)
                ?.let(SlotDiagnostics::summarize) ?: "none"
        }.getOrElse { "capture failed $it" }
    }

    fun geometry(): SlotGeometry? = activeGeometry

    fun setLayoutProfile(profile: WorkspaceLayoutProfile) {
        if (layoutProfile == profile) return
        layoutProfile = profile
        statusInsets.sync(virtualDisplay?.display, profile.statusBarHeight)
        updateDisplay(view.textureView.holder)
    }

    fun sourceBounds(): WorkspaceLayoutProfile.Source = checkNotNull(layoutProfile).source(index)

    /**
     * Runs [action] once the current output layer feeds the display, immediately when it already
     * does. A reparented SurfaceView is recreated in its new window on the next traversal; moving a
     * task before that lands it on a sleeping display. The action still runs after a timeout.
     */
    fun whenOutputBound(action: () -> Unit) {
        resume("output requested")
        mainHandler.removeCallbacks(outputBoundFallback)
        outputBoundAction = null
        val current = view.textureView.holder.surface
        if (virtualDisplay != null && surface === current && current.isValid) {
            action()
            return
        }
        outputBoundAction = action
        mainHandler.postDelayed(outputBoundFallback, OUTPUT_BOUND_TIMEOUT_MS)
    }

    /**
     * Connects the display to the current output layer again. After a move between windows the
     * display was once left on the old layer's queue (display on, nothing drawn) until the next
     * move; callers run this while something still covers the slot.
     */
    /**
     * Runs [done] once the output layer shows the display: when it holds no buffer at all, the
     * display is connected again first. A reconnect drops the last frame, so a paused or loading
     * app would show black until it draws again; a layer that already has a picture is left alone.
     */
    fun ensureOutputShowing(done: (rebound: Boolean) -> Unit) {
        val output = view.textureView
        if (virtualDisplay == null || !active || suspended || paused || !output.holder.surface.isValid) return done(false)
        val probe = android.graphics.Bitmap.createBitmap(16, 9, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(output, probe, { result ->
                probe.recycle()
                if (result == android.view.PixelCopy.ERROR_SOURCE_NO_DATA) {
                    NeXtepLog.info("slot_output", "No frame slot=$index displayState=${virtualDisplay?.display?.state} awake=${wake?.isHeld}")
                    rebindOutput()
                    mainHandler.postDelayed({ done(true) }, OUTPUT_REBIND_SETTLE_MS)
                } else done(false)
            }, mainHandler)
        }.onFailure {
            probe.recycle()
            done(false)
        }
    }

    /**
     * An emptied pane: its output can keep the last app's frame, which on ColorOS 16 showed faintly
     * through the frosted card. A reconnect drops it. The output is probed before and after, for the log.
     */
    fun dropStaleFrame() {
        if (virtualDisplay == null || !active || suspended || paused) return
        probeOutput { before ->
            if (!before.startsWith("content")) {
                NeXtepLog.info("slot_output", "Emptied slot=$index before=$before alpha=${view.textureView.alpha}")
                return@probeOutput
            }
            // Reconnecting the output kept the frame on ColorOS 16 (an empty display draws nothing new).
            view.hideOutputIfEmpty()
            NeXtepLog.info("slot_output", "Emptied slot=$index before=$before hid layer alpha=${view.textureView.alpha}")
        }
    }

    private fun probeOutput(report: (String) -> Unit) {
        val output = view.textureView
        if (!output.holder.surface.isValid) return report("no surface")
        val probe = android.graphics.Bitmap.createBitmap(9, 16, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(output, probe, { result ->
                val summary = if (result != android.view.PixelCopy.SUCCESS) "result=$result" else {
                    val pixels = IntArray(probe.width * probe.height).also { probe.getPixels(it, 0, probe.width, 0, 0, probe.width, probe.height) }
                    val luma = pixels.map { (android.graphics.Color.red(it) * 3 + android.graphics.Color.green(it) * 6 + android.graphics.Color.blue(it)) / 10 }
                    val spread = luma.max() - luma.min()
                    // A flat (black or single-colour) frame is no picture of an app.
                    "${if (spread > STALE_FRAME_SPREAD) "content" else "flat"} mean=${luma.average().toInt()} spread=$spread"
                }
                probe.recycle()
                report(summary)
            }, mainHandler)
        }.onFailure {
            probe.recycle()
            report("copy failed")
        }
    }

    private fun rebindOutput() {
        val display = virtualDisplay ?: return
        if (!active || suspended || paused) return
        val holder = view.textureView.holder
        val output = holder.surface.takeIf { it.isValid } ?: return
        runCatching {
            // Through the placeholder, not null: the display must not turn off in between.
            display.setSurface(placeholderOutput())
            display.setSurface(output)
            releasePlaceholder()
        }.onFailure { NeXtepLog.warn("virtual_display", "Output rebind failed slot=$index", it) }.onSuccess {
            surface = output
            boundWidth = holder.surfaceFrame.width()
            boundHeight = holder.surfaceFrame.height()
            NeXtepLog.info("virtual_display", "Rebound output slot=$index size=${boundWidth}x$boundHeight")
        }
    }

    private fun runOutputBound() {
        mainHandler.removeCallbacks(outputBoundFallback)
        val action = outputBoundAction ?: return
        outputBoundAction = null
        action()
    }

    fun isSuspended(): Boolean = suspended

    fun activate() {
        active = true
        val reused = parked
        parked = false
        mainHandler.removeCallbacks(releaseParked)
        updateDisplay(view.textureView.holder)
        // A parked display is ready as soon as its output is bound again; no new display is created.
        if (reused) virtualDisplay?.display?.displayId?.let { displayId ->
            whenOutputBound {
                if (active && virtualDisplay?.display?.displayId == displayId) {
                    keepAwake(true)
                    listener?.onDisplayReady(index, displayId)
                }
            }
        }
    }

    fun deactivate() {
        clearMirror()
        input.cancel()
        active = false
        suspended = false
        pendingLaunch = null
        // Parking below turns the display off anyway; the next entry starts every pane live.
        paused = false
        view.releaseCover()
        val display = virtualDisplay
        if (display == null || creating) {
            releaseDisplay("workspace hidden")
            return
        }
        // Keep the display, off and without output, for a quick re-entry: creating four displays
        // on entry stalled SystemUI's main thread on DisplayManager's lock during the slide-in.
        // The tasks already left it; a minute later it is released as before.
        mainHandler.removeCallbacks(outputBoundFallback)
        outputBoundAction = null
        runCatching { display.setSurface(null) }
        releasePlaceholder()
        surface = null
        keepAwake(false)
        parked = true
        view.showWaiting()
        mainHandler.postDelayed(releaseParked, PARKED_DISPLAY_RELEASE_MS)
        NeXtepLog.info("virtual_display", "Parked slot=$index displayId=${display.display.displayId}")
        listener?.onDisplayReleased(index, display.display.displayId, "workspace hidden")
    }

    private var parked = false
    private val releaseParked = Runnable {
        if (active || !parked) return@Runnable
        parked = false
        releaseDisplay("parked display expired")
    }

    fun setSuspended(value: Boolean) {
        if (value) {
            input.cancel()
            suspended = true
            creation++
            creating = false
            // A null Surface turns the display off without removing its tasks/windows.
            // Keep our Surface alive so unlock can reconnect the same display.
            runCatching { virtualDisplay?.setSurface(null) }.onFailure {
                NeXtepLog.warn("virtual_display", "Unable to suspend output slot=$index", it)
            }
            releasePlaceholder()
            keepAwake(false)
            return
        }
        if (!suspended) return
        // A paused pane stays off after unlock.
        if (!paused) virtualDisplay?.let { display ->
            val currentSurface = view.textureView.holder.surface.takeIf { it.isValid } ?: return
            surface = currentSurface
            check(currentSurface.isValid) { "Slot $index output Surface is invalid" }
            display.setSurface(currentSurface)
            keepAwake(true)
        }
        suspended = false
        updateDisplay(view.textureView.holder)
        virtualDisplay?.display?.displayId?.let { displayId ->
            pendingLaunch?.let { intent ->
                pendingLaunch = null
                launchNow(intent, displayId)
            }
        }
    }

    fun launch(intent: Intent): Result<Unit> {
        // An app launched onto a display that is off would stay stopped.
        resume("launch")
        val displayId = virtualDisplay?.display?.displayId
        if (suspended || displayId == null) {
            pendingLaunch = Intent(intent)
            view.showWaiting()
            return Result.success(Unit)
        }
        return launchNow(intent, displayId)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        NeXtepLog.info("virtual_display", "Surface created slot=$index display=${displayId()}")
        updateDisplay(holder, rebind = true)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NeXtepLog.info("virtual_display", "Surface changed slot=$index display=${displayId()} size=${width}x$height " +
            "bound=${boundWidth}x$boundHeight")
        // The holder keeps its Java Surface across a resize but replaces the producer; a change
        // at the bound size is the first callback after creation and must not reconnect the
        // display's queue a second time (that dropped the queued frames: NO_BUFFER_AVAILABLE).
        updateDisplay(holder, rebind = width != boundWidth || height != boundHeight)
        if (mirroredTask != null) mainHandler.post { if (mirroredTask != null) attachMirror() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // A lock, rotation or temporary reparent may replace the output layer.
        // Keep its tasks alive on the same display until the new layer arrives.
        NeXtepLog.info("virtual_display", "Surface destroyed slot=$index display=${displayId()}")
        detachMirror()
        val display = virtualDisplay
        // Without an output the display turns off; turned off and on again within a frame or two
        // (the wide pane's move into the top bar), the system once kept its tasks hidden. A
        // placeholder keeps it on until the new layer arrives.
        val placeholder = display?.takeIf { active && !suspended && !paused }?.let { placeholderOutput() }
        runCatching { display?.setSurface(placeholder) }.onFailure {
            NeXtepLog.warn("virtual_display", "Placeholder output failed slot=$index", it)
            runCatching { display?.setSurface(null) }
            releasePlaceholder()
        }
        surface = null
    }

    private var placeholder: android.media.ImageReader? = null

    /** An output that drops every frame, sized like the display. */
    private fun placeholderOutput(): Surface? = runCatching {
        releasePlaceholder()
        val geometry = activeGeometry ?: layoutProfile?.canvas(index) ?: return null
        android.media.ImageReader.newInstance(geometry.width, geometry.height, android.graphics.PixelFormat.RGBA_8888, 2)
            .also { reader ->
                reader.setOnImageAvailableListener({ runCatching { it.acquireLatestImage()?.close() } }, mainHandler)
                placeholder = reader
            }.surface
    }.onFailure { NeXtepLog.warn("virtual_display", "Placeholder unavailable slot=$index", it) }.getOrNull()

    private fun releasePlaceholder() {
        placeholder?.let { reader -> runCatching { reader.close() } }
        placeholder = null
    }

    private fun updateDisplay(holder: SurfaceHolder, rebind: Boolean = false) {
        val profile = layoutProfile ?: return
        val geometry = profile.canvas(index)
        view.setOutputSize(geometry.width, geometry.height, profile.slotCrop(index))
        holder.setFixedSize(geometry.width, geometry.height)
        if (!active || suspended) return
        val output = holder.surface.takeIf { it.isValid }
        val display = virtualDisplay
        if (display == null) {
            if (output != null) createDisplay(output, geometry)
            return
        }
        // A paused pane keeps its display off; resume() connects the current output.
        if (output != null && !paused && (rebind || surface !== output)) {
            display.setSurface(output)
            releasePlaceholder()
            surface = output
            boundWidth = holder.surfaceFrame.width()
            boundHeight = holder.surfaceFrame.height()
            runOutputBound()
        }
        if (geometry == activeGeometry) {
            return
        }
        // The display resizes without an output layer; a task parked while the
        // layer is being recreated then lays out at the new canvas, not the old one.
        activeGeometry = geometry
        runCatching {
            display.resize(geometry.width, geometry.height, geometry.densityDpi)
        }.onSuccess {
            NeXtepLog.info(
                "virtual_display",
                "Resized slot=$index generation=$surfaceGeneration geometry=$geometry",
            )
        }.onFailure { error ->
            NeXtepLog.error(
                "virtual_display",
                "Resize/rebind failed slot=$index generation=$surfaceGeneration",
                error,
            )
            releaseDisplay("resize/rebind failed")
            if (output != null) createDisplay(output, geometry)
        }
    }

    private fun createDisplay(createdSurface: Surface, geometry: SlotGeometry) {
        if (!active || suspended || virtualDisplay != null || creating || !createdSurface.isValid) return
        activeGeometry = geometry
        creating = true
        val token = ++creation
        // createVirtualDisplay is a ~100ms binder round trip per slot. Off the UI thread, the
        // entry animation and the main task keep moving while the four displays appear.
        creator.execute {
            val result = factory.create(index, geometry, createdSurface)
            mainHandler.post {
                if (token != creation) { result.getOrNull()?.release(); return@post }
                creating = false
                if (!active || suspended || virtualDisplay != null) { result.getOrNull()?.release(); return@post }
                if (!createdSurface.isValid) {
                    // The holder replaced its Surface meanwhile; retry against the current one.
                    result.getOrNull()?.release()
                    updateDisplay(view.textureView.holder, rebind = true)
                    return@post
                }
                result.onSuccess { display ->
                    surface = createdSurface
                    boundWidth = view.textureView.holder.surfaceFrame.width()
                    boundHeight = view.textureView.holder.surfaceFrame.height()
                    virtualDisplay = display
                    keepAwake(true)
                    statusInsets.sync(display.display, layoutProfile?.statusBarHeight ?: 0)
                    listener?.onDisplayReady(index, display.display.displayId)
                    NeXtepLog.info(
                        "virtual_display",
                        "Created slot=$index generation=$surfaceGeneration " +
                            "displayId=${display.display.displayId} geometry=$geometry",
                    )
                    runOutputBound()
                    pendingLaunch?.let { intent ->
                        pendingLaunch = null
                        launchNow(intent, display.display.displayId)
                    }
                }.onFailure {
                    view.showWaiting()
                    listener?.onDisplayFailed(index, it.message ?: "display unavailable")
                }
            }
        }
    }

    private fun launchNow(intent: Intent, displayId: Int): Result<Unit> = runCatching {
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(displayId) }
        UserTargetedActivityLauncher.start(
            applicationContext,
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            options.toBundle(),
        ).getOrThrow()
        NeXtepLog.info("virtual_display", "Launched slot=$index displayId=$displayId action=${intent.action}")
    }.onFailure { error ->
        view.showWaiting()
        NeXtepLog.error("virtual_display", "Launch failed slot=$index displayId=$displayId", error)
    }

    private fun releaseDisplay(reason: String) {
        // Any display still being created for the old state is released on arrival.
        creation++
        creating = false
        mainHandler.removeCallbacks(outputBoundFallback)
        outputBoundAction = null
        val releasedDisplayId = virtualDisplay?.display?.displayId
        paused = false
        view.releaseCover()
        runCatching { virtualDisplay?.setSurface(null) }
        releasePlaceholder()
        keepAwake(false)
        clearMirror()
        statusInsets.detach()
        virtualDisplay?.release()
        virtualDisplay = null
        activeGeometry = null
        // SurfaceHolder owns the output; releasing it here would invalidate a
        // newly attached layer during a workspace hide/show transition.
        surface = null
        view.showWaiting()
        NeXtepLog.info(
            "virtual_display",
            "Released slot=$index generation=$surfaceGeneration reason=$reason",
        )
        listener?.onDisplayReleased(index, releasedDisplayId, reason)
    }
}
