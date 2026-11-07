package com.rawwave.mobile.samsunghealth

import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.UserDataPoint
import com.rawwave.mobile.samsunghealth.export.SourceDuration
import com.rawwave.mobile.samsunghealth.export.SourcePayload
import com.rawwave.mobile.samsunghealth.export.SourceTime
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns Samsung Health SDK objects into [SourcePayload]s, without deciding what matters.
 *
 * Two deliberate choices make it impossible for a field to be dropped just because a developer did
 * not recognise it:
 *
 * 1. **Top-level records are extracted from the SDK's own field descriptors.** `DataType.allFields`
 *    is the SDK's list of what this type contains, so every field Samsung exposes is read and
 *    written. RawWave keeps no hand-written per-type field list that could drift or go stale.
 * 2. **Nested values are extracted by reflection over their public getters.** The nested entry types
 *    (`SleepSession`, `SleepStage`, `HeartRate`, `ExerciseSession`, `ExerciseRoute`, ...) carry no
 *    field descriptors, so the only complete option is to read every public accessor. A field added
 *    by a future SDK version therefore appears automatically instead of being silently absent.
 *
 * Nothing here computes a health meaning. A float is a float, an enum keeps Samsung's own constant
 * name, and an unknown enum value is passed through rather than being mapped to something RawWave
 * invented.
 */
class SdkValueExtractor(private val zone: ZoneId) {

    /** A nested value deeper than this is truncated rather than being allowed to recurse forever. */
    private val maxDepth = 8

    /** `Instant` plus every other representation of the same moment. Null in, null out. */
    fun sourceTime(
        instant: Instant?,
        offset: ZoneOffset?,
        local: LocalDateTime? = null,
    ): SourceTime? {
        if (instant == null) return null
        val effective = offset ?: zone.rules.getOffset(instant)
        return SourceTime(
            epochMs = instant.toEpochMilli(),
            isoInstantUtc = instant.toString(),
            localDateTime = (local ?: instant.atOffset(effective).toLocalDateTime()).toString(),
            zoneOffset = effective.id,
            zoneOffsetTotalSeconds = effective.totalSeconds,
        )
    }

    /** Converts one SDK value into its faithful JSON form. */
    fun valueToJson(value: Any?, offset: ZoneOffset?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value.toInt())
        is Byte -> JsonPrimitive(value.toInt())
        is Float -> finiteNumber(value.toDouble())
        is Double -> finiteNumber(value)
        is String -> JsonPrimitive(value)
        is Instant -> sourceTime(value, offset)?.let { timeJson(it) } ?: JsonNull
        is Duration -> durationJson(value)
        is LocalDateTime -> JsonPrimitive(value.toString())
        is LocalDate -> JsonPrimitive(value.toString())
        is LocalTime -> JsonPrimitive(value.toString())
        is ZoneOffset -> JsonPrimitive(value.id)
        is ZoneId -> JsonPrimitive(value.id)
        // Samsung's own constant name, verbatim. An enum this code has never seen still serializes,
        // so a future SDK addition can never crash the export.
        is Enum<*> -> JsonPrimitive(value.name)
        is List<*> -> JsonArray(value.map { valueToJson(it, offset) })
        is Array<*> -> JsonArray(value.map { valueToJson(it, offset) })
        else -> reflectObject(value, offset, 0)
    }

    /** Every field the SDK exposes for [info], read straight off the record. */
    fun payloadOf(
        point: HealthDataPoint,
        info: DataTypeInfo,
        fieldDescriptors: List<Field<*>>,
    ): SourcePayload {
        val offset = safe { point.zoneOffset }
        val fields = LinkedHashMap<String, JsonElement>()
        val errors = LinkedHashMap<String, String>()

        for (descriptor in fieldDescriptors) {
            try {
                @Suppress("UNCHECKED_CAST")
                val raw = point.getValueOrDefault(descriptor as Field<Any?>, null)
                fields[descriptor.name] = valueToJson(raw, offset)
            } catch (t: Throwable) {
                // A field that cannot be read is reported by name. It is never silently absent,
                // because an absent key and an unreadable key would look identical afterwards.
                errors[descriptor.name] = "${t.javaClass.name}: ${t.message}"
            }
        }

        return SourcePayload(
            sdkDataTypeName = info.sdkTypeName,
            typeId = info.typeId,
            uid = safe { point.uid },
            clientDataId = safe { point.clientDataId },
            clientVersion = safe { point.clientVersion },
            sourceAppId = safe { point.dataSource?.appId },
            sourceDeviceId = safe { point.dataSource?.deviceId },
            start = sourceTime(safe { point.startTime }, offset, safe { point.getStartLocalDateTime() }),
            end = sourceTime(safe { point.endTime }, offset, safe { point.getEndLocalDateTime() }),
            updateTime = sourceTime(safe { point.updateTime }, offset),
            zoneOffset = offset?.id,
            fields = fields,
            fieldReadErrors = errors,
        )
    }

    /**
     * The user profile.
     *
     * It has no uid, no source and no timestamps at all, because it is not a time series. Nothing is
     * invented to make it look like one: it is exported as a current profile and marked
     * `time_filter_applicable = false`.
     */
    fun userProfilePayload(
        point: UserDataPoint,
        info: DataTypeInfo,
        fieldDescriptors: List<Field<*>>,
    ): SourcePayload {
        val fields = LinkedHashMap<String, JsonElement>()
        val errors = LinkedHashMap<String, String>()
        for (descriptor in fieldDescriptors) {
            try {
                @Suppress("UNCHECKED_CAST")
                val raw = point.getValueOrDefault(descriptor as Field<Any?>, null)
                fields[descriptor.name] = valueToJson(raw, null)
            } catch (t: Throwable) {
                errors[descriptor.name] = "${t.javaClass.name}: ${t.message}"
            }
        }
        return SourcePayload(
            sdkDataTypeName = info.sdkTypeName,
            typeId = info.typeId,
            fields = fields,
            fieldReadErrors = errors,
        )
    }

    /** One aggregate value, in whatever shape Samsung returned it. */
    fun aggregateValueJson(value: Any?, offset: ZoneOffset?): JsonElement = valueToJson(value, offset)

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    private fun timeJson(time: SourceTime): JsonElement = buildJsonObject {
        put("epoch_ms", time.epochMs)
        put("iso_instant_utc", time.isoInstantUtc)
        time.localDateTime?.let { put("local_date_time", it) }
        time.zoneOffset?.let { put("zone_offset", it) }
        time.zoneOffsetTotalSeconds?.let { put("zone_offset_total_seconds", it) }
    }

    private fun durationJson(duration: Duration): JsonElement = buildJsonObject {
        put("seconds", duration.seconds)
        put("nanos", duration.nano)
        put("iso8601", duration.toString())
    }

    /**
     * JSON has no NaN or Infinity, and `kotlinx.serialization` refuses to write them. They are real
     * values Samsung can hold, so they are written as their literal names rather than being turned
     * into a number that would misrepresent them or being dropped.
     */
    private fun finiteNumber(value: Double): JsonElement =
        if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())

    /** Reads every public no-argument accessor of an SDK entry object. */
    private fun reflectObject(obj: Any, offset: ZoneOffset?, depth: Int): JsonElement {
        if (depth >= maxDepth) {
            return JsonPrimitive("<<max_nesting_depth_reached:${obj.javaClass.simpleName}>>")
        }
        val out = LinkedHashMap<String, JsonElement>()
        for (method in accessorsOf(obj.javaClass)) {
            val name = propertyNameOf(method)
            out[name] = try {
                valueToJson(method.invoke(obj), offset)
            } catch (t: Throwable) {
                // A getter that throws (an unpermitted exercise route, for instance) is recorded by
                // name instead of taking the whole record down with it.
                JsonPrimitive("<<unreadable:${t.javaClass.simpleName}>>")
            }
        }
        return JsonObject(out)
    }

    private fun accessorsOf(type: Class<*>): List<Method> =
        type.methods
            .filter { method ->
                method.parameterCount == 0 &&
                    Modifier.isPublic(method.modifiers) &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.declaringClass != Any::class.java &&
                    // Kotlin internal accessors are mangled with '$' and are not part of the
                    // documented surface (for example getDataSize$SDK_clientRelease).
                    !method.name.contains('$') &&
                    method.name != "describeContents" &&
                    (
                        (method.name.startsWith("get") && method.name.length > 3) ||
                            (
                                method.name.startsWith("is") && method.name.length > 2 &&
                                    (
                                        method.returnType == java.lang.Boolean.TYPE ||
                                            method.returnType == java.lang.Boolean::class.java
                                        )
                                )
                        )
            }
            .sortedBy { it.name }

    private fun propertyNameOf(method: Method): String {
        val raw = if (method.name.startsWith("get")) {
            method.name.substring(3)
        } else {
            method.name.substring(2)
        }
        return raw.replaceFirstChar { it.lowercaseChar() }
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        null
    }
}
