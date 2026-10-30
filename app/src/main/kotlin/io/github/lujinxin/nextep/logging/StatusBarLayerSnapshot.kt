package io.github.lujinxin.nextep.logging

/** SurfaceFlinger tree ancestry and numeric geometry only; arbitrary layer titles never leave memory. */
internal object StatusBarLayerSnapshot {
    private data class Node(val id: Long, val parent: Long?, val role: String, val relative: Boolean,
                            val flags: String, val alpha: String)
    private val number = "[-+0-9.eE]+"
    private val geometry = Regex("(bounds|toDisplayTransform|geomCrop|geomLayerBounds)=([\\[{][0-9., +eEtxya=\\-]*[\\]}])")

    fun sanitize(dump: String): String {
        val nodes = linkedMapOf<Long, Node>()
        val stack = mutableListOf<Pair<Int, Long>>()
        var tree = false
        for (line in dump.lineSequence()) {
            if (line.trim() == "Layer Hierarchy") { tree = true; continue }
            if (!tree) continue
            if (line.isNotEmpty() && !line.first().isWhitespace()) break
            val branch = listOf(line.indexOf("├─"), line.indexOf("└─")).filter { it >= 0 }.minOrNull() ?: continue
            val body = line.substring(branch + 2).trim()
            val id = Regex("#([0-9]+)").findAll(body).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: continue
            while (stack.isNotEmpty() && stack.last().first >= branch) stack.removeAt(stack.lastIndex)
            val relative = body.startsWith("(Relative)")
            val explicitParent = Regex("\\bparent=([0-9]+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
            val role = when {
                Regex("\\bStatusBar(?:#|\\b)").containsMatchIn(body) -> "StatusBar"
                Regex("\\bTask=[0-9]+#").containsMatchIn(body) -> Regex("Task=[0-9]+").find(body)!!.value
                body.contains("type=2000 ") -> "StatusBarToken"
                body.contains("DefaultTaskDisplayArea#") -> "TaskDisplayArea"
                else -> "layer"
            }
            nodes[id] = Node(id, explicitParent ?: stack.lastOrNull()?.second, role, relative,
                Regex("\\bflags:([0-9]+)").find(body)?.groupValues?.get(1) ?: "?",
                Regex("\\ba:($number)").find(body)?.groupValues?.get(1) ?: "?")
            stack += branch to id
            if (nodes.size >= 4096) break
        }
        val selected = linkedSetOf<Long>()
        for (bar in nodes.values.filter { it.role == "StatusBar" }.take(8)) {
            var node: Node? = bar
            val visited = mutableSetOf<Long>()
            repeat(24) {
                val current = node ?: return@repeat
                if (!visited.add(current.id)) { node = null; return@repeat }
                selected += current.id
                node = nodes[current.parent]
            }
        }
        if (selected.isEmpty()) return "status bar layer ancestry unavailable (permission or ROM format)"
        val details = mutableMapOf<Long, MutableList<String>>()
        var layer: Long? = null
        var rows = 0
        for (line in dump.lineSequence()) {
            Regex("^\\s*Layer \\[([0-9]+)] ").find(line)?.let {
                layer = it.groupValues[1].toLong(); rows = 0
            }
            val id = layer?.takeIf { it in selected } ?: continue
            val target = details.getOrPut(id) { mutableListOf() }
            if (target.size >= 10) continue
            if (line.trim().startsWith("invisible reason=")) target += "invisible=true"
            geometry.findAll(line).forEach { target += it.value }
            if (line.contains("geomLayerTransform")) rows = 3
            else if (rows > 0 && Regex("\\s*$number\\s+$number\\s+$number\\s*").matches(line)) {
                target += "matrix=${line.trim()}"; rows--
            }
        }
        return selected.joinToString("\n") { id ->
            val node = nodes.getValue(id)
            "layer=$id parent=${node.parent} role=${node.role} relative=${node.relative} " +
                "flags=${node.flags} alpha=${node.alpha} ${details[id].orEmpty().joinToString(" ")}"
        }.take(24_000)
    }
}
