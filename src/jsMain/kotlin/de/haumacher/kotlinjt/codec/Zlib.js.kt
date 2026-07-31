package de.haumacher.kotlinjt.codec

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

private fun ByteArray.toUint8Array(): Uint8Array {
    val int8 = this.unsafeCast<Int8Array>()
    return Uint8Array(int8.buffer, int8.byteOffset, int8.length)
}

private fun Uint8Array.toByteArray(): ByteArray = Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>()

// pako throws plain JS strings on corrupt input, which a Kotlin `catch (e: Throwable)`
// does not see — the try/catch has to live on the JS side of the boundary.
@Suppress("UNUSED_PARAMETER")
private fun callCatching(
    operation: dynamic,
    data: Uint8Array,
    options: dynamic,
): dynamic =
    js(
        "(function () { try { return { ok: operation(data, options) }; } catch (e) { return { err: String(e) }; } })()",
    )

private fun run(
    label: String,
    operation: dynamic,
    data: Uint8Array,
    options: dynamic,
): ByteArray {
    val result = callCatching(operation, data, options)
    if (result.err != null) {
        throw ZlibException("$label failed: ${result.err}")
    }
    val ok = result.ok
    if (ok == null) {
        throw ZlibException("$label produced no output")
    }
    return ok.unsafeCast<Uint8Array>().toByteArray()
}

actual fun zlibInflate(data: ByteArray): ByteArray =
    run(
        "inflate (corrupt zlib stream?)",
        Pako.asDynamic().inflate,
        data.toUint8Array(),
        undefined,
    )

actual fun zlibDeflate(
    data: ByteArray,
    level: Int,
): ByteArray {
    if (level !in 0..9) throw ZlibException("invalid deflate level $level (expected 0..9)")
    val options = js("{}")
    options.level = level
    return run("deflate", Pako.asDynamic().deflate, data.toUint8Array(), options)
}
