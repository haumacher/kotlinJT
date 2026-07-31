package de.haumacher.kotlinjt

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Reads and parses a JT file from the file system (JVM and Node.js; browsers have no file
 * system — pass bytes to [JtFile.parse] there).
 */
fun readJtFile(path: String): JtFile {
    val source = SystemFileSystem.source(Path(path)).buffered()
    val bytes =
        try {
            source.readByteArray()
        } finally {
            source.close()
        }
    return JtFile.parse(bytes)
}
