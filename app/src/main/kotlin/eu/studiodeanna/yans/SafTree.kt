package eu.studiodeanna.yans

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import eu.studiodeanna.yans.sync.FileInfo
import eu.studiodeanna.yans.sync.SyncException
import eu.studiodeanna.yans.sync.Tree
import java.io.InputStream
import java.io.OutputStream

/**
 * A folder on the phone that the user chose in Android's folder picker, read and written through the
 * provider that holds it. The tag of a file is its size and time: Android lets no app set a file's time, so
 * the time of a file downloaded is when it was downloaded, which is kept as the tag it then had.
 */
class SafTree(context: Context, private val treeUri: Uri) : Tree {
    private val resolver = context.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(treeUri)

    /** The provider's id of each file and folder listed or made, by path; "" is the folder itself. */
    private val ids = mutableMapOf<String, String>()

    init {
        if (resolver.persistedUriPermissions.none { it.uri == treeUri && it.isReadPermission && it.isWritePermission }) {
            throw SyncException("Android took back the leave to use the phone's folder")
        }
    }

    override fun list(): Map<String, FileInfo> {
        ids.clear()
        ids[""] = rootId
        val files = mutableMapOf<String, FileInfo>()
        val pending = ArrayDeque(listOf(""))
        while (pending.isNotEmpty()) {
            val folder = pending.removeFirst()
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, ids.getValue(folder))
            val cursor = resolver.query(uri, COLUMNS, null, null, null)
                ?: throw SyncException("The phone's folder cannot be read")
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    // Files that Android is still writing, or keeps in its bin for a while.
                    if (name.startsWith(".pending-") || name.startsWith(".trashed-")) continue
                    val path = if (folder.isEmpty()) name else "$folder/$name"
                    ids[path] = it.getString(0)
                    if (it.getString(2) == Document.MIME_TYPE_DIR) pending += path
                    else files[path] = info(path, it.getLong(3), it.getLong(4))
                }
            }
        }
        return files
    }

    override fun read(path: String, sink: OutputStream) {
        val id = ids[path] ?: throw SyncException("$path is no longer on the phone")
        (resolver.openInputStream(document(id)) ?: throw SyncException("$path cannot be read")).use { it.copyTo(sink) }
    }

    override fun write(path: String, source: InputStream, size: Long, modified: Long): FileInfo {
        val id = ids[path] ?: create(path, "application/octet-stream")
        (resolver.openOutputStream(document(id), "wt") ?: throw SyncException("$path cannot be written")).use {
            source.copyTo(it)
        }
        val cursor = resolver.query(document(id), COLUMNS, null, null, null) ?: throw SyncException("$path cannot be read")
        return cursor.use {
            if (!it.moveToFirst()) throw SyncException("$path is no longer on the phone")
            info(path, it.getLong(3), it.getLong(4))
        }
    }

    override fun delete(path: String) {
        val id = ids.remove(path) ?: return
        DocumentsContract.deleteDocument(resolver, document(id))
    }

    /** Makes the file or folder at [path], and the folders it is in when missing; returns its id. */
    private fun create(path: String, mimeType: String): String {
        val name = path.substringAfterLast('/')
        val folder = path.substringBeforeLast('/', "")
        val parent = ids[folder] ?: create(folder, Document.MIME_TYPE_DIR)
        val uri = DocumentsContract.createDocument(resolver, document(parent), mimeType, name)
            ?: throw SyncException("$path cannot be made on the phone")
        val id = DocumentsContract.getDocumentId(uri)
        // A provider may rename what it makes, to fit its rules or not to replace a file; that is not this file.
        val made = resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (made != name) {
            DocumentsContract.deleteDocument(resolver, uri)
            throw SyncException("The phone does not allow the name $name")
        }
        ids[path] = id
        return id
    }

    private fun document(id: String) = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun info(path: String, size: Long, modified: Long) = FileInfo(path, size, modified, "$size:$modified")

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        /** The name of the folder at [treeUri], as the picker showed it. */
        fun folderName(context: Context, treeUri: Uri): String {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            return context.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "?"
        }
    }
}
