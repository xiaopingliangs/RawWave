package com.rawwave.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoSyncGateTest {
    @Test public void eligibleHomeArmsOnceAndTimerStartsSync() {
        AutoSyncGate gate = new AutoSyncGate();
        assertTrue(gate.arm(true, false, false, 6));
        assertFalse(gate.arm(true, false, false, 6));
        assertTrue(gate.fire(true, false, false, 6));
        assertFalse(gate.armed());
    }

    @Test public void explicitUserActionCancelsPendingTimer() {
        AutoSyncGate gate = new AutoSyncGate();
        assertTrue(gate.arm(true, false, false, 1));
        assertTrue(gate.cancel());
        assertFalse(gate.fire(true, false, false, 1));
    }

    @Test public void returningToForegroundCanArmAgain() {
        AutoSyncGate gate = new AutoSyncGate();
        assertTrue(gate.arm(true, false, false, 1));
        assertTrue(gate.cancel());
        assertTrue(gate.arm(true, false, false, 1));
        assertTrue(gate.fire(true, false, false, 1));
    }

    @Test public void ineligibleStatesNeverStartSync() {
        AutoSyncGate gate = new AutoSyncGate();
        assertFalse(gate.arm(false, false, false, 1));
        assertFalse(gate.arm(true, true, false, 1));
        assertFalse(gate.arm(true, false, true, 1));
        assertFalse(gate.arm(true, false, false, 0));
        assertFalse(gate.fire(true, false, false, 1));
    }
}
