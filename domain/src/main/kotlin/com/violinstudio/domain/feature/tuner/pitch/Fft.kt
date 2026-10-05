package com.violinstudio.domain.feature.tuner.pitch

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** FFT radix-2 iterativa in-place de tamano [size] (potencia de 2); tablas de giro precalculadas. */
class Fft(val size: Int) {
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray

    init {
        require(size > 0 && size and (size - 1) == 0) { "size must be a power of two: $size" }
        cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
        sinTable = DoubleArray(size / 2) { -sin(2 * PI * it / size) }
    }

    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = false)

    /** Inversa normalizada por 1/N. */
    fun inverse(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = true)

    private fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        require(re.size == size && im.size == size) { "arrays must have size $size" }
        bitReverse(re, im)
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            for (start in 0 until size step len) {
                for (j in 0 until half) {
                    val wr = cosTable[j * step]
                    val wi = if (inverse) -sinTable[j * step] else sinTable[j * step]
                    val a = start + j
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until size) {
                re[i] /= size
                im[i] /= size
            }
        }
    }

    private fun bitReverse(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
    }
}
