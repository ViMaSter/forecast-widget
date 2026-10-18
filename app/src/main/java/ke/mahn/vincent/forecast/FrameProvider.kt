package ke.mahn.vincent.forecast

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only provider serving widget frames to the launcher process. Using URIs instead of
 * bitmaps keeps the frames out of the RemoteViews bitmap memory limit. It is exported because
 * the launcher package is not known in advance; it only serves the public radar PNGs.
 */
class FrameProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only")
        val ctx = context ?: throw FileNotFoundException()
        val root = FrameStore.rootDir(ctx).canonicalFile
        val file = File(root, uri.path.orEmpty()).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.name.endsWith(".png") || !file.isFile) {
            throw FileNotFoundException(uri.toString())
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        fun authority(context: Context) = "${context.packageName}.frames"

        fun uriFor(context: Context, file: File): Uri {
            val relative = file.canonicalFile.relativeTo(FrameStore.rootDir(context).canonicalFile).invariantSeparatorsPath
            return Uri.Builder().scheme("content").authority(authority(context)).path(relative).build()
        }
    }
}
