package com.verdenroz.corvid.core.auth

private val privateFlags = setOf("--private-window", "--incognito", "-private-window")
private val fieldCode = Regex("%[a-zA-Z]")

/** The argv that opens [url] through the private-window action of a `.desktop` file, or null if it has none. */
fun privateWindowCommand(
    desktopFile: String,
    url: String,
): List<String>? {
    var inAction = false
    for (line in desktopFile.lineSequence().map { it.trim() }) {
        if (line.startsWith("[")) inAction = line.startsWith("[Desktop Action ")
        if (!inAction || !line.startsWith("Exec=")) continue
        val argv = splitExec(line.removePrefix("Exec="))
        if (argv.any { it in privateFlags }) return substituteFieldCodes(argv, url)
    }
    return null
}

private fun splitExec(exec: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var started = false
    var i = 0
    while (i < exec.length) {
        val c = exec[i]
        when {
            c == '"' -> {
                quoted = !quoted
                started = true
            }
            c == '\\' && quoted && i + 1 < exec.length -> current.append(exec[++i])
            c.isWhitespace() && !quoted -> {
                if (started) args += current.toString()
                current.clear()
                started = false
            }
            else -> {
                current.append(c)
                started = true
            }
        }
        i++
    }
    if (started) args += current.toString()
    return args
}

private fun substituteFieldCodes(
    argv: List<String>,
    url: String,
): List<String> {
    val substituted =
        argv.mapNotNull { arg ->
            when {
                arg == "%u" || arg == "%U" -> url
                fieldCode.matches(arg) -> null
                else -> arg.replace("%%", "%")
            }
        }
    return if (url in substituted) substituted else substituted + url
}
