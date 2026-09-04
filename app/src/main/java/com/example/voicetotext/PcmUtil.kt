package com.example.voicetotext

/** Приведение PCM к формату Vosk: моно, 16 кГц, 16 бит, little-endian. */
object PcmUtil {

    fun toMono16k(input: ShortArray, sampleRate: Int, channels: Int): ShortArray {
        // 1. Смешиваем каналы в моно
        val mono = if (channels <= 1) input else {
            val len = input.size / channels
            val m = ShortArray(len)
            for (i in 0 until len) {
                var sum = 0
                for (c in 0 until channels) sum += input[i * channels + c]
                m[i] = (sum / channels).toShort()
            }
            m
        }
        // 2. Линейный ресемплинг к 16 кГц
        if (sampleRate == 16000) return mono
        val outLen = (mono.size.toLong() * 16000L / sampleRate).toInt()
        val out = ShortArray(outLen)
        val step = sampleRate / 16000.0
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt()
            val frac = pos - i0
            val s0 = mono[i0].toInt()
            val s1 = if (i0 + 1 < mono.size) mono[i0 + 1].toInt() else s0
            out[i] = (s0 + (s1 - s0) * frac).toInt().toShort()
        }
        return out
    }

    fun toBytesLE(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val v = shorts[i].toInt()
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        return bytes
    }
}
