package com.rawwave.mobile.samsunghealth.export

import com.rawwave.mobile.samsunghealth.AggregateSpec
import com.rawwave.mobile.samsunghealth.AggregateValue
import com.rawwave.mobile.samsunghealth.AssociatedSpec
import com.rawwave.mobile.samsunghealth.DEFAULT_PAGE_SIZE
import com.rawwave.mobile.samsunghealth.DataTypeInfo
import com.rawwave.mobile.samsunghealth.GatewayRead
import com.rawwave.mobile.samsunghealth.ReadSpec
import com.rawwave.mobile.samsunghealth.SamsungHealthGateway
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive

/** Where one data type's export has got to. */
class TypeProgress(
    val typeId: String,
    val typeIndex: Int,
    val typeTotal: Int,
    val recordsSoFar: Long,
    val pages: Int,
    val phase: String,
)

/** Everything one type's export needs. */
class TypeExportRequest(
    val info: DataTypeInfo,
    val window: ResolvedExportWindow,
    val permissionGranted: Boolean,
    val staging: ExportStaging,
    val typeIndex: Int,
    val typeTotal: Int,
    val onProgress: (TypeProgress) -> Unit,
    val isCancelled: () -> Boolean,
)

/** What one type's export produced. */
class TypeExportOutcome(
    val summary: TypeSummary,
    val stagedFiles: List<StagedTypeFile>,
    val errors: List<ExportError>,
    val cancelled: Boolean = false,
)

/**
 * Exports exactly one official Samsung Health data type.
 *
 * This is deliberately **not** a loop that calls `readData` on every type and hopes they behave the
 * same. Each type's route is decided from what the SDK itself declares: whether it is readable,
 * whether it is aggregate-only, whether it has a time filter at all, whether that filter is exact or
 * day-granular, and whether it has no entry point of its own. A type that answers differently is
 * exported differently, and `catalog.json` records which route was taken.
 *
 * Three invariants hold throughout:
 *
 * - **Nothing is summarised away.** Every record the SDK returned is written, with every field.
 * - **Nothing is clipped.** An interval that merely intersects the window is written whole.
 * - **Nothing fails the run.** A type that breaks gets a status and a reason; the others continue.
 */
class DataTypeExportAdapter(
    private val gateway: SamsungHealthGateway,
    private val catalog: SamsungHealthExportCatalog,
) {

    suspend fun export(request: TypeExportRequest): TypeExportOutcome {
        val info = request.info
        val operation = catalog.chosenOperation(info)

        if (!request.permissionGranted) {
            return preconditionOutcome(
                request = request,
                operation = operation,
                status = ExportStatus.PERMISSION_DENIED,
                message = "READ_${info.sdkFieldName} was not granted, so nothing was read for this type.",
            )
        }

        return when (operation) {
            ExportOperation.NESTED_IN_PARENT -> nestedOnly(request, operation)
            ExportOperation.READ_USER_PROFILE -> readUserProfile(request, operation)
            ExportOperation.READ_RECORDS -> readRecordsThenExtras(request, operation)
            ExportOperation.AGGREGATE -> aggregateOnly(request, operation)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Routes
    // ------------------------------------------------------------------------------------------

    /**
     * A type with no read or aggregate entry point of its own.
     *
     * Its data is exported inside its parent, so reporting an error here would be wrong and
     * reporting nothing at all would be worse. It gets an explicit status and a note.
     */
    private fun nestedOnly(request: TypeExportRequest, operation: ExportOperation): TypeExportOutcome {
        val parent = request.info.nestedInParentTypeId
        return TypeExportOutcome(
            summary = summaryOf(
                request = request,
                operation = operation,
                status = ExportStatus.NESTED_IN_PARENT_TYPE,
                seen = 0,
                exported = 0,
                pages = 0,
                duplicates = 0,
                dedupStrategy = null,
                outputPath = null,
                notes = listOf(
                    "SDK 1.1.0 declares no read or aggregate entry point for this type. Its data is " +
                        "exported nested inside '${parent ?: "its parent type"}'.",
                ),
            ),
            stagedFiles = emptyList(),
            errors = emptyList(),
        )
    }

    /** A type with no time filter: exported as a current value, never forced into a date window. */
    private suspend fun readUserProfile(
        request: TypeExportRequest,
        operation: ExportOperation,
    ): TypeExportOutcome {
        val info = request.info
        val path = catalog.profilePath(info)
            ?: return preconditionOutcome(request, operation, ExportStatus.NOT_IMPLEMENTED, "no profile path resolved")

        return when (val page = gateway.readPage(ReadSpec(typeId = info.typeId, window = null))) {
            is GatewayRead.Failed -> failedOutcome(request, operation, page, pageCount = 1)

            is GatewayRead.Ok -> {
                val records = page.value.payloads.map { payload ->
                    SourceRecord(
                        source = payload,
                        context = contextFor(request, IncludedBy.NO_TIME_FILTER, WindowRelation.NOT_APPLICABLE, null, null),
                    )
                }
                val staged = writeRecords(request, path, info.typeId, StagedKind.PROFILE, records)
                TypeExportOutcome(
                    summary = summaryOf(
                        request = request,
                        operation = operation,
                        status = if (records.isEmpty()) ExportStatus.NO_DATA else ExportStatus.OK,
                        seen = records.size.toLong(),
                        exported = records.size.toLong(),
                        pages = 1,
                        duplicates = 0,
                        dedupStrategy = null,
                        outputPath = path,
                        notes = listOf(
                            "Samsung exposes no time filter for this type, so it is exported as a " +
                                "current value and is not placed inside the requested date range.",
                        ),
                    ),
                    stagedFiles = listOfNotNull(staged),
                    errors = emptyList(),
                )
            }
        }
    }

    /** The ordinary case: paginated records, plus official aggregates and associated data. */
    private suspend fun readRecordsThenExtras(
        request: TypeExportRequest,
        operation: ExportOperation,
    ): TypeExportOutcome {
        val info = request.info
        val errors = ArrayList<ExportError>()
        val staged = ArrayList<StagedTypeFile>()
        val notes = ArrayList<String>()

        val records = readAllRecords(request, errors, notes)
        if (records.cancelled) {
            return TypeExportOutcome(records.summary, emptyList(), errors, cancelled = true)
        }
        records.stagedFile?.let { staged += it }

        var aggregateOperations = 0
        if (info.aggregateOperations.isNotEmpty()) {
            val aggregates = readAggregates(request, errors, notes)
            aggregateOperations = aggregates.operationCount
            aggregates.stagedFile?.let { staged += it }
        }

        var associatedRecords = 0L
        if (info.associatedTypes.isNotEmpty() && records.parentUids.isNotEmpty()) {
            val associated = readAssociated(request, records.parentUids, errors, notes)
            associatedRecords = associated.recordCount
            staged += associated.stagedFiles
        }

        return TypeExportOutcome(
            summary = summaryOf(
                request = request,
                operation = operation,
                status = records.status,
                seen = records.seen,
                exported = records.exported,
                pages = records.pages,
                duplicates = records.duplicates,
                dedupStrategy = UID_DEDUP_STRATEGY,
                outputPath = catalog.primaryOutputPath(info),
                associatedCount = associatedRecords,
                aggregateCount = aggregateOperations,
                errorCode = records.failure?.errorCode,
                errorMessage = records.failure?.message,
                notes = notes,
            ),
            stagedFiles = staged,
            errors = errors,
        )
    }

    /** A type Samsung only allows to be aggregated. Nothing is faked into a raw record. */
    private suspend fun aggregateOnly(
        request: TypeExportRequest,
        operation: ExportOperation,
    ): TypeExportOutcome {
        val errors = ArrayList<ExportError>()
        val notes = ArrayList<String>()
        val aggregates = readAggregates(request, errors, notes)

        val status = when {
            aggregates.failure != null && aggregates.recordCount == 0L -> aggregates.failure.status
            aggregates.failure != null -> aggregates.failure.status
            aggregates.recordCount == 0L -> ExportStatus.NO_DATA
            else -> ExportStatus.OK
        }

        return TypeExportOutcome(
            summary = summaryOf(
                request = request,
                operation = operation,
                status = status,
                seen = aggregates.recordCount,
                exported = aggregates.recordCount,
                pages = aggregates.pages,
                duplicates = aggregates.duplicates,
                dedupStrategy = AGGREGATE_DEDUP_STRATEGY,
                outputPath = catalog.aggregatesPath(request.info),
                aggregateCount = aggregates.operationCount,
                errorCode = aggregates.failure?.errorCode,
                errorMessage = aggregates.failure?.message,
                notes = notes,
            ),
            stagedFiles = listOfNotNull(aggregates.stagedFile),
            errors = errors,
        )
    }

    // ------------------------------------------------------------------------------------------
    // Reads, paginated
    // ------------------------------------------------------------------------------------------

    private class RecordsOutcome(
        val seen: Long,
        val exported: Long,
        val pages: Int,
        val duplicates: Long,
        val status: ExportStatus,
        val stagedFile: StagedTypeFile?,
        val parentUids: List<String>,
        val failure: GatewayRead.Failed?,
        val summary: TypeSummary,
        val cancelled: Boolean = false,
    )

    private suspend fun readAllRecords(
        request: TypeExportRequest,
        errors: MutableList<ExportError>,
        notes: MutableList<String>,
    ): RecordsOutcome {
        val info = request.info
        val path = catalog.recordsPath(info)

        val bounded = !request.window.isUnbounded
        val coarse = bounded && info.readTimeModel == TimeModel.LOCAL_DATE
        if (coarse) {
            notes += "Samsung only supports a day-granular time filter for this type, so the " +
                "requested logical days were sent as a local-date range and the returned records " +
                "were NOT re-filtered against the exact instant window. Re-filtering would discard " +
                "data Samsung deliberately returned."
        }
        if (!bounded) {
            notes += "All-available-history mode: this type was read with no time filter (Samsung's " +
                "own complete read) and paginated to the end."
        }

        val specWindow = if (bounded) request.window else null
        val intervalQueryWindow = if (bounded && info.typeId in setOf("sleep", "exercise")) {
            notes += "The interval query was padded by 48 hours before the window and 48 hours " +
                "after it to FIND sessions that start before or end after the requested range. " +
                "Inclusion still uses the exact logical window; a returned record is kept only " +
                "when it overlaps that window, and is then kept whole."
            request.window.paddedQueryWindow(Duration.ofHours(48))
        } else {
            null
        }
        val seenUids = HashSet<String>()
        val seenTokens = HashSet<String>()
        val parentUids = ArrayList<String>()
        val collectParents = info.associatedTypes.isNotEmpty()

        var seen = 0L
        var exported = 0L
        var duplicates = 0L
        var dropped = 0L
        var pages = 0
        var token: String? = null
        var failure: GatewayRead.Failed? = null
        var writer: TypeStageWriter? = if (path != null) {
            request.staging.open(path, info.typeId, StagedKind.RECORDS)
        } else {
            null
        }

        fun finish(): StagedTypeFile? {
            val open = writer ?: return null
            writer = null
            val finished = open.finish()
            return if (finished.recordCount > 0) {
                request.staging.adopt(finished)
                finished
            } else {
                null
            }
        }

        fun buildOutcome(cancelled: Boolean): RecordsOutcome {
            val staged = finish()
            val status = when {
                cancelled -> ExportStatus.SDK_ERROR // never used on the cancel path
                failure != null -> failure!!.status
                exported == 0L -> ExportStatus.NO_DATA
                else -> ExportStatus.OK
            }
            if (dropped > 0) {
                notes += "$dropped record(s) Samsung returned fell outside the exact half-open " +
                    "window and were not exported; they are counted here rather than dropped silently."
            }
            return RecordsOutcome(
                seen = seen,
                exported = exported,
                pages = pages,
                duplicates = duplicates,
                status = status,
                stagedFile = staged,
                parentUids = parentUids,
                failure = failure,
                summary = summaryOf(
                    request = request,
                    operation = ExportOperation.READ_RECORDS,
                    status = status,
                    seen = seen,
                    exported = exported,
                    pages = pages,
                    duplicates = duplicates,
                    dedupStrategy = UID_DEDUP_STRATEGY,
                    outputPath = if (exported > 0) path else null,
                    errorCode = failure?.errorCode,
                    errorMessage = failure?.message,
                    notes = notes,
                ),
                cancelled = cancelled,
            )
        }

        if (writer == null) {
            return buildOutcome(cancelled = false)
        }

        while (true) {
            if (request.isCancelled()) {
                return buildOutcome(cancelled = true)
            }

            val page = gateway.readPage(
                ReadSpec(
                    typeId = info.typeId,
                    window = specWindow,
                    queryWindow = intervalQueryWindow,
                    pageSize = DEFAULT_PAGE_SIZE,
                    pageToken = token,
                    useSamsungCoarseFilter = coarse,
                ),
            )

            when (page) {
                is GatewayRead.Failed -> {
                    failure = page
                    errors += failureError(info.typeId, "read", page)
                    notes += "Stopped after $pages page(s): ${page.message ?: page.status.name}. " +
                        "This type's data is partial."
                    return buildOutcome(cancelled = false)
                }

                is GatewayRead.Ok -> {
                    pages++
                    for (payload in page.value.payloads) {
                        seen++

                        // De-duplication by the Samsung UID. Every HealthDataPoint carries one, so
                        // overlapping windows and repeated pagination can never double-export a
                        // record, and two genuinely different records can never be merged.
                        val uid = payload.uid
                        if (uid != null && !seenUids.add(uid)) {
                            duplicates++
                            continue
                        }

                        val decision = inclusionOf(request, payload, coarse)
                        if (!decision.include) {
                            dropped++
                            continue
                        }

                        if (collectParents && uid != null) parentUids += uid

                        writer?.writeLine(ExportJson.records.encodeToString(sourceRecord(request, payload, decision)))
                        exported++
                    }

                    request.onProgress(
                        TypeProgress(info.typeId, request.typeIndex, request.typeTotal, exported, pages, "reading"),
                    )

                    val next = page.value.nextPageToken
                    if (next == null) return buildOutcome(cancelled = false)

                    if (!seenTokens.add(next)) {
                        // A repeated page token means Samsung is looping. Stopping and saying so is
                        // the only safe answer: continuing would spin forever or duplicate data
                        // indefinitely.
                        failure = GatewayRead.Failed(
                            status = ExportStatus.PAGINATION_ERROR,
                            errorCode = null,
                            exception = null,
                            message = "pagination token repeated: PAGINATION_TOKEN_LOOP",
                        )
                        errors += failureError(info.typeId, "pagination", failure)
                        notes += "Stopped because Samsung repeated a page token " +
                            "(PAGINATION_TOKEN_LOOP). This type's data is partial."
                        return buildOutcome(cancelled = false)
                    }

                    if (pages >= MAX_PAGES_PER_TYPE) {
                        failure = GatewayRead.Failed(
                            status = ExportStatus.QUERY_LIMIT_REACHED,
                            errorCode = null,
                            exception = null,
                            message = "reached the $MAX_PAGES_PER_TYPE page safety limit",
                        )
                        errors += failureError(info.typeId, "pagination", failure)
                        notes += "Stopped at the page safety limit; this type's data is partial."
                        return buildOutcome(cancelled = false)
                    }

                    token = next
                }
            }
        }
    }

    /**
     * Whether a record belongs in the export, and why.
     *
     * Points must fall inside `[start, end)`. Intervals only have to intersect the window, and are
     * then kept whole - the record is never trimmed to the boundary.
     */
    private class InclusionDecision(
        val include: Boolean,
        val includedBy: IncludedBy,
        val relation: WindowRelation,
    )

    private fun inclusionOf(
        request: TypeExportRequest,
        payload: SourcePayload,
        coarse: Boolean,
    ): InclusionDecision {
        if (request.window.isUnbounded) {
            return InclusionDecision(true, IncludedBy.ALL_AVAILABLE_HISTORY, WindowRelation.NOT_APPLICABLE)
        }
        val start = payload.start?.let { Instant.ofEpochMilli(it.epochMs) }
            ?: return InclusionDecision(true, IncludedBy.NO_TIME_FILTER, WindowRelation.NOT_APPLICABLE)
        val end = payload.end?.let { Instant.ofEpochMilli(it.epochMs) } ?: start
        val relation = request.window.relationOf(start, end)

        // A day-granular Samsung filter cannot be second-guessed without losing data Samsung
        // deliberately returned, so those records are kept and labelled instead.
        if (coarse) {
            return InclusionDecision(true, IncludedBy.SAMSUNG_COARSER_TIME_FILTER, relation)
        }

        return if (start == end) {
            InclusionDecision(request.window.containsPoint(start), IncludedBy.POINT_IN_WINDOW, relation)
        } else {
            InclusionDecision(request.window.overlapsInterval(start, end), IncludedBy.INTERVAL_OVERLAP, relation)
        }
    }

    private fun sourceRecord(
        request: TypeExportRequest,
        payload: SourcePayload,
        decision: InclusionDecision,
    ): SourceRecord = SourceRecord(
        source = payload,
        context = contextFor(
            request = request,
            includedBy = decision.includedBy,
            relation = decision.relation,
            start = payload.start?.let { Instant.ofEpochMilli(it.epochMs) },
            end = payload.end?.let { Instant.ofEpochMilli(it.epochMs) },
        ),
    )

    // ------------------------------------------------------------------------------------------
    // Aggregates, paginated
    // ------------------------------------------------------------------------------------------

    private class AggregatesOutcome(
        val recordCount: Long,
        val pages: Int,
        val duplicates: Long,
        val operationCount: Int,
        val stagedFile: StagedTypeFile?,
        val failure: GatewayRead.Failed?,
    )

    private suspend fun readAggregates(
        request: TypeExportRequest,
        errors: MutableList<ExportError>,
        notes: MutableList<String>,
    ): AggregatesOutcome {
        val info = request.info
        val path = catalog.aggregatesPath(info)
            ?: return AggregatesOutcome(0, 0, 0, 0, null, null)

        notes += "Aggregate grouping is chosen per operation. $AGGREGATE_GROUPING_POLICY_RATIONALE"

        val specWindow = if (request.window.isUnbounded) null else request.window
        val writer = request.staging.open(path, info.typeId, StagedKind.AGGREGATES)
        val seenKeys = HashSet<String>()
        var total = 0L
        var pages = 0
        var duplicates = 0L
        var operations = 0
        var failure: GatewayRead.Failed? = null
        var cancelled = false

        loop@ for (operation in info.aggregateOperations) {
            operations++
            val grouping = AggregateGroupingPolicy.groupingFor(info.typeId, operation.family)
            if (grouping == AggregateGroupingPolicy.MINUTELY) {
                notes += "Operation ${operation.operationName} is requested with MINUTELY grouping. " +
                    "Each value is Samsung's own minute bucket, not a raw record; the finer buckets " +
                    "let a reader sum a non-midnight logical day exactly."
            }
            var token: String? = null
            val seenTokens = HashSet<String>()
            var operationPages = 0

            while (true) {
                if (request.isCancelled()) {
                    cancelled = true
                    break@loop
                }

                val page = gateway.aggregatePage(
                    AggregateSpec(
                        typeId = info.typeId,
                        operationName = operation.operationName,
                        window = specWindow,
                        grouping = grouping,
                        pageSize = DEFAULT_PAGE_SIZE,
                        pageToken = token,
                    ),
                )

                when (page) {
                    is GatewayRead.Failed -> {
                        // One unsupported or refused operation must not lose the others.
                        failure = page
                        errors += failureError(info.typeId, "aggregate:${operation.operationName}", page)
                        notes += "Operation ${operation.operationName} stopped: " +
                            "${page.message ?: page.status.name}. Other operations of this type were " +
                            "still exported."
                        continue@loop
                    }

                    is GatewayRead.Ok -> {
                        pages++
                        operationPages++
                        for (value in page.value.values) {
                            val key = "${operation.operationName}|${value.start?.epochMs}|${value.end?.epochMs}|" +
                                "${value.startLocalDateTime}|${value.endLocalDateTime}"
                            if (!seenKeys.add(key)) {
                                duplicates++
                                continue
                            }
                            writer.writeLine(ExportJson.records.encodeToString(aggregateRecord(request, info, operation, value, grouping)))
                            total++
                        }
                        request.onProgress(
                            TypeProgress(info.typeId, request.typeIndex, request.typeTotal, total, pages, "aggregating"),
                        )

                        val next = page.value.nextPageToken
                        if (next == null) continue@loop
                        if (!seenTokens.add(next) || operationPages >= MAX_PAGES_PER_TYPE) {
                            val guardFailure = GatewayRead.Failed(
                                status = if (operationPages >= MAX_PAGES_PER_TYPE) {
                                    ExportStatus.QUERY_LIMIT_REACHED
                                } else {
                                    ExportStatus.PAGINATION_ERROR
                                },
                                errorCode = null,
                                exception = null,
                                message = if (operationPages >= MAX_PAGES_PER_TYPE) {
                                    "reached the $MAX_PAGES_PER_TYPE page safety limit"
                                } else {
                                    "pagination token repeated: PAGINATION_TOKEN_LOOP"
                                },
                            )
                            failure = guardFailure
                            errors += failureError(info.typeId, "aggregate:${operation.operationName}", guardFailure)
                            notes += "Operation ${operation.operationName} was stopped by a pagination guard."
                            continue@loop
                        }
                        token = next
                    }
                }
            }
        }

        val finished = writer.finish()
        val staged = if (finished.recordCount > 0 && !cancelled) {
            request.staging.adopt(finished)
            finished
        } else {
            null
        }
        return AggregatesOutcome(total, pages, duplicates, operations, staged, failure)
    }

    private fun aggregateRecord(
        request: TypeExportRequest,
        info: DataTypeInfo,
        operation: AggregateOperationInfo,
        value: AggregateValue,
        grouping: String,
    ): SourceRecord = SourceRecord(
        source = SourcePayload(
            sdkDataTypeName = info.sdkTypeName,
            typeId = info.typeId,
            start = value.start,
            end = value.end,
            fields = linkedMapOf(
                "operation" to JsonPrimitive(operation.operationName),
                "operation_type_name" to JsonPrimitive(operation.typeName),
                "grouping" to JsonPrimitive(grouping),
                "value" to value.value,
            ),
        ),
        context = contextFor(
            request = request,
            includedBy = IncludedBy.AGGREGATE_OVER_WINDOW,
            relation = relationForAggregate(request, value),
            start = value.start?.let { Instant.ofEpochMilli(it.epochMs) },
            end = value.end?.let { Instant.ofEpochMilli(it.epochMs) },
            isAggregate = true,
            aggregateOperation = operation.operationName,
            aggregateGrouping = grouping,
            aggregateSdkSemantics = aggregateSemanticsOf(operation.family, grouping),
        ),
    )

    /** Samsung's own semantics for a builder family, recorded rather than reinterpreted. */
    private fun aggregateSemanticsOf(family: TimeModel, grouping: String): String = when (family) {
        TimeModel.DUAL_TIME ->
            "Samsung aggregate over an exact local date-time window, grouped " +
                "${grouping.lowercase()}."

        TimeModel.LOCAL_TIME ->
            if (grouping == AggregateGroupingPolicy.MINUTELY) {
                "Samsung aggregate over a local time window, grouped minutely. Each value is " +
                    "Samsung's own minute aggregate, not a per-step or per-sensor event; the raw " +
                    "record path for this type, when one exists, is exported separately."
            } else {
                "Samsung aggregate over a local time window, grouped daily."
            }

        TimeModel.LOCAL_DATE ->
            "Samsung aggregate over a LOCAL DATE window. Samsung's granularity is one day, so this " +
                "cannot express the user's clock boundary exactly; the logical days the user chose " +
                "were sent as-is and no raw record was faked from the result."

        TimeModel.ALL_SOURCE_LOCAL_DATE ->
            "Samsung aggregate over a local date window across all sources, grouped " +
                "${grouping.lowercase()}. Day granularity, so it cannot express the user's clock " +
                "boundary exactly. For 'LAST' style operations Samsung's own meaning - the last " +
                "value of that day - is preserved rather than reinterpreted."

        TimeModel.NO_TIME_FILTER ->
            "Samsung declares no time model for this operation; its own semantics is preserved."
    }

    private fun relationForAggregate(request: TypeExportRequest, value: AggregateValue): WindowRelation {
        if (request.window.isUnbounded) return WindowRelation.NOT_APPLICABLE
        val start = value.start?.let { Instant.ofEpochMilli(it.epochMs) } ?: return WindowRelation.NOT_APPLICABLE
        val end = value.end?.let { Instant.ofEpochMilli(it.epochMs) } ?: start
        return request.window.relationOf(start, end)
    }

    // ------------------------------------------------------------------------------------------
    // Associated data, paginated
    // ------------------------------------------------------------------------------------------

    private class AssociatedOutcome(val recordCount: Long, val stagedFiles: List<StagedTypeFile>)

    private suspend fun readAssociated(
        request: TypeExportRequest,
        parentUids: List<String>,
        errors: MutableList<ExportError>,
        notes: MutableList<String>,
    ): AssociatedOutcome {
        val info = request.info
        val staged = ArrayList<StagedTypeFile>()
        var total = 0L

        notes += "Associated data is read per included parent record: when a parent is in the " +
            "export, its associated data is kept whole and is never cut at the day boundary."

        for (associateId in info.associatedTypes) {
            val path = catalog.associatedPath(info.typeId, associateId)
            val writer = request.staging.open(path, associateId, StagedKind.ASSOCIATED)
            val seen = HashSet<String>()
            var written = 0L
            var cancelled = false

            for (parentUid in parentUids) {
                if (request.isCancelled()) {
                    cancelled = true
                    break
                }
                var token: String? = null
                val seenTokens = HashSet<String>()
                var pages = 0

                while (true) {
                    val page = gateway.readAssociatedPage(
                        AssociatedSpec(
                            parentTypeId = info.typeId,
                            associateId = associateId,
                            parentUid = parentUid,
                            pageSize = DEFAULT_PAGE_SIZE,
                            pageToken = token,
                        ),
                    )

                    when (page) {
                        is GatewayRead.Failed -> {
                            errors += failureError(info.typeId, "associated:$associateId", page)
                            notes += "Associated '$associateId' stopped: ${page.message ?: page.status.name}."
                            break
                        }

                        is GatewayRead.Ok -> {
                            pages++
                            for (payload in page.value.payloads) {
                                val key = payload.uid ?: "${payload.start?.epochMs}|${payload.end?.epochMs}"
                                if (!seen.add(key)) continue

                                val record = SourceRecord(
                                    source = payload,
                                    context = contextFor(
                                        request = request,
                                        includedBy = IncludedBy.ASSOCIATED_OF_INCLUDED_PARENT,
                                        relation = relationForPayload(request, payload),
                                        start = payload.start?.let { Instant.ofEpochMilli(it.epochMs) },
                                        end = payload.end?.let { Instant.ofEpochMilli(it.epochMs) },
                                        nestedInType = info.typeId,
                                    ),
                                )
                                writer.writeLine(ExportJson.records.encodeToString(record))
                                written++
                                total++
                            }

                            val next = page.value.nextPageToken
                            if (next == null) break
                            if (!seenTokens.add(next) || pages >= MAX_PAGES_PER_TYPE) {
                                errors += ExportError(
                                    scope = "type",
                                    typeId = info.typeId,
                                    stage = "associated:$associateId",
                                    exception = null,
                                    errorCode = null,
                                    message = "associated pagination guard tripped",
                                )
                                notes += "Associated '$associateId' was stopped by a pagination guard."
                                break
                            }
                            token = next
                        }
                    }
                }

                request.onProgress(
                    TypeProgress(
                        info.typeId, request.typeIndex, request.typeTotal, total, pages,
                        "associated:$associateId",
                    ),
                )
            }

            val finished = writer.finish()
            if (finished.recordCount > 0 && !cancelled) {
                request.staging.adopt(finished)
                staged += finished
            }
        }
        return AssociatedOutcome(total, staged)
    }

    private fun relationForPayload(request: TypeExportRequest, payload: SourcePayload): WindowRelation {
        if (request.window.isUnbounded) return WindowRelation.NOT_APPLICABLE
        val start = payload.start?.let { Instant.ofEpochMilli(it.epochMs) }
            ?: return WindowRelation.NOT_APPLICABLE
        val end = payload.end?.let { Instant.ofEpochMilli(it.epochMs) } ?: start
        return request.window.relationOf(start, end)
    }

    // ------------------------------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------------------------------

    /** Builds the RawWave annotation for one record, including its logical-day view. */
    private fun contextFor(
        request: TypeExportRequest,
        includedBy: IncludedBy,
        relation: WindowRelation,
        start: Instant?,
        end: Instant?,
        isAggregate: Boolean = false,
        aggregateOperation: String? = null,
        aggregateGrouping: String? = null,
        aggregateSdkSemantics: String? = null,
        nestedInType: String? = null,
    ): ExportContext {
        val window = request.window
        val startLocal = start?.atZone(window.zoneId)?.toLocalDateTime()
        val endLocal = end?.atZone(window.zoneId)?.toLocalDateTime()

        val dayContext = when {
            startLocal == null -> LogicalDayContext(
                dailyBoundaryLocalTime = window.dailyBoundary.toString(),
            )

            endLocal == null || startLocal == endLocal -> LogicalDayContext(
                // A single instant has one unambiguous logical day.
                logicalDayKey = window.logicalDayKeyOf(startLocal).toString(),
                logicalDaysTouched = listOf(window.logicalDayKeyOf(startLocal).toString()),
                dailyBoundaryLocalTime = window.dailyBoundary.toString(),
            )

            else -> {
                val days = window.logicalDaysTouched(startLocal, endLocal)
                LogicalDayContext(
                    // An interval can touch several logical days. No single key is invented to hide
                    // that; the whole list travels instead.
                    logicalDayKey = null,
                    logicalDaysTouched = days.map { it.toString() },
                    logicalDaysTouchedTruncated = days.size >= ResolvedExportWindow.MAX_LOGICAL_DAYS,
                    dailyBoundaryLocalTime = window.dailyBoundary.toString(),
                )
            }
        }

        return ExportContext(
            includedBy = includedBy,
            windowRelation = relation,
            logicalDayContext = dayContext,
            isAggregate = isAggregate,
            aggregateOperation = aggregateOperation,
            aggregateGrouping = aggregateGrouping,
            aggregateSdkSemantics = aggregateSdkSemantics,
            nestedInType = nestedInType,
        )
    }

    private fun writeRecords(
        request: TypeExportRequest,
        path: String,
        typeId: String,
        kind: StagedKind,
        records: List<SourceRecord>,
    ): StagedTypeFile? {
        val writer = request.staging.open(path, typeId, kind)
        for (record in records) writer.writeLine(ExportJson.records.encodeToString(record))
        val finished = writer.finish()
        return if (finished.recordCount > 0) {
            request.staging.adopt(finished)
            finished
        } else {
            null
        }
    }

    private fun preconditionOutcome(
        request: TypeExportRequest,
        operation: ExportOperation,
        status: ExportStatus,
        message: String,
        notes: List<String> = emptyList(),
    ): TypeExportOutcome {
        val info = request.info
        return TypeExportOutcome(
            summary = summaryOf(
                request = request,
                operation = operation,
                status = status,
                seen = 0,
                exported = 0,
                pages = 0,
                duplicates = 0,
                dedupStrategy = null,
                outputPath = null,
                errorMessage = message,
                notes = notes,
            ),
            stagedFiles = emptyList(),
            errors = listOf(
                ExportError(scope = "type", typeId = info.typeId, stage = "precondition", message = message),
            ),
        )
    }

    private fun failedOutcome(
        request: TypeExportRequest,
        operation: ExportOperation,
        failure: GatewayRead.Failed,
        pageCount: Int,
    ): TypeExportOutcome = TypeExportOutcome(
        summary = summaryOf(
            request = request,
            operation = operation,
            status = failure.status,
            seen = 0,
            exported = 0,
            pages = pageCount,
            duplicates = 0,
            dedupStrategy = null,
            outputPath = null,
            errorCode = failure.errorCode,
            errorMessage = failure.message,
        ),
        stagedFiles = emptyList(),
        errors = listOf(failureError(request.info.typeId, "read", failure)),
    )

    private fun summaryOf(
        request: TypeExportRequest,
        operation: ExportOperation,
        status: ExportStatus,
        seen: Long,
        exported: Long,
        pages: Int,
        duplicates: Long,
        dedupStrategy: String?,
        outputPath: String?,
        associatedCount: Long = 0,
        aggregateCount: Int = 0,
        errorCode: Int? = null,
        errorMessage: String? = null,
        notes: List<String> = emptyList(),
    ) = TypeSummary(
        typeId = request.info.typeId,
        sdkDataTypeName = request.info.sdkTypeName,
        operation = operation,
        status = status,
        permission = "READ_${request.info.sdkFieldName}",
        permissionGranted = request.permissionGranted,
        recordsSeen = seen,
        recordsExported = exported,
        pages = pages,
        duplicatesSuppressed = duplicates,
        dedupKeyStrategy = dedupStrategy,
        associatedRecordCount = associatedCount,
        aggregateOperationCount = aggregateCount,
        outputPath = outputPath,
        errorCode = errorCode,
        errorMessage = errorMessage,
        notes = notes,
    )

    private fun failureError(typeId: String, stage: String, failure: GatewayRead.Failed) = ExportError(
        scope = "type",
        typeId = typeId,
        stage = stage,
        exception = failure.exception,
        errorCode = failure.errorCode,
        message = failure.message,
    )

    companion object {
        /** Guards against an endless page loop. High enough never to bite a real export. */
        const val MAX_PAGES_PER_TYPE: Int = 100_000

        /** Every `HealthDataPoint` carries a Samsung UID, so that is the natural identity. */
        const val UID_DEDUP_STRATEGY: String = "SAMSUNG_HEALTH_DATA_UID"

        /**
         * Aggregates have no UID of their own: Samsung returns a value attributed to a time bucket.
         * The stable key is therefore the operation plus the exact bucket Samsung attributed it to,
         * which de-duplicates a value seen twice without merging two genuinely different buckets.
         */
        const val AGGREGATE_DEDUP_STRATEGY: String = "AGGREGATE_OPERATION+WINDOW_BUCKET"
    }
}
