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

    /** 允许免权限触发的调用方包名（GestureEVO 正式版 / debug 版）。 */
    private static final java.util.Set<String> ALLOWED_SENDERS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "io.github.lumiagg.gestureevo",
                    "io.github.lumiagg.gestureevo.debug",
                    "com.floatwindow.morebubblebutton"));

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
     * 调用方校验：持有 {@link #PERMISSION}，或来自 shell / root，或白名单包名。
     *
     * <p>注意：动态注册的广播接收器在 onReceive 里 {@code Binder.getCallingUid()} 拿到的是
     * 广播投递线程的身份（通常是自己），**不是**发送方 —— 广播是异步投递的，Binder 调用身份
     * 早已结束。因此用 {@link BroadcastReceiver#getSentFromUid()} /
     * {@link BroadcastReceiver#getSentFromPackage()}，这两个 API 在 onReceive 里可用，
     * 且由系统填写、不可被发送方伪造。
     */
    private boolean isTrusted() {
        try {
            int uid = getSentFromUid();
            if (uid == UID_ROOT || uid == UID_SHELL) return true;
            String sender = getSentFromPackage();
            if (sender != null && ALLOWED_SENDERS.contains(sender)) return true;
            if (uid < 0) return false;
            PackageManager pm = systemUiContext != null
                    ? systemUiContext.getPackageManager() : null;
            if (pm == null) return false;
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs == null) return false;
            for (String p : pkgs) {
                if (pm.checkPermission(PERMISSION, p) == PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
