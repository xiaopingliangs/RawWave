package com.rawwave.mobile.samsunghealth.export

import com.rawwave.mobile.samsunghealth.GatewayRead
import com.rawwave.mobile.samsunghealth.SamsungHealthGateway
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Coarse progress for the screen. Deliberately plain: this is not a dashboard. */
class ExportProgress(
    val phase: String,
    val typeId: String?,
    val typeIndex: Int,
    val typeTotal: Int,
    val records: Long,
    val completedTypes: Int,
)

/**
 * Runs one whole export: resolve the window, then export every official data type in turn.
 *
 * The coordinator owns three decisions and nothing else:
 *
 * - **One type's failure never stops the run.** Each type gets its own status and its own error
 *   entries, and the remaining types still export.
 * - **Every official type is attempted.** There is no data-type selection anywhere in this feature,
 *   because the SDK already decides what is readable and a second filter would only lose data.
 * - **Nothing is packaged until it succeeded.** Cancelling discards the staging area, so a cancelled
 *   run cannot leave behind a ZIP that looks complete.
 */
class SamsungHealthExportCoordinator(
    private val gateway: SamsungHealthGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) {

    suspend fun run(
        mode: ExportMode,
        boundary: LocalTime,
        startDate: LocalDate?,
        endDate: LocalDate?,
        stagingRoot: File,
        appVersion: String,
        onProgress: (ExportProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): ExportRunResult {
        val zone = zoneId()
        val window = ExportWindowResolver.resolve(mode, boundary, zone, startDate, endDate)
        val errors = ArrayList<ExportError>()

        onProgress(ExportProgress("preparing", null, 0, 0, 0, 0))

        val sdkVersion = gateway.sdkVersionName()
        val sdkVersionCode = gateway.sdkVersionCode()

        val catalog = SamsungHealthExportCatalog(gateway.dataTypes())

        // If the permission query itself fails, RawWave does not assume "denied". It records the
        // failure and lets each read speak for itself, so an SDK problem is never disguised as the
        // user having said no.
        var permissionKnown = true
        val granted: Set<String> = when (val result = gateway.grantedReadPermissions()) {
            is GatewayRead.Ok -> result.value
            is GatewayRead.Failed -> {
                permissionKnown = false
                errors += ExportError(
                    scope = "run",
                    stage = "permissions",
                    exception = result.exception,
                    errorCode = result.errorCode,
                    message = result.message,
                )
                emptySet()
            }
        }

        if (isCancelled()) {
            return ExportRunResult(
                status = ExportRunStatus.CANCELLED, mode = mode, window = window,
                stagedFiles = emptyList(), summaries = emptyList(),
                catalog = emptyList(), errors = errors,
                sdkVersion = sdkVersion, sdkVersionCode = sdkVersionCode,
                officialDataTypeCount = catalog.entries.size,
                totalRecordsExported = 0, totalDuplicatesSuppressed = 0,
            )
        }

        val staging = ExportStaging(stagingRoot)
        val adapter = DataTypeExportAdapter(gateway, catalog)

        val summaries = ArrayList<TypeSummary>()
        val stagedFiles = ArrayList<StagedTypeFile>()
        var totalRecords = 0L
        var totalDuplicates = 0L
        var completed = 0

        try {
            catalog.entries.forEachIndexed { index, info ->
                if (isCancelled()) return@forEachIndexed

                val permissionGranted = if (permissionKnown) info.typeId in granted else true
                val outcome = adapter.export(
                    TypeExportRequest(
                        info = info,
                        window = window,
                        permissionGranted = permissionGranted,
                        staging = staging,
                        typeIndex = index + 1,
                        typeTotal = catalog.entries.size,
                        onProgress = { typeProgress ->
                            onProgress(
                                ExportProgress(
                                    phase = typeProgress.phase,
                                    typeId = typeProgress.typeId,
                                    typeIndex = typeProgress.typeIndex,
                                    typeTotal = typeProgress.typeTotal,
                                    records = typeProgress.recordsSoFar,
                                    completedTypes = completed,
                                ),
                            )
                        },
                        isCancelled = isCancelled,
                    ),
                )

                if (outcome.cancelled) return@forEachIndexed

                summaries += outcome.summary
                stagedFiles += outcome.stagedFiles
                errors += outcome.errors
                totalRecords += outcome.summary.recordsExported
                totalDuplicates += outcome.summary.duplicatesSuppressed
                completed++

                onProgress(
                    ExportProgress(
                        phase = "type_done",
                        typeId = info.typeId,
                        typeIndex = index + 1,
                        typeTotal = catalog.entries.size,
                        records = totalRecords,
                        completedTypes = completed,
                    ),
                )
            }
        } catch (t: Throwable) {
            errors += ExportError(
                scope = "run",
                stage = "export",
                exception = t::class.java.name,
                message = t.message,
            )
        }

        if (isCancelled()) {
            staging.discardAll()
            return ExportRunResult(
                status = ExportRunStatus.CANCELLED, mode = mode, window = window,
                stagedFiles = emptyList(), summaries = summaries,
                catalog = catalog.entries.map { catalog.catalogType(it, ExportStatus.SDK_ERROR, 0) },
                errors = errors,
                sdkVersion = sdkVersion, sdkVersionCode = sdkVersionCode,
                officialDataTypeCount = catalog.entries.size,
                totalRecordsExported = 0, totalDuplicatesSuppressed = 0,
            )
        }

        // A type is only "a problem" when it is not one of the definite answers. NO_DATA and
        // NESTED_IN_PARENT_TYPE are successful outcomes, not failures.
        val hasProblems = summaries.any { it.status !in BENIGN_STATUSES }
        val catalogTypes = summaries.map { summary ->
            catalog.byId(summary.typeId)?.let { info ->
                catalog.catalogType(info, summary.status, summary.recordsExported.toInt())
            }
        }.filterNotNull()

        return ExportRunResult(
            status = if (hasProblems) ExportRunStatus.COMPLETED_WITH_TYPE_PROBLEMS else ExportRunStatus.COMPLETED,
            mode = mode,
            window = window,
            stagedFiles = stagedFiles,
            summaries = summaries,
            catalog = catalogTypes,
            errors = errors,
            sdkVersion = sdkVersion,
            sdkVersionCode = sdkVersionCode,
            officialDataTypeCount = catalog.entries.size,
            totalRecordsExported = totalRecords,
            totalDuplicatesSuppressed = totalDuplicates,
        )
    }

    private companion object {
        /**
         * Statuses that are a complete answer rather than a problem.
         *
         * `NO_DATA` means Samsung Health holds nothing, and `NESTED_IN_PARENT_TYPE` means the type's
         * data travelled inside its parent. Reporting either as a failure would train the reader to
         * ignore failures.
         */
        val BENIGN_STATUSES = setOf(
            ExportStatus.OK,
            ExportStatus.NO_DATA,
            ExportStatus.NESTED_IN_PARENT_TYPE,
        )
    }
}
