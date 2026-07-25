package com.rauaap.keyboardsetter;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/** Package name -> IME ComponentName.flattenToString() mapping, backed by SharedPreferences. */
final class MappingStore {

    private static final String PREFS_NAME = "ime_mappings";

    private MappingStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static String get(Context ctx, String packageName) {
        return prefs(ctx).getString(packageName, null);
    }

    static void set(Context ctx, String packageName, String imeComponentId) {
        prefs(ctx).edit().putString(packageName, imeComponentId).apply();
    }

    static void remove(Context ctx, String packageName) {
        prefs(ctx).edit().remove(packageName).apply();
    }

    static Map<String, String> getAll(Context ctx) {
        Map<String, ?> raw = prefs(ctx).getAll();
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                result.put(entry.getKey(), (String) value);
            }
        }
        return result;
    }
}
