package eu.studiodeanna.yans.sync

import java.io.InputStream
import java.io.OutputStream

/** A side kept in memory, whose tag is a count of the writes, as an ETag would change. */
class MemoryTree : Tree {
    private class Entry(val bytes: ByteArray, val modified: Long, val version: Int)

    private val files = mutableMapOf<String, Entry>()
    private var writes = 0

    /** Fails reading or writing these paths, as a server out of reach would. */
    val broken = mutableSetOf<String>()

    fun put(path: String, text: String, modified: Long) {
        files[path] = Entry(text.toByteArray(), modified, ++writes)
    }

    fun text(path: String): String? = files[path]?.bytes?.decodeToString()

    fun paths(): Set<String> = files.keys.toSortedSet()

    fun remove(path: String) {
        files.remove(path)
    }

    override fun list() = files.mapValues { (path, entry) -> info(path, entry) }

    override fun read(path: String, sink: OutputStream) {
        if (path in broken) throw java.io.IOException("Out of reach")
        sink.write(files.getValue(path).bytes)
    }

    override fun write(path: String, source: InputStream, size: Long, modified: Long): FileInfo {
        if (path in broken) throw java.io.IOException("Out of reach")
        val entry = Entry(source.readBytes(), modified, ++writes)
        files[path] = entry
        return info(path, entry)
    }

    override fun delete(path: String) {
        files.remove(path)
    }

    private fun info(path: String, entry: Entry) = FileInfo(path, entry.bytes.size.toLong(), entry.modified, "v${entry.version}")
}
