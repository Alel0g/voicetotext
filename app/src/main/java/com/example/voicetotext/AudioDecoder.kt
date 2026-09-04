package com.example.voicetotext

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteOrder

/**
 * Декодирует аудиофайл (WAV, MP3, M4A, OGG, AMR и др.) в поток чанков
 * 16-битного PCM. onChunk(выборки, частота, число каналов).
 */
object AudioDecoder {

    fun decode(context: Context, uri: Uri, onChunk: (ShortArray, Int, Int) -> Unit) {
        // Проверяем по магическим байтам, WAV это или нет
        val header = ByteArray(12)
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val bis = BufferedInputStream(ins, 8192)
            if (readFully(bis, header) == 12 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
                decodeWav(bis, onChunk)
                return
            }
        }
        decodeWithMediaCodec(context, uri, onChunk)
    }

    private fun readFully(ins: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun le16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    // ---------- WAV (любая частота/каналы, 16 бит) ----------
    private fun decodeWav(ins: InputStream, onChunk: (ShortArray, Int, Int) -> Unit) {
        var sampleRate = 0; var channels = 0; var bits = 0
        var dataLen = -1L

        while (true) {
            val ch = ByteArray(8)
            if (readFully(ins, ch) < 8) break
            val id = String(ch, 0, 4, Charsets.US_ASCII)
            val size = le32(ch, 4)
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size)
                    if (readFully(ins, fmt) < size) throw IOException("Повреждённый WAV")
                    channels = le16(fmt, 2)
                    sampleRate = le32(fmt, 4)
                    bits = le16(fmt, 14)
                }
                "data" -> { dataLen = size.toLong() and 0xFFFFFFFFL; break }
                else -> {
                    var toSkip = (size + (size % 2)).toLong()
                    while (toSkip > 0) {
                        val s = ins.skip(toSkip); if (s <= 0) break; toSkip -= s
                    }
                }
            }
        }

        if (channels <= 0 || sampleRate <= 0) throw IOException("Не удалось прочитать WAV-заголовок")
        if (bits != 0 && bits != 16) throw IOException("Поддерживаются только 16-битные WAV")

        val buf = ByteArray(65536)
        var remaining = dataLen
        while (true) {
            val toRead = if (remaining < 0) buf.size else minOf(buf.size.toLong(), remaining).toInt()
            if (toRead <= 0) break
            val n = ins.read(buf, 0, toRead)
            if (n <= 0) break
            if (remaining >= 0) remaining -= n
            val count = n / 2
            if (count == 0) continue
            val shorts = ShortArray(count)
            for (i in 0 until count) {
                shorts[i] = ((buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)).toShort()
            }
            onChunk(shorts, sampleRate, channels)
        }
    }

    // ---------- MP3 / M4A / OGG и прочее (через MediaCodec) ----------
    private fun decodeWithMediaCodec(context: Context, uri: Uri,
                                     onChunk: (ShortArray, Int, Int) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true) { trackIndex = i; break }
            }
            if (trackIndex < 0) throw IOException("В файле нет аудиодорожки")
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false

            while (!outputEos) {
                if (!inputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.order(ByteOrder.nativeOrder())
                        val count = info.size / 2
                        if (count > 0) {
                            val shorts = ShortArray(count)
                            outBuf.asShortBuffer().get(shorts)
                            onChunk(shorts, sampleRate, channels)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                }
            }
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) { }
            extractor.release()
        }
    }
}
