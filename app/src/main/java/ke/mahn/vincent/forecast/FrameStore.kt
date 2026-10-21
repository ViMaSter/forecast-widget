package ke.mahn.vincent.forecast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    data class AlphaVideos(val color: ByteArray, val mask: ByteArray)

    /**
     * Decodes every frame of the forecast MP4 (AV1) with the platform's media decoders.
     * With [footerRange] (epoch ms of the first and last frame) the time box is drawn onto each frame.
     */
    fun saveVideo(zoom: Int, video: ByteArray, footerRange: Pair<Long, Long>? = null): Int {
        val frameCount = decodeVideo(zoom, video, footerRange)
        val zoomDir = File(root, "z$zoom")
        if (footerRange != null) {
            // Kept so that a 304 with a moved time range can redraw the footer without downloading again.
            File(zoomDir, FOOTERLESS_VIDEO).writeBytes(video)
            File(zoomDir, FOOTER_RANGE).writeText("${footerRange.first},${footerRange.second}")
        } else {
            File(zoomDir, FOOTERLESS_VIDEO).delete()
            File(zoomDir, FOOTER_RANGE).delete()
        }
        return frameCount
    }

    fun saveAlphaVideos(
        zoom: Int,
        colorVideo: ByteArray,
        maskVideo: ByteArray,
        footerRange: Pair<Long, Long>,
        palette: RainPalette,
    ): Int = decodeAlphaVideos(zoom, colorVideo, maskVideo, footerRange, palette)

    /** Re-renders cached alpha videos when the time range or theme palette changed; returns whether frames changed. */
    fun rerenderAlpha(zoom: Int, start: Long, end: Long, palette: RainPalette): Boolean {
        val dir = latestDir(zoom) ?: return false
        if (File(dir, RENDER_KEY).takeIf { it.isFile }?.readText() == renderKey(start to end, palette)) return false
        val videos = alphaVideos(zoom) ?: return false
        saveAlphaVideos(zoom, videos.color, videos.mask, start to end, palette)
        return true
    }

    /** Whether any cached alpha forecast (optionally only of [zooms]) was rendered with a different theme palette. */
    fun hasStalePalette(palette: RainPalette, zooms: Set<Int>? = null): Boolean =
        root.listFiles().orEmpty().mapNotNull { zoomDir ->
            zoomDir.name.removePrefix("z").toIntOrNull()
                ?.takeIf { zooms == null || it in zooms }
                ?.let(::latestDir)
        }.any { dir ->
            val key = File(dir, RENDER_KEY)
            key.isFile && !key.readText().endsWith("|${palette.key}")
        }

    private fun renderKey(footerRange: Pair<Long, Long>, palette: RainPalette) =
        "${footerRange.first},${footerRange.second}|${palette.key}"

    fun alphaVideos(zoom: Int): AlphaVideos? {
        val dir = latestDir(zoom) ?: return null
        val color = File(dir, ALPHA_COLOR_VIDEO)
        val mask = File(dir, ALPHA_MASK_VIDEO)
        if (!color.isFile || !mask.isFile) return null
        return AlphaVideos(color.readBytes(), mask.readBytes())
    }

    fun canRedrawFooter(zoom: Int): Boolean = File(File(root, "z$zoom"), FOOTERLESS_VIDEO).exists()

    /** Redraws the footer of the stored footerless video for a new time range; returns whether frames changed. */
    fun redrawFooter(zoom: Int, start: Long, end: Long): Boolean {
        val zoomDir = File(root, "z$zoom")
        val video = File(zoomDir, FOOTERLESS_VIDEO).takeIf { it.exists() } ?: return false
        val current = File(zoomDir, FOOTER_RANGE).takeIf { it.exists() }?.readText()
        if (current == "$start,$end") return false
        saveVideo(zoom, video.readBytes(), start to end)
        return true
    }

    private fun decodeVideo(zoom: Int, video: ByteArray, footerRange: Pair<Long, Long>?): Int {
        val videoFile = File.createTempFile("forecast", ".mp4", appContext.cacheDir)
        val retriever = MediaMetadataRetriever()
        try {
            videoFile.writeBytes(video)
            retriever.setDataSource(videoFile.path)
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull()?.takeIf { it > 0 } ?: throw IOException("Video has no frames")
            val timeline = footerRange?.let { FrameTimeline(it.first, it.second, frameCount) }
            val footer = timeline?.let { ForecastFooter() }
            return publishFrames(zoom) { tmpDir ->
                // Decoding in small batches is sequential (fast) without holding every bitmap at once.
                for (start in 0 until frameCount step VIDEO_DECODE_BATCH) {
                    val count = minOf(VIDEO_DECODE_BATCH, frameCount - start)
                    retriever.getFramesAtIndex(start, count).forEachIndexed { offset, decoded ->
                        val index = start + offset
                        val frame = if (timeline != null && footer != null) {
                            footer.draw(decoded, timeline.timeAt(index), timeline.progressAt(index))
                        } else decoded
                        writeFrame(tmpDir, index, frame)
                        if (frame !== decoded) frame.recycle()
                        decoded.recycle()
                    }
                }
                frameCount
            }
        } finally {
            retriever.release()
            videoFile.delete()
        }
    }

    private fun decodeAlphaVideos(
        zoom: Int,
        colorVideo: ByteArray,
        maskVideo: ByteArray,
        footerRange: Pair<Long, Long>,
        palette: RainPalette,
    ): Int {
        val colorFile = File.createTempFile("forecast-color", ".mp4", appContext.cacheDir)
        val maskFile = File.createTempFile("forecast-mask", ".mp4", appContext.cacheDir)
        val colorRetriever = MediaMetadataRetriever()
        val maskRetriever = MediaMetadataRetriever()
        try {
            colorFile.writeBytes(colorVideo)
            maskFile.writeBytes(maskVideo)
            colorRetriever.setDataSource(colorFile.path)
            maskRetriever.setDataSource(maskFile.path)
            val colorCount = videoFrameCount(colorRetriever)
            val maskCount = videoFrameCount(maskRetriever)
            if (colorCount != maskCount) throw IOException("Rain and alpha videos have different frame counts")
            return publishFrames(zoom) { tmpDir ->
                var hasVisibleRain = false
                for (start in 0 until colorCount step VIDEO_DECODE_BATCH) {
                    val count = minOf(VIDEO_DECODE_BATCH, colorCount - start)
                    val colors = colorRetriever.getFramesAtIndex(start, count)
                    val masks = maskRetriever.getFramesAtIndex(start, count)
                    try {
                        if (colors.size != count || masks.size != count) {
                            throw IOException("Rain and alpha videos did not decode all requested frames")
                        }
                        colors.indices.forEach { offset ->
                            val color = colors[offset]
                            val mask = masks[offset]
                            if (color.width != mask.width || color.height != mask.height) {
                                throw IOException("Rain and alpha frames have different dimensions")
                            }
                            val frame = applyAlphaMask(color, mask, palette)
                            try {
                                if (!hasVisibleRain) hasVisibleRain = hasVisiblePixels(frame)
                                writeFrame(tmpDir, start + offset, frame)
                            } finally {
                                frame.recycle()
                            }
                        }
                    } finally {
                        colors.forEach(Bitmap::recycle)
                        masks.forEach(Bitmap::recycle)
                    }
                }
                // Decide for the whole forecast before adding a footer to any frame.
                if (hasVisibleRain) {
                    val timeline = FrameTimeline(footerRange.first, footerRange.second, colorCount)
                    val footer = ForecastFooter()
                    for (index in 0 until colorCount) {
                        val decoded = BitmapFactory.decodeFile(File(tmpDir, frameName(index)).path)
                            ?: throw IOException("Failed to read alpha frame $index")
                        try {
                            val frame = footer.draw(decoded, timeline.timeAt(index), timeline.progressAt(index))
                            try {
                                writeFrame(tmpDir, index, frame)
                            } finally {
                                if (frame !== decoded) frame.recycle()
                            }
                        } finally {
                            decoded.recycle()
                        }
                    }
                }
                File(tmpDir, ALPHA_COLOR_VIDEO).writeBytes(colorVideo)
                File(tmpDir, ALPHA_MASK_VIDEO).writeBytes(maskVideo)
                File(tmpDir, RENDER_KEY).writeText(renderKey(footerRange, palette))
                colorCount
            }
        } finally {
            colorRetriever.release()
            maskRetriever.release()
            colorFile.delete()
            maskFile.delete()
        }
    }

    private fun videoFrameCount(retriever: MediaMetadataRetriever): Int =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            ?.toIntOrNull()?.takeIf { it > 0 } ?: throw IOException("Video has no frames")

    private fun applyAlphaMask(color: Bitmap, mask: Bitmap, palette: RainPalette): Bitmap {
        val width = color.width
        val height = color.height
        val colorPixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        val outputPixels = IntArray(width * height)
        color.getPixels(colorPixels, 0, width, 0, 0, width, height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
        for (index in outputPixels.indices) {
            val opacity = (maskPixels[index] ushr 16) and 0xff
            outputPixels[index] = palette.recolorPremultiplied(colorPixels[index] and 0x00ffffff, opacity)
        }
        return Bitmap.createBitmap(outputPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun hasVisiblePixels(frame: Bitmap): Boolean {
        val row = IntArray(frame.width)
        for (y in 0 until frame.height) {
            frame.getPixels(row, 0, frame.width, 0, y, frame.width, 1)
            if (row.any { it ushr 24 != 0 }) return true
        }
        return false
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
        private const val FOOTERLESS_VIDEO = "footerless.mp4"
        private const val FOOTER_RANGE = "footer-range"
        private const val ALPHA_COLOR_VIDEO = "alpha-color.mp4"
        private const val ALPHA_MASK_VIDEO = "alpha-mask.mp4"
        private const val RENDER_KEY = "render-key"

        fun rootDir(context: Context) = File(context.filesDir, "frames")
    }
}
