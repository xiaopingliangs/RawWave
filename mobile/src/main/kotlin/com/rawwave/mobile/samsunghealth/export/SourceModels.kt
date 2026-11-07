package com.rawwave.mobile.samsunghealth.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The shape of everything RawWave took out of Samsung Health.
 *
 * The rule this whole file exists to enforce is that **Samsung Health's own data and RawWave's own
 * annotations are never mixed**. Every record is `{ "source": ..., "rawave_export_context": ... }`:
 * the first half is Samsung Health's, the second half is ours, and ours never overwrites theirs.
 *
 * No field here is derived from a source field. Where a convenience view exists (`iso_instant_utc`
 * next to `epoch_ms`, `local_date_time` next to the zone offset) it is an ADDITIONAL representation
 * of the same instant, so nothing about the original time semantics is lost to formatting.
 */

/** One instant, in every representation the SDK gave us. */
@Serializable
data class SourceTime(
    @SerialName("epoch_ms") val epochMs: Long,
    @SerialName("iso_instant_utc") val isoInstantUtc: String,
    @SerialName("local_date_time") val localDateTime: String? = null,
    @SerialName("zone_offset") val zoneOffset: String? = null,
    @SerialName("zone_offset_total_seconds") val zoneOffsetTotalSeconds: Int? = null,
)

/** A `java.time.Duration`, kept exactly: seconds, nanos and the ISO-8601 form. */
@Serializable
data class SourceDuration(
    @SerialName("seconds") val seconds: Long,
    @SerialName("nanos") val nanos: Int,
    @SerialName("iso8601") val iso8601: String,
)

/**
 * RawWave's per-record annotation.
 *
 * `included_by` and `window_relation` exist so that a record which was pulled in whole because it
 * merely *intersected* the window can be told apart from one that genuinely sits inside it. That is
 * the difference between "this sleep happened between 08:00 and 10:00" and "this sleep started at
 * 01:00 and was kept whole so it would not be cut in half".
 */
@Serializable
data class ExportContext(
    @SerialName("included_by") val includedBy: IncludedBy,
    @SerialName("window_relation") val windowRelation: WindowRelation,
    @SerialName("logical_day_context") val logicalDayContext: LogicalDayContext,
    @SerialName("is_aggregate") val isAggregate: Boolean = false,
    @SerialName("aggregate_operation") val aggregateOperation: String? = null,
    @SerialName("aggregate_grouping") val aggregateGrouping: String? = null,
    @SerialName("aggregate_sdk_semantics") val aggregateSdkSemantics: String? = null,
    /** Set when the record was reached as a nested part of another type, not read on its own. */
    @SerialName("nested_in_type") val nestedInType: String? = null,
)

/**
 * The logical-day view, which is a LABEL and never a replacement for the source timestamps.
 *
 * A point has one unambiguous logical day. An interval that crosses the boundary has several, and
 * all of them are listed rather than being collapsed onto a single invented date.
 */
@Serializable
data class LogicalDayContext(
    @SerialName("logical_day_key") val logicalDayKey: String? = null,
    @SerialName("logical_days_touched") val logicalDaysTouched: List<String> = emptyList(),
    @SerialName("logical_days_touched_truncated") val logicalDaysTouchedTruncated: Boolean = false,
    @SerialName("daily_boundary_local_time") val dailyBoundaryLocalTime: String,
)

/**
 * Everything Samsung Health said about one record.
 *
 * `fields` carries **every** field the SDK exposes for this data type, extracted from the SDK's own
 * `Field` descriptors rather than from a hand-written list, so a field cannot be dropped because a
 * developer did not think it looked useful.
 */
@Serializable
data class SourcePayload(
    /** The SDK's own data-type name, verbatim. */
    @SerialName("sdk_data_type_name") val sdkDataTypeName: String,
    /** RawWave's stable id for the type, used for directories and lookups. */
    @SerialName("type_id") val typeId: String,
    @SerialName("uid") val uid: String? = null,
    @SerialName("client_data_id") val clientDataId: String? = null,
    @SerialName("client_version") val clientVersion: Int? = null,
    @SerialName("source_app_id") val sourceAppId: String? = null,
    @SerialName("source_device_id") val sourceDeviceId: String? = null,
    @SerialName("start") val start: SourceTime? = null,
    @SerialName("end") val end: SourceTime? = null,
    @SerialName("update_time") val updateTime: SourceTime? = null,
    @SerialName("zone_offset") val zoneOffset: String? = null,
    /** Absent only when the type exposes no readable fields at all. */
    @SerialName("fields") val fields: Map<String, JsonElement> = emptyMap(),
    /** Field reads that threw, kept so a failure is visible instead of being a missing key. */
    @SerialName("field_read_errors") val fieldReadErrors: Map<String, String> = emptyMap(),
)

/** One exported record: Samsung Health's data, plus RawWave's annotation of why it is here. */
@Serializable
data class SourceRecord(
    @SerialName("source") val source: SourcePayload,
    @SerialName("rawave_export_context") val context: ExportContext,
)

/** A record's identity for de-duplication, and the strategy that produced it. */
data class DedupKey(val strategy: String, val value: String)
