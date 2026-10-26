package io.github.lujinxin.nextep.task

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry

class MainTaskPresentationCoordinator(context: Context) {
    private val applicationContext = context.applicationContext ?: context
    private val taskRepository = TaskRepository(applicationContext)
    private val surfacePresenter = SurfaceTaskPresenter()
    private var presentedTaskId: Int? = null
    private var activePresenter: MainTaskPresenter? = null
    var onHomePresented: (() -> Unit)? = null

    /** Whether the status bar is hidden right now; only the main app can hide it in the workspace. */
    var statusBarHidden: () -> Boolean = { false }

    /** An app hiding the status bar draws content at its top, so it is shown whole instead of cropped. */
    private fun mainGeometry(geometry: WorkspaceGeometry) = if (statusBarHidden()) geometry.uncropped() else geometry
    private var homePresented = false

    private class EntrySource(val packageName: String, val bounds: Rect, val since: Long, val until: Long)
    private var entrySource: EntrySource? = null

    /** Called once the task launched from an icon is in place, so the flying icon can fade away. */
    var onEntryPresented: (() -> Unit)? = null

    /** The next task of [packageName] to be presented was launched from [source], the icon the user tapped. */
    fun expectEntryFrom(packageName: String, source: Rect, geometry: WorkspaceGeometry) {
        val now = SystemClock.uptimeMillis()
        entrySource = EntrySource(packageName, Rect(source), now, now + ENTRY_SOURCE_TTL_MS)
        TaskSurfaceCompat.expectEntry(packageName, mainGeometry(geometry))
    }

    /** The source stays until a presentation succeeds: a cold task's first attempt may find no leash yet. */
    private fun peekEntrySource(packageName: String): EntrySource? {
        val entry = entrySource ?: return null
        if (SystemClock.uptimeMillis() > entry.until) { entrySource = null; return null }
        return entry.takeIf { it.packageName == packageName }
    }

    fun presentForeground(geometry: WorkspaceGeometry): Result<Unit> {
        val task = taskRepository.foregroundTask()
            ?: return Result.failure(IllegalStateException("No foreground Display 0 task"))
        return presentTask(task, geometry)
    }

    fun presentTask(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> {
        val task = taskRepository.findTask(taskId)
            ?: return Result.failure(IllegalStateException("Task $taskId is unavailable"))
        return presentTask(task, geometry)
    }

    private fun presentTask(
        task: TaskRepository.TaskSnapshot,
        geometry: WorkspaceGeometry,
    ): Result<Unit> {
        if (!isMainFullscreen(task)) {
            // Native freeform/PiP/split-screen surfaces belong to WM Shell, not our
            // fullscreen presentation. Do not replace their position or rotation matrix.
            return restoreForMove(task.taskId)
        }
        return presentTask(task, geometry, TriggerBroadcastContract.resolveHomePackage(applicationContext))
    }

    private fun presentTask(
        task: TaskRepository.TaskSnapshot,
        geometry: WorkspaceGeometry,
        homePackage: String?,
    ): Result<Unit> {
        if (task.component.packageName == homePackage) {
            NeXtepLog.info("main_task_presenter", "Foreground task is HOME; Launcher owns transform")
            notifyHomePresented()
            return Result.success(Unit)
        }
        if (task.component.packageName == TriggerBroadcastContract.SYSTEM_UI_PACKAGE) {
            return Result.failure(
                IllegalStateException("Unsafe foreground task ${task.component.packageName}"),
            )
        }

        val main = mainGeometry(geometry)
        pendingRestores -= task.taskId
        val entry = peekEntrySource(task.component.packageName)
        val source = entry?.bounds
        if (presentedTaskId == task.taskId && activePresenter === surfacePresenter) {
            return applyMainBounds(task, geometry).mapCatching { surfacePresenter.reapply(task.taskId, main).getOrThrow() }
        }
        if (entry != null && !TaskSurfaceCompat.hasObservedLeash(task.taskId) &&
            SystemClock.uptimeMillis() - entry.since < ENTRY_WAIT_MS
        ) {
            // Shell has not shown the task yet; its callback brings the next attempt. A lookup
            // through the organizer and a live task query now would only burn binder time.
            return Result.failure(IllegalStateException("Task ${task.taskId} has not appeared in Shell yet"))
        }

        // Fullscreen tasks inherit display bounds. Explicit screen-sized bounds survive
        // rotation and can leave a portrait Activity confined to the old landscape area.
        homePresented = false
        val fresh = entry != null && TaskSurfaceCompat.hasEntryPrepared(task.taskId)
        val surfaceResult = applyMainBounds(task, geometry, fresh).mapCatching {
            surfacePresenter.present(task.taskId, main, source).getOrThrow()
        }
        if (surfaceResult.isSuccess) {
            if (source != null) {
                entrySource = null
                onEntryPresented?.invoke()
            }
            val previousTaskId = presentedTaskId
            val previousPresenter = activePresenter
            presentedTaskId = task.taskId
            activePresenter = surfacePresenter
            if (previousTaskId != null && previousTaskId != task.taskId) {
                previousPresenter?.restore(previousTaskId)?.onFailure {
                    NeXtepLog.warn("main_task_presenter", "Previous task release failed taskId=$previousTaskId", it)
                }
            }
        }
        return surfaceResult
    }

    fun reconcileForeground(geometry: WorkspaceGeometry): Result<Unit> =
        reconcileForeground(geometry, taskRepository.runningTasks())

    fun reconcileForeground(
        geometry: WorkspaceGeometry,
        tasks: List<TaskRepository.TaskSnapshot>,
    ): Result<Unit> = runCatching {
        restorePending(tasks)
        val foreground = tasks.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
            ?: error("No foreground Display 0 task")
        val homePackage = TriggerBroadcastContract.resolveHomePackage(applicationContext)
        if (foreground.component.packageName == homePackage) {
            release(tasks).getOrThrow()
            notifyHomePresented()
            return@runCatching
        }
        if (foreground.component.packageName == TriggerBroadcastContract.SYSTEM_UI_PACKAGE) {
            return@runCatching
        }
        if (!isMainFullscreen(foreground)) {
            // Keep a different fullscreen task fitted behind the native floating window.
            // If the presented task itself became freeform, release our ownership.
            presentedTaskId?.let { taskId ->
                if (tasks.firstOrNull { it.taskId == taskId }?.let(::isMainFullscreen) != true) {
                    restorePresented().getOrThrow()
                }
            }
            return@runCatching
        }
        val current = presentedTaskId
        if (current != null && current != foreground.taskId) {
            release(tasks).getOrThrow()
        }
        if (presentedTaskId == foreground.taskId) {
            activePresenter?.reapply(foreground.taskId, mainGeometry(geometry))
                ?.getOrThrow()
                ?: error("Main presenter state is incomplete")
        } else {
            presentTask(foreground, geometry, homePackage).getOrThrow()
        }
    }

    /** The move that follows sets the task's bounds itself; clearing them first would lay the app out twice. */
    fun restoreForMove(taskId: Int): Result<Unit> {
        if (pendingRestores.remove(taskId)) return surfacePresenter.restore(taskId)
        if (presentedTaskId != taskId) return Result.success(Unit)
        return restorePresented(clearBounds = false)
    }

    fun currentTaskId(): Int? = presentedTaskId

    /** Leaving the workspace: every task this coordinator transformed goes back to full screen. */
    fun restoreForeground(animated: Boolean = false): Result<Unit> {
        pendingRestores.toList().forEach { taskId ->
            surfacePresenter.restore(taskId).onFailure {
                NeXtepLog.warn("main_task_presenter", "Deferred release failed taskId=$taskId", it)
            }
        }
        pendingRestores.clear()
        return restorePresented(animated)
    }

    /**
     * Tasks given up while still on screen. Going home or a quick switch hands them to the launcher's
     * animation; putting them back to full screen then showed the app at full size, cut by that
     * animation (seen on ColorOS 16). They go back once off screen.
     */
    private val pendingRestores = mutableSetOf<Int>()

    private fun release(tasks: List<TaskRepository.TaskSnapshot>): Result<Unit> {
        val taskId = presentedTaskId ?: return Result.success(Unit)
        if (shortenedTaskId == taskId || tasks.none { it.taskId == taskId && it.visible }) return restorePresented()
        pendingRestores += taskId
        presentedTaskId = null
        activePresenter = null
        NeXtepLog.info("main_task_presenter", "Release of taskId=$taskId waits until it leaves the screen")
        return Result.success(Unit)
    }

    private fun restorePending(tasks: List<TaskRepository.TaskSnapshot>) {
        pendingRestores.removeAll { taskId ->
            if (tasks.any { it.taskId == taskId && it.visible }) return@removeAll false
            surfacePresenter.restore(taskId)
                .onSuccess { NeXtepLog.info("main_task_presenter", "Released taskId=$taskId off screen") }
                .onFailure { NeXtepLog.warn("main_task_presenter", "Deferred release failed taskId=$taskId", it) }
            true
        }
    }

    private fun restorePresented(animated: Boolean = false, clearBounds: Boolean = true): Result<Unit> {
        val taskId = presentedTaskId ?: return Result.success(Unit)
        val presenter = activePresenter ?: return Result.success(Unit)
        return presenter.restore(taskId, animated).mapCatching {
            // The entry cleared bounds already; release must not start another layout change,
            // unless the app was laid out on the shortened wide-picture canvas.
            if (shortenedTaskId == taskId) {
                shortenedTaskId = null
                if (clearBounds) taskRepository.findTask(taskId)?.let { task ->
                    task.token?.let { token -> WindowContainerTransactionCompat.applyTaskBounds(token, Rect()) }
                        ?: ActivityTaskManagerCompat.resizeTask(taskId, Rect())
                }
            }
            if (presentedTaskId == taskId && activePresenter === presenter) {
                presentedTaskId = null
                activePresenter = null
            }
        }
    }

    private var shortenedTaskId: Int? = null

    /**
     * Under a wide picture the main app is laid out at the shortened canvas so it shares the rail
     * cards' aspect; otherwise any override is cleared as before.
     */
    private fun applyMainBounds(task: TaskRepository.TaskSnapshot, geometry: WorkspaceGeometry,
                                fresh: Boolean = false): Result<Unit> {
        val height = geometry.mainTaskHeight
        if (height <= 0) {
            if (shortenedTaskId == task.taskId) shortenedTaskId = null
            return clearFullscreenBounds(task, fresh)
        }
        clearedBoundsTaskId = null
        if (task.vendorWindowed || task.displayId != Display.DEFAULT_DISPLAY) return Result.success(Unit)
        val bounds = Rect(0, geometry.mainTaskTop, geometry.screenWidth, geometry.screenHeight)
        // A move into the main pane carries these bounds already.
        if (task.bounds == bounds) {
            shortenedTaskId = task.taskId
            return Result.success(Unit)
        }
        NeXtepLog.info("main_task_bounds", "Laying out taskId=${task.taskId} at $bounds for the wide picture")
        return (task.token?.let { WindowContainerTransactionCompat.applyTaskBounds(it, bounds) }
            ?: ActivityTaskManagerCompat.resizeTask(task.taskId, bounds)).onSuccess { shortenedTaskId = task.taskId }
    }

    private fun notifyHomePresented() {
        if (homePresented) return
        homePresented = true
        onHomePresented?.invoke()
    }

    /** The task whose override this presentation already cleared; clearing again only costs the app a relayout. */
    private var clearedBoundsTaskId: Int? = null

    private fun clearFullscreenBounds(
        task: TaskRepository.TaskSnapshot,
        fresh: Boolean = false,
    ): Result<Unit> {
        // The display-move owner already cleared the fullscreen override during
        // layout restoration. Do not request a second layout after the move.
        if (TaskSurfaceCompat.hasPreparedPromotion(task.taskId)) return Result.success(Unit)
        // A task that just appeared for an icon launch has no override; neither does one this
        // presentation cleared already. Either relayout would land on the app's first frames.
        if (fresh) { clearedBoundsTaskId = task.taskId; return Result.success(Unit) }
        if (clearedBoundsTaskId == task.taskId && presentedTaskId == task.taskId) return Result.success(Unit)
        val fullscreenMode = ActivityTaskManagerCompat
            .resolveWindowingMode("WINDOWING_MODE_FULLSCREEN")
            ?: WINDOWING_MODE_FULLSCREEN
        if (task.vendorWindowed || task.displayId != Display.DEFAULT_DISPLAY || task.windowingMode != fullscreenMode) {
            return Result.success(Unit)
        }

        NeXtepLog.info(
            "main_task_bounds",
            "Clearing fullscreen bounds override taskId=${task.taskId}",
        )
        // Empty bounds remove the override, including when the resolved bounds already
        // equal the display. Comparing resolved bounds cannot detect a stale override.
        return (task.token?.let { token ->
            WindowContainerTransactionCompat.applyTaskBounds(token, Rect())
        } ?: ActivityTaskManagerCompat.resizeTask(task.taskId, Rect())).onSuccess { clearedBoundsTaskId = task.taskId }
    }

    private companion object {
        const val WINDOWING_MODE_FULLSCREEN = 1
        /** How long an icon launch waits for Shell's task-appeared callback before looking the task up itself. */
        const val ENTRY_WAIT_MS = 1_500L
        /** Covers the launch polling; an icon tap older than this no longer explains a new task. */
        const val ENTRY_SOURCE_TTL_MS = 3_000L
    }

    private fun isMainFullscreen(task: TaskRepository.TaskSnapshot): Boolean =
        !task.vendorWindowed && task.displayId == Display.DEFAULT_DISPLAY &&
            task.windowingMode == WINDOWING_MODE_FULLSCREEN

}
