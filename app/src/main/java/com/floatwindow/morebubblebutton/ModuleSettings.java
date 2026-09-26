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
    public static final String KEY_POSITION_MODE = "position_mode"; // 0=跟随原按钮 1=第二行
    public static final String KEY_POS_X = "pos_x"; // 0-100, 50=居中
    public static final String KEY_POS_Y = "pos_y"; // 0-100, 50=居中
    public static final String KEY_SYSTEMUI_BUBBLE_ENABLED = "systemui_bubble_enabled";
    public static final String KEY_BUBBLE_WIDTH_PERCENT = "bubble_width_percent";
    public static final String KEY_BUBBLE_HEIGHT_PERCENT = "bubble_height_percent";
    public static final String KEY_CONTENT_SCALE_PERCENT = "content_scale_percent";
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

    private static final java.util.concurrent.atomic.AtomicBoolean sRemoteLoadedLogged =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean sRemoteFailureLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

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
            if (sRemoteLoadedLogged.compareAndSet(false, true)) {
                android.util.Log.i("MoreBubbleModule", "MBDBG remote settings loaded process="
                        + ctx.getApplicationContext().getPackageName()
                        + " width=" + map.get(KEY_BUBBLE_WIDTH_PERCENT)
                        + " height=" + map.get(KEY_BUBBLE_HEIGHT_PERCENT));
            }
            return map;
        } catch (Throwable t) {
            if (sRemoteFailureLogged.compareAndSet(false, true)) {
                android.util.Log.w("MoreBubbleModule", "MBDBG remote settings query failed process="
                        + ctx.getApplicationContext().getPackageName()
                        + " type=" + t.getClass().getName()
                        + " message=" + t.getMessage());
            }
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

    /** 0=跟随原按钮  1=第二行 */
    public static int getPositionMode(Context ctx) {
        return getInt(ctx, KEY_POSITION_MODE, 0);
    }
    public static void setPositionMode(Context ctx, int v) {
        getPrefs(ctx).edit().putInt(KEY_POSITION_MODE, v).apply();
    }

    /** X 轴位置 0-100，50=居中（已含图标偏移补偿） */
    public static int getPosX(Context ctx) {
        return getInt(ctx, KEY_POS_X, 50);
    }
    public static void setPosX(Context ctx, int v) {
        getPrefs(ctx).edit().putInt(KEY_POS_X, clampPercent(v)).apply();
    }

    /** Y 轴位置 0-100，50=居中 */
    public static int getPosY(Context ctx) {
        return getInt(ctx, KEY_POS_Y, 50);
    }
    public static void setPosY(Context ctx, int v) {
        getPrefs(ctx).edit().putInt(KEY_POS_Y, clampPercent(v)).apply();
    }

    /** 通知横幅气泡按钮开关 */
    public static boolean isSystemUiBubbleEnabled(Context ctx) {
        return getBoolean(ctx, KEY_SYSTEMUI_BUBBLE_ENABLED, true);
    }
    public static void setSystemUiBubbleEnabled(Context ctx, boolean v) {
        getPrefs(ctx).edit().putBoolean(KEY_SYSTEMUI_BUBBLE_ENABLED, v).apply();
    }

    /** Android 17 应用气泡窗口宽度百分比，默认 100% */
    public static int getBubbleWidthPercent(Context ctx) {
        return clampBubblePercent(getInt(ctx, KEY_BUBBLE_WIDTH_PERCENT, 100));
    }
    public static void setBubbleWidthPercent(Context ctx, int v) {
        getPrefs(ctx).edit().putInt(KEY_BUBBLE_WIDTH_PERCENT, clampBubblePercent(v)).apply();
    }

    /** Android 17 应用气泡窗口高度百分比，默认 100% */
    public static int getBubbleHeightPercent(Context ctx) {
        return clampBubblePercent(getInt(ctx, KEY_BUBBLE_HEIGHT_PERCENT, 100));
    }
    public static void setBubbleHeightPercent(Context ctx, int v) {
        getPrefs(ctx).edit().putInt(KEY_BUBBLE_HEIGHT_PERCENT, clampBubblePercent(v)).apply();
    }

    private static int clampBubblePercent(int v) {
        return Math.max(50, Math.min(150, v));
    }

    /**
     * 气泡内 app 内容的缩放百分比，默认 100%（不缩放）。
     * 小于 100% 时 app 会按更大的尺寸排版，再把画面等比缩小，视觉上等于「内容整体变小」。
     */
    public static int getContentScalePercent(Context ctx) {
        return Math.max(50, Math.min(100, getInt(ctx, KEY_CONTENT_SCALE_PERCENT, 100)));
    }
    public static void setContentScalePercent(Context ctx, int v) {
        getPrefs(ctx).edit().putInt(KEY_CONTENT_SCALE_PERCENT, Math.max(50, Math.min(100, v))).apply();
    }

    private static int clampPercent(int v) {
        return Math.max(0, Math.min(100, v));
    }

    // 兼容旧接口
    public static int getBottomPosition(Context ctx) {
        return getPositionMode(ctx) == 1 ? 1 : 0;
    }
}
