package com.rawwave.mobile.samsunghealth

import com.rawwave.mobile.samsunghealth.export.AggregateOperationInfo
import com.rawwave.mobile.samsunghealth.export.TimeModel
import com.samsung.android.sdk.health.data.data.AggregateOperation
import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.request.AggregateRequest
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import java.util.Locale

/** One official data type, discovered from the SDK, with the SDK handles needed to read it. */
internal class DiscoveredType(
    val dataType: DataType,
    val fields: List<Field<*>>,
    val info: DataTypeInfo,
    val aggregateOperations: Map<String, AggregateOperation<Any, *>>,
)

/**
 * Discovers the official Samsung Health data-type catalog **from the installed SDK**.
 *
 * There is no hand-written list of data types anywhere in this feature, and that is deliberate. A
 * hand-written list can drift from the SDK: a type Samsung adds would be silently missing from
 * every export, and a type Samsung removes would produce a confusing error. Discovery cannot drift,
 * and it is also what lets `catalog.json` answer "does Samsung Health offer this type at all?"
 * separately from "did RawWave implement it?" and "did Samsung return anything?".
 *
 * Everything here is derived from the SDK's own declarations:
 *
 * | Question | Source |
 * | --- | --- |
 * | Which types exist | the `DataTypes` interface's own `DataType` constants |
 * | Which fields a type has | that type's own `allFields` |
 * | Which aggregate operations exist | the `AggregateOperation` constants on that type |
 * | How a type expresses time | the family of its own request builders |
 * | Whether it can be read at all | whether it implements `DataType.Readable` |
 * | Which associates a type has | that type's own associate enum |
 *
 * No Android `Context` is needed, which is why this can be exercised in an ordinary JVM test
 * against the real SDK rather than against a description of it.
 */
internal object SdkCatalogDiscovery {

    fun discover(): List<DiscoveredType> {
        val out = ArrayList<DiscoveredType>()

        val declaredTypes = DataTypes::class.java.fields
            .filter { DataType::class.java.isAssignableFrom(it.type) }
            .sortedBy { it.name }

        for (declared in declaredTypes) {
            val dataType = try {
                declared.get(null) as? DataType ?: continue
            } catch (t: Throwable) {
                continue
            }

            val typeId = declared.name.lowercase(Locale.ROOT)
            val operations = aggregateOperationInstances(dataType)
            val readable = dataType is DataType.Readable<*, *>
            val readTimeModel = readTimeModelOf(dataType)

            val info = DataTypeInfo(
                typeId = typeId,
                sdkFieldName = declared.name,
                sdkTypeName = dataType.name,
                readable = readable,
                changeReadable = dataType is DataType.ChangeReadable<*>,
                writeable = dataType is DataType.Writeable<*>,
                fields = dataType.allFields.map { it.name },
                aggregateOperations = operations.values.map { operation ->
                    AggregateOperationInfo(
                        operationName = operation.operationName,
                        typeName = operation.typeName,
                        family = familyOf(operation),
                    )
                }.sortedBy { it.operationName },
                readTimeModel = readTimeModel,
                associatedTypes = associatedTypesOf(dataType),
                nestedInParentTypeId = if (!readable && operations.isEmpty()) nestedParentOf(typeId) else null,
            )

            out += DiscoveredType(
                dataType = dataType,
                fields = dataType.allFields,
                info = info,
                aggregateOperations = operations,
            )
        }
        return out
    }

    private fun aggregateOperationInstances(dataType: DataType): Map<String, AggregateOperation<Any, *>> {
        val out = LinkedHashMap<String, AggregateOperation<Any, *>>()
        for (field in dataType.javaClass.declaredFields) {
            if (!AggregateOperation::class.java.isAssignableFrom(field.type)) continue
            try {
                @Suppress("UNCHECKED_CAST")
                val operation = field.get(null) as? AggregateOperation<Any, *> ?: continue
                out[operation.operationName] = operation
            } catch (t: Throwable) {
                // An operation that cannot be read is absent from the catalog rather than being
                // replaced by a guess about what it might have been.
                continue
            }
        }
        return out
    }

    private fun familyOf(operation: AggregateOperation<Any, *>): TimeModel =
        when (operation.requestBuilder as Any) {
            is AggregateRequest.DualTimeBuilder<*> -> TimeModel.DUAL_TIME
            is AggregateRequest.LocalTimeBuilder<*> -> TimeModel.LOCAL_TIME
            is AggregateRequest.LocalDateBuilder<*> -> TimeModel.LOCAL_DATE
            is AggregateRequest.AllSourceLocalDateBuilder<*> -> TimeModel.ALL_SOURCE_LOCAL_DATE
            else -> TimeModel.NO_TIME_FILTER
        }

    private fun readTimeModelOf(dataType: DataType): TimeModel {
        val readable = dataType as? DataType.Readable<*, *> ?: return TimeModel.NO_TIME_FILTER
        return when (readable.readDataRequestBuilder as Any) {
            is ReadDataRequest.DualTimeBuilder<*> -> TimeModel.DUAL_TIME
            is ReadDataRequest.LocalDateBuilder<*> -> TimeModel.LOCAL_DATE
            is ReadDataRequest.UserProfileBuilder -> TimeModel.NO_TIME_FILTER
            else -> TimeModel.NO_TIME_FILTER
        }
    }

    /** Derived from the SDK's own associate enum, so a new associate cannot be forgotten. */
    private fun associatedTypesOf(dataType: DataType): List<String> =
        if (dataType is DataType.SleepType) {
            DataType.SleepType.Associates.entries.map { it.name.lowercase(Locale.ROOT) }.sorted()
        } else {
            emptyList()
        }

    /**
     * The one type in SDK 1.1.0 with no read or aggregate entry point of its own.
     *
     * `EXERCISE_LOCATION` is only reachable as the `route` of an exercise session, so its data is
     * exported inside `exercise` and its catalog entry says so instead of reporting an error.
     */
    private fun nestedParentOf(typeId: String): String? = when (typeId) {
        "exercise_location" -> "exercise"
        else -> null
    }
}
