package com.rawwave.mobile.samsunghealth.export

/**
 * Chooses the official Samsung aggregate grouping for one operation.
 *
 * Samsung's aggregate builder families do not support the same group units. Local-date builders
 * only expose DAILY; local-time builders also expose MINUTELY. The previous archive used DAILY
 * everywhere for one simple rule, but that loses real resolution for **steps**, which Samsung
 * exposes ONLY as an aggregate and which a reader needs at a finer grain to sum into a
 * non-midnight logical day (for example 07:30 -> 07:30).
 *
 * The policy is deliberately narrow and pure:
 *
 * - `steps` + a local-time builder -> `MINUTELY`, because minute buckets are the finest official
 *   representation and allow exact logical-day arithmetic.
 * - Everything else -> `DAILY`, because it is a goal, a score, a derived summary, or a type whose
 *   raw records are already exported separately.
 *
 * No SDK types appear here, so this policy can be unit-tested and replaced without touching the
 * gateway, the adapter, or the archive writer. It is part of the export module's contract: the
 * adapter passes the returned string through [com.rawwave.mobile.samsunghealth.AggregateSpec], and
 * the gateway translates it into Samsung's own group unit.
 */
object AggregateGroupingPolicy {

    const val MINUTELY: String = "MINUTELY"
    const val DAILY: String = "DAILY"

    fun groupingFor(typeId: String, family: TimeModel): String =
        if (typeId == "steps" && family == TimeModel.LOCAL_TIME) {
            MINUTELY
        } else {
            DAILY
        }
}
