package app.memem.engine

/** IEEE float16 bits to Float. */
fun halfToFloat(bits: Int): Float {
    val h = bits and 0xFFFF
    val sign = (h and 0x8000) shl 16
    val exp = (h ushr 10) and 0x1F
    val mant = h and 0x3FF
    val out = when (exp) {
        0 -> if (mant == 0) {
            sign
        } else {
            var m = mant
            var e = 127 - 15 + 1
            while (m and 0x400 == 0) {
                m = m shl 1
                e -= 1
            }
            sign or (e shl 23) or ((m and 0x3FF) shl 13)
        }
        0x1F -> sign or 0x7F800000 or (mant shl 13)
        else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
    }
    return Float.fromBits(out)
}
