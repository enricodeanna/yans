package eu.studiodeanna.yans.sync

import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private val phone = MemoryTree()
    private val server = MemoryTree()
    private val state = mutableMapOf<String, Base>()
    private val scratch = Files.createTempDirectory("yans").toFile()

    /** 29 October 2026, 15:30:12 UTC. */
    private val now = 1_793_287_812_000L

    private fun sync() = Sync(phone, server, state, scratch, { now }, ZoneOffset.UTC).run()

    @Test
    fun `new files go both ways`() {
        phone.put("monkey2.001", "phone save", 1000)
        server.put("sky.002", "server save", 2000)
        val report = sync()
        assertEquals(listOf("monkey2.001"), report.uploaded)
        assertEquals(listOf("sky.002"), report.downloaded)
        assertEquals("phone save", server.text("monkey2.001"))
        assertEquals("server save", phone.text("sky.002"))
        assertEquals(0, sync().changed)
    }

    @Test
    fun `changes on one side reach the other`() {
        phone.put("monkey2.001", "first", 1000)
        sync()
        phone.put("monkey2.001", "second", 2000)
        assertEquals(listOf("monkey2.001"), sync().uploaded)
        assertEquals("second", server.text("monkey2.001"))
        server.put("monkey2.001", "third", 3000)
        assertEquals(listOf("monkey2.001"), sync().downloaded)
        assertEquals("third", phone.text("monkey2.001"))
        assertEquals(0, sync().changed)
    }

    @Test
    fun `deletions reach the other side`() {
        phone.put("a.001", "a", 1000)
        phone.put("b.001", "b", 1000)
        phone.put("c.001", "c", 1000)
        sync()
        phone.remove("a.001")
        server.remove("b.001")
        val report = sync()
        assertEquals(listOf("a.001"), report.deletedThere)
        assertEquals(listOf("b.001"), report.deletedHere)
        assertEquals(setOf("c.001"), phone.paths())
        assertEquals(setOf("c.001"), server.paths())
        assertEquals(setOf("c.001"), state.keys)
    }

    @Test
    fun `a change wins over a deletion`() {
        phone.put("a.001", "a", 1000)
        phone.put("keep.001", "k", 1000)
        sync()
        phone.remove("a.001")
        server.put("a.001", "changed", 2000)
        assertEquals(listOf("a.001"), sync().downloaded)
        assertEquals("changed", phone.text("a.001"))
    }

    @Test
    fun `the newer of two changes wins and the older is kept as a conflict copy`() {
        phone.put("saves/monkey2.001", "start", 1000)
        sync()
        phone.put("saves/monkey2.001", "played on the phone", 3000)
        server.put("saves/monkey2.001", "played on the computer", 2000)
        val report = sync()
        assertEquals(listOf("saves/monkey2.001"), report.conflicts)
        assertEquals(emptyList(), report.uploaded)
        val copy = "saves/monkey2 (conflict 2026-10-29 15.30.12).001"
        assertEquals(setOf("saves/monkey2.001", copy), phone.paths())
        assertEquals(setOf("saves/monkey2.001", copy), server.paths())
        assertEquals("played on the phone", server.text("saves/monkey2.001"))
        assertEquals("played on the computer", phone.text(copy))
        assertEquals("played on the computer", server.text(copy))
        assertEquals(0, sync().changed)
    }

    @Test
    fun `the server's change wins when it is newer`() {
        phone.put("notes", "start", 1000)
        sync()
        phone.put("notes", "old", 2000)
        server.put("notes", "new", 3000)
        sync()
        assertEquals("new", phone.text("notes"))
        assertEquals("old", server.text("notes (conflict 2026-10-29 15.30.12)"))
    }

    @Test
    fun `the same file on both sides is no conflict`() {
        phone.put("monkey2.001", "same", 1000)
        server.put("monkey2.001", "same", 2000)
        val report = sync()
        assertEquals(0, report.changed)
        assertEquals(setOf("monkey2.001"), state.keys)
    }

    @Test
    fun `different files met for the first time are a conflict`() {
        phone.put("monkey2.001", "phone", 1000)
        server.put("monkey2.001", "server", 2000)
        assertEquals(listOf("monkey2.001"), sync().conflicts)
        assertEquals("server", phone.text("monkey2.001"))
        assertEquals("phone", server.text("monkey2 (conflict 2026-10-29 15.30.12).001"))
    }

    @Test
    fun `a side emptied whole stops the sync rather than emptying the other`() {
        phone.put("a.001", "a", 1000)
        phone.put("b.001", "b", 1000)
        sync()
        phone.remove("a.001")
        phone.remove("b.001")
        val e = assertFailsWith<EmptySideException> { sync() }
        assertTrue(e.here)
        assertEquals(setOf("a.001", "b.001"), server.paths())
    }

    @Test
    fun `a file that fails is reported and the others go on`() {
        phone.put("a.001", "a", 1000)
        phone.put("b.001", "b", 1000)
        server.broken += "a.001"
        val report = sync()
        assertEquals(setOf("a.001"), report.failed.keys)
        assertEquals(listOf("b.001"), report.uploaded)
        assertNull(state["a.001"])
        server.broken.clear()
        assertEquals(listOf("a.001"), sync().uploaded)
    }

    @Test
    fun `the state survives being written and read`() {
        val file = scratch.resolve("state")
        val written = mapOf("a\tb/c\\d\ne" to Base("1:2", "\"etag\""), "x" to Base("", "y"))
        SyncState.write(file, written)
        assertEquals(written, SyncState.read(file))
    }
}
