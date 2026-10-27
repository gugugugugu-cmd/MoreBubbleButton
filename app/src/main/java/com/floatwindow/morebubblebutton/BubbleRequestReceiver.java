package com.floatwindow.morebubblebutton;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 外部调用入口：让第三方手势工具（如 GestureEVO 的轮盘）请求「给某个应用弹出气泡小窗」。
 *
 * <h3>为什么需要这个入口</h3>
 * 模块真正的开气泡能力（{@code expandAppBubbleA17}）跑在 SystemUI 进程里，此前只在
 * 通知横幅气泡按钮的点击回调里暴露，外部无法触达。要让轮盘也能开气泡，就必须有一条
 * 从外部进程进入 SystemUI 的通路。
 *
 * <h3>协议</h3>
 * <pre>
 * am broadcast -a com.floatwindow.morebubblebutton.action.SHOW_BUBBLE \
 *              --es pkg com.tencent.mm
 * </pre>
 * 需要 {@link #PERMISSION} 权限或 UID 属于 shell/root（见 {@link #isTrusted}）。
 *
 * <h3>为什么用广播而不是 AIDL</h3>
 * 调用方（GestureEVO 的 OPEN_SHELL 动作）只需要执行一条 shell 命令，不需要绑定服务、
 * 不需要在对方侧引入我们的 AAR。注册的是 **动态** 接收器，注册时机由 SystemUI 的 hook
 * 安装完成决定，不依赖宿主是否在清单里声明。
 */
public final class BubbleRequestReceiver extends BroadcastReceiver {
    private static final String TAG = "MoreBubbleModule";

    /** 广播动作：请求给某个应用开气泡。 */
    public static final String ACTION_SHOW_BUBBLE =
            "com.floatwindow.morebubblebutton.action.SHOW_BUBBLE";

    /** 受保护广播所需的权限（由本模块的 Manifest 声明）。 */
    public static final String PERMISSION =
            "com.floatwindow.morebubblebutton.permission.SHOW_BUBBLE";

    public static final String EXTRA_PACKAGE = "pkg";

    /** shell UID 与 root UID：允许它们免权限调用，方便用 am/adb 直接触发调试。 */
    private static final int UID_SHELL = 2000;
    private static final int UID_ROOT = 0;

    private static volatile boolean registered = false;
    private static BubbleRequestReceiver instance;

    private final Context systemUiContext;

    private BubbleRequestReceiver(Context systemUiContext) {
        this.systemUiContext = systemUiContext;
    }

    /**
     * 在 SystemUI 进程注册接收器，重复调用是安全的。
     *
     * @param context 任意 SystemUI 进程内的 Context，仅用来 registerReceiver
     */
    public static void install(Context context) {
        if (context == null || registered) return;
        synchronized (BubbleRequestReceiver.class) {
            if (registered) return;
            try {
                BubbleRequestReceiver receiver = new BubbleRequestReceiver(context);
                IntentFilter filter = new IntentFilter(ACTION_SHOW_BUBBLE);
                // 动态注册时不带权限参数：改用 isTrusted() 在 onReceive 里自行校验。
                // 这样 shell（am broadcast）和正式调用方都能用，又不会对任意应用开放。
                context.registerReceiver(receiver, filter, null, null, Context.RECEIVER_EXPORTED);
                instance = receiver;
                registered = true;
                Log.i(TAG, "BubbleRequestReceiver installed action=" + ACTION_SHOW_BUBBLE);
            } catch (Throwable t) {
                Log.w(TAG, "BubbleRequestReceiver install failed: " + t.getMessage());
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SHOW_BUBBLE.equals(intent.getAction())) return;
        if (!isTrusted()) {
            Log.w(TAG, "SHOW_BUBBLE rejected: caller not trusted");
            return;
        }
        String pkg = intent.getStringExtra(EXTRA_PACKAGE);
        if (pkg == null || pkg.isEmpty()) {
            Log.w(TAG, "SHOW_BUBBLE rejected: missing " + EXTRA_PACKAGE);
            return;
        }
        int userId = 0;
        try {
            int extra = intent.getIntExtra("userId", -1);
            if (extra >= 0) userId = extra;
        } catch (Throwable ignored) { }
        Log.i(TAG, "SHOW_BUBBLE requested pkg=" + pkg + " userId=" + userId);
        MoreBubbleHookModule.requestAppBubble(systemUiContext, pkg, userId);
    }

    /**
     * 调用方校验：持有 {@link #PERMISSION}，或来自 shell / root。
     *
     * <p>用 Binder 上的 callingUid 而不是 intent 里的字段——后者可被伪造。
     * 动态接收器在 onReceive 执行时 Binder 调用身份仍在，可以拿到。
     */
    private static boolean isTrusted() {
        try {
            int uid = android.os.Binder.getCallingUid();
            if (uid == UID_ROOT || uid == UID_SHELL) return true;
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Method current = at.getMethod("currentActivityThread");
                Object thread = current.invoke(null);
                Method getPm = at.getMethod("getPackageManager");
                Object pm = getPm.invoke(thread);
                Method check = PackageManager.class.getMethod(
                        "getPackagesForUid", int.class);
                String[] pkgs = (String[]) check.invoke(pm, uid);
                if (pkgs == null) return false;
                Method checkPerm = PackageManager.class.getMethod(
                        "checkPermission", String.class, String.class);
                for (String p : pkgs) {
                    int r = (int) checkPerm.invoke(pm, PERMISSION, p);
                    if (r == PackageManager.PERMISSION_GRANTED) return true;
                }
            } catch (Throwable ignored) { }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
