package app.memem.engine

import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Must stay in lockstep with tools/hash_embed.py. Used only when the LiteRT
 * embedding model is not on the device yet.
 */
object HashEmbedder {
    const val DIM = 768

    fun embed(text: String, idf: Map<String, Float>, nDocs: Int): FloatArray {
        val vec = FloatArray(DIM)
        val default = (ln((nDocs + 1.0) / 1.0) + 1.0).toFloat()
        for (token in features(text)) {
            val (index, sign) = bucket(token)
            var weight = idf[token] ?: default
            if (token.startsWith("g:")) weight *= 0.35f
            vec[index] += sign * weight
        }
        var norm = 0.0
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-8) {
            val n = norm.toFloat()
            for (i in vec.indices) vec[i] /= n
        }
        return vec
    }

    fun features(text: String): List<String> {
        val words = normalize(text).split(' ').filter { it.length >= 2 }
        val joined = words.joinToString(" ")
        val grams = if (joined.length >= 3) {
            (0..joined.length - 3).map { "g:" + joined.substring(it, it + 3) }
        } else {
            emptyList()
        }
        return words.map { "w:$it" } + grams
    }

    fun normalize(text: String): String {
        val nfkc = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            if (ch.isLetterOrDigit()) sb.append(ch) else sb.append(' ')
        }
        return sb.split().joinToString(" ")
    }

    private fun StringBuilder.split(): List<String> = toString().split(' ').filter { it.isNotEmpty() }

    private fun bucket(token: String): Pair<Int, Float> {
        val crc = CRC32()
        crc.update(token.toByteArray(Charsets.UTF_8))
        val h = crc.value and 0xFFFFFFFFL
        val sign = if ((h and 0x80000000L) == 0L) 1f else -1f
        return (h % DIM).toInt() to sign
    }
}
