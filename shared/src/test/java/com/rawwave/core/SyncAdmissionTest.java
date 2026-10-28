package com.rawwave.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SyncAdmissionTest {
    @Test public void idlePhoneAcceptsRawActivity() {
        assertEquals(SyncAdmission.Decision.ACCEPT,
            SyncAdmission.activityArrival(false, false));
    }

    @Test public void rawActivityPreemptsDiagnosticReceive() {
        assertEquals(SyncAdmission.Decision.PREEMPT_DIAGNOSTIC,
            SyncAdmission.activityArrival(true, true));
    }

    @Test public void rawActivityDoesNotCorruptUnrelatedBusyWork() {
        assertEquals(SyncAdmission.Decision.REJECT_BUSY,
            SyncAdmission.activityArrival(true, false));
    }
}
