package io.github.lujinxin.nextep.display

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/** Whether a side pane's app may pause now and, if not, why it stays live. */
class SlotPauseRules(
    context: Context,
    private val chosen: (String) -> Boolean,
    private val wideSlot: () -> Int?,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)

    fun blocker(index: Int, packageName: String): String? {
        if (!chosen(packageName)) return NOT_CHOSEN
        return blocker(true, index == wideSlot(), inCall(), playing(packageName))
    }

    /** Why a pane scrolled out of the rail's window stays live; the chosen-apps list does not
     * apply (nothing is on screen to watch), media, calls and the wide pane still keep it live. */
    fun offscreenBlocker(index: Int, packageName: String): String? =
        blocker(true, index == wideSlot(), inCall(), playing(packageName))

    // A call keeps every pane live: the mode does not say which app holds it.
    private fun inCall(): Boolean = audio?.mode.let { it != null && it != AudioManager.MODE_NORMAL && it != AudioManager.MODE_RINGTONE }

    private fun playing(packageName: String): Boolean = runCatching {
        sessions?.getActiveSessions(null).orEmpty().any {
            it.packageName == packageName && it.playbackState?.state in PLAYING
        }
    }.getOrDefault(false)

    companion object {
        const val NOT_CHOSEN = "not chosen"
        private val PLAYING = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)

        /** null: pause; otherwise why the pane stays live. */
        fun blocker(chosen: Boolean, wide: Boolean, inCall: Boolean, playing: Boolean): String? = when {
            !chosen -> NOT_CHOSEN
            wide -> "wide"
            inCall -> "call"
            playing -> "media"
            else -> null
        }
    }
}
