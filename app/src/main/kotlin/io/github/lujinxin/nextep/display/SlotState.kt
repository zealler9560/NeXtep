package io.github.lujinxin.nextep.display

import android.content.ComponentName

/** Slots visible in the rail at once; every card size and the geometry solve derive from this. */
const val WORKSPACE_VISIBLE_SLOT_COUNT = 4

/** Roster cap: the column scrolls, so at most this many slots exist (one is always a trailing empty). */
const val MAX_WORKSPACE_SLOTS = 12

/** The roster size a fresh layout starts with; kept as the historic name for the geometry solve. */
const val WORKSPACE_SLOT_COUNT = WORKSPACE_VISIBLE_SLOT_COUNT

sealed interface SlotState {
    data object Empty : SlotState

    data class Ready(val displayId: Int) : SlotState

    data class Occupied(
        val displayId: Int,
        val taskId: Int,
        val component: ComponentName,
    ) : SlotState

    data class Failed(val message: String) : SlotState
}
