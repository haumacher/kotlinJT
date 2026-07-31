package de.haumacher.kotlinjt.codec

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

actual fun zlibInflate(data: ByteArray): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream(maxOf(64, data.size * 4))
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val count =
                try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    throw ZlibException("corrupt zlib stream: ${e.message}", e)
                }
            if (count > 0) {
                out.write(buffer, 0, count)
            } else if (!inflater.finished()) {
                if (inflater.needsDictionary()) {
                    throw ZlibException("zlib stream requires a preset dictionary")
                }
                if (inflater.needsInput()) {
                    throw ZlibException("truncated zlib stream")
                }
            }
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}

actual fun zlibDeflate(
    data: ByteArray,
    level: Int,
): ByteArray {
    if (level !in 0..9) throw ZlibException("invalid deflate level $level (expected 0..9)")
    val deflater = Deflater(level)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(maxOf(64, data.size / 2))
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}
