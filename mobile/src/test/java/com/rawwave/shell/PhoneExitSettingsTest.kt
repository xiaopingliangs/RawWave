package com.rawwave.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneExitSettingsTest {

    @Test fun zeroMeansNeverExit() {
        assertEquals("不自动退出", PhoneExitSettings.labelFor(0))
        assertEquals("不自动退出", PhoneExitSettings.labelFor(-5))
    }

    @Test fun labelUsesHoursAndMinutes() {
        assertEquals("30 分钟", PhoneExitSettings.labelFor(30))
        assertEquals("1 小时", PhoneExitSettings.labelFor(60))
        assertEquals("1 小时 30 分钟", PhoneExitSettings.labelFor(90))
        assertEquals("23 小时 59 分钟", PhoneExitSettings.labelFor(2000))
    }
}
