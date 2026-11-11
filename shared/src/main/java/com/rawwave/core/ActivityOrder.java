package com.rawwave.core;

import java.util.*;

/**
 * User-defined display order for activity templates. Pure list operations; the host owns
 * persistence. Saved ids that no longer exist are ignored, and new ids are appended in the
 * store's stable name order instead of jumping to the front.
 */
public final class ActivityOrder {
    private ActivityOrder() {}

    public static List<String> apply(List<String> allIds, List<String> savedOrder) {
        LinkedHashSet<String> remaining = new LinkedHashSet<>(allIds);
        List<String> result = new ArrayList<>();
        if (savedOrder != null) {
            for (String id : savedOrder) {
                if (id != null && remaining.remove(id) && !result.contains(id)) result.add(id);
            }
        }
        result.addAll(remaining);
        return result;
    }

    /** Moves one id by delta positions (-1 up, +1 down) and returns the new order. */
    public static List<String> move(List<String> ids, String target, int delta) {
        List<String> result = new ArrayList<>(ids);
        int from = result.indexOf(target);
        if (from < 0 || delta == 0) return result;
        int to = Math.max(0, Math.min(result.size() - 1, from + delta));
        if (to == from) return result;
        result.remove(from);
        result.add(to, target);
        return result;
    }
}
