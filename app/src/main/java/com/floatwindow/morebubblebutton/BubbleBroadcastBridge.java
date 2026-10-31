package com.floatwindow.morebubblebutton;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * 外部触发桥：让手势应用（如 GestureEVO）在任意界面把指定应用送进气泡小窗。
 *
 * <p>模块原本的开气泡入口都发生在 SystemUI / 桌面自己的界面里（通知横幅气泡按钮、
 * 最近任务气泡按钮）。这里在 SystemUI 进程内动态注册一个接收器，收到广播后直接复用
 * 模块已经验证过的 A17 路径（BubbleController.expandStackAndSelectAppBubble），
 * 不需要 NotificationEntry，也不依赖通知栏是否展开。
 *
 * <h3>为什么不用 Binder.getCallingUid() 校验</h3>
 * 广播是异步投递的：onReceive 执行时，发送方的 Binder 调用身份早已结束，
 * {@code Binder.getCallingUid()} 拿到的是投递线程自己的 uid，外部请求会被全部误拒。
 * 因此改用 {@link BroadcastReceiver#getSentFromPackage()} / {@code getSentFromUid()}，
 * 这两个值由系统在投递时填写，发送方无法伪造。
 *
 * <h3>协议</h3>
 * <pre>
 * am broadcast -p com.android.systemui \
 *      -a com.floatwindow.morebubblebutton.action.SHOW_BUBBLE --es pkg com.tencent.mm
 * </pre>
 */
public final class BubbleBroadcastBridge {
    private static final String TAG = "MoreBubbleBridge";

    /** 手势侧发送的动作名。 */
    public static final String ACTION_SHOW_BUBBLE =
            "com.floatwindow.morebubblebutton.action.SHOW_BUBBLE";
    public static final String EXTRA_PACKAGE = "pkg";
    public static final String EXTRA_ACTIVITY = "activityName";
    public static final String EXTRA_USER_ID = "userId";

    /** 受保护广播所需的权限（由本模块的 Manifest 声明）。 */
    public static final String PERMISSION =
            "com.floatwindow.morebubblebutton.permission.SHOW_BUBBLE";

    /** shell UID 与 root UID：允许它们免权限调用，方便用 am/adb 直接触发调试。 */
    private static final int UID_SHELL = 2000;
    private static final int UID_ROOT = 0;

    /** 允许触发的外部调用方包名（GestureEVO 正式版 / debug 版）。 */
    private static final String[] ALLOWED_SENDERS = {
            "io.github.lumiagg.gestureevo",
            "io.github.lumiagg.gestureevo.debug",
            "com.floatwindow.morebubblebutton",
    };

    private static volatile boolean sRegistered;

    private BubbleBroadcastBridge() {}

    /**
     * 在 SystemUI 进程注册接收器，重复调用是安全的。
     *
     * @param context SystemUI 进程内的 Context，仅用来 registerReceiver
     */
    static void install(Context context) {
        if (context == null || sRegistered) return;
        synchronized (BubbleBroadcastBridge.class) {
            if (sRegistered) return;
            try {
                IntentFilter filter = new IntentFilter(ACTION_SHOW_BUBBLE);
                // SystemUI 的 targetSdk 很高，动态注册必须显式声明导出，
                // 否则来自外部应用的广播会被系统直接丢弃。
                context.registerReceiver(new Receiver(context), filter, Context.RECEIVER_EXPORTED);
                sRegistered = true;
                Log.i(TAG, "bridge installed action=" + ACTION_SHOW_BUBBLE);
            } catch (Throwable t) {
                Log.e(TAG, "bridge install failed: " + t.getMessage());
            }
        }
    }

    private static final class Receiver extends BroadcastReceiver {
        private final Context systemUiContext;

        Receiver(Context systemUiContext) {
            this.systemUiContext = systemUiContext;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_SHOW_BUBBLE.equals(intent.getAction())) return;
            if (!isTrusted()) {
                Log.w(TAG, "SHOW_BUBBLE rejected: caller not trusted sender=" + safeSender());
                return;
            }
            if (!ModuleSettings.isExternalTriggerEnabled(context)) {
                Log.w(TAG, "SHOW_BUBBLE rejected: external trigger disabled in settings");
                return;
            }
            String pkg = intent.getStringExtra(EXTRA_PACKAGE);
            if (pkg == null || pkg.isEmpty()) {
                Log.w(TAG, "SHOW_BUBBLE rejected: missing " + EXTRA_PACKAGE);
                return;
            }
            int userId = 0;
            try {
                int extra = intent.getIntExtra(EXTRA_USER_ID, -1);
                if (extra >= 0) userId = extra;
            } catch (Throwable ignored) { }

            Log.i(TAG, "SHOW_BUBBLE requested pkg=" + pkg + " userId=" + userId);
            String activity = intent.getStringExtra(EXTRA_ACTIVITY);
            try {
                MoreBubbleHookModule.requestAppBubble(systemUiContext, pkg, activity, userId);
            } catch (Throwable t) {
                Log.w(TAG, "SHOW_BUBBLE dispatch failed: " + t.getMessage());
            }
        }

        private String safeSender() {
            try {
                return String.valueOf(getSentFromPackage());
            } catch (Throwable t) {
                return "unknown";
            }
        }

        /**
         * shell / root、白名单包名，或持有 {@code SHOW_BUBBLE} 权限的调用方放行。
         */
        private boolean isTrusted() {
            try {
                int uid = getSentFromUid();
                if (uid == UID_ROOT || uid == UID_SHELL) return true;
                String sender = getSentFromPackage();
                if (sender != null) {
                    for (String allowed : ALLOWED_SENDERS) {
                        if (allowed.equals(sender)) return true;
                    }
                }
                if (uid < 0 || systemUiContext == null) return false;
                android.content.pm.PackageManager pm = systemUiContext.getPackageManager();
                String[] pkgs = pm.getPackagesForUid(uid);
                if (pkgs == null) return false;
                for (String p : pkgs) {
                    if (pm.checkPermission(PERMISSION, p)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        return true;
                    }
                }
            } catch (Throwable ignored) { }
            return false;
        }
    }
}
