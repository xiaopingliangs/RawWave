package com.rawwave.mobile.samsunghealth

import android.app.Activity
import android.content.Context
import com.rawwave.mobile.samsunghealth.export.AggregateOperationInfo
import com.rawwave.mobile.samsunghealth.export.ExportStatus
import com.rawwave.mobile.samsunghealth.export.TimeModel
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.AggregateOperation
import com.samsung.android.sdk.health.data.data.AggregatedData
import com.samsung.android.sdk.health.data.data.DataPoint
import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.UserDataPoint
import com.samsung.android.sdk.health.data.error.AuthorizationException
import com.samsung.android.sdk.health.data.error.ErrorCode
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.InvalidRequestException
import com.samsung.android.sdk.health.data.helper.SdkVersion
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.AggregateRequest
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.IdFilter
import com.samsung.android.sdk.health.data.request.LocalDateFilter
import com.samsung.android.sdk.health.data.request.LocalDateGroup
import com.samsung.android.sdk.health.data.request.LocalDateGroupUnit
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.LocalTimeGroup
import com.samsung.android.sdk.health.data.request.LocalTimeGroupUnit
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The real Samsung Health Data SDK boundary.
 *
 * Together with [SdkValueExtractor] this is the only code in `:mobile` that imports
 * `com.samsung.android.sdk.health.data.*`. Everything above it - the catalog, the adapters, the
 * coordinator, the exporter and all of the tests - works on SDK-free values.
 *
 * The catalog is **derived from the SDK**, not declared by hand: the official data types come from
 * the SDK's own `DataTypes` registry via reflection, each type's fields come from its own
 * `allFields`, and each type's aggregate operations come from the `AggregateOperation` constants the
 * SDK declares on the type. A hand-written list could drift from the SDK and silently lose a type;
 * this cannot.
 *
 * READ ONLY. No insert, update or delete exists here.
 */
class AndroidSamsungHealthGateway(
    context: Context,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : SamsungHealthGateway {

    private val appContext: Context = context.applicationContext
    private val extractor = SdkValueExtractor(zone)

    private val store: HealthDataStore by lazy {
        HealthDataService.getStore(appContext, CoroutineScope(Dispatchers.Default))
    }

    /**
     * The official catalog, discovered from the SDK rather than declared by hand.
     *
     * See [SdkCatalogDiscovery]: a hand-written data-type list could drift from the SDK and
     * silently drop a type from every export.
     */
    private val registry: LinkedHashMap<String, DiscoveredType> by lazy {
        val map = LinkedHashMap<String, DiscoveredType>()
        SdkCatalogDiscovery.discover().forEach { map[it.info.typeId] = it }
        map
    }

    /** `Permission` object to our type id, and back. */
    private val permissionToTypeId: Map<Permission, String> by lazy {
        registry.values.associate { Permission.of(it.dataType, AccessType.READ) to it.info.typeId }
    }

    // ------------------------------------------------------------------------------------------
    // Catalog
    // ------------------------------------------------------------------------------------------

    override fun dataTypes(): List<DataTypeInfo> = registry.values.map { it.info }

    private fun entry(typeId: String): DiscoveredType? = registry[typeId]

    // ------------------------------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------------------------------

    override suspend fun grantedReadPermissions(): GatewayRead<Set<String>> = try {
        val granted = store.getGrantedPermissions(permissionToTypeId.keys)
        GatewayRead.Ok(granted.mapNotNull { permissionToTypeId[it] }.toSet())
    } catch (t: Throwable) {
        t.toFailure()
    }

    override suspend fun requestReadPermissions(activity: Activity, typeIds: Set<String>): Set<String> {
        val wanted = typeIds.mapNotNull { entry(it) }
            .map { Permission.of(it.dataType, AccessType.READ) }
            .toSet()
        if (wanted.isEmpty()) return emptySet()
        val granted = store.requestPermissions(wanted, activity)
        return granted.mapNotNull { permissionToTypeId[it] }.toSet()
    }

    // ------------------------------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------------------------------

    override suspend fun readPage(spec: ReadSpec): GatewayRead<ReadPage> {
        val target = entry(spec.typeId)
            ?: return failure(ExportStatus.NOT_IMPLEMENTED, "unknown data type '${spec.typeId}'")

        if (target.info.readTimeModel == TimeModel.NO_TIME_FILTER) {
            return try {
                GatewayRead.Ok(readUserProfile(target))
            } catch (t: Throwable) {
                t.toFailure()
            }
        }

        // All-history mode asks for no time filter at all, which is the SDK's own complete read.
        // Where a type insists on a filter, an unbounded `since` is used - an official, reliable
        // bound. Nothing guesses that "a few empty windows mean there is no older data".
        return try {
            GatewayRead.Ok(performRead(target, spec, unboundedSince = false))
        } catch (t: Throwable) {
            if (spec.window == null) {
                try {
                    GatewayRead.Ok(performRead(target, spec, unboundedSince = true))
                } catch (t2: Throwable) {
                    t2.toFailure()
                }
            } else {
                t.toFailure()
            }
        }
    }

    private suspend fun readUserProfile(target: DiscoveredType): ReadPage {
        @Suppress("UNCHECKED_CAST")
        val readable = target.dataType as DataType.Readable<UserDataPoint, ReadDataRequest.UserProfileBuilder>
        val response = store.readData(readable.readDataRequestBuilder.build())
        return ReadPage(
            payloads = response.dataList.map { extractor.userProfilePayload(it, target.info, target.fields) },
            nextPageToken = response.pageToken,
        )
    }

    private suspend fun performRead(
        target: DiscoveredType,
        spec: ReadSpec,
        unboundedSince: Boolean,
    ): ReadPage {
        val request = buildReadRequest(target, spec, unboundedSince)
        val response = store.readData(request)
        return ReadPage(
            payloads = response.dataList.map { extractor.payloadOf(it, target.info, target.fields) },
            nextPageToken = response.pageToken,
        )
    }

    private fun buildReadRequest(
        target: DiscoveredType,
        spec: ReadSpec,
        unboundedSince: Boolean,
    ): ReadDataRequest<HealthDataPoint> = when (target.info.readTimeModel) {
        TimeModel.DUAL_TIME -> {
            @Suppress("UNCHECKED_CAST")
            val builder = (
                target.dataType as DataType.Readable<HealthDataPoint, ReadDataRequest.DualTimeBuilder<HealthDataPoint>>
                ).readDataRequestBuilder
            builder.setOrdering(Ordering.DESC)
            builder.setPageSize(spec.pageSize)
            spec.pageToken?.let { builder.setPageToken(it) }
            applyDualTimeFilter(builder, spec, unboundedSince)
            builder.build()
        }

        TimeModel.LOCAL_DATE -> {
            @Suppress("UNCHECKED_CAST")
            val builder = (
                target.dataType as DataType.Readable<HealthDataPoint, ReadDataRequest.LocalDateBuilder<HealthDataPoint>>
                ).readDataRequestBuilder
            builder.setOrdering(Ordering.DESC)
            builder.setPageSize(spec.pageSize)
            spec.pageToken?.let { builder.setPageToken(it) }
            applyLocalDateFilter(builder, spec, unboundedSince)
            builder.build()
        }

        else -> throw IllegalStateException("${spec.typeId} has no readable time model")
    }

    private fun applyDualTimeFilter(
        builder: ReadDataRequest.DualTimeBuilder<HealthDataPoint>,
        spec: ReadSpec,
        unboundedSince: Boolean,
    ) {
        val window = spec.queryWindow ?: spec.window
        when {
            // The exact half-open window, to the instant.
            window != null && !window.isUnbounded ->
                builder.setLocalTimeFilter(
                    LocalTimeFilter.of(window.startLocal, window.endExclusiveLocal, true, false),
                )

            unboundedSince ->
                builder.setLocalTimeFilter(LocalTimeFilter.since(EPOCH_START_LOCAL))

            else -> Unit // No filter: the SDK's own complete read.
        }
    }

    private fun applyLocalDateFilter(
        builder: ReadDataRequest.LocalDateBuilder<HealthDataPoint>,
        spec: ReadSpec,
        unboundedSince: Boolean,
    ) {
        val window = spec.window
        when {
            // A local-date type cannot express an 08:00 boundary, so the user's chosen LOGICAL days
            // are used and the fact that Samsung's granularity is a day is recorded in the archive.
            window != null && window.requestedStartDate != null && window.requestedEndDate != null ->
                builder.setLocalDateFilter(
                    LocalDateFilter.of(window.requestedStartDate, window.requestedEndDate, true, true),
                )

            unboundedSince -> builder.setLocalDateFilter(LocalDateFilter.since(EPOCH_START_DATE))

            else -> Unit
        }
    }

    // ------------------------------------------------------------------------------------------
    // Aggregates
    // ------------------------------------------------------------------------------------------

    override suspend fun aggregatePage(spec: AggregateSpec): GatewayRead<AggregatePage> {
        val target = entry(spec.typeId)
            ?: return failure(ExportStatus.NOT_IMPLEMENTED, "unknown data type '${spec.typeId}'")
        val operation = target.aggregateOperations[spec.operationName]
            ?: return failure(
                ExportStatus.UNSUPPORTED_ON_INSTALLED_VERSION,
                "'${spec.typeId}' has no aggregate operation '${spec.operationName}'",
            )

        return try {
            val request = buildAggregateRequest(operation, spec)
            val response = store.aggregateData(request)
            GatewayRead.Ok(
                AggregatePage(
                    values = response.dataList.map { it.toAggregateValue() },
                    nextPageToken = response.pageToken,
                ),
            )
        } catch (t: Throwable) {
            t.toFailure()
        }
    }

    private fun buildAggregateRequest(
        operation: AggregateOperation<Any, *>,
        spec: AggregateSpec,
    ): AggregateRequest<Any> {
        val builder: Any = operation.requestBuilder as Any
        val window = spec.window

        return when (builder) {
            is AggregateRequest.DualTimeBuilder<*> -> {
                builder.setPageSize(spec.pageSize)
                builder.setOrdering(Ordering.ASC)
                spec.pageToken?.let { builder.setPageToken(it) }
                if (window != null && !window.isUnbounded) {
                    builder.setLocalTimeFilterWithGroup(
                        LocalTimeFilter.of(window.startLocal, window.endExclusiveLocal, true, false),
                        LocalTimeGroup.of(localTimeGroupUnit(spec.grouping), 1),
                    )
                } else {
                    builder.setLocalTimeFilterWithGroup(
                        LocalTimeFilter.since(EPOCH_START_LOCAL),
                        LocalTimeGroup.of(localTimeGroupUnit(spec.grouping), 1),
                    )
                }
                cast(builder.build())
            }

            is AggregateRequest.LocalTimeBuilder<*> -> {
                builder.setPageSize(spec.pageSize)
                builder.setOrdering(Ordering.ASC)
                spec.pageToken?.let { builder.setPageToken(it) }
                if (window != null && !window.isUnbounded) {
                    builder.setLocalTimeFilterWithGroup(
                        LocalTimeFilter.of(window.startLocal, window.endExclusiveLocal, true, false),
                        LocalTimeGroup.of(localTimeGroupUnit(spec.grouping), 1),
                    )
                } else {
                    builder.setLocalTimeFilterWithGroup(
                        LocalTimeFilter.since(EPOCH_START_LOCAL),
                        LocalTimeGroup.of(localTimeGroupUnit(spec.grouping), 1),
                    )
                }
                cast(builder.build())
            }

            is AggregateRequest.LocalDateBuilder<*> -> {
                builder.setPageSize(spec.pageSize)
                builder.setOrdering(Ordering.ASC)
                spec.pageToken?.let { builder.setPageToken(it) }
                builder.setLocalDateFilterWithGroup(
                    localDateFilterFor(window),
                    LocalDateGroup.of(LocalDateGroupUnit.DAILY, 1),
                )
                cast(builder.build())
            }

            is AggregateRequest.AllSourceLocalDateBuilder<*> -> {
                builder.setPageSize(spec.pageSize)
                builder.setOrdering(Ordering.ASC)
                spec.pageToken?.let { builder.setPageToken(it) }
                builder.setLocalDateFilterWithGroup(
                    localDateFilterFor(window),
                    LocalDateGroup.of(LocalDateGroupUnit.DAILY, 1),
                )
                cast(builder.build())
            }

            else -> throw IllegalStateException(
                "aggregate builder ${builder.javaClass.name} is not a known Samsung builder family",
            )
        }
    }

    /**
     * Translates the archive-level grouping string into Samsung's own group unit.
     *
     * Only local-time builder families expose finer units; the local-date branches above keep
     * their mandatory DAILY grouping. An unrecognised value falls back to DAILY rather than
     * failing an otherwise valid export.
     */
    private fun localTimeGroupUnit(grouping: String): LocalTimeGroupUnit = when (grouping.uppercase()) {
        "MINUTELY" -> LocalTimeGroupUnit.MINUTELY
        "HOURLY" -> LocalTimeGroupUnit.HOURLY
        else -> LocalTimeGroupUnit.DAILY
    }

    /** Day-granular by Samsung's own definition; the archive records that this is the case. */
    private fun localDateFilterFor(window: com.rawwave.mobile.samsunghealth.export.ResolvedExportWindow?): LocalDateFilter =
        if (window?.requestedStartDate != null && window.requestedEndDate != null) {
            LocalDateFilter.of(window.requestedStartDate, window.requestedEndDate, true, true)
        } else {
            LocalDateFilter.since(EPOCH_START_DATE)
        }

    @Suppress("UNCHECKED_CAST")
    private fun cast(request: AggregateRequest<*>): AggregateRequest<Any> = request as AggregateRequest<Any>

    private fun AggregatedData<*>.toAggregateValue(): AggregateValue = AggregateValue(
        value = extractor.aggregateValueJson(value, null),
        start = extractor.sourceTime(startTime, null, safeLocal { getStartLocalDateTime() }),
        end = extractor.sourceTime(endTime, null, safeLocal { getEndLocalDateTime() }),
        startLocalDateTime = safeLocal { getStartLocalDateTime() }?.toString(),
        endLocalDateTime = safeLocal { getEndLocalDateTime() }?.toString(),
    )

    private inline fun safeLocal(block: () -> LocalDateTime?): LocalDateTime? = try {
        block()
    } catch (t: Throwable) {
        null
    }

    // ------------------------------------------------------------------------------------------
    // Associated data
    // ------------------------------------------------------------------------------------------

    override suspend fun readAssociatedPage(spec: AssociatedSpec): GatewayRead<ReadPage> {
        if (spec.parentTypeId != "sleep") {
            return failure(
                ExportStatus.UNSUPPORTED_ON_INSTALLED_VERSION,
                "associated reads are not declared for '${spec.parentTypeId}'",
            )
        }
        val associate = when (spec.associateId) {
            "blood_oxygen" -> DataType.SleepType.Associates.BLOOD_OXYGEN
            "skin_temperature" -> DataType.SleepType.Associates.SKIN_TEMPERATURE
            else -> return failure(
                ExportStatus.UNSUPPORTED_ON_INSTALLED_VERSION,
                "'${spec.associateId}' is not an associate of sleep",
            )
        }
        val associateEntry = entry(spec.associateId)
            ?: return failure(ExportStatus.NOT_IMPLEMENTED, "no catalog entry for '${spec.associateId}'")

        return try {
            val builder = DataTypes.SLEEP.associatedReadRequestBuilder
                .setIdFilter(IdFilter.fromDataUid(spec.parentUid))
                .addAssociatedDataType(associate)
            builder.setPageSize(spec.pageSize)
            spec.pageToken?.let { builder.setPageToken(it) }

            val dataType = associate.dataType
            val response = store.readAssociatedData(builder.build())
            val payloads = response.dataList
                .flatMap { it.getDataPointOf(dataType).orEmpty() }
                .map { extractor.payloadOf(it, associateEntry.info, associateEntry.fields) }

            GatewayRead.Ok(ReadPage(payloads, response.pageToken))
        } catch (t: Throwable) {
            t.toFailure()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Version
    // ------------------------------------------------------------------------------------------

    override suspend fun sdkVersionName(): String = try {
        // The shipped AAR is R8-processed, so this is a plain static function rather than a
        // Kotlin property accessor.
        SdkVersion.getVersionName()
    } catch (t: Throwable) {
        "UNKNOWN"
    }

    override suspend fun sdkVersionCode(): Int? = try {
        SdkVersion.getVersionCode()
    } catch (t: Throwable) {
        null
    }

    // ------------------------------------------------------------------------------------------
    // Error mapping
    // ------------------------------------------------------------------------------------------

    private fun failure(status: ExportStatus, message: String) =
        GatewayRead.Failed(status = status, errorCode = null, exception = null, message = message)

    /**
     * Maps a Samsung failure onto the status the archive reports.
     *
     * The three states that must never be conflated - "not permitted", "this platform cannot do it"
     * and "the call broke" - are separated here, at the only layer that knows what the SDK's error
     * codes mean.
     */
    private fun Throwable.toFailure(): GatewayRead.Failed {
        val code = (this as? HealthDataException)?.errorCode
        val status = when {
            this is AuthorizationException -> ExportStatus.PERMISSION_DENIED

            this is InvalidRequestException -> ExportStatus.UNSUPPORTED_ON_INSTALLED_VERSION

            code == ErrorCode.ERR_PLATFORM_NOT_INSTALLED -> ExportStatus.UNSUPPORTED_ON_INSTALLED_VERSION
            code == ErrorCode.ERR_OLD_VERSION_PLATFORM -> ExportStatus.UNSUPPORTED_ON_INSTALLED_VERSION
            code == ErrorCode.ERR_UNSUPPORTED_OPERATION -> ExportStatus.UNSUPPORTED_ON_INSTALLED_VERSION

            code == ErrorCode.ERR_PLATFORM_DISABLED -> ExportStatus.NOT_AVAILABLE_FOR_THIS_ACCOUNT_OR_DEVICE
            code == ErrorCode.ERR_PLATFORM_NOT_INITIALIZED -> ExportStatus.NOT_AVAILABLE_FOR_THIS_ACCOUNT_OR_DEVICE
            code == ErrorCode.ERR_CHILD_ACCOUNT_ACCESS -> ExportStatus.NOT_AVAILABLE_FOR_THIS_ACCOUNT_OR_DEVICE
            code == ErrorCode.ERR_ACCESS_CONTROL -> ExportStatus.NOT_AVAILABLE_FOR_THIS_ACCOUNT_OR_DEVICE
            code == ErrorCode.ERR_INVALID_CALLER -> ExportStatus.NOT_AVAILABLE_FOR_THIS_ACCOUNT_OR_DEVICE

            code == ErrorCode.ERR_NO_USER_PERMISSION -> ExportStatus.PERMISSION_DENIED

            else -> ExportStatus.SDK_ERROR
        }
        return GatewayRead.Failed(
            status = status,
            errorCode = code,
            exception = this::class.java.name,
            message = message,
        )
    }

    private fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        null
    }

    private companion object {
        /**
         * The lower bound used when a type insists on a time filter but the user asked for all
         * history. 1970-01-01 is a single, official, reliable bound - not a heuristic that could
         * stop early and silently miss sparse older data.
         */
        val EPOCH_START_DATE: LocalDate = LocalDate.of(1970, 1, 1)
        val EPOCH_START_LOCAL: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
    }
}
