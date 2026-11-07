package com.rawwave.mobile.samsunghealth.export

import org.junit.Assert.assertEquals
import org.junit.Test

class AggregateGroupingPolicyTest {

    @Test
    fun stepsUsesMinutelyBecauseItIsAggregateOnlyAndNeedsLogicalDaySums() {
        assertEquals(
            AggregateGroupingPolicy.MINUTELY,
            AggregateGroupingPolicy.groupingFor("steps", TimeModel.LOCAL_TIME),
        )
    }

    @Test
    fun stepsFallsBackToDailyIfSamsungChangesTheBuilderFamily() {
        assertEquals(
            AggregateGroupingPolicy.DAILY,
            AggregateGroupingPolicy.groupingFor("steps", TimeModel.LOCAL_DATE),
        )
    }

    @Test
    fun derivedLocalTimeAggregatesStayDaily() {
        assertEquals(
            AggregateGroupingPolicy.DAILY,
            AggregateGroupingPolicy.groupingFor("activity_summary", TimeModel.LOCAL_TIME),
        )
        assertEquals(
            AggregateGroupingPolicy.DAILY,
            AggregateGroupingPolicy.groupingFor("floors_climbed", TimeModel.LOCAL_TIME),
        )
    }

    @Test
    fun localDateAndGoalAggregatesStayDaily() {
        assertEquals(
            AggregateGroupingPolicy.DAILY,
            AggregateGroupingPolicy.groupingFor("heart_rate", TimeModel.LOCAL_DATE),
        )
        assertEquals(
            AggregateGroupingPolicy.DAILY,
            AggregateGroupingPolicy.groupingFor("steps_goal", TimeModel.ALL_SOURCE_LOCAL_DATE),
        )
    }

    @Test
    fun unknownTypesNeverGainAnUnsupportedGrouping() {
        assertEquals(
            AggregateGroupingPolicy.DAILY,
            AggregateGroupingPolicy.groupingFor("some_future_type", TimeModel.NO_TIME_FILTER),
        )
    }
}
