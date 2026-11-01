package com.rawwave.core;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class CallbackAdmissionTest {
    @Test public void admitsBurstAndReleasesExactlyOnce() {
        var budget=new CallbackAdmission();var leases=new ArrayList<CallbackAdmission.Lease>();
        for(int n=0;n<1000;n++) {var lease=budget.reserve(List.of(Map.of("PPG_GREEN",-n)));assertNotNull(lease);leases.add(lease);}
        assertTrue(budget.pendingEstimatedBytes()>0);
        for(var lease:leases){lease.close();lease.close();}
        assertEquals(0,budget.pendingEstimatedBytes());
    }
    @Test public void nestedPayloadBudgetRejectsWithoutLeakingReservation() {
        var budget=new CallbackAdmission();
        assertNull(budget.reserve(List.of(Map.of("IBI_LIST",Collections.nCopies(300000,1)))));
        assertEquals(0,budget.pendingEstimatedBytes());
        assertNotNull(budget.reserve(List.of(Map.of("STATUS",-1))));
    }
}
