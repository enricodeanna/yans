package eu.studiodeanna.yans.sync

import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class OneWayTest {
    private val phone = MemoryTree()
    private val server = MemoryTree()
    private val state = mutableMapOf<String, Base>()
    private val scratch = Files.createTempDirectory("yans").toFile()

    /** 29 October 2026, 15:30:12 UTC. */
    private val now = 1_793_287_812_000L
    private val day = 24L * 60 * 60 * 1000

    private fun sync(direction: Direction, keep: Keep = Keep()) =
        Sync(phone, server, state, scratch, { now }, ZoneOffset.UTC, direction, keep).run()

    @Test
    fun `uploads go one way, and deletions stay where they are`() {
        phone.put("a.txt", "a", 1000)
        server.put("from the computer.txt", "c", 1000)
        val first = sync(Direction.UPLOAD)
        assertEquals(listOf("a.txt"), first.uploaded)
        assertEquals(emptyList(), first.downloaded)
        assertEquals(setOf("a.txt"), phone.paths())

        phone.remove("a.txt")
        server.put("from the computer.txt", "changed", 2000)
        assertEquals(0, sync(Direction.UPLOAD).changed)
        assertEquals(setOf("a.txt", "from the computer.txt"), server.paths())
    }

    @Test
    fun `a file deleted on the receiving side is not sent again until it changes`() {
        phone.put("a.txt", "a", 1000)
        sync(Direction.UPLOAD)
        server.remove("a.txt")
        assertEquals(0, sync(Direction.UPLOAD).changed)
        phone.put("a.txt", "a again", 2000)
        assertEquals(listOf("a.txt"), sync(Direction.UPLOAD).uploaded)
        assertEquals("a again", server.text("a.txt"))
    }

    @Test
    fun `the source wins, and the receiving side keeps its own change as a conflict copy`() {
        server.put("notes.txt", "first", 1000)
        sync(Direction.DOWNLOAD)
        assertEquals("first", phone.text("notes.txt"))
        phone.put("notes.txt", "phone's", 5000)
        server.put("notes.txt", "server's", 2000)
        val report = sync(Direction.DOWNLOAD)
        assertEquals(listOf("notes.txt"), report.conflicts)
        assertEquals("server's", phone.text("notes.txt"))
        assertEquals("phone's", phone.text("notes (conflict 2026-10-29 15.30.12).txt"))
        assertEquals(setOf("notes.txt"), server.paths())
    }

    @Test
    fun `the same file on both sides is no conflict`() {
        phone.put("a.txt", "same", 1000)
        server.put("a.txt", "same", 2000)
        assertEquals(0, sync(Direction.UPLOAD).changed)
        assertEquals(setOf("a.txt"), state.keys)
    }

    @Test
    fun `only the newest files stay on the server, and the older ones are not sent again`() {
        for (n in 1..5) phone.put("backup $n.zip", "backup $n", n * 1000L)
        server.put("older.zip", "from elsewhere", 500)
        val first = sync(Direction.UPLOAD, Keep(newest = 3))
        assertEquals(listOf("backup 3.zip", "backup 4.zip", "backup 5.zip"), first.uploaded)
        assertEquals(listOf("older.zip"), first.deletedThere)
        assertEquals(setOf("backup 3.zip", "backup 4.zip", "backup 5.zip"), server.paths())
        assertEquals(0, sync(Direction.UPLOAD, Keep(newest = 3)).changed)

        phone.put("backup 6.zip", "backup 6", 6000)
        val second = sync(Direction.UPLOAD, Keep(newest = 3))
        assertEquals(listOf("backup 6.zip"), second.uploaded)
        assertEquals(listOf("backup 3.zip"), second.deletedThere)
        assertEquals(setOf("backup 4.zip", "backup 5.zip", "backup 6.zip"), server.paths())
        assertEquals(0, sync(Direction.UPLOAD, Keep(newest = 3)).changed)
        assertEquals((1..6).map { "backup $it.zip" }.toSet(), phone.paths())
    }

    @Test
    fun `only recent files stay, and with both limits only files within both`() {
        for (age in listOf(1, 5, 10, 40, 100)) phone.put("$age days.log", "log", now - age * day)
        val recent = sync(Direction.UPLOAD, Keep(days = 30))
        assertEquals(listOf("1 days.log", "10 days.log", "5 days.log"), recent.uploaded)

        val both = sync(Direction.UPLOAD, Keep(newest = 2, days = 30))
        assertEquals(listOf("10 days.log"), both.deletedThere)
        assertEquals(setOf("1 days.log", "5 days.log"), server.paths())
    }

    @Test
    fun `on the phone, files downloaded are ranked by the server's time`() {
        server.put("old.jpg", "old", 1000)
        server.put("new.jpg", "new", 9000)
        // The phone gives a file the time it was written, here later for the older file.
        sync(Direction.DOWNLOAD)
        phone.put("old.jpg", "old", 20_000)
        state["old.jpg"] = state.getValue("old.jpg").copy(localTag = phone.list().getValue("old.jpg").tag)
        server.put("newest.jpg", "newest", 10_000)
        val report = sync(Direction.DOWNLOAD, Keep(newest = 2))
        assertEquals(listOf("newest.jpg"), report.downloaded)
        assertEquals(listOf("old.jpg"), report.deletedHere)
        assertEquals(setOf("new.jpg", "newest.jpg"), phone.paths())
        assertEquals(setOf("old.jpg", "new.jpg", "newest.jpg"), server.paths())
    }

    @Test
    fun `an empty side stops no one-way sync`() {
        phone.put("a.txt", "a", 1000)
        sync(Direction.UPLOAD)
        phone.remove("a.txt")
        assertEquals(0, sync(Direction.UPLOAD).changed)
        assertEquals(setOf("a.txt"), server.paths())
    }
}
