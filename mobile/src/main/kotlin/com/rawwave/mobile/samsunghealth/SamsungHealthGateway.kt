package com.rawwave.mobile.samsunghealth

import android.app.Activity
import com.rawwave.mobile.samsunghealth.export.AggregateOperationInfo
import com.rawwave.mobile.samsunghealth.export.ExportOperation
import com.rawwave.mobile.samsunghealth.export.ExportStatus
import com.rawwave.mobile.samsunghealth.export.ResolvedExportWindow
import com.rawwave.mobile.samsunghealth.export.SourcePayload
import com.rawwave.mobile.samsunghealth.export.SourceTime
import com.rawwave.mobile.samsunghealth.export.TimeModel
import kotlinx.serialization.json.JsonElement

/**
 * The Samsung Health Data SDK boundary, expressed with NO SDK types.
 *
 * Everything the rest of `:mobile` is allowed to know about Samsung Health is declared here. The
 * concrete implementation is the only code that imports `com.samsung.android.sdk.health.data.*`,
 * which is what lets the catalog, the adapters, the coordinator, the exporter and every test work
 * without the SDK present.
 *
 * READ ONLY. There is no insert, update or delete on this interface, and none may be added.
 */
interface SamsungHealthGateway {

    /** The SDK's own version string. Never guessed. */
    suspend fun sdkVersionName(): String

    /** The SDK's own version code, when it exposes one. */
    suspend fun sdkVersionCode(): Int?

    /**
     * The official data-type catalog **as the installed SDK exposes it**.
     *
     * Derived by reflecting over the SDK's own type registry rather than from a hand-written list,
     * so a type Samsung adds or removes is reflected here instead of being silently ignored.
     */
    fun dataTypes(): List<DataTypeInfo>

    /** Which types currently have their READ permission granted, by type id. */
    suspend fun grantedReadPermissions(): GatewayRead<Set<String>>

    /** Shows the permission sheet for exactly [typeIds] and returns what the user actually granted. */
    suspend fun requestReadPermissions(activity: Activity, typeIds: Set<String>): Set<String>

    /** One page of a `readData` query. */
    suspend fun readPage(spec: ReadSpec): GatewayRead<ReadPage>

    /** One page of an `aggregateData` query. */
    suspend fun aggregatePage(spec: AggregateSpec): GatewayRead<AggregatePage>

    /**
     * One page of associated data belonging to one parent record.
     *
     * Paginated for the same reason reads are: the sleep proof only ever saw one page because its
     * dataset was small, and assuming that forever would silently truncate.
     */
    suspend fun readAssociatedPage(spec: AssociatedSpec): GatewayRead<ReadPage>
}

/** Outcome of an operation that can fail as a whole. Failures never abort the whole export. */
sealed interface GatewayRead<out T> {
    data class Ok<T>(val value: T) : GatewayRead<T>

    /**
     * The failure, already mapped to the status the archive should report.
     *
     * The SDK-to-status mapping lives in the gateway because that is the only layer that knows what
     * a Samsung error code means; everything downstream just records the status.
     */
    data class Failed(
        val status: ExportStatus,
        val errorCode: Int?,
        val exception: String?,
        val message: String?,
    ) : GatewayRead<Nothing>
}

/** One official data type, as the SDK describes it. */
data class DataTypeInfo(
    /** RawWave's stable id, e.g. `heart_rate`. */
    val typeId: String,
    /** The `DataTypes` constant this came from, e.g. `HEART_RATE`. */
    val sdkFieldName: String,
    /** The SDK's own type name, verbatim. */
    val sdkTypeName: String,
    val readable: Boolean,
    val changeReadable: Boolean,
    val writeable: Boolean,
    /** Every field the SDK exposes for this type, in the SDK's own order. */
    val fields: List<String>,
    /** The official aggregate operations this type declares. */
    val aggregateOperations: List<AggregateOperationInfo>,
    /** How this type expresses time in its READ builder. */
    val readTimeModel: TimeModel,
    /** The associated types this type can be read with, e.g. sleep -> blood oxygen, skin temperature. */
    val associatedTypes: List<String> = emptyList(),
    /**
     * When a type has no read or aggregate entry point of its own and only exists nested inside a
     * parent, this names the parent's `typeId`.
     */
    val nestedInParentTypeId: String? = null,
) {
    val supportedOperations: List<ExportOperation>
        get() = buildList {
            if (readable) {
                add(
                    if (readTimeModel == TimeModel.NO_TIME_FILTER) {
                        ExportOperation.READ_USER_PROFILE
                    } else {
                        ExportOperation.READ_RECORDS
                    },
                )
            }
            if (aggregateOperations.isNotEmpty()) add(ExportOperation.AGGREGATE)
            if (isEmpty()) add(ExportOperation.NESTED_IN_PARENT)
        }
}

/** Default page size. Large enough to be quick, small enough that streaming stays streaming. */
const val DEFAULT_PAGE_SIZE: Int = 500

/** A `readData` request, already resolved to concrete parameters. */
data class ReadSpec(
    val typeId: String,
    /** `null` means "no time filter at all", which is what all-available-history asks for. */
    val window: ResolvedExportWindow?,
    /**
     * A wider window used only to fetch interval records that may start before [window].
     * Inclusion still uses [window]; this never widens what is exported.
     */
    val queryWindow: ResolvedExportWindow? = null,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val pageToken: String? = null,
    /**
     * True when Samsung's own filter for this type is coarser than the window (for example a
     * local-date-only type, which cannot express an 08:00 boundary). Recorded so the archive can
     * say the selection came from Samsung's day granularity rather than our exact window.
     */
    val useSamsungCoarseFilter: Boolean = false,
)

/** An `aggregateData` request for one official operation. */
data class AggregateSpec(
    val typeId: String,
    val operationName: String,
    val window: ResolvedExportWindow?,
    /**
     * The grouping requested for this operation, chosen by the export module's
     * `AggregateGroupingPolicy` and recorded next to every aggregate it produces.
     *
     * The gateway translates it into Samsung's own group unit where the builder family supports
     * it; local-date builders keep their mandatory DAILY grouping. Keeping this as a plain string
     * is the hand-off contract between the export module and the SDK adapter: no SDK group-unit
     * type leaks into the archive layer.
     */
    val grouping: String,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val pageToken: String? = null,
)

/** An associated-data request for one parent record. */
data class AssociatedSpec(
    val parentTypeId: String,
    val associateId: String,
    val parentUid: String,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val pageToken: String? = null,
)

/** One page of source payloads. */
class ReadPage(
    val payloads: List<SourcePayload>,
    val nextPageToken: String?,
)

/** One aggregate value plus the interval Samsung Health attributed it to. */
class AggregateValue(
    val value: JsonElement,
    val start: SourceTime?,
    val end: SourceTime?,
    val startLocalDateTime: String?,
    val endLocalDateTime: String?,
)

/** One page of aggregate values. */
class AggregatePage(
    val values: List<AggregateValue>,
    val nextPageToken: String?,
)
