package com.serein.reader.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal const val DEMO_BOOK_ID = "demo-room"

internal class ArchiveQuotaException(message: String) : IllegalArgumentException(message)

internal class ByteBudget(
    private var remainingBytes: Long,
    private val label: String,
) {
    init {
        require(remainingBytes >= 0L)
    }

    fun consume(byteCount: Int) {
        if (byteCount > remainingBytes) {
            throw ArchiveQuotaException("$label exceeds Serein's safety limit.")
        }
        remainingBytes -= byteCount
    }
}

internal class LimitedInputStream(
    input: InputStream,
    private val maximumBytes: Long,
    private val label: String,
) : FilterInputStream(input) {
    private var bytesRead = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) record(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) record(read)
        return read
    }

    private fun record(byteCount: Int) {
        if (bytesRead > maximumBytes - byteCount) {
            throw ArchiveQuotaException("$label exceeds Serein's safety limit.")
        }
        bytesRead += byteCount
    }
}

internal fun copyWithLimit(
    input: InputStream,
    output: OutputStream,
    maximumBytes: Long,
    label: String,
    aggregateBudget: ByteBudget? = null,
): Long {
    require(maximumBytes >= 0L)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (copied > maximumBytes - read) {
            throw ArchiveQuotaException("$label exceeds Serein's safety limit.")
        }
        aggregateBudget?.consume(read)
        output.write(buffer, 0, read)
        copied += read
    }
    return copied
}

internal fun readBytesWithLimit(
    input: InputStream,
    maximumBytes: Long,
    label: String,
    aggregateBudget: ByteBudget? = null,
): ByteArray {
    val initialSize = minOf(maximumBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt()
    return ByteArrayOutputStream(initialSize).use { output ->
        copyWithLimit(input, output, maximumBytes, label, aggregateBudget)
        output.toByteArray()
    }
}

internal fun requireValidBookIdentity(id: String, isDemo: Boolean): String {
    if (isDemo) {
        require(id == DEMO_BOOK_ID) { "The backup contains an invalid sample-book identity." }
        return id
    }
    val parsed = runCatching { UUID.fromString(id) }.getOrNull()
    require(parsed != null && parsed.toString() == id) {
        "The backup contains an invalid book identity."
    }
    return id
}

internal fun ownedDirectChild(root: File, name: String): File {
    require(name.isNotBlank() && name != "." && name != "..") {
        "An invalid app-owned filename was rejected."
    }
    require('/' !in name && '\\' !in name && '\u0000' !in name) {
        "An invalid app-owned filename was rejected."
    }
    val canonicalRoot = root.canonicalFile
    val canonicalChild = File(canonicalRoot, name).canonicalFile
    require(canonicalChild.parentFile == canonicalRoot) {
        "An app-owned path escaped its expected directory."
    }
    return canonicalChild
}

internal fun storageBudget(root: File, configuredMaximum: Long, reserveBytes: Long): Long =
    minOf(configuredMaximum, (root.usableSpace - reserveBytes).coerceAtLeast(0L))
