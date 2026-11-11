package com.rawwave.shell

import com.rawwave.core.ActivitySyncPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WatchSyncRetryTest {
    private class FakeHandle(private val onStart: () -> Unit) : ActivityTransferHandle {
        override fun start() = onStart()
        override fun cancel() {}
    }

    private fun owner(app: ProbeApplication, starts: AtomicInteger): WatchActivitySyncSession {
        val owner=WatchActivitySyncSession(app,ActivitySyncPolicy.USER_MANUAL)
        app.activeWatchActivitySync=owner
        owner.post={ it() }
        owner.postDelayed={ _, action -> action() }
        owner.leaseFactory={ _, _, ready, _ -> ready(); null }
        owner.transferFactory={ callbacks, _ ->
            val attempt=starts.incrementAndGet()
            FakeHandle {
                if(attempt == 1) callbacks.finished(false,"injected first failure")
                else callbacks.finished(true,"injected success")
            }
        }
        return owner
    }

    @Test fun firstFailureRetriesOnceThenSucceeds() {
        val app=ProbeApplication();val starts=AtomicInteger()
        val owner=owner(app,starts)
        owner.start()
        assertEquals(2,starts.get())
        assertNull(app.activeSyncOwner)
        assertNull(app.activeWatchActivitySync)
        assertFalse(app.activitySyncStatus.running)
        assertTrue(app.activitySyncStatus.success)
    }

    @Test fun thirdFailureStopsAfterTwoRetries() {
        val app=ProbeApplication();val starts=AtomicInteger()
        val owner=WatchActivitySyncSession(app,ActivitySyncPolicy.USER_MANUAL)
        app.activeWatchActivitySync=owner
        owner.post={ it() }
        owner.postDelayed={ _, action -> action() }
        owner.leaseFactory={ _, _, ready, _ -> ready(); null }
        owner.transferFactory={ callbacks, _ ->
            starts.incrementAndGet()
            FakeHandle { callbacks.finished(false,"injected failure") }
        }
        owner.start()
        assertEquals(3,starts.get())
        assertNull(app.activeSyncOwner)
        assertNull(app.activeWatchActivitySync)
        assertFalse(app.activitySyncStatus.running)
        assertFalse(app.activitySyncStatus.success)
        assertTrue(app.activitySyncStatus.text.contains("injected failure"))
    }

    @Test fun hardWindowExpiryPausesInsteadOfRetrying() {
        val app=ProbeApplication();val starts=AtomicInteger()
        val owner=WatchActivitySyncSession(app,ActivitySyncPolicy.USER_MANUAL)
        app.activeWatchActivitySync=owner
        owner.post={ it() }
        owner.postDelayed={ _, action -> action() }
        owner.leaseFactory={ _, _, ready, _ -> ready(); null }
        owner.transferFactory={ callbacks, _ ->
            starts.incrementAndGet()
            FakeHandle { callbacks.finished(false,
                "活动数据同步未完成，停在：ACTIVITY_FILE_SEND_STARTED\n原因：本轮同步时间已用完\n已采集数据仍保留在手表") }
        }
        owner.start()
        assertEquals(1,starts.get())
        assertNull(app.activeSyncOwner)
        assertNull(app.activeWatchActivitySync)
        assertFalse(app.activitySyncStatus.running)
        assertFalse(app.activitySyncStatus.success)
        assertTrue(app.activitySyncStatus.text.contains("5 分钟单次上限"))
    }

    @Test fun userStopCancelsPendingRetry() {
        val app=ProbeApplication();val starts=AtomicInteger()
        val owner=WatchActivitySyncSession(app,ActivitySyncPolicy.USER_MANUAL)
        app.activeWatchActivitySync=owner
        owner.post={ it() }
        var delayed:(() -> Unit)?=null
        owner.postDelayed={ _, action -> delayed=action }
        owner.leaseFactory={ _, _, ready, _ -> ready(); null }
        owner.transferFactory={ callbacks, _ ->
            starts.incrementAndGet()
            FakeHandle { callbacks.finished(false,"injected failure") }
        }
        owner.start()
        assertEquals(1,starts.get())
        app.requestStopBackgroundSync("USER")
        delayed?.invoke()
        assertEquals(1,starts.get())
        assertNull(app.activeSyncOwner)
        assertNull(app.activeWatchActivitySync)
        assertFalse(app.activitySyncStatus.running)
        assertFalse(app.activitySyncStatus.success)
    }
}
