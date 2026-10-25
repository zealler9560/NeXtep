package io.github.lujinxin.nextep.workspace

import io.github.lujinxin.nextep.display.SlotGeometry
import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT

/** One recorded layout shared by panels, main-task fitting and all preview displays. */
data class WorkspaceLayoutProfile(
    val geometry: WorkspaceGeometry,
    val densityDpi: Int,
    val gapPixels: Int,
    val wideSlotIndex: Int? = null,
    /** Width/height of the wide slot's picture; 16:9 fills the width for any common video. */
    val wideAspect: Double = DEFAULT_WIDE_ASPECT,
    /** The measured status bar height; not recorded, a record is checked against it on reading. */
    val statusBar: Int = 0,
) {
    data class Slot(val width: Int, val height: Int, val top: Int)
    data class Source(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
    data class PreviewTransform(val scaleX: Float, val scaleY: Float, val x: Float, val y: Float)

    init {
        require(densityDpi > 0 && gapPixels > 0)
        require(geometry.sidebarWidth > gapPixels)
        require(wideSlotIndex == null || wideSlotIndex in 0 until WORKSPACE_SLOT_COUNT)
        require(wideAspect in MIN_WIDE_ASPECT..MAX_WIDE_ASPECT)
    }

    /** Between rail slots; [gapPixels] separates the rail from the main window. */
    val slotGapPixels: Int get() = (WorkspaceControlMetrics.SLOT_GAP_DP * densityDpi / 160f).toInt().coerceAtLeast(1)

    private val leftGeometry = geometry.copy(sidebarSide = SidebarSide.LEFT)
    private val rightGeometry = geometry.copy(sidebarSide = SidebarSide.RIGHT)
    val canvas = SlotGeometry.forRecordedDisplay(geometry.availableWidth, geometry.availableHeight, densityDpi)
    // Apps lay out directly at the recorded usable resolution. Fit that complete
    // buffer into a slot; do not crop navigation pixels off an already laid-out app.
    val source = Source(0, 0, canvas.width, canvas.height)
    // Under a wide picture the rail cards fill the rail edge to edge; their displays take the
    // card's own aspect (see canvas) instead of leaving slack beside the main window.
    val slots: List<Slot> = if (wideSlotIndex == null) slotBounds(WORKSPACE_SLOT_COUNT) else {
        val rail = slotBounds(WORKSPACE_SLOT_COUNT - 1, fit = false).iterator()
        List(WORKSPACE_SLOT_COUNT) { index ->
            if (index == wideSlotIndex) Slot(minOf(geometry.availableWidth, geometry.availableHeight),
                wideHeight(), 0) else rail.next()
        }
    }

    // Under a wide picture the status bar/camera strip stays inside the control strip, free; the video
    // lives below it like the controls.
    private val wideStatus: Int get() = (WorkspaceControlMetrics.STATUS_HEIGHT_DP * densityDpi / 160f).toInt()

    private fun wideHeight(): Int = (geometry.topHeight - wideStatus).coerceAtLeast(1)

    fun canvas(index: Int): SlotGeometry = when {
        index == wideSlotIndex -> SlotGeometry(canvas.width, (canvas.width.toDouble() * wideHeight() /
            minOf(geometry.availableWidth, geometry.availableHeight)).toInt().coerceAtLeast(1), densityDpi)
        // A rail card under a wide picture: the app lays out at the card's aspect, full canvas width.
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
            geometry.displayRotation, bottom, statusBar).withWideSlot(wideSlotIndex, wideAspect)
    }

    fun withWideSlot(index: Int?, aspect: Double = DEFAULT_WIDE_ASPECT): WorkspaceLayoutProfile {
        if (index == null && wideSlotIndex == null) return this
        val normal = create(geometry.screenWidth, geometry.screenHeight, densityDpi,
            geometry.navigationInsets, geometry.displayRotation, geometry.controlAtBottom, statusBar)
        if (index == null) return normal
        // A picture at the top keeps the status strip inside the control strip (wideStatus), and the
        // main app lays out at its own canvas there: nothing is cropped.
        val base = if (geometry.controlAtBottom) normal else create(geometry.screenWidth, geometry.screenHeight,
            densityDpi, geometry.navigationInsets, geometry.displayRotation).copy(statusBar = statusBar)
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
        }
        val remaining = long - top
        val cards = WORKSPACE_SLOT_COUNT - 1
        val shared = cards * (short - gapPixels).toDouble() / ((cards + 1.0) * remaining - (cards - 1) * slotGapPixels)
        val main = (remaining * shared).toInt().coerceIn(1, short - gapPixels - 1)
        val rail = (short - main).coerceAtLeast(gapPixels + 1)
        return base.copy(geometry = g.copy(topHeight = top, sidebarWidth = rail,
            mainCanvasHeight = wideMainCanvasHeight(g.copy(topHeight = top, sidebarWidth = rail))),
            wideSlotIndex = index, wideAspect = clamped)
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
                val scale = minOf(cellWidth.toDouble() / canvas.width, cellHeight.toDouble() / canvas.height)
                val height = (canvas.height * scale).toInt().coerceAtLeast(1)
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

    fun encode(): String = (listOf(if (wideSlotIndex == null) NORMAL_VERSION else WIDE_VERSION, geometry.screenWidth, geometry.screenHeight,
        geometry.topHeight, geometry.sidebarWidth, densityDpi, gapPixels,
        geometry.navigationInsets.left, geometry.navigationInsets.top,
        geometry.navigationInsets.right, geometry.navigationInsets.bottom,
        geometry.contentWidth, geometry.contentHeight, geometry.contentScale,
        canvas.width, canvas.height,
        source.left, source.top, source.right, source.bottom,
        *slots.flatMap { listOf(it.width, it.height, it.top) }.toTypedArray(),
        geometry.displayRotation) + if (wideSlotIndex == null) emptyList() else listOf(wideSlotIndex)).joinToString(",")

    companion object {
        /** The main canvas height that fills the main window at the width-fit scale. */
        internal fun wideMainCanvasHeight(g: WorkspaceGeometry): Int =
            (g.contentHeight.toDouble() * g.availableWidth / g.contentWidth).toInt().coerceAtLeast(1)

        const val DEFAULT_WIDE_ASPECT = 16.0 / 9
        const val MIN_WIDE_ASPECT = 16.0 / 9
        const val MAX_WIDE_ASPECT = 2.4

        fun create(width: Int, height: Int, densityDpi: Int, insets: WorkspaceNavigationInsets,
            displayRotation: Int = 0, controlAtBottom: Boolean = false, statusBar: Int = 0) =
            WorkspaceLayoutProfile(WorkspaceGeometry.forDisplay(width, height, density = densityDpi / 160f,
                navigationInsets = insets, displayRotation = displayRotation, controlAtBottom = controlAtBottom,
                statusInset = statusBar),
                densityDpi, (WorkspaceControlMetrics.RAIL_GAP_DP * densityDpi / 160f).toInt().coerceAtLeast(1),
                statusBar = statusBar)

        /** [statusBar] is today's measured height: a record solved for another one (or none) is rejected. */
        fun decode(encoded: String?, statusBar: Int = 0): WorkspaceLayoutProfile? = runCatching {
            val p = encoded?.split(',') ?: return@runCatching null
            val wide = p.firstOrNull() == WIDE_VERSION
            if (!wide && p.firstOrNull() != NORMAL_VERSION ||
                p.size != 21 + WORKSPACE_SLOT_COUNT * 3 + if (wide) 1 else 0) return@runCatching null
            val portrait = p[2].toInt() > p[1].toInt()
            val profile = WorkspaceLayoutProfile(WorkspaceGeometry(p[1].toInt(), p[2].toInt(),
                p[3].toInt(), p[4].toInt(), SidebarSide.RIGHT,
                WorkspaceNavigationInsets(p[7].toInt(), p[8].toInt(), p[9].toInt(), p[10].toInt()),
                displayRotation = p[20 + WORKSPACE_SLOT_COUNT * 3].toInt(),
                // The band a portrait record was solved below; a wide record keeps it in its strip.
                statusInset = if (portrait && !wide) statusBar else 0,
                bottomGap = WorkspaceGeometry.gestureGap(portrait, false,
                    if (wide) 0 else statusBar, p[10].toInt(), p[5].toInt() / 160f)),
                p[5].toInt(), p[6].toInt(), if (wide) p.last().toInt() else null, statusBar = statusBar)
            if (profile.encode() != encoded) return@runCatching null
            if (!wide) profile else profile.copy(geometry = profile.geometry.copy(mainCanvasHeight =
                if (profile.geometry.isLandscape) 0 else wideMainCanvasHeight(profile.geometry)))
        }.getOrNull()

        // The version names the slot count: a record of another layout (any older one) is measured again.
        private const val NORMAL_VERSION = "14"
        private const val WIDE_VERSION = "15"

    }
}
