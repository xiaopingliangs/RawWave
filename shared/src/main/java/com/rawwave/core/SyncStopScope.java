package com.rawwave.core;

/** Stop ownership v1: automatic page cleanup cannot cancel another owner's background work. */
public final class SyncStopScope {
    private SyncStopScope() {}
    public static boolean mayStop(boolean explicitUserStop, String pageOwner, String activeOwner) {
        return explicitUserStop || (pageOwner!=null && !pageOwner.isEmpty() && pageOwner.equals(activeOwner));
    }
}
