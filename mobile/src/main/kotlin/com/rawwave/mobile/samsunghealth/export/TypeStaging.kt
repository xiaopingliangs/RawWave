package com.rawwave.mobile.samsunghealth.export

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** What kind of content a staged file holds. */
enum class StagedKind {
    /** `readData` records as JSONL. */
    RECORDS,

    /** `aggregateData` values as JSONL. */
    AGGREGATES,

    /** Associated data belonging to a parent type, as JSONL. */
    ASSOCIATED,

    /** The user profile: one JSON object, no time series. */
    PROFILE,
}

/** A finished staging file: where it is, how big it is, and what it hashed to. */
class StagedTypeFile(
    val relativePath: String,
    val typeId: String,
    val kind: StagedKind,
    val file: File,
    val sizeBytes: Long,
    /** Hash of the bytes as they were written, checked again while copying into the ZIP. */
    val sha256AsWritten: String,
    val recordCount: Long,
)

/**
 * Writes one data type's records to a staging file, one JSON object per line.
 *
 * This is what keeps an all-history export from having to fit in memory: a page is read from the
 * SDK, serialized, appended to disk, counted and hashed, and then released. Memory stays flat
 * whether the store holds a thousand heart-rate points or ten million.
 *
 * JSONL rather than one big JSON array, because the archive is meant to be read back by a program or
 * a model that may want to stream it, and because a single malformed line does not invalidate the
 * whole file.
 */
class TypeStageWriter internal constructor(
    val relativePath: String,
    val typeId: String,
    val kind: StagedKind,
    private val target: File,
) {

    private val out = BufferedOutputStream(FileOutputStream(target))
    private val digest = MessageDigest.getInstance("SHA-256")
    private var size = 0L
    private var records = 0L
    private var closed = false

    /** Appends one already-serialized JSON object as a single line. */
    fun writeLine(json: String) {
        check(!closed) { "staging writer for $relativePath is already closed" }
        val bytes = (json + "\n").toByteArray(Charsets.UTF_8)
        out.write(bytes)
        digest.update(bytes)
        size += bytes.size
        records++
    }

    val recordCount: Long get() = records

    fun finish(): StagedTypeFile {
        check(!closed) { "staging writer for $relativePath was closed twice" }
        closed = true
        out.flush()
        out.close()
        return StagedTypeFile(
            relativePath = relativePath,
            typeId = typeId,
            kind = kind,
            file = target,
            sizeBytes = size,
            sha256AsWritten = digest.digest().toHex(),
            recordCount = records,
        )
    }
}

/**
 * The staging area for one export.
 *
 * Staging exists so the final ZIP is assembled from finished, measured files rather than from a
 * growing in-memory structure. If the run is cancelled or fails, everything here is deleted: a ZIP
 * that looks complete but is not is worse than no ZIP.
 */
class ExportStaging(root: File) {

    val root: File = root

    private val opened = ArrayList<TypeStageWriter>()
    private val finished = ArrayList<StagedTypeFile>()

    init {
        if (root.exists()) root.deleteRecursively()
        check(root.mkdirs() || root.isDirectory) { "could not create the staging directory $root" }
    }

    /** Opens (or re-opens, appending) a staging file for one type. */
    fun open(relativePath: String, typeId: String, kind: StagedKind): TypeStageWriter {
        val target = File(root, relativePath)
        target.parentFile?.mkdirs()
        val writer = TypeStageWriter(relativePath, typeId, kind, target)
        opened += writer
        return writer
    }

    fun adopt(staged: StagedTypeFile) {
        finished += staged
    }

    fun staged(): List<StagedTypeFile> = finished.toList()

    /** Removes every staging artifact. Called on cancel and on any fatal failure. */
    fun discardAll() {
        opened.forEach { runCatching { it.finish() } }
        opened.clear()
        finished.clear()
        runCatching { root.deleteRecursively() }
    }

    fun cleanupOnSuccess() {
        runCatching { root.deleteRecursively() }
    }
}

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
