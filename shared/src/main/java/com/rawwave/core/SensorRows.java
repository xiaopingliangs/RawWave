package com.rawwave.core;

import java.util.*;

/** Own the handoff data, including nested SDK lists; never filter or infer raw fields. */
public final class SensorRows {
    private SensorRows() {}
    public static List<Map<String,Object>> snapshot(List<? extends Map<String,?>> rows) {
        List<Map<String,Object>> copy = new ArrayList<>(rows.size());
        for (Map<String,?> row : rows) copy.add(copyMap(row));
        return Collections.unmodifiableList(copy);
    }
    private static Map<String,Object> copyMap(Map<String,?> source) {
        Map<String,Object> copy = new LinkedHashMap<>();
        source.forEach((key,value) -> copy.put(Objects.requireNonNull(key), copyValue(value)));
        return Collections.unmodifiableMap(copy);
    }
    private static Object copyValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean ||
                value instanceof Byte || value instanceof Short || value instanceof Integer ||
                value instanceof Long || value instanceof Float || value instanceof Double) return value;
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(copyValue(item));
            return Collections.unmodifiableList(copy);
        }
        // Do not silently serialize an SDK object or stringify unknown scientific values.
        throw new IllegalArgumentException("UNSUPPORTED_SENSOR_VALUE:" + value.getClass().getName());
    }
}
