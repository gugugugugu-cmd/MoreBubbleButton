package com.floatwindow.morebubblebutton;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import java.util.HashMap;
import java.util.Map;

public class ModuleSettings {
    public static final String PREFS_NAME = "morebubblebutton_settings";
    public static final String KEY_MENU_ENABLED = "menu_enabled";
    public static final String KEY_ACTION_BAR_ENABLED = "action_bar_enabled";
    public static final String KEY_SYSTEMUI_BUBBLE_ENABLED = "systemui_bubble_enabled";
    private static final Uri SETTINGS_URI = SettingsProvider.CONTENT_URI;
    private static final long REMOTE_CACHE_MS = 250L;
    private static volatile long sRemoteCacheAt;
    private static volatile Map<String, String> sRemoteCache;

    private static SharedPreferences getPrefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean isModuleContext(Context ctx) {
        return "com.floatwindow.morebubblebutton".equals(ctx.getApplicationContext().getPackageName());
    }

    private static Map<String, String> getRemoteSettings(Context ctx) {
        if (isModuleContext(ctx)) return null;
        long now = android.os.SystemClock.uptimeMillis();
        Map<String, String> cached = sRemoteCache;
        if (cached != null && now - sRemoteCacheAt < REMOTE_CACHE_MS) return cached;
        Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(SETTINGS_URI, null, null, null, null);
            if (cursor == null) return cached;
            Map<String, String> map = new HashMap<>();
            int keyIndex = cursor.getColumnIndex("key");
            int valueIndex = cursor.getColumnIndex("value");
            while (cursor.moveToNext()) {
                map.put(cursor.getString(keyIndex), cursor.getString(valueIndex));
            }
            sRemoteCache = map;
            sRemoteCacheAt = now;
            return map;
        } catch (Throwable ignored) {
            return cached;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public static void invalidateRemoteCache() {
        sRemoteCacheAt = 0;
        sRemoteCache = null;
    }

    private static boolean getBoolean(Context ctx, String key, boolean defValue) {
        Map<String, String> remote = getRemoteSettings(ctx);
        if (remote != null && remote.containsKey(key)) return "1".equals(remote.get(key));
        return getPrefs(ctx).getBoolean(key, defValue);
    }

    private static int getInt(Context ctx, String key, int defValue) {
        Map<String, String> remote = getRemoteSettings(ctx);
        if (remote != null && remote.containsKey(key)) {
            try { return Integer.parseInt(remote.get(key)); } catch (Throwable ignored) {}
        }
        return getPrefs(ctx).getInt(key, defValue);
    }

    public static boolean isMenuEnabled(Context ctx) {
        return getBoolean(ctx, KEY_MENU_ENABLED, true);
    }
    public static void setMenuEnabled(Context ctx, boolean v) {
        getPrefs(ctx).edit().putBoolean(KEY_MENU_ENABLED, v).apply();
    }

    public static boolean isActionBarEnabled(Context ctx) {
        return getBoolean(ctx, KEY_ACTION_BAR_ENABLED, true);
    }
    public static void setActionBarEnabled(Context ctx, boolean v) {
        getPrefs(ctx).edit().putBoolean(KEY_ACTION_BAR_ENABLED, v).apply();
    }

    /** 通知横幅气泡按钮开关 */
    public static boolean isSystemUiBubbleEnabled(Context ctx) {
        return getBoolean(ctx, KEY_SYSTEMUI_BUBBLE_ENABLED, true);
    }
    public static void setSystemUiBubbleEnabled(Context ctx, boolean v) {
        getPrefs(ctx).edit().putBoolean(KEY_SYSTEMUI_BUBBLE_ENABLED, v).apply();
    }

}
