package com.rawwave.mobile.samsunghealth.export

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString

/** The result of packaging one export. */
class FullExportReport(
    val entries: List<String>,
    val files: List<ExportFileRecord>,
    val manifestJson: String,
    val runStatus: ExportRunStatus,
) {
    val fileCount: Int get() = entries.size
}

/**
 * Raised when the archive could not be produced.
 *
 * Like RawWave's session exporter, this is all-or-nothing: a ZIP that claims to be a complete
 * Samsung Health export while silently missing a declared file is worse than no ZIP, because it
 * looks like evidence.
 */
class FullExportException(message: String) : Exception(message)

/**
 * Packages a finished export into the archive.
 *
 * The rules, and why they are these rules:
 *
 * 1. **Staged files are copied byte for byte and re-hashed while they are copied.** The size and
 *    SHA-256 that go into `manifest.json` describe what was actually written into the ZIP, not what
 *    the writer intended to write. The staging-time hash is checked against the copy, so a file that
 *    changed underneath the export fails the archive instead of being reported as fine.
 * 2. **`manifest.json` is written last**, so its presence means the archive is complete.
 * 3. **Nothing is summarised.** A JSONL file with one line per record goes in as-is; the archive is
 *    compressed by the ZIP, not by dropping detail.
 * 4. **Nothing is held in memory.** Files are streamed one at a time; the exporter never builds an
 *    in-memory copy of the export.
 */
class SamsungHealthFullExporter(
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun export(out: OutputStream, run: ExportRunResult, zoneId: ZoneId): FullExportReport {
        val exportTime = timeOf(clock(), zoneId)

        // The generated documents are produced from the run's own accounting, so they cannot
        // disagree with the per-type summaries.
        val catalogDocument = CatalogDocument(
            schemaVersion = FULL_EXPORT_SCHEMA_VERSION,
            sdkVersion = run.sdkVersion,
            sdkVersionCode = run.sdkVersionCode,
            generatedAt = exportTime,
            officialDataTypeCount = run.officialDataTypeCount,
            howObtained = "Enumerated at runtime from the installed Samsung Health Data SDK's own " +
                "DataTypes registry, then described from each type's own fields and aggregate " +
                "operations. RawWave keeps no hand-written data-type list.",
            aggregateGrouping = AGGREGATE_GROUPING_POLICY,
            aggregateGroupingRationale = AGGREGATE_GROUPING_POLICY_RATIONALE,
            dataTypes = run.catalog,
            statusMeanings = SamsungHealthExportCatalog.statusMeanings(),
        )
        val catalogBytes = ExportJson.documents.encodeToString(catalogDocument).toByteArray(Charsets.UTF_8)

        val semanticsDocument = buildSemantics(run, exportTime)
        val semanticsBytes = ExportJson.documents.encodeToString(semanticsDocument).toByteArray(Charsets.UTF_8)

        val files = ArrayList<ExportFileRecord>()
        val written = ArrayList<String>()

        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            // 1. Everything the SDK produced, streamed in and measured on the way through.
            for (staged in run.stagedFiles) {
                val record = copyStaged(zip, staged)
                files += record
                written += staged.relativePath
            }

            // 2. The generated documents.
            written += writeBytes(zip, CATALOG_PATH, catalogBytes, files)
            written += writeBytes(zip, SEMANTICS_PATH, semanticsBytes, files)

            // 3. The index, last.
            val manifest = buildManifest(run, exportTime, files)
            val manifestBytes = ExportJson.documents.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(MANIFEST_PATH))
            zip.write(manifestBytes)
            zip.closeEntry()
            written += MANIFEST_PATH

            if (written.last() != MANIFEST_PATH) {
                throw FullExportException("$MANIFEST_PATH was not the last entry; the archive is not complete")
            }
            if (written.size != files.size + 1) {
                throw FullExportException(
                    "the archive lists ${files.size} files but wrote ${written.size} entries",
                )
            }

            return FullExportReport(
                entries = written,
                files = files,
                manifestJson = String(manifestBytes, Charsets.UTF_8),
                runStatus = run.status,
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pieces
    // ------------------------------------------------------------------------------------------

    private fun buildManifest(
        run: ExportRunResult,
        exportTime: SourceTime,
        files: List<ExportFileRecord>,
    ) = ExportManifest(
        schemaVersion = FULL_EXPORT_SCHEMA_VERSION,
        appVersion = appVersion,
        sdkVersion = run.sdkVersion,
        exportTime = exportTime,
        exportMode = run.mode,
        runStatus = run.status,
        historyScope = if (run.mode == ExportMode.ALL_AVAILABLE_HISTORY) {
            HISTORY_SCOPE_ALL_CURRENTLY_ACCESSIBLE
        } else {
            null
        },
        userTimeSemantics = UserTimeSemantics(
            dailyBoundaryLocalTime = run.window.dailyBoundary.toString(),
            timezoneId = run.window.zoneId.id,
        ),
        requestedLogicalRange = run.window.toDocument().requestedLogicalRange,
        resolvedQueryWindow = run.window.toDocument(),
        officialDataTypeCount = run.officialDataTypeCount,
        dataTypeSummary = run.summaries,
        errors = run.errors,
        files = files,
        manifestNote = "$MANIFEST_PATH is written last and therefore does not list its own hash; " +
            "every other file in this archive is listed with the exact size and sha256 of the bytes " +
            "written into it.",
    )

    private fun buildSemantics(run: ExportRunResult, exportTime: SourceTime) = ExportSemanticsDocument(
        schemaVersion = FULL_EXPORT_SCHEMA_VERSION,
        generatedAt = exportTime,
        documentPurpose = ExportSemanticsDocument.PURPOSE,
        dataOrigin = DataOriginSemantics(),
        sourceTimePolicy = ExportManifest.SOURCE_TIME_POLICY,
        sourceTimestampsModified = false,
        logicalDayPolicy =
            "The user's 'my day starts at' setting (daily boundary) converts chosen logical dates " +
                "into a query window. It is the ONLY thing it does. It does not modify, shift or " +
                "reinterpret any Samsung Health timestamp, and no source record is split at the " +
                "boundary.",
        dailyBoundaryLocalTime = run.window.dailyBoundary.toString(),
        timezoneId = run.window.zoneId.id,
        timezoneResolution =
            "Resolved with java.time ZoneRules at export time. A local time inside a DST gap is " +
                "moved forward by the gap; a local time that occurs twice resolves to the earlier " +
                "offset. The actual resolved instants and offsets are in manifest.resolved_query_window.",
        rangeSemantics = ResolvedQueryWindow.RANGE_SEMANTICS,
        selectedDatesAre = "USER_LOGICAL_DATES",
        endIsExclusive = true,
        pointRecordInclusionPolicy =
            "A point/sample record is included when its own source timestamp falls inside " +
                "[resolved_start, resolved_end_exclusive). It is never moved to a day boundary.",
        intervalRecordInclusionPolicy =
            "An interval/session record is included when source_start < resolved_end AND " +
                "source_end > resolved_start. The record is then carried WHOLE. Its source start and " +
                "end are never clipped, split or trimmed to the window, even when it crosses the " +
                "daily boundary; rawave_export_context.window_relation says how it sits relative to " +
                "the window so a reader can tell a record that happens to overlap from one that " +
                "genuinely sits inside. Sleep and exercise queries are padded by 48 hours on both " +
                "sides solely to FIND such overlapping sessions; inclusion still uses the exact " +
                "resolved window above.",
        associatedDataPolicy =
            "Associated data is driven by the parent record: when a parent is included, its " +
                "associated data is read in full and kept whole. It is never cut at the day boundary.",
        aggregateDataPolicy =
            "Types Samsung only exposes through aggregate operations are requested through " +
                "Samsung's own official aggregate operations over the resolved window. No raw record " +
                "is invented from an aggregate result, and the operation, grouping, window and " +
                "Samsung's own semantics are recorded next to every value.",
        aggregateGrouping = AGGREGATE_GROUPING_POLICY,
        aggregateGroupingRationale = AGGREGATE_GROUPING_POLICY_RATIONALE,
        intervalIsNeverClipped = true,
        windowRelations = linkedMapOf(
            WindowRelation.INSIDE_WINDOW.name to
                "The whole record lies inside the window.",
            WindowRelation.OVERLAPS_WINDOW_START.name to
                "The record starts before the window and ends inside it. The part before the window " +
                "start is NOT inside the window; it is present only because the record is kept whole.",
            WindowRelation.OVERLAPS_WINDOW_END.name to
                "The record starts inside the window and ends after it. The part after the window " +
                "end is NOT inside the window; it is present only because the record is kept whole.",
            WindowRelation.COVERS_WINDOW.name to
                "The record covers the entire window.",
            WindowRelation.OUTSIDE_WINDOW.name to
                "No part of the record is inside the window.",
            WindowRelation.NOT_APPLICABLE.name to
                "The window is unbounded (all available history) or the record carries no time, so " +
                "no relation can be stated.",
        ),
        inclusionReasons = linkedMapOf(
            IncludedBy.POINT_IN_WINDOW.name to "The record's own timestamp is inside the window.",
            IncludedBy.INTERVAL_OVERLAP.name to
                "The record intersected the window and was carried whole.",
            IncludedBy.ASSOCIATED_OF_INCLUDED_PARENT.name to
                "The record belongs to a parent record that was included.",
            IncludedBy.NO_TIME_FILTER.name to
                "Samsung exposes no time filter for this type; it is exported as a current value.",
            IncludedBy.ALL_AVAILABLE_HISTORY.name to
                "All-available-history mode: no window filtered anything out.",
            IncludedBy.AGGREGATE_OVER_WINDOW.name to
                "Samsung Health computed this value over the resolved window.",
            IncludedBy.SAMSUNG_COARSER_TIME_FILTER.name to
                "Samsung's own filter for this type is day-granular, so it cannot express the user's " +
                "clock boundary. Samsung selected the records and RawWave did NOT re-filter them, " +
                "because discarding rows Samsung returned would lose real data.",
            IncludedBy.NESTED_IN_INCLUDED_PARENT.name to
                "The record was reached nested inside an included parent record.",
        ),
        recordAnatomy = linkedMapOf(
            "source" to "Everything Samsung Health supplied. RawWave wrote none of it.",
            "rawave_export_context" to
                "RawWave's annotation: why this record is here and how it relates to the window. " +
                "It never overwrites a source field.",
            "source.fields" to
                "Every field the installed SDK exposes for this data type, keyed by the SDK's own " +
                "field name. Nested values (sleep sessions and stages, exercise routes, heart-rate " +
                "series and so on) are expanded in place, not summarised.",
            "source.field_read_errors" to
                "Fields that the SDK refused to read, by name, so an unreadable field is never " +
                "mistaken for an absent one.",
            "rawave_export_context.logical_day_context" to
                "A LABEL derived from the daily boundary. For a single instant it carries " +
                "logical_day_key; for an interval that crosses the boundary it carries " +
                "logical_days_touched instead, because one date would misrepresent the record.",
        ),
        fieldExtractionPolicy =
            "Fields are read from the SDK's own field descriptors for each data type and from the " +
                "public accessors of nested entry objects. RawWave keeps no hand-written field list, " +
                "so a field cannot be dropped because a developer did not recognise it, and a field " +
                "added by a future SDK version appears automatically.",
        enumPolicy =
            "Enumerated values are written as Samsung's own constant name, verbatim. RawWave does " +
                "not map them to its own vocabulary, so an unrecognised or newly added constant is " +
                "still preserved exactly.",
        dedupPolicy =
            "Records are de-duplicated by the Samsung Health data UID, which every data point " +
                "carries. Aggregate values, which have no UID, are de-duplicated by operation plus " +
                "the exact time bucket Samsung attributed them to. Duplicates suppressed are counted " +
                "per type in data_type_summary.",
        paginationPolicy =
            "Every read and every aggregate is paginated to the end. A read never stops at an " +
                "arbitrary limit. If Samsung repeats a page token the type is stopped and reported " +
                "as PAGINATION_ERROR rather than looping or duplicating; a page-count safety limit " +
                "reports QUERY_LIMIT_REACHED instead of silently truncating.",
        allHistoryPolicy =
            "All-available-history means 'everything the SDK can currently give us'. Where Samsung " +
                "allows a read with no time filter, none is applied. Where a filter is required, a " +
                "single unbounded lower bound of 1970-01-01 is used - an official, reliable bound, " +
                "never a heuristic such as 'a few empty windows mean there is nothing older'. " +
                "RawWave makes no claim about data Samsung Health has already discarded.",
        statusMeanings = SamsungHealthExportCatalog.statusMeanings(),
        fileLayout = linkedMapOf(
            MANIFEST_PATH to "This archive's index. Written last. Lists every other file with its size and sha256.",
            SEMANTICS_PATH to "This document: the machine-readable time and provenance semantics.",
            CATALOG_PATH to "Every official Samsung Health data type, what RawWave did with it, and its status.",
            "types/<type_id>/records.jsonl" to
                "One JSON object per line: {\"source\": ..., \"rawave_export_context\": ...}.",
            "types/<type_id>/aggregates.jsonl" to
                "Samsung's own aggregate values for that type, one per line, each tagged with the " +
                "operation and grouping used.",
            "types/<type_id>/associated/<associate_id>.jsonl" to
                "Associated data belonging to that type's included records (for sleep: blood oxygen " +
                "and skin temperature).",
            "types/user_profile/profile.jsonl" to
                "The current user profile. It has no time filter and is not placed in a date window.",
        ),
    )

    // ------------------------------------------------------------------------------------------
    // ZIP mechanics
    // ------------------------------------------------------------------------------------------

    /**
     * Streams one staged file into the ZIP while hashing exactly the bytes written.
     *
     * The staging-time hash is compared against this one. They are computed at different times from
     * the same bytes, so a mismatch means the file changed underneath the export and the archive
     * must not claim to describe it.
     */
    private fun copyStaged(zip: ZipOutputStream, staged: StagedTypeFile): ExportFileRecord {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L

        zip.putNextEntry(ZipEntry(staged.relativePath))
        staged.file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                zip.write(buffer, 0, read)
                size += read
            }
        }
        zip.closeEntry()

        val actual = digest.digest().toHex()
        if (actual != staged.sha256AsWritten) {
            throw FullExportException(
                "${staged.relativePath} changed while it was being packaged: staging hash " +
                    "${staged.sha256AsWritten} but the packaged bytes hash to $actual",
            )
        }
        if (size.toLong() != staged.sizeBytes) {
            throw FullExportException(
                "${staged.relativePath} was ${staged.sizeBytes} bytes at staging but $size bytes " +
                    "when packaged",
            )
        }
        return ExportFileRecord(path = staged.relativePath, sizeBytes = size, sha256 = actual)
    }

    private fun writeBytes(
        zip: ZipOutputStream,
        path: String,
        bytes: ByteArray,
        files: MutableList<ExportFileRecord>,
    ): String {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
        files += ExportFileRecord(
            path = path,
            sizeBytes = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
        )
        return path
    }

    private fun timeOf(epochMs: Long, zoneId: ZoneId): SourceTime {
        val instant = Instant.ofEpochMilli(epochMs)
        val offset = zoneId.rules.getOffset(instant)
        return SourceTime(
            epochMs = epochMs,
            isoInstantUtc = instant.toString(),
            localDateTime = instant.atOffset(offset).toLocalDateTime().toString(),
            zoneOffset = offset.id,
            zoneOffsetTotalSeconds = offset.totalSeconds,
        )
    }

    companion object {
        const val MANIFEST_PATH: String = "manifest.json"
        const val SEMANTICS_PATH: String = "export_semantics.json"
        const val CATALOG_PATH: String = "catalog.json"

        private const val BUFFER_SIZE = 64 * 1024

        /** The archive name for a run. Date-range exports name the days; all-history ones are stamped. */
        fun archiveNameFor(
            mode: ExportMode,
            window: ResolvedExportWindow,
            epochMs: Long,
            zoneId: ZoneId,
        ): String = when (mode) {
            ExportMode.DATE_RANGE -> {
                val start = window.requestedStartDate ?: LocalDate.now(zoneId)
                val end = window.requestedEndDate ?: start
                "SamsungHealthExport_${start}_${end}.zip"
            }

            ExportMode.ALL_AVAILABLE_HISTORY -> {
                val stamp = Instant.ofEpochMilli(epochMs)
                    .atZone(zoneId)
                    .toLocalDateTime()
                    .toString()
                    .replace(':', '-')
                    .substringBeforeLast('-')
                "SamsungHealthExport_AllHistory_$stamp.zip"
            }
        }
    }
}

/** Convenience for callers that hold a `File`. */
fun File.exportStagingRootFor(timestampMs: Long): File = File(this, "samsunghealth-export-$timestampMs")
