package io.github.lujinxin.nextep.workspace

import io.github.lujinxin.nextep.display.MAX_WORKSPACE_SLOTS
import io.github.lujinxin.nextep.display.SlotGeometry
import io.github.lujinxin.nextep.display.WORKSPACE_VISIBLE_SLOT_COUNT

/** One recorded layout shared by panels, main-task fitting and all preview displays. */
data class WorkspaceLayoutProfile(
    val geometry: WorkspaceGeometry,
    val densityDpi: Int,
    val gapPixels: Int,
    val wideSlotIndex: Int? = null,
    /** Width/height of the wide slot's picture; 16:9 fills the width for any common video. */
    val wideAspect: Double = DEFAULT_WIDE_ASPECT,
    /** The measured status bar height less the kept gap; not recorded, a record is checked against it on reading. */
    val statusBar: Int = 0,
    /** How many slots the roster holds; only [WORKSPACE_VISIBLE_SLOT_COUNT] are on screen at once,
     * the rest continue below the rail's window and are reached by scrolling the column. */
    val slotCount: Int = WORKSPACE_VISIBLE_SLOT_COUNT,
    /** Blank part retained inside panes; HyperOS uses zero so icons cannot leak into a card. */
    val statusBarGap: Int = (WorkspaceControlMetrics.STATUS_KEEP_DP * densityDpi / 160f).toInt(),
) {
    /** The whole status bar: the inset a pane's display provides so apps lay out as on the phone's. */
    val statusBarHeight: Int get() = if (statusBar == 0) 0 else
        statusBar + statusBarGap

    data class Slot(val width: Int, val height: Int, val top: Int)
    data class Source(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
    data class PreviewTransform(val scaleX: Float, val scaleY: Float, val x: Float, val y: Float)

    init {
        require(densityDpi > 0 && gapPixels > 0)
        require(statusBarGap >= 0)
        require(geometry.sidebarWidth > gapPixels)
        require(slotCount in WORKSPACE_VISIBLE_SLOT_COUNT..MAX_WORKSPACE_SLOTS)
        require(wideSlotIndex == null || wideSlotIndex in 0 until slotCount)
        require(wideAspect in MIN_WIDE_ASPECT..MAX_WIDE_ASPECT)
    }

    /** Between rail slots; [gapPixels] separates the rail from the main window. */
    val slotGapPixels: Int get() = (WorkspaceControlMetrics.SLOT_GAP_DP * densityDpi / 160f).toInt().coerceAtLeast(1)

    private val leftGeometry = geometry.copy(sidebarSide = SidebarSide.LEFT)
    private val rightGeometry = geometry.copy(sidebarSide = SidebarSide.RIGHT)
    /** A pane's display is as tall as the main app's window (the status band included), so an app keeps
     * its window size when it changes pane; a pane shows it from [slotCrop] down, as the main window does. */
    val canvas = SlotGeometry.forRecordedDisplay(geometry.availableWidth,
        geometry.availableHeight + geometry.statusInset, densityDpi)
    // Apps lay out directly at the recorded usable resolution. Fit that complete
    // buffer into a slot; do not crop navigation pixels off an already laid-out app.
    val source = Source(0, 0, canvas.width, canvas.height)

    /** Rows of a pane's display left out above its card, when its app keeps them blank (see TaskSwitcherView). */
    val slotCrop: Int get() = geometry.statusInset

    /** The candidate crop for pane [index]: none under a wide picture, where cards take their own aspect. */
    fun slotCrop(index: Int): Int = if (wideSlotIndex == null) slotCrop else 0
    // Under a wide picture in portrait the rail cards take the main app's canvas (see canvas): the
    // shared solve fills the rail edge to edge, and a capped canvas leaves a little slack instead;
    // in landscape they fill the rail at their own aspect.
    val slots: List<Slot> = if (wideSlotIndex == null) rosterSlotBounds(slotCount, WORKSPACE_VISIBLE_SLOT_COUNT) else {
        val rail = rosterSlotBounds(slotCount - 1, WORKSPACE_VISIBLE_SLOT_COUNT - 1,
            fit = geometry.mainCanvasHeight > 0).iterator()
        List(slotCount) { index ->
            if (index == wideSlotIndex) Slot(minOf(geometry.availableWidth, geometry.availableHeight),
                wideHeight(), 0) else rail.next()
        }
    }

    /** How far the rail column can scroll: the roster's lowest card bottom past the rail's window. */
    val maxScrollOffset: Int get() {
        val rail = if (wideSlotIndex == null) slots else slots.filterIndexed { index, _ -> index != wideSlotIndex }
        return ((rail.maxOfOrNull { it.top + it.height } ?: 0) - geometry.sidebarLogicalHeight).coerceAtLeast(0)
    }

    // Under a wide picture the status bar/camera strip stays inside the control strip, free; the video
    // lives below it like the controls.
    private val wideStatus: Int get() = (WorkspaceControlMetrics.STATUS_HEIGHT_DP * densityDpi / 160f).toInt()

    private fun wideHeight(): Int = (geometry.topHeight - wideStatus).coerceAtLeast(1)

    fun canvas(index: Int): SlotGeometry = when {
        index == wideSlotIndex -> SlotGeometry(canvas.width, (canvas.width.toDouble() * wideHeight() /
            minOf(geometry.availableWidth, geometry.availableHeight)).toInt().coerceAtLeast(1), densityDpi)
        // A rail card under a wide picture: the main app's canvas in portrait, so a swap keeps the
        // layout; in landscape the card's own aspect, full canvas width.
        wideSlotIndex != null && geometry.mainCanvasHeight > 0 -> SlotGeometry(canvas.width, geometry.mainCanvasHeight, densityDpi)
        wideSlotIndex != null -> slots[index].let { slot ->
            SlotGeometry(canvas.width, (canvas.width.toDouble() * slot.height / slot.width).toInt().coerceAtLeast(1), densityDpi)
        }
        else -> canvas
    }

    fun source(index: Int): Source = canvas(index).let { Source(0, 0, it.width, it.height) }

    /** Session choice derived from the recorded profile; never persisted. */
    fun withControlAtBottom(bottom: Boolean): WorkspaceLayoutProfile {
        if (bottom == geometry.controlAtBottom) return this
        return create(geometry.screenWidth, geometry.screenHeight, densityDpi, geometry.navigationInsets,
            geometry.displayRotation, bottom, statusBar, statusBarGap).withWideSlot(wideSlotIndex, wideAspect)
            .copy(slotCount = slotCount)
    }

    fun withWideSlot(index: Int?, aspect: Double = DEFAULT_WIDE_ASPECT): WorkspaceLayoutProfile {
        if (index == null && wideSlotIndex == null) return this
        val normal = create(geometry.screenWidth, geometry.screenHeight, densityDpi,
            geometry.navigationInsets, geometry.displayRotation, geometry.controlAtBottom, statusBar, statusBarGap)
        if (index == null) return normal.copy(slotCount = slotCount)
        // A picture at the top keeps the status strip inside the control strip (wideStatus), and the
        // main app lays out at its own canvas there: nothing is cropped.
        val base = if (geometry.controlAtBottom) normal else create(geometry.screenWidth, geometry.screenHeight,
            densityDpi, geometry.navigationInsets, geometry.displayRotation).copy(statusBar = statusBar, statusBarGap = statusBarGap)
        val clamped = aspect.coerceIn(MIN_WIDE_ASPECT, MAX_WIDE_ASPECT)
        val g = base.geometry
        val short = minOf(g.availableWidth, g.availableHeight)
        val long = maxOf(g.availableWidth, g.availableHeight)
        // The picture spans the full width at its aspect. Below it the main window and the n rail
        // cards share one aspect so an app keeps its layout when it swaps between them:
        // main = R·A, card = (R − (n−1)g)/n · A, main + card + g = short  ⇒  A = n(short − g)/((n+1)R − (n−1)g).
        val top = (wideStatus + short / clamped).toInt().coerceIn(1, (long * .70).toInt())
        if (g.isLandscape) {
            // Landscape keeps the proportional rail; the shared-aspect solve is a portrait layout.
            val rail = WorkspaceGeometry.proportionalRail(short, long, top).coerceAtLeast(gapPixels + 1)
            return base.copy(geometry = g.copy(topHeight = top, sidebarWidth = rail), wideSlotIndex = index, wideAspect = clamped)
                .copy(slotCount = slotCount)
        }
        val remaining = long - top
        // The shared-aspect solve fits the main window beside the VISIBLE rail cards; a longer
        // roster scrolls through the same rail and does not re-solve it.
        val cards = WORKSPACE_VISIBLE_SLOT_COUNT - 1
        val shared = cards * (short - gapPixels).toDouble() / ((cards + 1.0) * remaining - (cards - 1) * slotGapPixels)
        val main = (remaining * shared).toInt().coerceIn(1, short - gapPixels - 1)
        val rail = (short - main).coerceAtLeast(gapPixels + 1)
        return base.copy(geometry = g.copy(topHeight = top, sidebarWidth = rail,
            mainCanvasHeight = wideMainCanvasHeight(g.copy(topHeight = top, sidebarWidth = rail))),
            wideSlotIndex = index, wideAspect = clamped).copy(slotCount = slotCount)
    }

    /**
     * Bounds for a roster of [total] slots whose first [visible] fill the rail exactly as
     * [slotBounds] lays them out today; the rest continue below the rail's window at the same
     * pitch and card size, to be scrolled into view. Card size never depends on [total].
     */
    private fun rosterSlotBounds(total: Int, visible: Int, fit: Boolean = true): List<Slot> {
        val head = slotBounds(visible, fit)
        if (total <= visible) return head.take(total)
        // The last visible cell is always a full-height one (the remainder rows come first),
        // so the column continues at its pitch with the last card's size and centering.
        val base = (geometry.sidebarLogicalHeight - slotGapPixels * (visible - 1))
            .coerceAtLeast(visible) / visible
        val pitch = base + slotGapPixels
        val last = head.last()
        return head + (visible until total).map { index ->
            Slot(last.width, last.height, last.top + (index - (visible - 1)) * pitch)
        }
    }

    private fun slotBounds(count: Int, fit: Boolean = true): List<Slot> {
        val height = (geometry.sidebarLogicalHeight - slotGapPixels * (count - 1))
            .coerceAtLeast(count)
        val base = height / count
        val remainder = height % count
        return List(count) { index ->
            val cellHeight = base + if (index < remainder) 1 else 0
            val cellWidth = geometry.sidebarWidth - gapPixels
            val top = index * (base + slotGapPixels) + minOf(index, remainder)
            if (!fit) Slot(cellWidth, cellHeight, top) else {
                // Cards take the aspect of what they show: the display below its crop, or under a wide
                // picture the main app's canvas.
                val visible = if (geometry.mainCanvasHeight > 0) geometry.mainCanvasHeight else canvas.height - slotCrop
                val scale = minOf(cellWidth.toDouble() / canvas.width, cellHeight.toDouble() / visible)
                val height = (visible * scale).toInt().coerceAtLeast(1)
                Slot((canvas.width * scale).toInt().coerceAtLeast(1), height, top + (cellHeight - height) / 2)
            }
        }
    }

    /** The recorded profile is position-neutral; the side is a session choice. */
    fun geometry(side: SidebarSide): WorkspaceGeometry =
        if (side == SidebarSide.LEFT) leftGeometry else rightGeometry

    /** Undo TextureView's default stretching and fit the complete recorded canvas.
     * Slot bounds have the same aspect ratio apart from integer-pixel rounding. */
    fun previewTransform(width: Int, height: Int): PreviewTransform {
        require(width > 0 && height > 0)
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        return PreviewTransform(canvas.width * scale / width, canvas.height * scale / height,
            (width - source.width * scale) / 2f - source.left * scale,
            (height - source.height * scale) / 2f - source.top * scale)
    }

    fun encode(): String = (listOf(if (wideSlotIndex == null) NORMAL_VERSION else WIDE_VERSION, slotCount, geometry.screenWidth, geometry.screenHeight,
        geometry.topHeight, geometry.sidebarWidth, densityDpi, gapPixels,
        geometry.navigationInsets.left, geometry.navigationInsets.top,
        geometry.navigationInsets.right, geometry.navigationInsets.bottom,
        geometry.contentWidth, geometry.contentHeight, geometry.contentScale,
        canvas.width, canvas.height,
        source.left, source.top, source.right, source.bottom,
        *slots.flatMap { listOf(it.width, it.height, it.top) }.toTypedArray(),
        geometry.displayRotation) + if (wideSlotIndex == null) emptyList() else listOf(wideSlotIndex)).joinToString(",")

    companion object {
        /** The main canvas height that fills the main window at the width-fit scale, at most the screen's:
         * a taller canvas cannot be laid out on the display, and the pane displays must match it. */
        internal fun wideMainCanvasHeight(g: WorkspaceGeometry): Int =
            (g.contentHeight.toDouble() * g.availableWidth / g.contentWidth).toInt().coerceIn(1, g.availableHeight)

        const val DEFAULT_WIDE_ASPECT = 16.0 / 9
        const val MIN_WIDE_ASPECT = 16.0 / 9
        const val MAX_WIDE_ASPECT = 2.4

        fun create(width: Int, height: Int, densityDpi: Int, insets: WorkspaceNavigationInsets,
            displayRotation: Int = 0, controlAtBottom: Boolean = false, statusBar: Int = 0,
            statusBarGap: Int = (WorkspaceControlMetrics.STATUS_KEEP_DP * densityDpi / 160f).toInt()) =
            WorkspaceLayoutProfile(WorkspaceGeometry.forDisplay(width, height, density = densityDpi / 160f,
                navigationInsets = insets, displayRotation = displayRotation, controlAtBottom = controlAtBottom,
                statusInset = statusBar),
                densityDpi, (WorkspaceControlMetrics.RAIL_GAP_DP * densityDpi / 160f).toInt().coerceAtLeast(1),
                statusBar = statusBar, statusBarGap = statusBarGap)

        /** [statusBar] is today's measured height: a record solved for another one (or none) is rejected. */
        fun decode(encoded: String?, statusBar: Int = 0, statusBarGap: Int? = null): WorkspaceLayoutProfile? = runCatching {
            val p = encoded?.split(',') ?: return@runCatching null
            val wide = p.firstOrNull() == WIDE_VERSION
            if (!wide && p.firstOrNull() != NORMAL_VERSION) return@runCatching null
            // The record carries its roster size; the field count follows from it.
            val count = p.getOrNull(1)?.toIntOrNull() ?: return@runCatching null
            if (count !in WORKSPACE_VISIBLE_SLOT_COUNT..MAX_WORKSPACE_SLOTS ||
                p.size != 22 + count * 3 + if (wide) 1 else 0) return@runCatching null
            val portrait = p[3].toInt() > p[2].toInt()
            val geometry = WorkspaceGeometry(p[2].toInt(), p[3].toInt(),
                p[4].toInt(), p[5].toInt(), SidebarSide.RIGHT,
                WorkspaceNavigationInsets(p[8].toInt(), p[9].toInt(), p[10].toInt(), p[11].toInt()),
                displayRotation = p[21 + count * 3].toInt(),
                // The band a portrait record was solved below; a wide record keeps it in its strip.
                statusInset = if (portrait && !wide) statusBar else 0)
            // The main canvas is not recorded: it follows from the rail, and the cards are solved on it.
            val profile = WorkspaceLayoutProfile(if (wide && portrait) geometry.copy(mainCanvasHeight = wideMainCanvasHeight(geometry)) else geometry,
                p[6].toInt(), p[7].toInt(), if (wide) p.last().toInt() else null, statusBar = statusBar,
                slotCount = count,
                statusBarGap = statusBarGap ?: (WorkspaceControlMetrics.STATUS_KEEP_DP * p[6].toInt() / 160f).toInt())
            if (profile.encode() != encoded) return@runCatching null
            profile
        }.getOrNull()

        // The version names the record format: 18/19 carry the roster slot count after the
        // version field. A record of another layout (any older one) is measured again.
        private const val NORMAL_VERSION = "18"
        private const val WIDE_VERSION = "19"

    }
}
