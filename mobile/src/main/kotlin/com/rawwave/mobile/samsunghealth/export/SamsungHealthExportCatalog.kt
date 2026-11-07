package com.rawwave.mobile.samsunghealth.export

import com.rawwave.mobile.samsunghealth.DataTypeInfo

/**
 * RawWave's view of the official Samsung Health data-type catalog.
 *
 * The catalog is not written by hand: it is built from [DataTypeInfo] values that the gateway
 * discovered by reflecting over the SDK's own `DataTypes` registry. This class adds only what
 * RawWave decides on top of the SDK's own description - which export operation to use, where the
 * output goes, and what status each type ended up with.
 *
 * That split is what makes "there is no file for this data type" answerable: the catalog records
 * whether Samsung Health offers the type at all, whether RawWave implemented an export for it,
 * whether the user granted it, and what Samsung actually returned - four different questions that
 * would otherwise all look like a missing file.
 */
class SamsungHealthExportCatalog(types: List<DataTypeInfo>) {

    /** Every official type, in a stable order. */
    val entries: List<DataTypeInfo> = types.sortedBy { it.typeId }

    private val byId: Map<String, DataTypeInfo> = entries.associateBy { it.typeId }

    fun byId(typeId: String): DataTypeInfo? = byId[typeId]

    fun ids(): Set<String> = byId.keys

    /**
     * The operation RawWave actually uses for this type.
     *
     * Chosen from the SDK's own capabilities, never assumed: a readable type is read, an
     * aggregate-only type is aggregated through Samsung's official operations, a type with no entry
     * point of its own is exported inside its parent, and a type with no time filter is read as a
     * current profile rather than being forced into a date window it does not have.
     */
    fun chosenOperation(info: DataTypeInfo): ExportOperation = when {
        info.readable && info.readTimeModel == TimeModel.NO_TIME_FILTER -> ExportOperation.READ_USER_PROFILE
        info.readable -> ExportOperation.READ_RECORDS
        info.aggregateOperations.isNotEmpty() -> ExportOperation.AGGREGATE
        else -> ExportOperation.NESTED_IN_PARENT
    }

    fun recordsPath(info: DataTypeInfo): String? =
        if (info.readable && info.readTimeModel != TimeModel.NO_TIME_FILTER) {
            "types/${info.typeId}/records.jsonl"
        } else {
            null
        }

    fun profilePath(info: DataTypeInfo): String? =
        if (chosenOperation(info) == ExportOperation.READ_USER_PROFILE) {
            "types/${info.typeId}/profile.jsonl"
        } else {
            null
        }

    fun aggregatesPath(info: DataTypeInfo): String? =
        if (info.aggregateOperations.isNotEmpty()) "types/${info.typeId}/aggregates.jsonl" else null

    fun associatedPath(parentTypeId: String, associateId: String): String =
        "types/$parentTypeId/associated/$associateId.jsonl"

    /** The single path a reader should look at first for this type, or null when it has none. */
    fun primaryOutputPath(info: DataTypeInfo): String? = when (chosenOperation(info)) {
        ExportOperation.READ_RECORDS -> recordsPath(info)
        ExportOperation.READ_USER_PROFILE -> profilePath(info)
        ExportOperation.AGGREGATE -> aggregatesPath(info)
        ExportOperation.NESTED_IN_PARENT -> null
    }

    /** One catalog entry for `catalog.json`, with the outcome of this run folded in. */
    fun catalogType(info: DataTypeInfo, status: ExportStatus, recordCount: Int): CatalogType = CatalogType(
        typeId = info.typeId,
        sdkFieldName = info.sdkFieldName,
        sdkDataTypeName = info.sdkTypeName,
        supportedOperations = info.supportedOperations.map { it.name },
        chosenExportOperation = chosenOperation(info),
        timeModel = info.readTimeModel,
        timeFilterApplicable = info.readTimeModel != TimeModel.NO_TIME_FILTER,
        requiredPermission = "READ_${info.sdkFieldName}",
        outputPath = primaryOutputPath(info),
        status = status,
        recordCount = recordCount,
        fields = info.fields,
        aggregateOperations = info.aggregateOperations.map { it.operationName },
        note = noteFor(info),
    )

    private fun noteFor(info: DataTypeInfo): String? = when {
        info.nestedInParentTypeId != null ->
            "No read or aggregate entry point of its own in SDK 1.1.0; its data is exported nested " +
                "inside '${info.nestedInParentTypeId}'."

        info.readTimeModel == TimeModel.NO_TIME_FILTER && info.readable ->
            "Exported as a current value. Samsung exposes no time filter for this type, so no " +
                "timestamp is invented for it and it is not placed inside a date window."

        info.associatedTypes.isNotEmpty() ->
            "Readable, and additionally supports associated reads: ${info.associatedTypes.joinToString()}."

        else -> null
    }

    /** Types for which RawWave has no strategy at all. Should always be empty. */
    fun typesWithoutStrategy(): List<String> = entries
        .filter { info ->
            !info.readable && info.aggregateOperations.isEmpty() && info.nestedInParentTypeId == null
        }
        .map { it.typeId }

    companion object {
        /**
         * What every status means, so a reader of the archive never has to infer it.
         *
         * The distinction that matters most is between "Samsung has nothing", "we were not allowed"
         * and "the call failed", which are three completely different actions for whoever reads this.
         */
        fun statusMeanings(): Map<String, String> = linkedMapOf(
            ExportStatus.OK.name to
                "The operation succeeded and records were written for this type.",
            ExportStatus.NO_DATA.name to
                "The operation succeeded and Samsung Health holds no data of this type for the " +
                "requested window. This is a real answer, not a failure.",
            ExportStatus.PERMISSION_DENIED.name to
                "The READ permission for this type was not granted, or Samsung Health refused it. " +
                "No data was read. Other types are unaffected.",
            ExportStatus.SDK_ERROR.name to
                "The Samsung Health Data SDK call threw. The error code and message are recorded " +
                "next to this status.",
            ExportStatus.UNSUPPORTED_ON_INSTALLED_VERSION.name to
                "The installed Samsung Health (or the installed SDK) does not support this data " +
                "type or operation.",
            ExportStatus.NOT_AVAILABLE_FOR_THIS_ACCOUNT_OR_DEVICE.name to
                "Samsung Health refused because of account, region or device state rather than " +
                "because of how RawWave asked.",
            ExportStatus.QUERY_LIMIT_REACHED.name to
                "A Samsung-imposed query limit stopped this type early. Recorded rather than " +
                "silently truncating: treat this type's data as partial.",
            ExportStatus.PAGINATION_ERROR.name to
                "Pagination failed or was detected looping, so this type was stopped. Its data is " +
                "partial.",
            ExportStatus.NESTED_IN_PARENT_TYPE.name to
                "This type has no read or aggregate entry point of its own in SDK 1.1.0. Its data " +
                "is exported nested inside its parent type; see the catalog note for which one.",
            ExportStatus.NOT_IMPLEMENTED.name to
                "No export strategy could be resolved for this type. This should never appear and " +
                "indicates a RawWave defect.",
        )
    }
}
