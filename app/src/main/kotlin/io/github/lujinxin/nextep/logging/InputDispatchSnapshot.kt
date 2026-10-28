package io.github.lujinxin.nextep.logging

/** Keep routing/geometry only. Never export device identifiers, window titles or event contents. */
internal object InputDispatchSnapshot {
    private val packageName = Regex("\\b(?:com|org|net|io)\\.[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val number = "[-+0-9.eE]+"
    private val fields = Regex("(?:^|, )(id|displayId|ownerPid|ownerUid|alpha|globalScale|dispatchingTimeout)=($number(?:ms)?)(?=,|$)")
    private val flags = Regex("(?:^|, )(inputConfig|touchOcclusionMode)=([A-Z_0-9 |]+)(?=,|$)")
    private val rects = Regex("(?:^|, )(frame|touchableRegion)=((?:\\[-?[0-9]+,-?[0-9]+\\])+(?:\\|(?:\\[-?[0-9]+,-?[0-9]+\\])+)*|<empty>)(?=,|$)")
    private val matrix = Regex("\\s*$number\\s+$number\\s+$number\\s*")

    fun packages(dump: String): Set<String> = dump.lineSequence().filter { it.contains(": name=") }
        .map { it.substringAfter("name=").substringBefore(", id=") }
        .mapNotNull { packageName.find(it)?.value?.substringBefore('/') }.take(32).toSet()

    private fun identity(name: String): String = "w=${name.hashCode().toUInt().toString(16)}" +
        (packageName.find(name)?.value?.let { " pkg=$it" } ?: "") +
        (name.substringAfter(' ', name).takeIf { it in setOf("StatusBar", "NotificationShade", "ControlCenter",
            "NeXtepTopBar", "NeXtepSidebar", "NeXtepContentPanel") }?.let { " role=$it" } ?: "") +
        (name.takeIf { it in setOf("[Gesture Monitor] NeXtep-bottom-corners", "[Gesture Monitor] NeXtep-wide-back",
            "[Gesture Monitor] OplusExInputReceiver1", "[Gesture Monitor] swipe-up", "[Gesture Monitor] edge-swipe") }
            ?.let { " monitor=${it.substringAfter("] ")}" } ?: "")

    fun sanitize(dump: String): String {
        val result = mutableListOf<String>()
        var dispatcher = false
        var section = ""
        var matrixRows = 0
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line == "Input Dispatcher State:") {
                if (dispatcher) break // Some OEMs repeat the entire dump.
                dispatcher = true
            }
            if (!dispatcher) continue
            when {
                line.startsWith("FocusedWindows:") || line.startsWith("TouchStatesByDisplay:") -> {
                    section = line.substringBefore(':')
                    result += section + if (line.contains("<no displays touched>")) ": none" else ":"
                }
                raw.startsWith("  ") && !raw.startsWith("    ") && line.endsWith(':') -> section = ""
            }
            when {
                Regex("(?:DispatchEnabled|DispatchFrozen|InputFilterEnabled): (true|false)").matches(line) ||
                    Regex("(?:FocusedDisplayId:|Display:) [0-9]+").matches(line) ||
                    Regex("logicalSize=[0-9]+x[0-9]+").matches(line) -> result += line
                line.contains(": name=") && line.contains(", id=") -> {
                    val name = line.substringAfter("name=").substringBefore(", id=")
                    val values = (fields.findAll(line) + flags.findAll(line) + rects.findAll(line))
                        .map { "${it.groupValues[1]}=${it.groupValues[2]}" }.joinToString(" ")
                    result += "window ${identity(name)} $values"
                    matrixRows = 0
                }
                Regex("transform \\([A-Z_0-9]+\\)(?: \\([A-Z_0-9| ]+\\))?").matches(line) -> {
                    result += line
                    matrixRows = 3
                }
                matrixRows > 0 && matrix.matches(raw) -> { result += line; matrixRows-- }
                section in setOf("FocusedWindows", "TouchStatesByDisplay") && line.contains("name=") -> {
                    val name = line.substringAfter("name=").substringBefore(", ").trim('\'')
                    val targetFlags = Regex("(?:targetFlags|flags)=([A-Z_0-9 |]+)").find(line)?.groupValues?.get(1).orEmpty()
                    result += "$section ${identity(name)} flags=$targetFlags"
                }
                section == "TouchStatesByDisplay" && Regex("(?:displayId|deviceId|source|down|split)=[0-9a-zA-Z, =]+").matches(line) ->
                    result += line
                section == "TouchStatesByDisplay" && Regex("LogicalDisplayId [0-9]+:").matches(line) -> result += line
                section == "TouchStatesByDisplay" && line.startsWith("DeviceId(") -> {
                    Regex("pilferingPointerIds=(<none>|[01]+)").find(line)?.let { result += it.value }
                }
            }
            if (result.size >= 300) { result += "truncated"; break }
        }
        return if (!dispatcher) "Input dispatcher unavailable (permission or ROM format)" else result.joinToString("\n").take(24_000)
    }
}
