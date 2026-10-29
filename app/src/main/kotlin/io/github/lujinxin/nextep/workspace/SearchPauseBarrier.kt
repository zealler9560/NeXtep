package io.github.lujinxin.nextep.workspace

/** A search page existing is not proof its results have drawn. Unknown/blank output stays live. */
internal class SearchPauseBarrier {
    private data class Pending(val since: Long, var previous: IntArray? = null, var stableSince: Long = 0,
                               var ready: Boolean = false)
    private val pending = mutableMapOf<Int, Pending>()
    fun protect(taskId: Int, now: Long) { pending[taskId] = Pending(now) }
    fun contains(taskId: Int): Boolean = taskId in pending
    fun shouldSample(taskId: Int, now: Long): Boolean = pending[taskId]?.let { now - it.since < 60_000 } == true
    fun remove(taskId: Int) { pending.remove(taskId) }
    fun clear() { pending.clear() }

    fun observe(taskId: Int, grid: IntArray?, now: Long) {
        val state = pending[taskId] ?: return
        // Ignore toolbar/footer rows; a rendered header over an empty result body is still loading.
        val body = grid?.takeIf { it.size == 9 * 16 }?.copyOfRange(9 * 3, 9 * 14)
        val hasContent = body != null && (0 until 11).count { row ->
            val pixels = body.sliceArray(row * 9 until (row + 1) * 9)
            pixels.max() - pixels.min() >= 24
        } >= 4
        if (!hasContent) {
            state.previous = null
            state.ready = false
            state.stableSince = now
            return
        }
        val previous = state.previous
        // shortcut: visual stability is a heuristic, not a network completion callback. Unknown pages stay live;
        // use app-specific completion signals if a client can provide them.
        val changed = previous == null || body!!.indices.sumOf { kotlin.math.abs(body[it] - previous[it]) } / body.size >= 3
        if (changed) state.stableSince = now
        state.previous = body
        state.ready = now - state.since >= 10_000 && now - state.stableSince >= 3_000
    }

    /** Release the entire batch together, only after all still-present destinations are ready. */
    fun releaseIfReady(): Set<Int> {
        if (pending.isEmpty() || pending.values.any { !it.ready }) return emptySet()
        return pending.keys.toSet().also { pending.clear() }
    }
}
