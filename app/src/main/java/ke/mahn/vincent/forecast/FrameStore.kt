package ke.mahn.vincent.forecast

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Stores decoded video frames as PNG files in `files/frames/z<zoom>/<version>/NN.png`.
 * Widgets reference them through [FrameProvider] URIs; a new version directory per update makes
 * launchers reload instead of showing cached images.
 */
class FrameStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = rootDir(appContext)

    /** Decodes every frame of the forecast MP4 (AV1) with the platform's media decoders. */
    fun saveVideo(zoom: Int, video: ByteArray): Int {
        val videoFile = File.createTempFile("forecast", ".mp4", appContext.cacheDir)
        val retriever = MediaMetadataRetriever()
        try {
            videoFile.writeBytes(video)
            retriever.setDataSource(videoFile.path)
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull()?.takeIf { it > 0 } ?: throw IOException("Video has no frames")
            return publishFrames(zoom) { tmpDir ->
                // Decoding in small batches is sequential (fast) without holding every bitmap at once.
                for (start in 0 until frameCount step VIDEO_DECODE_BATCH) {
                    val count = minOf(VIDEO_DECODE_BATCH, frameCount - start)
                    retriever.getFramesAtIndex(start, count).forEachIndexed { offset, frame ->
                        writeFrame(tmpDir, start + offset, frame)
                        frame.recycle()
                    }
                }
                frameCount
            }
        } finally {
            retriever.release()
            videoFile.delete()
        }
    }

    private fun publishFrames(zoom: Int, writeFrames: (File) -> Int): Int {
        val version = System.currentTimeMillis().toString()
        val zoomDir = File(root, "z$zoom")
        val tmpDir = File(zoomDir, "$version.tmp").apply { deleteRecursively(); mkdirs() }
        val frameCount = try {
            writeFrames(tmpDir).also {
                if (!tmpDir.renameTo(File(zoomDir, version))) throw IOException("Failed to publish frames")
            }
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            throw e
        }
        prune(zoomDir, keep = 2)
        return frameCount
    }

    private fun writeFrame(dir: File, index: Int, frame: Bitmap) {
        File(dir, frameName(index)).outputStream().use { out ->
            if (!frame.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("Failed to encode frame $index")
            }
        }
    }

    fun frameUris(zoom: Int): List<Uri> {
        val dir = latestDir(zoom) ?: return emptyList()
        return dir.listFiles { file -> file.name.endsWith(".png") }.orEmpty()
            .sortedBy { it.name }
            .map { FrameProvider.uriFor(appContext, it) }
    }

    fun hasFrames(zoom: Int): Boolean = latestDir(zoom) != null

    /** Removes frame directories of zooms no widget uses anymore. */
    fun retainZooms(zooms: Set<Int>) {
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.removePrefix("z").toIntOrNull() !in zooms }
            .forEach { it.deleteRecursively() }
    }

    private fun latestDir(zoom: Int): File? =
        File(root, "z$zoom").listFiles { file -> file.isDirectory && file.name.toLongOrNull() != null }
            .orEmpty()
            .maxByOrNull { it.name.toLong() }

    // The previous version is kept because a launcher may still be loading its URIs.
    private fun prune(zoomDir: File, keep: Int) {
        zoomDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedByDescending { it.name.toLongOrNull() ?: Long.MIN_VALUE }
            .drop(keep)
            .forEach { it.deleteRecursively() }
    }

    private fun frameName(index: Int) = "%02d.png".format(index)

    companion object {
        private const val VIDEO_DECODE_BATCH = 8

        fun rootDir(context: Context) = File(context.filesDir, "frames")
    }
}
