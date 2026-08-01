package com.plutoforce.tapsave

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Pulls just the audio track out of a video, on the phone, without re-encoding.
 *
 * Transcription only needs the sound, but the fallback route was uploading the
 * whole video — tens of megabytes over mobile data, which is what made it slow.
 * Android can demux the audio and remux it into an .m4a directly, so the upload
 * drops to a couple of megabytes and the server has less to do as well.
 */
object AudioExtractor {

    /** Writes the audio of [sourceUrl] to an .m4a in the cache. Null if it can't. */
    fun extract(cacheDir: File, sourceUrl: String, headers: Map<String, String>): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        val output = File(cacheDir, "tapsave_audio_${System.currentTimeMillis()}.m4a")

        try {
            extractor.setDataSource(sourceUrl, headers)

            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    audioTrack = i
                    format = candidate
                    break
                }
            }
            if (audioTrack < 0 || format == null) return null

            extractor.selectTrack(audioTrack)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()

            val maxSize = format.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }
                ?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) ?: (256 * 1024)
            val buffer = ByteBuffer.allocate(maxSize.coerceAtLeast(64 * 1024))
            val info = android.media.MediaCodec.BufferInfo()

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }

            muxer.stop()
            return output.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            output.delete()
            return null
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
