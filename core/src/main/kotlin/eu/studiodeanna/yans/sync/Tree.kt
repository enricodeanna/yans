package eu.studiodeanna.yans.sync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A file on one side of a sync. [path] is under the synced folder, with '/' between folders; [modified] is
 * when it last changed, in milliseconds; [tag] changes whenever its content does (the server's ETag, or the
 * phone's size and time).
 */
data class FileInfo(val path: String, val size: Long, val modified: Long, val tag: String)

/** One side of a sync: a folder on the phone, or one on the server. Paths are relative to that folder. */
interface Tree {
    /** Every file in the folder and in its subfolders, by path. Folders themselves are not listed. */
    fun list(): Map<String, FileInfo>

    /** Writes the file's content to [sink]. */
    fun read(path: String, sink: OutputStream)

    /**
     * Makes the file, or replaces it, with the [size] bytes of [source], making its folders when missing;
     * [modified] is kept as its time where the side can. Returns the file as [list] would list it now.
     */
    fun write(path: String, source: InputStream, size: Long, modified: Long): FileInfo

    /** Deletes the file; one already gone is no failure. */
    fun delete(path: String)
}

/** A failure the user should read as it is, such as the server refusing the login. */
open class SyncException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The server refused the account's name or password. */
class LoginRefusedException(message: String) : SyncException(message)
