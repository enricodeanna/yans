package eu.studiodeanna.yans.sync

import java.io.File

/** How a file was on both sides when they last agreed: the phone's tag and the server's. */
data class Base(val localTag: String, val remoteTag: String)

/**
 * What the last sync left the same on both sides, by path: a file whose tag differs from it changed since.
 * Kept in a small text file, a line for each file.
 */
object SyncState {
    private const val HEADER = "yans-state 1"

    fun read(file: File): MutableMap<String, Base> {
        val state = mutableMapOf<String, Base>()
        if (!file.exists()) return state
        val lines = file.readLines()
        if (lines.firstOrNull() != HEADER) return state
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            if (fields.size != 3) continue
            state[unescape(fields[0])] = Base(unescape(fields[1]), unescape(fields[2]))
        }
        return state
    }

    /** Writes [state] whole, through a file of its own, so that a stop halfway leaves the old one. */
    fun write(file: File, state: Map<String, Base>) {
        file.parentFile?.mkdirs()
        val text = buildString {
            appendLine(HEADER)
            for ((path, base) in state.toSortedMap()) {
                append(escape(path)).append('\t').append(escape(base.localTag)).append('\t')
                    .append(escape(base.remoteTag)).append('\n')
            }
        }
        val next = File(file.path + ".new")
        next.writeText(text)
        if (!next.renameTo(file)) {
            file.delete()
            next.renameTo(file)
        }
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(text: String) = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c != '\\' || i == text.length) {
                append(c)
                continue
            }
            when (val next = text[i++]) {
                't' -> append('\t')
                'n' -> append('\n')
                else -> append(next)
            }
        }
    }
}
