package de.haumacher.kotlinjt

/**
 * The clean top-level failure of [JtFile.parse]: the byte image cannot be a JT file at all
 * (no readable header, unusable TOC). Everything less fatal — a single segment that cannot be
 * decoded, an unknown compression algorithm, a region the TOC does not explain — is reported
 * as a named [LoadNote] on a successfully parsed file instead, with the raw bytes preserved.
 */
class JtFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
