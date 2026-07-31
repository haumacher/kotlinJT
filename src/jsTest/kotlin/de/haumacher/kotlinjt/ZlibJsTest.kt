package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.codec.ZlibException
import de.haumacher.kotlinjt.codec.zlibDeflate
import de.haumacher.kotlinjt.codec.zlibInflate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** JS-specific edges of the pako-backed zlib `actual` (the common suite runs here too). */
class ZlibJsTest {
    @Test
    fun bytesAboveSignBitSurviveTheTypedArrayBoundary() {
        // ByteArray <-> Uint8Array conversion must not mangle values >= 0x80.
        val data = ByteArray(512) { (it % 256).toByte() }
        assertContentEquals(data, zlibInflate(zlibDeflate(data)))
    }

    @Test
    fun pakoErrorsBecomeZlibException() {
        assertFailsWith<ZlibException> { zlibInflate(ByteArray(64) { 0x55 }) }
    }
}
