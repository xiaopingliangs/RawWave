package com.rawwave.shell
import org.junit.Assert.*
import org.junit.Test

class WatchSyncOwnerTest {
    @Test fun constructorFailureClearsOwnershipAndPublishesFailure() {
        val app=ProbeApplication()
        val owner=WatchActivitySyncSession(app,"MANUAL")
        app.activeWatchActivitySync=owner
        owner.transferFactory={ _, _ -> throw IllegalStateException("injected constructor failure")}
        owner.start()
        assertNull(app.activeSyncOwner)
        assertNull(app.activeWatchActivitySync)
        assertNull(app.stopBackgroundSync)
        assertFalse(app.activitySyncStatus.running)
        assertFalse(app.activitySyncStatus.success)
        assertTrue(app.activitySyncStatus.text.contains("injected constructor failure"))
    }
    @Test fun busyStartDoesNotClearAnotherOwnersState() {
        val app=ProbeApplication();app.activeSyncOwner="existing"
        val status=ProbeApplication.ActivitySyncStatus(true,false,"existing")
        app.activitySyncStatus=status
        val owner=WatchActivitySyncSession(app,"MANUAL")
        owner.transferFactory={ _, _ -> error("must not construct while busy")}
        owner.start()
        assertEquals("existing",app.activeSyncOwner)
        assertSame(status,app.activitySyncStatus)
    }
}
