package eu.studiodeanna.yans.sync

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a sync did. [failed] holds, by path, why a file could not be synced; the others went on. */
data class Report(
    val uploaded: List<String>,
    val downloaded: List<String>,
    val deletedHere: List<String>,
    val deletedThere: List<String>,
    /** Files changed on both sides: the newer is kept, the other is saved next to it as a conflict copy. */
    val conflicts: List<String>,
    val failed: Map<String, String>,
) {
    val changed get() = uploaded.size + downloaded.size + deletedHere.size + deletedThere.size + conflicts.size
}

/**
 * Two-way sync of [local] with [remote]. [state] is what the last sync left the same on both sides; it is
 * brought up to date as each file is synced, and the caller keeps it for the next time, failure or not.
 *
 * A file that changed on one side only is copied to the other, and one deleted on one side is deleted on the
 * other. A file deleted on one side and changed on the other comes back: changes win over deletions. A file
 * changed on both sides is a conflict, unless both now hold the same: the newer of the two stays under its
 * name, and the older is kept on both sides as "name (conflict <date> <time>).ext", so nothing is lost.
 */
class Sync(
    private val local: Tree,
    private val remote: Tree,
    private val state: MutableMap<String, Base>,
    /** A folder for files on their way from one side to the other. */
    private val scratch: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val uploaded = mutableListOf<String>()
    private val downloaded = mutableListOf<String>()
    private val deletedHere = mutableListOf<String>()
    private val deletedThere = mutableListOf<String>()
    private val conflicts = mutableListOf<String>()
    private val failed = mutableMapOf<String, String>()

    fun run(): Report {
        scratch.mkdirs()
        val localFiles = local.list()
        val remoteFiles = remote.list()
        refuseToEmpty(localFiles, remoteFiles)
        for (path in (localFiles.keys + remoteFiles.keys + state.keys).sorted()) {
            try {
                syncFile(path, localFiles[path], remoteFiles[path], state[path], localFiles.keys + remoteFiles.keys)
            } catch (e: LoginRefusedException) {
                throw e
            } catch (e: Exception) {
                failed[path] = e.message ?: e.javaClass.simpleName
            }
        }
        return Report(uploaded, downloaded, deletedHere, deletedThere, conflicts, failed)
    }

    /**
     * A side that lists nothing at all, where the last sync left files, is far more likely a folder lost (a
     * card taken out, leave to read it taken back, the server's folder renamed) than every file deleted on
     * purpose: deleting them all on the other side would lose them, so the sync stops instead.
     */
    private fun refuseToEmpty(localFiles: Map<String, FileInfo>, remoteFiles: Map<String, FileInfo>) {
        if (state.isEmpty()) return
        if (localFiles.isEmpty() && remoteFiles.isNotEmpty()) throw EmptySideException(here = true)
        if (remoteFiles.isEmpty() && localFiles.isNotEmpty()) throw EmptySideException(here = false)
    }

    private fun syncFile(path: String, here: FileInfo?, there: FileInfo?, base: Base?, taken: Set<String>) {
        when {
            here == null && there == null -> state.remove(path)
            base == null -> when {
                there == null -> upload(here!!)
                here == null -> download(there)
                else -> settle(here, there, taken)
            }
            else -> {
                val changedHere = here?.tag != base.localTag
                val changedThere = there?.tag != base.remoteTag
                when {
                    !changedHere && !changedThere -> {}
                    !changedThere -> if (here == null) deleteThere(path) else upload(here)
                    !changedHere -> if (there == null) deleteHere(path) else download(there)
                    here == null -> download(there!!)
                    there == null -> upload(here)
                    else -> settle(here, there, taken)
                }
            }
        }
    }

    private fun upload(file: FileInfo) {
        val written = carry(local, file.path, remote, file.path, file.modified)
        state[file.path] = Base(file.tag, written.tag)
        uploaded += file.path
    }

    private fun download(file: FileInfo) {
        val written = carry(remote, file.path, local, file.path, file.modified)
        state[file.path] = Base(written.tag, file.tag)
        downloaded += file.path
    }

    private fun deleteThere(path: String) {
        remote.delete(path)
        state.remove(path)
        deletedThere += path
    }

    private fun deleteHere(path: String) {
        local.delete(path)
        state.remove(path)
        deletedHere += path
    }

    /** Both sides have the file, changed or new: the same content is no conflict. */
    private fun settle(here: FileInfo, there: FileInfo, taken: Set<String>) {
        if (here.size == there.size && sameContent(here.path)) {
            state[here.path] = Base(here.tag, there.tag)
            return
        }
        val hereWins = here.modified >= there.modified
        val copy = conflictPath(here.path, taken)
        val loser = fetch(if (hereWins) remote else local, here.path)
        try {
            val copyModified = if (hereWins) there.modified else here.modified
            val copyHere = loser.inputStream().use { local.write(copy, it, loser.length(), copyModified) }
            val copyThere = loser.inputStream().use { remote.write(copy, it, loser.length(), copyModified) }
            state[copy] = Base(copyHere.tag, copyThere.tag)
        } finally {
            loser.delete()
        }
        if (hereWins) upload(here) else download(there)
        uploaded.remove(here.path)
        downloaded.remove(here.path)
        conflicts += here.path
    }

    private fun sameContent(path: String): Boolean {
        val here = fetch(local, path)
        val there = fetch(remote, path)
        try {
            return here.length() == there.length() && here.readBytes().contentEquals(there.readBytes())
        } finally {
            here.delete()
            there.delete()
        }
    }

    /**
     * Copies a file whole into [scratch] first and only then to the other side, so that a connection lost
     * halfway never leaves half a file, which the next sync would take for a change.
     */
    private fun carry(from: Tree, fromPath: String, to: Tree, toPath: String, modified: Long): FileInfo {
        val file = fetch(from, fromPath)
        try {
            return file.inputStream().use { to.write(toPath, it, file.length(), modified) }
        } finally {
            file.delete()
        }
    }

    private fun fetch(tree: Tree, path: String): File {
        val file = File.createTempFile("carry", null, scratch)
        try {
            file.outputStream().use { tree.read(path, it) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    /** "saves/monkey2 (conflict 2026-10-29 15.30.12).001" for "saves/monkey2.001", not yet taken. */
    private fun conflictPath(path: String, taken: Set<String>): String {
        val folder = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        val time = CONFLICT_TIME.withZone(zone).format(Instant.ofEpochMilli(now()))
        var number = 1
        while (true) {
            val label = if (number == 1) "conflict $time" else "conflict $time $number"
            val candidate = (if (folder.isEmpty()) "" else "$folder/") + "$stem ($label)$extension"
            if (candidate !in taken && candidate !in state) return candidate
            number++
        }
    }

    private companion object {
        val CONFLICT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")
    }
}

/** One side of a sync lists no files at all where the last sync left some; [here] is true for the phone. */
class EmptySideException(val here: Boolean) :
    SyncException(if (here) "The phone's folder is empty" else "The server's folder is empty")
