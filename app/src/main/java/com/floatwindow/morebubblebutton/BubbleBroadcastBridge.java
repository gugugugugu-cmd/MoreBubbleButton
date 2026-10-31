package com.floatwindow.morebubblebutton;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * 外部触发桥：让手势应用（如 GestureEVO）在任意界面把指定应用送进气泡小窗。
 *
 * <p>模块原本只有「点通知气泡按钮」「最近任务气泡按钮」两个入口，都发生在
 * SystemUI / 桌面自己的界面里。这里在 SystemUI 进程内动态注册一个接收器，
 * 收到广播后直接复用模块已经验证过的 A17 路径
 * （BubbleController.expandStackAndSelectAppBubble），不需要 NotificationEntry，
 * 也不依赖通知栏是否展开。
 *
 * <p>广播是显式的（调用方必须 setPackage("com.android.systemui")），并且这里还会
 * 校验发送方包名，避免任意应用都能把窗口拉成气泡。
 */
public final class BubbleBroadcastBridge {
    private static final String TAG = "MoreBubbleBridge";

    /** 手势侧发送的动作名。 */
    public static final String ACTION_SHOW_APP_BUBBLE =
            "com.floatwindow.morebubblebutton.action.SHOW_APP_BUBBLE";
    public static final String EXTRA_PACKAGE = "packageName";
    public static final String EXTRA_ACTIVITY = "activityName";
    public static final String EXTRA_USER_ID = "userId";

    /** 允许触发的外部调用方包名。 */
    private static final String[] ALLOWED_SENDERS = {
            "io.github.lumiagg.gestureevo",
            "io.github.lumiagg.gestureevo.debug",
            "com.floatwindow.morebubblebutton",
    };

    private static volatile boolean sRegistered;
    private static volatile Object sManager;

    private BubbleBroadcastBridge() {}

    /**
     * 由 {@link MoreBubbleHookModule} 在 BubblesManager 构造完成后调用。
     * 幂等：接收器只注册一次，但每次都更新 manager 引用（旧实例可能已失效）。
     *
     * @param ctx            SystemUI 的 Context（BubblesManager 构造参数）
     * @param bubblesManager 刚构造出的 BubblesManager 实例
     */
    static void attach(Context ctx, Object bubblesManager) {
        if (bubblesManager == null) return;
        sManager = bubblesManager;
        if (sRegistered || ctx == null) return;
        try {
            IntentFilter filter = new IntentFilter(ACTION_SHOW_APP_BUBBLE);
            // SystemUI 的 targetSdk 很高，动态注册必须显式声明导出，
            // 否则来自外部应用的广播会被系统直接丢弃。
            ctx.registerReceiver(new Receiver(), filter, Context.RECEIVER_EXPORTED);
            sRegistered = true;
            Log.i(TAG, "gesture bridge receiver registered");
        } catch (Throwable t) {
            Log.e(TAG, "register bridge failed: " + t.getMessage());
        }
    }

    private static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_SHOW_APP_BUBBLE.equals(intent.getAction())) return;

            String sender = null;
            try {
                sender = getSentFromPackage();
            } catch (Throwable ignored) {
            }
            if (!isAllowedSender(sender)) {
                Log.w(TAG, "rejected bubble request from " + sender);
                return;
            }
            if (!ModuleSettings.isExternalTriggerEnabled(context)) {
                Log.w(TAG, "external trigger disabled in settings");
                return;
            }

            String pkg = intent.getStringExtra(EXTRA_PACKAGE);
            if (pkg == null || pkg.isEmpty()) {
                Log.w(TAG, "bubble request without package");
                return;
            }
            String activity = intent.getStringExtra(EXTRA_ACTIVITY);
            int userId = intent.getIntExtra(EXTRA_USER_ID, 0);
            if (userId < 0) userId = 0;

            Object manager = sManager;
            if (manager == null) {
                Log.w(TAG, "no BubblesManager instance, cannot show bubble");
                return;
            }
            try {
                boolean ok = MoreBubbleHookModule.showAppBubbleFromGesture(
                        manager, context, pkg, activity, userId);
                Log.i(TAG, "bubble request " + pkg + " -> " + ok);
            } catch (Throwable t) {
                Log.e(TAG, "bubble request failed: " + t.getMessage());
            }
        }
    }

    private static boolean isAllowedSender(String sender) {
        // 拿不到发送方时放行：显式广播本身已经限制了目标进程。
        if (sender == null) return true;
        for (String allowed : ALLOWED_SENDERS) {
            if (allowed.equals(sender)) return true;
        }
        return false;
    }
}
