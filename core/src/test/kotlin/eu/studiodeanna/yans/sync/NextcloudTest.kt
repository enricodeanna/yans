package eu.studiodeanna.yans.sync

import okhttp3.OkHttpClient
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextcloudTest {
    private val nextcloud = FakeNextcloud()
    private val client = OkHttpClient()
    private val scratch = Files.createTempDirectory("yans").toFile()

    @AfterTest
    fun stop() = nextcloud.server.shutdown()

    private fun login(): Account {
        val api = Nextcloud(client)
        val login = api.startLogin(nextcloud.url)
        assertEquals("https://x/login", login.loginUrl)
        nextcloud.pollsBeforeApproval = 2
        assertNull(api.poll(login))
        return api.poll(login)!!
    }

    @Test
    fun `logging in finds the user's id, on the server as typed`() {
        val account = login()
        assertEquals(nextcloud.url, account.server)
        assertEquals("enrico@example.org", account.loginName)
        assertEquals("enrico", account.userId)
        assertEquals("app-pass", account.password)
        assertEquals(nextcloud.server.url("/nextcloud/remote.php/dav/files/enrico/"), account.davRoot)
    }

    @Test
    fun `a wrong password is told apart`() {
        assertFailsWith<LoginRefusedException> { Nextcloud(client).account(nextcloud.url, "enrico@example.org", "wrong") }
    }

    @Test
    fun `server addresses are read as typed`() {
        assertEquals("https://cloud.example.org/nextcloud", Nextcloud.serverUrl("cloud.example.org/nextcloud/")!!.toString())
        assertEquals("https://cloud.example.org/", Nextcloud.serverUrl(" https://cloud.example.org/index.php/apps/files ")!!.toString())
        assertNull(Nextcloud.serverUrl(""))
    }

    @Test
    fun `syncs with the server's folder, making it and its subfolders`() {
        val account = login()
        val folder = "Games/ScummVM Saves & more"
        val server = WebDavTree(client, account.davRoot, folder, account.loginName, account.password)
        val phone = MemoryTree()
        phone.put("monkey2.001", "phone save", 1_000_000)
        phone.put("deep/in/sky.002", "nested", 2_000_000)
        val state = mutableMapOf<String, Base>()

        val first = Sync(phone, server, state, scratch).run()
        assertEquals(emptyMap(), first.failed)
        assertEquals(listOf("deep/in/sky.002", "monkey2.001"), first.uploaded)
        assertEquals("phone save", nextcloud.file("$folder/monkey2.001"))
        assertTrue(nextcloud.folderExists("$folder/deep/in"))
        assertEquals(0, Sync(phone, server, state, scratch).run().changed)

        val listed = server.list()
        assertEquals(setOf("monkey2.001", "deep/in/sky.002"), listed.keys)
        assertEquals(1_000_000, listed.getValue("monkey2.001").modified)
        assertEquals(10, listed.getValue("monkey2.001").size)

        nextcloud.put("$folder/new one.003", "from the computer", 3_000_000)
        phone.remove("deep/in/sky.002")
        val second = Sync(phone, server, state, scratch).run()
        assertEquals(listOf("new one.003"), second.downloaded)
        assertEquals(listOf("deep/in/sky.002"), second.deletedThere)
        assertEquals("from the computer", phone.text("new one.003"))
        assertNull(nextcloud.file("$folder/deep/in/sky.002"))
    }

    @Test
    fun `the server's folders can be browsed and made`() {
        val account = login()
        nextcloud.put("Games/ScummVM Saves/monkey2.001", "save", 1000)
        nextcloud.put("Games/emu/a.srm", "save", 1000)
        nextcloud.put("documents & more/notes.txt", "notes", 1000)
        nextcloud.put("photo.jpg", "not a folder", 1000)
        val folders = RemoteFolders(client, account)
        assertEquals(listOf("documents & more", "Games"), folders.list(""))
        assertEquals(listOf("emu", "ScummVM Saves"), folders.list("Games"))
        assertEquals(emptyList(), folders.list("Games/ScummVM Saves"))
        assertEquals("Games/New one", folders.make("Games", "New one"))
        assertEquals("Games/New one", folders.make("Games", "New one"))
        assertEquals(listOf("emu", "New one", "ScummVM Saves"), folders.list("Games"))
        assertFailsWith<SyncException> { folders.list("Nowhere") }
    }

    @Test
    fun `a folder not yet on the server lists empty`() {
        val account = login()
        assertEquals(emptyMap(), WebDavTree(client, account.davRoot, "Nowhere", account.loginName, account.password).list())
    }

    @Test
    fun `a password taken back stops the sync`() {
        val account = login()
        val server = WebDavTree(client, account.davRoot, "Saves", account.loginName, "revoked")
        val phone = MemoryTree()
        phone.put("a.001", "a", 1000)
        assertFailsWith<LoginRefusedException> { Sync(phone, server, mutableMapOf(), scratch).run() }
    }
}
