package de.haumacher.kotlinjt.codec

import org.khronos.webgl.Uint8Array

/** The pako zlib port — the `actual` engine behind the zlib seam on Kotlin/JS. */
@JsModule("pako")
@JsNonModule
internal external object Pako {
    fun inflate(data: Uint8Array): Uint8Array

    fun deflate(
        data: Uint8Array,
        options: dynamic = definedExternally,
    ): Uint8Array
}
