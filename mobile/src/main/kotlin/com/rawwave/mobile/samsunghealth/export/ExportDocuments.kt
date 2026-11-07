package com.rawwave.mobile.samsunghealth.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Schema revision of the Samsung Health full export archive. */
const val FULL_EXPORT_SCHEMA_VERSION: Int = 1

/** RawWave never claims more than this, because it cannot prove what Samsung Health has deleted. */
const val HISTORY_SCOPE_ALL_CURRENTLY_ACCESSIBLE: String = "ALL_CURRENTLY_ACCESSIBLE_DATA"

/**
 * The archive-level description of how aggregate grouping is chosen.
 *
 * It is not one fixed unit: see [AggregateGroupingPolicy]. Each aggregate row still records its
 * own actual `grouping`, so a reader never has to infer it from this summary.
 */
const val AGGREGATE_GROUPING_POLICY: String = "PER_OPERATION"

/**
 * Why grouping is per-operation instead of one canonical unit:
 *
 * - Samsung's local-date builders only expose DAILY, so a single MINUTELY rule would be impossible
 *   for goals, heart-rate min/max and sleep duration.
 * - Steps is the one measured quantity Samsung exposes only as an aggregate. Minute buckets are
 *   needed to sum a 07:30-to-07:30 logical day exactly, so steps deliberately uses MINUTELY.
 * - Every other aggregate-only type is a goal, a score or a derived summary whose natural unit is
 *   a day; the raw record types already carry finer data separately.
 *
 * The alternative - MINUTELY for every local-time operation - would add tens of thousands of
 * derived rows (activity summary, exercise calories, floors) without adding measured resolution.
 */
const val AGGREGATE_GROUPING_POLICY_RATIONALE: String =
    "Grouping is chosen per operation. Steps uses MINUTELY because Samsung exposes steps only as " +
        "an aggregate, and minute buckets are required to sum a non-midnight logical day exactly. " +
        "Every other operation uses DAILY: local-date builders do not support MINUTELY, and the " +
        "remaining aggregate-only types are goals, scores or derived summaries whose natural unit " +
        "is a day. Each aggregate row records its own actual grouping, so this is a summary, not " +
        "the sole source of truth."

/**
 * The one and only status of one official data type in one export.
 *
 * Every type the SDK exposes ends up with exactly one of these, so "there is no file for this type"
 * can always be answered by looking it up rather than by guessing.
 */
enum class ExportStatus {
    /** Records were read and written. */
    OK,

    /** The API succeeded and Samsung Health holds nothing for this window. */
    NO_DATA,

    /** The READ permission for this type was not granted. Other types still exported. */
    PERMISSION_DENIED,

    /** The SDK threw. The code and message are recorded. */
    SDK_ERROR,

    /** The installed Samsung Health does not support this type or operation. */
    UNSUPPORTED_ON_INSTALLED_VERSION,

    /** The platform refused because of account or device state, not because of our request. */
    NOT_AVAILABLE_FOR_THIS_ACCOUNT_OR_DEVICE,

    /** A Samsung-imposed query limit stopped this type early. Reported, never silently truncated. */
    QUERY_LIMIT_REACHED,

    /** Pagination failed or looped; the type stopped and says so. */
    PAGINATION_ERROR,

    /**
     * The type has no read or aggregate entry point of its own in SDK 1.1.0 and is only reachable
     * as a nested part of a parent type (for example exercise location inside an exercise session).
     * Its data is exported inside that parent, and this status says exactly that.
     */
    NESTED_IN_PARENT_TYPE,

    /** No export strategy could be resolved. Defensive: it should never appear. */
    NOT_IMPLEMENTED,
}

/** What a run of the whole export produced. */
enum class ExportRunStatus {
    /** Every type reached a definite status. Some may still be failures - see the manifest. */
    COMPLETED,

    /** Every type that could run did, and at least one did not succeed. */
    COMPLETED_WITH_TYPE_PROBLEMS,

    /** The user cancelled. No final ZIP is produced. */
    CANCELLED,
}

/** How a type is exported, decided from the SDK's own interfaces rather than from a hand-written list. */
enum class ExportOperation {
    /** `HealthDataStore.readData`, paginated. */
    READ_RECORDS,

    /** `HealthDataStore.aggregateData` per official operation, paginated. */
    AGGREGATE,

    /** `HealthDataStore.readData` with the SDK's user-profile builder; no time filter applies. */
    READ_USER_PROFILE,

    /** No own entry point: the data arrives nested inside a parent type. */
    NESTED_IN_PARENT,
}

/** One official aggregate operation, as the SDK declares it. */
data class AggregateOperationInfo(
    /** The SDK's own operation name, e.g. `TOTAL` or `TOTAL_DURATION`. Taken from the SDK. */
    val operationName: String,
    /** The SDK's own type name for the operation. */
    val typeName: String,
    /** Which builder family the operation uses, which is what decides how a window is expressed. */
    val family: TimeModel,
)

/** The SDK's time model for a type, used to decide how a window can be expressed to Samsung. */
enum class TimeModel {
    /** Filter and group by local date-time (the SDK's `DualTimeBuilder`). */
    DUAL_TIME,

    /** Filter and group by local time only (the SDK's `LocalTimeBuilder`). */
    LOCAL_TIME,

    /** Filter and group by local date only (the SDK's `LocalDateBuilder`). */
    LOCAL_DATE,

    /** Local-date aggregation across all sources (the SDK's `AllSourceLocalDateBuilder`). */
    ALL_SOURCE_LOCAL_DATE,

    /** No time filter exists for this type at all. */
    NO_TIME_FILTER,
}

/** One official data type, as the installed SDK actually exposes it. */
@Serializable
data class CatalogType(
    @SerialName("type_id") val typeId: String,
    @SerialName("sdk_field_name") val sdkFieldName: String,
    @SerialName("sdk_data_type_name") val sdkDataTypeName: String,
    @SerialName("supported_operations") val supportedOperations: List<String>,
    @SerialName("chosen_export_operation") val chosenExportOperation: ExportOperation,
    @SerialName("time_model") val timeModel: TimeModel,
    @SerialName("time_filter_applicable") val timeFilterApplicable: Boolean,
    @SerialName("required_permission") val requiredPermission: String,
    @SerialName("output_path") val outputPath: String? = null,
    @SerialName("status") val status: ExportStatus,
    @SerialName("record_count") val recordCount: Int,
    @SerialName("fields") val fields: List<String>,
    @SerialName("aggregate_operations") val aggregateOperations: List<String> = emptyList(),
    @SerialName("note") val note: String? = null,
)

/** `catalog.json`: what this RawWave version knows about, tried, and got. */
@Serializable
data class CatalogDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("sdk_version") val sdkVersion: String,
    @SerialName("sdk_version_code") val sdkVersionCode: Int? = null,
    @SerialName("generated_at") val generatedAt: SourceTime,
    @SerialName("official_data_type_count") val officialDataTypeCount: Int,
    @SerialName("how_the_catalog_was_obtained") val howObtained: String,
    @SerialName("aggregate_grouping") val aggregateGrouping: String,
    @SerialName("aggregate_grouping_rationale") val aggregateGroupingRationale: String,
    @SerialName("data_types") val dataTypes: List<CatalogType>,
    @SerialName("status_meanings") val statusMeanings: Map<String, String>,
)

/** A file that is actually in the archive, with the size and hash of the bytes that were written. */
@Serializable
data class ExportFileRecord(
    @SerialName("path") val path: String,
    @SerialName("size") val sizeBytes: Long,
    @SerialName("sha256") val sha256: String,
)

/** Per-type accounting for `manifest.json`. */
@Serializable
data class TypeSummary(
    @SerialName("type_id") val typeId: String,
    @SerialName("sdk_data_type_name") val sdkDataTypeName: String,
    @SerialName("operation") val operation: ExportOperation,
    @SerialName("status") val status: ExportStatus,
    @SerialName("permission") val permission: String,
    @SerialName("permission_granted") val permissionGranted: Boolean,
    @SerialName("records_seen") val recordsSeen: Long,
    @SerialName("records_exported") val recordsExported: Long,
    @SerialName("pages") val pages: Int,
    @SerialName("duplicates_suppressed") val duplicatesSuppressed: Long,
    @SerialName("dedup_key_strategy") val dedupKeyStrategy: String? = null,
    @SerialName("associated_record_count") val associatedRecordCount: Long = 0,
    @SerialName("aggregate_operation_count") val aggregateOperationCount: Int = 0,
    @SerialName("output_path") val outputPath: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("notes") val notes: List<String> = emptyList(),
)

/** `manifest.json`: the archive index. Written last, so its presence means the archive is complete. */
@Serializable
data class ExportManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("app_version") val appVersion: String,
    @SerialName("sdk_version") val sdkVersion: String,
    @SerialName("export_time") val exportTime: SourceTime,
    @SerialName("export_mode") val exportMode: ExportMode,
    @SerialName("run_status") val runStatus: ExportRunStatus,
    @SerialName("history_scope") val historyScope: String? = null,
    @SerialName("user_time_semantics") val userTimeSemantics: UserTimeSemantics,
    @SerialName("requested_logical_range") val requestedLogicalRange: RequestedLogicalRange? = null,
    @SerialName("resolved_query_window") val resolvedQueryWindow: ResolvedQueryWindow,
    @SerialName("source_time_policy") val sourceTimePolicy: String = SOURCE_TIME_POLICY,
    @SerialName("source_timestamps_modified") val sourceTimestampsModified: Boolean = false,
    @SerialName("data_classification") val dataClassification: String = "SAMSUNG_HEALTH_STORE",
    @SerialName("is_raw_sensor_data") val isRawSensorData: Boolean = false,
    @SerialName("interval_query_padding_hours") val intervalQueryPaddingHours: Int = 48,
    @SerialName("official_data_type_count") val officialDataTypeCount: Int,
    @SerialName("data_type_summary") val dataTypeSummary: List<TypeSummary>,
    @SerialName("errors") val errors: List<ExportError>,
    @SerialName("files") val files: List<ExportFileRecord>,
    @SerialName("manifest_note") val manifestNote: String,
) {
    companion object {
        const val SOURCE_TIME_POLICY: String = "PRESERVE_SAMSUNG_HEALTH_SOURCE_TIME"
    }
}

/** The daily-boundary snapshot that was in force for this export. */
@Serializable
data class UserTimeSemantics(
    @SerialName("daily_boundary_local_time") val dailyBoundaryLocalTime: String,
    @SerialName("timezone_id") val timezoneId: String,
    @SerialName("daily_boundary_meaning") val dailyBoundaryMeaning: String =
        "The local time at which a RawWave logical day starts. It converts chosen dates into a " +
            "query window and never modifies a Samsung Health timestamp. It is a snapshot: changing " +
            "the setting later does not change what this archive means.",
)

/** A failure worth reporting. One type failing never voids the export. */
@Serializable
data class ExportError(
    @SerialName("scope") val scope: String,
    @SerialName("type_id") val typeId: String? = null,
    @SerialName("stage") val stage: String,
    @SerialName("exception") val exception: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("message") val message: String? = null,
)

/**
 * `export_semantics.json`: the machine-readable data dictionary.
 *
 * This is written for a program or a model that receives the ZIP years from now with no access to
 * this conversation. It states what was preserved, what RawWave derived, and exactly how the two can
 * be told apart.
 */
@Serializable
data class ExportSemanticsDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("generated_at") val generatedAt: SourceTime,
    @SerialName("document_purpose") val documentPurpose: String,
    @SerialName("data_origin") val dataOrigin: DataOriginSemantics,
    @SerialName("source_time_policy") val sourceTimePolicy: String,
    @SerialName("source_timestamps_modified") val sourceTimestampsModified: Boolean,
    @SerialName("logical_day_policy") val logicalDayPolicy: String,
    @SerialName("daily_boundary_local_time") val dailyBoundaryLocalTime: String,
    @SerialName("timezone_id") val timezoneId: String,
    @SerialName("timezone_resolution") val timezoneResolution: String,
    @SerialName("range_semantics") val rangeSemantics: String,
    @SerialName("selected_dates_are") val selectedDatesAre: String,
    @SerialName("end_is_exclusive") val endIsExclusive: Boolean,
    @SerialName("point_record_inclusion_policy") val pointRecordInclusionPolicy: String,
    @SerialName("interval_record_inclusion_policy") val intervalRecordInclusionPolicy: String,
    @SerialName("associated_data_policy") val associatedDataPolicy: String,
    @SerialName("aggregate_data_policy") val aggregateDataPolicy: String,
    @SerialName("aggregate_grouping") val aggregateGrouping: String,
    @SerialName("aggregate_grouping_rationale") val aggregateGroupingRationale: String,
    @SerialName("interval_is_never_clipped") val intervalIsNeverClipped: Boolean,
    @SerialName("window_relations") val windowRelations: Map<String, String>,
    @SerialName("inclusion_reasons") val inclusionReasons: Map<String, String>,
    @SerialName("record_anatomy") val recordAnatomy: Map<String, String>,
    @SerialName("field_extraction_policy") val fieldExtractionPolicy: String,
    @SerialName("enum_policy") val enumPolicy: String,
    @SerialName("dedup_policy") val dedupPolicy: String,
    @SerialName("pagination_policy") val paginationPolicy: String,
    @SerialName("all_history_policy") val allHistoryPolicy: String,
    @SerialName("status_meanings") val statusMeanings: Map<String, String>,
    @SerialName("file_layout") val fileLayout: Map<String, String>,
) {
    companion object {
        const val PURPOSE: String =
            "Machine-readable description of the time and provenance semantics of this archive, " +
                "written so the data can be interpreted correctly without RawWave or the app that " +
                "produced it."
        const val RECORD_ANATOMY_NOTE: String =
            "Every line of every records.jsonl is " +
                "{\"source\": <Samsung Health data>, \"rawave_export_context\": <RawWave annotation>}. " +
                "Nothing under \"source\" was written by RawWave; nothing under " +
                "\"rawave_export_context\" came from Samsung Health."
    }
}

/** Data-origin semantics, so this can never be mistaken for raw sensor waveforms. */
@Serializable
data class DataOriginSemantics(
    @SerialName("source_family") val sourceFamily: String = "SAMSUNG_HEALTH",
    @SerialName("source_kind") val sourceKind: String = "HEALTH_DATA_STORE",
    @SerialName("data_classification") val dataClassification: String = "SAMSUNG_HEALTH_STORE",
    @SerialName("is_raw_sensor_data") val isRawSensorData: Boolean = false,
    @SerialName("explanation") val explanation: String =
        "These values were computed and stored by Samsung Health (including sleep staging, blood " +
            "oxygen and skin temperature). They are NOT raw PPG/ECG/ACC waveforms obtained from the " +
            "RawWave watch sensor path, and RawWave did not derive them.",
)

/** Everything one run of the coordinator produced, ready to be packaged. */
class ExportRunResult(
    val status: ExportRunStatus,
    val mode: ExportMode,
    val window: ResolvedExportWindow,
    val stagedFiles: List<StagedTypeFile>,
    val summaries: List<TypeSummary>,
    val catalog: List<CatalogType>,
    val errors: List<ExportError>,
    val sdkVersion: String,
    val sdkVersionCode: Int?,
    val officialDataTypeCount: Int,
    val totalRecordsExported: Long,
    val totalDuplicatesSuppressed: Long,
)
