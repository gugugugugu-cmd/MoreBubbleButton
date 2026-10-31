package com.floatwindow.morebubblebutton;

import android.app.Notification;
import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.net.Uri;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class MoreBubbleHookModule extends XposedModule {
    private static final String TAG = "MoreBubbleModule";
    private Object recentsViewInstance;
    private View bubbleButton;
    private ClassLoader mLauncherClassLoader;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // 只用来确认模块真的进了进程：logcat -s MoreBubbleModule 之前看不到任何一行，
        // 说明 SystemUI 里要么没加载模块，要么热重载被拒（LSPosed 日志：refused to be hot reloaded）。
        Log.i(TAG, "module loaded: process=" + param.getProcessName()
                + " first=" + param.isFirstPackage() + " api=" + getApiVersion());
    }


    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if ("com.google.android.apps.nexuslauncher".equals(pkg)
                || "com.android.launcher3".equals(pkg)) {
            mLauncherClassLoader = param.getDefaultClassLoader();
            hookLauncher(param);
        } else if ("com.android.systemui".equals(pkg)) {
            // SystemUI 不支持热重载，只在首次加载时 hook
            if (!param.isFirstPackage()) return;
            ClassLoader cl = param.getDefaultClassLoader();
            hookSystemUi(cl);
        }
    }

    private ClassLoader mSystemUiClassLoader;
    private static final Map<String, Long> sForcedBubbleKeys = new ConcurrentHashMap<>();
    private static final long FORCED_BUBBLE_GRACE_MS = 8000L;

    // The right corner action uses the official Bubble -> fullscreen handoff.
    // No Bubble -> fullscreen transition is submitted, so SystemUI never rebuilds
    // the edge bubble and its adjacent plus as a side effect.

    private void hookSystemUi(ClassLoader cl) {
        Log.i(TAG, "Hooking SystemUI...");
        // 1. shouldShowBubbleButton: 让所有非前台通知显示气泡按钮
        try {
            Class<?> clazz = cl.loadClass(
                    "com.android.systemui.statusbar.notification.row.NotificationContentView");
            hook(clazz.getMethod("shouldShowBubbleButton")).intercept(chain -> {
                try {
                    Context ctx = null;
                    try { ctx = ((View) chain.getThisObject()).getContext(); } catch (Throwable ignored) {}
                    if (ctx != null && !ModuleSettings.isSystemUiBubbleEnabled(ctx)) return chain.proceed();
                } catch (Throwable ignored) {}
                boolean original = (boolean) chain.proceed();
                if (original) return true;
                try {
                    Object contentView = chain.getThisObject();
                    Object row = getFieldSystemUi(contentView, "mContainingNotification");
                    if (row == null) return true;
                    Object adapter = getFieldSystemUi(row, "mEntryAdapter");
                    if (adapter == null) return true;
                    Object sbn = invokeSystemUi(adapter, "getSbn");
                    if (sbn == null) return true;
                    Notification notif = (Notification) invokeSystemUi(sbn, "getNotification");
                    if (notif == null) return true;
                    if ((notif.flags & 0x40) != 0) return false;
                    String pkg = (String) sbn.getClass().getMethod("getPackageName").invoke(sbn);
                    Context viewCtx = ((View) contentView).getContext();
                    if (pkg == null || viewCtx.getPackageManager().getLaunchIntentForPackage(pkg) == null) return false;
                    return true;
                } catch (Throwable t) { return true; }
            });
            Log.i(TAG, "Hooked shouldShowBubbleButton OK");
        } catch (Throwable t) { Log.e(TAG, "Hook shouldShowBubbleButton: " + t.getMessage()); }

        // 2. injectBubbleMetadata at bind time
        try {
            Class<?> binderClass = cl.loadClass(
                    "com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl");
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : binderClass.getDeclaredMethods()) {
                if (m.getParameterCount() >= 3 && m.getParameterTypes()[2].getName().contains("ExpandableNotificationRow")) {
                    target = m;
                    break;
                }
            }
            if (target != null) {
                hook(target).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object row = chain.getArg(2);
                        if (row == null) return result;
                        Object entry = getFieldSystemUi(row, "mEntry");
                        if (entry == null) return result;
                        Object sbn = getFieldSystemUi(entry, "mSbn");
                        if (sbn == null) return result;
                        Notification notif = (Notification) invokeSystemUi(sbn, "getNotification");
                        if (notif == null) return result;
                        if ((notif.flags & 0x40) != 0) return result;
                        if (notif.contentIntent == null) return result;
                        injectBubbleMetadata(entry, notif);
                    } catch (Throwable t) { Log.w(TAG, "bindRow meta inject: " + t.getMessage()); }
                    return result;
                });
                Log.i(TAG, "Hooked NotificationRowBinderImpl OK");
            }
        } catch (Throwable t) { Log.e(TAG, "Hook RowBinder: " + t.getMessage()); }

        // 3. BubblesManager.expandStackAndSelectBubble - 拦截系统点击调用
        try {
            Class<?> bubblesCls = cl.loadClass("com.android.systemui.wmshell.BubblesManager");
            java.lang.reflect.Method expand = null;
            for (java.lang.reflect.Method m : bubblesCls.getDeclaredMethods()) {
                if (m.getName().equals("expandStackAndSelectBubble")
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].getName().contains("NotificationEntry")) {
                    expand = m;
                    break;
                }
            }
            if (expand != null) {
                hook(expand).intercept(chain -> {
                    try {
                        Object entry = chain.getArg(0);
                        if (entry != null) {
                            Object sbn = getFieldSystemUi(entry, "mSbn");
                            Notification notif = sbn != null ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
                            if (notif != null && (notif.flags & 0x40) == 0 && notif.contentIntent != null) {
                                expandAppBubbleFromNotification(chain.getThisObject(), entry, "expand guard");
                                return null;
                            }
                        }
                    } catch (Throwable t) { Log.w(TAG, "expand guard: " + t.getMessage()); }
                    return chain.proceed();
                });
                Log.i(TAG, "Hooked BubblesManager.expandStackAndSelectBubble OK");
            } else {
                Log.w(TAG, "BubblesManager.expandStackAndSelectBubble(NotificationEntry) not found");
            }
        } catch (Throwable t) { Log.w(TAG, "Hook BubblesManager: " + t.getMessage()); }

        // 4. BubblesManager.onUserChangedBubble - 非 bubble 通知首次点击走这里，原生只折叠 shade。
        try {
            Class<?> bubblesCls = cl.loadClass("com.android.systemui.wmshell.BubblesManager");
            java.lang.reflect.Method onUserChanged = null;
            for (java.lang.reflect.Method m : bubblesCls.getDeclaredMethods()) {
                if (m.getName().equals("onUserChangedBubble")
                        && m.getParameterCount() == 2
                        && m.getParameterTypes()[0].getName().contains("NotificationEntry")
                        && m.getParameterTypes()[1] == boolean.class) {
                    onUserChanged = m;
                    break;
                }
            }
            if (onUserChanged != null) {
                hook(onUserChanged).intercept(chain -> {
                    // 必须先放行原方法：它负责把通知标记为可气泡（Notification.flags |= FLAG_BUBBLE）、
                    // channel.setAllowBubbles(true)、必要时 setBubblesAllowed()，并折叠 shade。
                    // 这些是气泡能被系统接受、不会立刻被清掉的前置条件，跳过会导致小窗一闪即灭。
                    Object result = chain.proceed();
                    try {
                        boolean enabled = (boolean) chain.getArg(1);
                        Object entry = chain.getArg(0);
                        if (enabled && entry != null) {
                            expandAppBubbleFromNotification(chain.getThisObject(), entry, "user change");
                        }
                    } catch (Throwable t) { Log.w(TAG, "user change bubble: " + t.getMessage()); }
                    return result;
                });
                Log.i(TAG, "Hooked BubblesManager.onUserChangedBubble OK");
            } else {
                Log.w(TAG, "BubblesManager.onUserChangedBubble(NotificationEntry, boolean) not found");
            }
        } catch (Throwable t) { Log.w(TAG, "Hook onUserChangedBubble: " + t.getMessage()); }

        // 5. dismissBubbleWithKey guard - 防止刚强制创建的气泡被 Ranking/Channel 立刻移除。
        try {
            Class<?> dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData");
            for (java.lang.reflect.Method dm : dataCls.getDeclaredMethods()) {
                if (dm.getName().equals("dismissBubbleWithKey")
                        && dm.getParameterCount() >= 2
                        && dm.getParameterTypes()[0] == int.class
                        && dm.getParameterTypes()[dm.getParameterCount() - 1] == String.class) {
                    final int keyArgIndex = dm.getParameterCount() - 1;
                    hook(dm).intercept(chain -> {
                        try {
                            int reason = (int) chain.getArg(0);
                            String key = (String) chain.getArg(keyArgIndex);
                            if ((reason == 4 || reason == 7 || reason == 14) && isRecentlyForcedBubble(key)) {
                                Log.i(TAG, "keep forced bubble: skip dismiss reason=" + reason + " key=" + key);
                                return null;
                            }
                        } catch (Throwable t) { Log.w(TAG, "dismiss guard: " + t.getMessage()); }
                        return chain.proceed();
                    });
                }
            }
            Log.i(TAG, "Hooked BubbleData.dismissBubbleWithKey OK");
        } catch (Throwable t) { Log.w(TAG, "Hook dismiss guard: " + t.getMessage()); }

        // 6. setSelectedBubbleInternal guard
        try {
            Class<?> dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData");
            java.lang.reflect.Method m = findMethodSystemUi(dataCls, "setSelectedBubbleInternal");
            if (m != null) {
                hook(m).intercept(chain -> {
                    try {
                        Object provider = chain.getArg(0);
                        Object bubbleData = chain.getThisObject();
                        if (provider != null && provider.getClass().getName().endsWith("BubbleEntry")) {
                            java.lang.reflect.Field f = findFieldSystemUi(bubbleData.getClass(), "mBubbles");
                            if (f != null) {
                                f.setAccessible(true);
                                java.util.List list = (java.util.List) f.get(bubbleData);
                                if (list != null && !list.contains(provider)) {
                                    list.add(provider);
                                    Log.i(TAG, "BubbleData.mBubbles forcibly added BubbleEntry");
                                }
                            }
                        }
                    } catch (Throwable t) { Log.w(TAG, "select guard: " + t.getMessage()); }
                    return chain.proceed();
                });
                Log.i(TAG, "Hooked setSelectedBubbleInternal OK");
            }
        } catch (Throwable t) { Log.w(TAG, "Hook select guard: " + t.getMessage()); }

        // 7. 改造气泡小窗底部「管理」按钮 (Android 17 BubbleExpandedView)：
        //    不再屏蔽管理按钮，而是把它改造为两种动作按钮——
        //    左下角官方 mManageButton 本体点击 = 「关闭小窗」(收起气泡回到贴边列表，不删除)；
        //    在其右侧克隆一颗同款按钮，点击 = 「全屏展开」(moveTaskViewToFullscreen 反射链)。
        //    官方 updateManageButtonIfExists 每次调用都会 inflate -> addView -> setVisibility
        //    -> setOnClickListener -> post，因此 proceed 之后 mManageButton 已在 this 的 child 列表里，
        //    可直接读取并重设；两颗按钮放进同一个 SyncCornerLayout，同一级、同一动画。
        try {
            Class<?> bevCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleExpandedView");
            hook(bevCls.getMethod("updateManageButtonIfExists")).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    java.lang.reflect.Field f = bevCls.getDeclaredField("mManageButton");
                    f.setAccessible(true);
                    Object btn = f.get(chain.getThisObject());
                    if (btn instanceof View) {
                        // 两颗按钮同父同层（见 SyncCornerLayout），官方对「管理」按钮行的位移
                        // （上滑 / 展开动画）会同时带上右侧按钮。
                        applyCornerButtonActions(chain.getThisObject(), (View) btn);
                    } else {
                        Log.i(TAG, "corner action: mManageButton not a View; skipped");
                    }
                } catch (Throwable t) {
                    Log.i(TAG, "corner action install: " + t.getMessage(), t);
                }
                return result;
            });
            Log.i(TAG, "Hooked BubbleExpandedView.updateManageButtonIfExists (corner buttons) OK");
        } catch (Throwable t) { Log.e(TAG, "Hook manage button: " + t.getMessage()); }

        // 外部调用入口：BubblesManager 一创建就把广播接收器挂上去。
        // 只有拿到 manager 实例才能调用 BubbleController.expandStackAndSelectAppBubble；
        // 注意 hook 构造函数时 proceed() 返回 null，新实例要从 chain.getThisObject() 取。
        try {
            Class<?> bubblesCls = cl.loadClass("com.android.systemui.wmshell.BubblesManager");
            for (java.lang.reflect.Constructor<?> ctor : bubblesCls.getDeclaredConstructors()) {
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        java.util.List<Object> args = chain.getArgs();
                        Object arg0 = args != null && args.size() > 0 ? args.get(0) : null;
                        Context appCtx = arg0 instanceof Context ? (Context) arg0 : null;
                        BubbleBroadcastBridge.attach(appCtx, self);
                    } catch (Throwable t) { Log.w(TAG, "attach bridge: " + t.getMessage()); }
                    return result;
                });
            }
            Log.i(TAG, "Hooked BubblesManager ctor for gesture bridge");
        } catch (Throwable t) { Log.w(TAG, "Hook BubblesManager ctor: " + t.getMessage()); }

        Log.i(TAG, "All SystemUI hooks installed");
    }

    /**
     * 手势触发的入口：没有 NotificationEntry，直接按包名/组件建一个应用气泡。
     *
     * <p>复用 {@link #expandAppBubbleA17} 里已经验证过的官方配方
     * （Bubble + expandStackAndSelectAppBubble），并沿用模块对
     * 「刚强制创建的气泡别被 Ranking/Channel 立刻移除」的保护。
     */
    static boolean showAppBubbleFromGesture(Object bubblesManager, Context ctx,
            String pkg, String activityName, int userId) {
        try {
            Object bubblesImpl = getFieldSystemUi(bubblesManager, "mBubbles");
            Object controller = bubblesImpl != null ? getFieldSystemUi(bubblesImpl, "this$0") : null;
            if (controller == null) {
                Log.w(TAG, "gesture bubble: BubbleController not found");
                return false;
            }
            Intent launchIntent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            Intent intent = new Intent(launchIntent != null ? launchIntent : new Intent());
            intent.setPackage(pkg);
            if (activityName != null && !activityName.isEmpty()) {
                String cls = activityName.startsWith(".") ? pkg + activityName : activityName;
                intent.setComponent(new ComponentName(pkg, cls));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            UserHandle user = (UserHandle) UserHandle.class.getMethod("of", int.class)
                    .invoke(null, userId);

            // 记下这个 key，让 dismissBubbleWithKey 的保护窗口生效
            try {
                Class<?> bubbleCls = controller.getClass().getClassLoader()
                        .loadClass("com.android.wm.shell.bubbles.Bubble");
                Method keyForApp = findMethodSystemUi(bubbleCls, "getAppBubbleKeyForApp",
                        String.class, UserHandle.class);
                if (keyForApp != null) {
                    String key = (String) keyForApp.invoke(null, pkg, user);
                    rememberForcedBubble(key);
                }
            } catch (Throwable ignored) {}

            Runnable work = () -> {
                boolean done = expandAppBubbleA17(controller, intent, user, "gesture");
                if (!done) done = expandAppBubbleLegacy(controller, intent, user, "gesture");
                Log.i(TAG, "gesture bubble " + pkg + ": " + (done ? "expanded" : "failed"));
            };
            Object executor = getFieldSystemUi(controller, "mMainExecutor");
            Method execute = executor != null
                    ? findMethodSystemUi(executor.getClass(), "execute", Runnable.class) : null;
            if (execute != null) execute.invoke(executor, work); else work.run();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "gesture bubble failed: " + t.getMessage());
            return false;
        }
    }


    private static boolean expandAppBubbleFromNotification(Object bubblesManager, Object entry, String reason) {
        try {
            Object sbn = getFieldSystemUi(entry, "mSbn");
            if (sbn == null) return false;
            String pkg = (String) sbn.getClass().getMethod("getPackageName").invoke(sbn);
            int userId = 0;
            try { userId = (int) sbn.getClass().getMethod("getUserId").invoke(sbn); } catch (Throwable ignored) {}
            if (userId < 0) userId = 0;
            UserHandle user = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, userId);
            Object bubblesImpl = getFieldSystemUi(bubblesManager, "mBubbles");
            Object controller = getFieldSystemUi(bubblesImpl, "this$0");
            if (controller == null || pkg == null) return false;
            Notification notif = (Notification) invokeSystemUi(sbn, "getNotification");
            Context ctx = (Context) getFieldSystemUi(controller, "mContext");
            Intent targetIntent = getNotificationTargetIntent(notif, pkg);
            Intent launchIntent = ctx != null ? ctx.getPackageManager().getLaunchIntentForPackage(pkg) : null;
            if (targetIntent == null) targetIntent = launchIntent;
            if (targetIntent == null || launchIntent == null) {
                Log.w(TAG, reason + ": skip app bubble, no target intent for " + pkg);
                collapseShadeFromManager(bubblesManager);
                return true;
            }
            if (targetIntent.getPackage() == null) {
                targetIntent.setPackage(pkg);
            }
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            final Intent finalIntent = targetIntent;
            final UserHandle finalUser = user;
            Runnable work = () -> {
                try {
                    boolean done = expandAppBubbleA17(controller, finalIntent, finalUser, reason);
                    if (!done) done = expandAppBubbleLegacy(controller, finalIntent, finalUser, reason);
                    if (done) {
                        Log.i(TAG, reason + ": expanded app bubble for " + pkg);
                        runOnSysuiMain(bubblesManager, () -> dismissClickedNotificationIfAutoCancel(bubblesManager, entry));
                        runOnSysuiMain(bubblesManager, () -> collapseShadeFromManager(bubblesManager));
                    } else {
                        Log.w(TAG, reason + ": app bubble expand method not found");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, reason + ": app bubble expand failed: " + t.getMessage());
                }
            };
            Object executor = getFieldSystemUi(controller, "mMainExecutor");
            Method execute = executor != null ? findMethodSystemUi(executor.getClass(), "execute", Runnable.class) : null;
            if (execute != null) execute.invoke(executor, work); else work.run();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": app bubble schedule failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Android 17 官方配方（与 BubbleController$IBubblesImpl$$ExternalSyntheticLambda13 / IBubbles.showAppBubble 一致）：
     *   key    = Bubble.getAppBubbleKeyForApp(intent.getPackage(), user)
     *   bubble = data.findAndRemoveBubbleFromOverflow(key)
     *   if (bubble == null) {
     *       pm   = BubbleController.getPackageManagerForUser(user.getIdentifier(), data.mContext)
     *       icon = data.mAppInfoProvider.getActivityInfoIcon(pm, intent)
     *       bubble = new Bubble(intent, user, icon, Bubble$BubbleType.TYPE_APP, key)
     *   }
     *   controller.expandStackAndSelectAppBubble(bubble, EntryPoint.NOTIFICATION_BUBBLE_BUTTON, null)
     *
     * Android 17 已删除旧的
     * expandStackAndSelectBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation)，这是通知横幅气泡
     * 点击后打不开小窗的根因。
     */
    private static boolean expandAppBubbleA17(Object controller, Intent intent, UserHandle user, String reason) {
        try {
            ClassLoader cl = controller.getClass().getClassLoader();
            Class<?> bubbleCls = cl.loadClass("com.android.wm.shell.bubbles.Bubble");
            Class<?> dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData");
            Class<?> typeCls = cl.loadClass("com.android.wm.shell.bubbles.Bubble$BubbleType");
            Class<?> epCls = cl.loadClass("com.android.wm.shell.shared.bubbles.logging.EntryPoint");
            Class<?> reqCls = cl.loadClass("com.android.wm.shell.shared.bubbles.BubbleBarLocation$UpdateLocationRequest");

            Method expand = findMethodSystemUi(controller.getClass(), "expandStackAndSelectAppBubble",
                    bubbleCls, epCls, reqCls);
            if (expand == null) return false;

            Object data = getFieldSystemUi(controller, "mBubbleData");
            if (data == null) return false;

            String pkg = intent.getPackage();
            if (pkg == null) return false;

            Method keyForApp = findMethodSystemUi(bubbleCls, "getAppBubbleKeyForApp", String.class, UserHandle.class);
            if (keyForApp == null) return false;
            String key = (String) keyForApp.invoke(null, pkg, user);

            Method findOverflow = findMethodSystemUi(dataCls, "findAndRemoveBubbleFromOverflow", String.class);
            Object bubble = findOverflow != null ? findOverflow.invoke(data, key) : null;

            if (bubble == null) {
                Object typeApp = enumConstant(typeCls, "TYPE_APP");
                if (typeApp == null) return false;
                Context dataCtx = (Context) getFieldSystemUi(data, "mContext");
                if (dataCtx == null) return false;
                Object pm;
                Method getPm = findMethodSystemUi(controller.getClass(),
                        "getPackageManagerForUser", int.class, Context.class);
                pm = getPm != null ? getPm.invoke(null, userIdOf(user), dataCtx)
                        : dataCtx.getPackageManager();
                Object icon = null;
                Object appInfoProvider = getFieldSystemUi(data, "mAppInfoProvider");
                if (appInfoProvider != null) {
                    Method getIcon = findMethodSystemUi(appInfoProvider.getClass(), "getActivityInfoIcon",
                            android.content.pm.PackageManager.class, Intent.class);
                    if (getIcon != null) {
                        try { icon = getIcon.invoke(appInfoProvider, pm, intent); } catch (Throwable ignored) {}
                    }
                }
                java.lang.reflect.Constructor<?> ctor = bubbleCls.getConstructor(
                        Intent.class, UserHandle.class, android.graphics.drawable.Icon.class, typeCls, String.class);
                bubble = ctor.newInstance(intent, user, icon, typeApp, key);
            }
            if (bubble == null) return false;

            Object entryPoint = enumConstant(epCls, "NOTIFICATION_BUBBLE_BUTTON");
            if (entryPoint == null) entryPoint = findEntryPoint(cl, "NOTIFICATION");
            if (entryPoint == null) return false;

            expand.invoke(controller, bubble, entryPoint, null);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": A17 expand failed: " + t.getMessage());
            return false;
        }
    }

    /** Android 16 及以前的旧 API，保留作为回退。 */
    private static boolean expandAppBubbleLegacy(Object controller, Intent intent, UserHandle user, String reason) {
        try {
            ClassLoader cl = controller.getClass().getClassLoader();
            Object entryPoint = findEntryPoint(cl, "NOTIFICATION");
            Class<?> locCls = clOrNull(cl, "com.android.wm.shell.shared.bubbles.BubbleBarLocation");
            Method expand = findMethodSystemUi(controller.getClass(), "expandStackAndSelectBubble",
                    Intent.class, UserHandle.class,
                    entryPoint != null ? entryPoint.getClass() : Object.class, locCls);
            if (expand == null) {
                for (Method m : controller.getClass().getDeclaredMethods()) {
                    if (m.getName().equals("expandStackAndSelectBubble")
                            && m.getParameterCount() == 4
                            && m.getParameterTypes()[0] == Intent.class) {
                        m.setAccessible(true);
                        expand = m;
                        break;
                    }
                }
            }
            if (expand == null) return false;
            expand.invoke(controller, intent, user, entryPoint, null);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": legacy expand failed: " + t.getMessage());
            return false;
        }
    }

    private static Object enumConstant(Class<?> enumCls, String name) {
        try {
            for (Object o : (Object[]) enumCls.getMethod("values").invoke(null)) {
                if (name.equals(o.toString())) return o;
            }
        } catch (Throwable t) { Log.w(TAG, "enumConstant: " + t.getMessage()); }
        return null;
    }

    private static int userIdOf(UserHandle user) {
        try {
            Object id = invokeSystemUi(user, "getIdentifier");
            if (id instanceof Integer) return (Integer) id;
        } catch (Throwable ignored) {}
        try {
            Method m = UserHandle.class.getMethod("getUserId", UserHandle.class);
            return (Integer) m.invoke(null, user);
        } catch (Throwable ignored) {}
        return 0;
    }

    private static Intent getNotificationTargetIntent(Notification notif, String pkg) {
        try {
            PendingIntent pi = notif != null ? notif.contentIntent : null;
            Intent intent = null;
            if (pi != null) {
                Method getIntent = findMethodSystemUi(pi.getClass(), "getIntent");
                if (getIntent != null) intent = (Intent) getIntent.invoke(pi);
            }
            if (intent == null) return null;
            Intent copy = new Intent(intent);
            copy.putExtra("IS_FROM_NOTIFICATION", true);
            if (pkg != null && copy.getPackage() == null) {
                copy.setPackage(pkg);
            }
            return copy;
        } catch (Throwable t) {
            Log.w(TAG, "notification target intent: " + t.getMessage());
            return null;
        }
    }

    private static void dismissClickedNotificationIfAutoCancel(Object bubblesManager, Object entry) {
        try {
            Object sbn = getFieldSystemUi(entry, "mSbn");
            Notification notif = sbn != null ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
            if (notif == null || (notif.flags & Notification.FLAG_AUTO_CANCEL) == 0) return;
            Object visibilityProvider = getFieldSystemUi(bubblesManager, "mVisibilityProvider");
            Object visibility = null;
            if (visibilityProvider != null) {
                Method obtain = findCompatibleMethodByName(visibilityProvider.getClass(), "obtain", entry.getClass());
                if (obtain == null) obtain = findCompatibleMethodByName(visibilityProvider.getClass(), "obtain", String.class);
                if (obtain != null) {
                    Class<?> argType = obtain.getParameterTypes()[0];
                    Object arg = argType == String.class ? getFieldSystemUi(entry, "key") : entry;
                    visibility = obtain.invoke(visibilityProvider, arg);
                }
            }
            ClassLoader cl = bubblesManager.getClass().getClassLoader();
            Class<?> statsCls = cl.loadClass("com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats");
            Object stats = null;
            for (java.lang.reflect.Constructor<?> c : statsCls.getDeclaredConstructors()) {
                if (c.getParameterCount() == 2) {
                    c.setAccessible(true);
                    stats = c.newInstance(1, visibility);
                    break;
                }
            }
            if (stats == null) return;
            Object callbacks = getFieldSystemUi(bubblesManager, "mCallbacks");
            if (callbacks instanceof java.util.List) {
                for (Object cb : (java.util.List<?>) callbacks) {
                    Method remove = findMethodByNameAndCount(cb.getClass(), "removeNotification", 2);
                    if (remove != null) remove.invoke(cb, entry, stats);
                }
                Log.i(TAG, "dismissed clicked auto-cancel notification");
            }
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            Log.w(TAG, "dismiss clicked notification: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static void runOnSysuiMain(Object bubblesManager, Runnable runnable) {
        try {
            Object executor = getFieldSystemUi(bubblesManager, "mSysuiMainExecutor");
            Method execute = executor != null ? findMethodSystemUi(executor.getClass(), "execute", Runnable.class) : null;
            if (execute != null) {
                execute.invoke(executor, runnable);
            } else {
                new android.os.Handler(Looper.getMainLooper()).post(runnable);
            }
        } catch (Throwable t) {
            try { runnable.run(); } catch (Throwable ignored) {}
        }
    }

    private static void collapseShadeFromManager(Object bubblesManager) {
        try {
            Object shadeController = getFieldSystemUi(bubblesManager, "mShadeController");
            if (shadeController == null) return;
            Method postForce = findMethodSystemUi(shadeController.getClass(), "postAnimateForceCollapseShade");
            if (postForce != null) { postForce.invoke(shadeController); return; }
            Method instant = findMethodSystemUi(shadeController.getClass(), "instantCollapseShade");
            if (instant != null) { instant.invoke(shadeController); return; }
            Method full = findMethodSystemUi(shadeController.getClass(), "animateCollapseShade", int.class, boolean.class, boolean.class);
            if (full != null) { full.invoke(shadeController, 2, true, true); return; }
            Method normal = findMethodSystemUi(shadeController.getClass(), "animateCollapseShade", int.class);
            if (normal != null) normal.invoke(shadeController, 0);
        } catch (Throwable t) {
            Log.w(TAG, "collapseShade: " + t.getMessage());
        }
    }

    private static Method findMethodByNameAndCount(Class<?> c, String n, int count) {
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(n) && m.getParameterCount() == count) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Method findCompatibleMethodByName(Class<?> c, String n, Class<?> argType) {
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(n) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(argType)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Class<?> clOrNull(ClassLoader cl, String name) {
        try { return cl.loadClass(name); } catch (Throwable t) { return null; }
    }

    private static Object findEntryPoint(ClassLoader cl, String name) {
        try {
            Class<?> epCls = cl.loadClass("com.android.wm.shell.shared.bubbles.logging.EntryPoint");
            for (Object e : (Object[]) epCls.getDeclaredField("$VALUES").get(null)) {
                if (name.equals(e.toString())) return e;
            }
        } catch (Throwable t) { Log.w(TAG, "findEntryPoint: " + t.getMessage()); }
        return null;
    }

    private static void rememberForcedBubble(String key) {
        if (key != null) sForcedBubbleKeys.put(key, System.currentTimeMillis());
    }

    private static boolean isRecentlyForcedBubble(String key) {
        if (key == null) return false;
        Long ts = sForcedBubbleKeys.get(key);
        if (ts == null) return false;
        long age = System.currentTimeMillis() - ts;
        if (age > FORCED_BUBBLE_GRACE_MS) {
            sForcedBubbleKeys.remove(key);
            return false;
        }
        return true;
    }

    private static void injectBubbleMetadata(Object entry, Notification notif) {
        try {
            java.lang.reflect.Field metaField = findFieldSystemUi(entry.getClass(), "mBubbleMetadata");
            if (metaField == null) return;
            metaField.setAccessible(true);
            Object existing = metaField.get(entry);
            if (existing instanceof Notification.BubbleMetadata) {
                setNotificationBubbleMetadata(notif, (Notification.BubbleMetadata) existing);
                return;
            }
            Notification.BubbleMetadata.Builder builder = new Notification.BubbleMetadata.Builder();
            builder.setIntent(notif.contentIntent);
            builder.setDeleteIntent(notif.deleteIntent);
            int iconResId = 0;
            try {
                java.lang.reflect.Method getSmall = notif.getClass().getMethod("getSmallIcon");
                Object smallIcon = getSmall.invoke(notif);
                if (smallIcon != null) {
                    java.lang.reflect.Method getRes = smallIcon.getClass().getMethod("getResId");
                    iconResId = (int) getRes.invoke(smallIcon);
                }
            } catch (Throwable ignore) {}
            String pkg = null;
            try {
                java.lang.reflect.Method m = notif.getClass().getMethod("getPackageName");
                pkg = (String) m.invoke(notif);
            } catch (Throwable ignore) {}
            try {
                if (iconResId != 0) {
                    builder.getClass().getMethod("setIcon", int.class).invoke(builder, iconResId);
                } else if (pkg != null) {
                    builder.getClass().getMethod("setShortcutId", String.class).invoke(builder, pkg);
                } else {
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "icon/shortcut set failed: " + t.getMessage());
                return;
            }
            Notification.BubbleMetadata metadata = builder.build();
            setNotificationBubbleMetadata(notif, metadata);
            metaField.set(entry, metadata);
            Log.i(TAG, "Set bubble metadata OK for " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "injectBubbleMetadata: " + t.getMessage());
        }
    }

    private static void setNotificationBubbleMetadata(Notification notif, Notification.BubbleMetadata metadata) {
        try {
            Method m = notif.getClass().getMethod("setBubbleMetadata", Notification.BubbleMetadata.class);
            m.invoke(notif, metadata);
            return;
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Field f = findFieldSystemUi(notif.getClass(), "mBubbleMetadata");
            if (f == null) f = findFieldSystemUi(notif.getClass(), "bubbleMetadata");
            if (f != null) {
                f.setAccessible(true);
                f.set(notif, metadata);
            }
        } catch (Throwable t) {
            Log.w(TAG, "setNotificationBubbleMetadata: " + t.getMessage());
        }
    }

    private static Object getField(Object obj, String name) {
        try { java.lang.reflect.Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
        catch (Throwable t) { return null; }
    }
    private static Object invoke(Object obj, String method) {
        try { java.lang.reflect.Method m = findMethodSystemUi(obj.getClass(), method); return m != null ? m.invoke(obj) : null; }
        catch (Throwable t) { return null; }
    }
    private static java.lang.reflect.Field findField(Class<?> c, String n) {
        while (c != null) { try { return c.getDeclaredField(n); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } return null;
    }
    private static java.lang.reflect.Method findMethod(Class<?> c, String n, Class<?>... p) {
        while (c != null) { try { java.lang.reflect.Method m = c.getDeclaredMethod(n, p); m.setAccessible(true); return m; } catch (NoSuchMethodException e) { c = c.getSuperclass(); } } return null;
    }

    // ===== 气泡小窗底部「关闭 / 全屏」双按钮改造 (Android 17 BubbleExpandedView) =====

    /** 标记我们克隆出来的右侧按钮，便于每次官方重建 mManageButton 时先清掉旧的克隆体。 */
    private static final int CORNER_ROW_TAG = 0x4d4f5245; // "MORE"

    /**
     * 改造气泡小窗底部按钮：官方 mManageButton 只改文案与点击 = 「关闭小窗」；再克隆一颗
     * 同款按钮 = 「展开小窗」。两颗按钮放进同一个 {@link SyncCornerLayout}，成为同一个父 View 的
     * 两个 child —— 官方对小窗做的位移（上滑手势、展开/收起动画）推的是 mManageButton 这一行，
     * 容器跟着走，右侧按钮因此与左侧按钮永远同一级、同一个平移，不会再固定在原位。
     *
     * <p>为什么不再直接 addView 到 BubbleExpandedView：那是垂直 LinearLayout，多一个 child 就多一行，
     * 自造按钮只能靠负 topMargin 硬叠、再 post 里补 translationY —— 布局尺寸一没定下来就错位，
     * 这就是之前对不齐、动画上脱节的根因。
     *
     * <p>官方 updateManageButtonIfExists 每轮 removeView 旧按钮 → 重新 inflate → addView 新按钮。
     * 因此容器每轮都先从父容器摘掉（不摘就会越堆越多），再 bind 新按钮；官方 addView 时把容器插回，
     * 容器不参与测量、不加高度、不加负 margin。
     */
    private static void applyCornerButtonActions(Object expandedView, View manageButton) {
        try {
            // 官方方法可能复用同一个 BubbleExpandedView 在应用气泡和 overflow 抽屉间切换，
            // 先清掉上一轮的容器。
            dumpCornerTree(expandedView, manageButton, "before");

            // overflow 抽屉没有可关闭/展开的任务。
            Object isOverflow = getField(expandedView, "mIsOverflow");
            Object bubble = getField(expandedView, "mBubble");
            Object bubbleKey = invoke(expandedView, "getBubbleKey");
            if (Boolean.TRUE.equals(isOverflow)
                    || "Overflow".equals(bubbleKey)
                    || bubble == null) {
                Log.i(TAG, "corner action: skip overflow drawer");
                return;
            }

            // 左下角按钮复用“拖到底部关闭”的官方语义：USER_GESTURE。
            // 不再调用 collapseStack()，否则只是贴边，随后 SystemUI 仍会重新展开该 Bubble。
            if (manageButton instanceof android.widget.TextView) {
                ((android.widget.TextView) manageButton).setText("关闭小窗");
            }
            manageButton.setOnClickListener(v -> dismissBubbleAsUserGesture(expandedView));

            ViewGroup parent = manageButton.getParent() instanceof ViewGroup
                    ? (ViewGroup) manageButton.getParent()
                    : (expandedView instanceof ViewGroup ? (ViewGroup) expandedView : null);
            if (parent == null) {
                Log.i(TAG, "corner action: no parent for manage button; row not added");
                return;
            }
            int index = parent.indexOfChild(manageButton);

            // 右侧按钮的文字尺寸用隐藏模板量出来（不改左侧按钮，免得左侧宽度被撑变）。
            View template = cloneManageButton(manageButton);
            if (template != null) {
                if (template instanceof android.widget.TextView) {
                    ((android.widget.TextView) template).setText("展开小窗");
                }
                template.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            }
            View clone = cloneManageButton(manageButton);
            if (clone == null) {
                Log.i(TAG, "corner action: clone button failed; only close button installed");
                return;
            }
            if (clone instanceof android.widget.TextView) {
                ((android.widget.TextView) clone).setText("展开小窗");
            }
            if (template != null) {
                clone.setMinimumWidth(template.getMeasuredWidth());
            }
            clone.setOnClickListener(v -> expandBubbleFullscreen(expandedView));
            clone.setTag(CORNER_ROW_TAG, Boolean.TRUE);

            // 放进容器：官方按钮只换实例、不换父容器，所以容器也复用同一个 —— 每次把树拆掉重建
            // 会让父容器在重建过程中丢样式、丢前后兄弟，容器变得不可见。
            SyncCornerLayout row = findCornerRow(parent);
            if (row == null) {
                parent.removeView(manageButton);
                row = new SyncCornerLayout(parent.getContext());
                row.setTag(CORNER_ROW_TAG, Boolean.TRUE);
                ViewGroup.LayoutParams lp = cloneParentParams(manageButton.getLayoutParams());
                // 容器接替官方按钮那一行，并带上 bubble_expanded_view 这个 id：官方有代码按它
                // findViewById 找底部容器，id 丢了容器就会变成一棵“没人管”的子树。
                row.setId(expandedViewId(expandedView, manageButton));
                if (index >= 0 && index <= parent.getChildCount()) {
                    parent.addView(row, index, lp);
                } else {
                    parent.addView(row, lp);
                }
            } else {
                row.removeAllViews();
                ViewGroup.LayoutParams lp = row.getLayoutParams();
                if (lp != null) lp.width = manageButton.getLayoutParams() != null
                        ? manageButton.getLayoutParams().width : ViewGroup.LayoutParams.MATCH_PARENT;
            }
            row.bind(manageButton, template, clone);
            Log.i(TAG, "corner action: close + fullscreen buttons share row "
                    + parent.getClass().getSimpleName());
            // 调试用：几帧后把结果打到日志里（父容器类型 / 容器 id / 两个子 View 的可见性与尺寸），
            // 下次“按钮丢失”时直接看这几行，不用再猜是容器被隐藏还是布局塌了。
            final SyncCornerLayout dumpRow = row;
            final View dumpClose = manageButton;
            final View dumpClone = clone;
            row.post(new Runnable() {
                @Override public void run() {
                    try {
                        Log.i(TAG, "corner row: parent=" + parent.getClass().getSimpleName()
                                + "#" + Integer.toHexString(System.identityHashCode(parent))
                                + " rowId=" + (dumpRow.getId() == View.NO_ID ? "none" : "0x" + Integer.toHexString(dumpRow.getId()))
                                + " row=" + dumpRow.getWidth() + "x" + dumpRow.getHeight()
                                + " vis=" + dumpRow.getVisibility());
                        if (template != null) Log.i(TAG, "corner button template vis=" + template.getVisibility()
                                + " alpha=" + template.getAlpha() + " size=" + template.getMeasuredWidth()
                                + "x" + template.getMeasuredHeight());
                        if (dumpClone != null) Log.i(TAG, "corner button fullscreen vis=" + dumpClone.getVisibility()
                                + " size=" + dumpClone.getWidth() + "x" + dumpClone.getHeight());
                        if (dumpClose != null) Log.i(TAG, "corner button close vis=" + dumpClose.getVisibility()
                                + " size=" + dumpClose.getWidth() + "x" + dumpClose.getHeight());
                    } catch (Throwable t) { Log.i(TAG, "corner row dump: " + t.getMessage()); }
                }
            });
        } catch (Throwable t) {
            Log.i(TAG, "corner action install failed: " + t.getMessage(), t);
        }
    }

    /** 复制 mManageButton：优先用官方 AlphaOptimizedButton 实例，克隆背景/文案/尺寸；否则用按钮通用拷贝。 */
    private static View cloneManageButton(View manageButton) {
        try {
            Context ctx = manageButton.getContext();
            Class<?> aob = ctx.getClassLoader().loadClass("com.android.wm.shell.AlphaOptimizedButton");
            Object cloned = aob.getConstructor(Context.class).newInstance(ctx);
            if (cloned instanceof View) {
                View cv = (View) cloned;
                cv.setBackground(manageButton.getBackground());
                if (manageButton instanceof android.widget.TextView && cv instanceof android.widget.TextView) {
                    android.widget.TextView src = (android.widget.TextView) manageButton;
                    android.widget.TextView tv = (android.widget.TextView) cv;
                    tv.setText(src.getText());
                    tv.setTextColor(src.getTextColors());
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, src.getTextSize());
                }
                LayoutParams mp = manageButton.getLayoutParams();
                if (mp != null) { cv.setLayoutParams(new LayoutParams(mp.width, mp.height)); }
                Log.i(TAG, "corner action: cloned AlphaOptimizedButton OK");
                return cv;
            }
        } catch (Throwable alphaErr) {
            Log.i(TAG, "corner action: AlphaOptimizedButton clone unavailable: "
                    + alphaErr.getMessage() + "; falling back to generic copy");
        }
        try {
            Context ctx = manageButton.getContext();
            Button b = new Button(ctx);
            b.setBackground(manageButton.getBackground());
            if (manageButton instanceof android.widget.TextView) {
                android.widget.TextView src = (android.widget.TextView) manageButton;
                b.setText(src.getText());
                b.setTextColor(src.getTextColors());
                b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, src.getTextSize());
            }
            LayoutParams mp = manageButton.getLayoutParams();
            if (mp != null) { b.setLayoutParams(new LayoutParams(mp.width, mp.height)); }
            Log.i(TAG, "corner action: cloned generic Button OK");
            return b;
        } catch (Throwable genericErr) {
            Log.i(TAG, "corner action: generic clone failed: " + genericErr.getMessage(), genericErr);
            return null;
        }
    }

    /** 用官方资源里的 R.id.bubble_expanded_view 给容器，找不到时退回 -1（不设 id）。 */
    private static int expandedViewId(Object expandedView, View fallback) {
        try {
            if (fallback != null && fallback.getId() != View.NO_ID) return fallback.getId();
            Class<?> rId = expandedView.getClass().getClassLoader()
                    .loadClass("com.android.internal.R$id");
            java.lang.reflect.Field f = rId.getField("bubble_expanded_view");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            Log.i(TAG, "corner action: bubble_expanded_view id unavailable: " + t.getMessage());
            return View.NO_ID;
        }
    }

    /**
     * 容器接替官方按钮在父容器里的位置：宽度沿用官方按钮那一行（wrap_content / match_parent 都一样），
     * 高度用 WRAP_CONTENT —— 容器的高度就是两颗按钮里最高的那颗，和原来「管理按钮一行」的高度一致，
     * 不会把窗口撑高，也不会被父容器给到 0 高度。
     * 原来按钮上的 margin 不要：那是给固定高度的按钮贴边用的，容器自己排位置。
     */
    private static ViewGroup.LayoutParams cloneParentParams(ViewGroup.LayoutParams src) {
        int width = src != null ? src.width : ViewGroup.LayoutParams.WRAP_CONTENT;
        ViewGroup.LayoutParams lp;
        if (src instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams f = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) src);
            f.setMargins(0, 0, 0, 0);
            lp = f;
        } else if (src instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) src);
            l.setMargins(0, 0, 0, 0);
            lp = l;
        } else {
            lp = new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        lp.width = width;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        return lp;
    }

    /** 在同一父容器里找已经挂好的按钮容器。 */
    private static SyncCornerLayout findCornerRow(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof SyncCornerLayout) return (SyncCornerLayout) child;
        }
        return null;
    }

    /**
     * 把官方按钮那一棵树的形状打进日志：父容器类型、父容器的父容器、每个兄弟 View 的
     * 类型 / id / 可见性 / 尺寸，以及官方按钮自己的可见性 / 布局参数。
     * 只在第一次遇到某棵树时打（同一棵树不重复刷屏），一次日志就能看出按钮为什么不见。
     */
    private static final Map<String, Long> sDumpedTrees = new ConcurrentHashMap<>();

    private static void dumpCornerTree(Object expandedView, View manageButton, String when) {
        try {
            ViewGroup parent = manageButton.getParent() instanceof ViewGroup
                    ? (ViewGroup) manageButton.getParent() : null;
            String key = (parent != null ? System.identityHashCode(parent) : 0)
                    + ":" + System.identityHashCode(manageButton);
            if (sDumpedTrees.containsKey(key)) return;
            sDumpedTrees.put(key, System.currentTimeMillis());
            StringBuilder sb = new StringBuilder();
            sb.append("corner tree[").append(when).append("] manage=")
                    .append(manageButton.getClass().getSimpleName())
                    .append(" vis=").append(manageButton.getVisibility())
                    .append(" id=0x").append(Integer.toHexString(manageButton.getId()));
            ViewGroup.LayoutParams mlp = manageButton.getLayoutParams();
            if (mlp != null) {
                sb.append(" lp=").append(mlp.getClass().getSimpleName())
                        .append('(').append(mlp.width).append('x').append(mlp.height).append(')');
            }
            if (parent != null) {
                sb.append(" parent=").append(parent.getClass().getSimpleName())
                        .append(" vis=").append(parent.getVisibility())
                        .append(" id=0x").append(Integer.toHexString(parent.getId()))
                        .append(" children=").append(parent.getChildCount());
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View c = parent.getChildAt(i);
                    sb.append(" [").append(i).append(':').append(c.getClass().getSimpleName())
                            .append(" id=0x").append(Integer.toHexString(c.getId()))
                            .append(" vis=").append(c.getVisibility());
                    ViewGroup.LayoutParams lp = c.getLayoutParams();
                    if (lp != null) sb.append(' ').append(lp.width).append('x').append(lp.height);
                    sb.append(']');
                }
                if (parent.getParent() instanceof ViewGroup) {
                    sb.append(" grandparent=").append(parent.getParent().getClass().getSimpleName());
                }
                sb.append(" lineHeight(parent)=").append(parent.getHeight());
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable t) {
            Log.i(TAG, "corner tree dump failed: " + t.getMessage());
        }
    }

    /**
     * 换气泡 / 切到 overflow 抽屉时，清掉挂在这棵树上的旧容器。
     *
     * <p>唯一不能动的是「官方按钮现在就在容器里」的情况：那时容器正接替着它的位置，
     * 拆掉就等于把按钮从视图树上摘下来（下一次官方只换 mManageButton 的引用，不会再 addView）。
     */
    private static void removeCornerButtonClones(Object expandedView, View manage) {
        java.util.ArrayList<ViewGroup> parents = new java.util.ArrayList<>();
        if (expandedView instanceof ViewGroup) parents.add((ViewGroup) expandedView);
        if (manage != null && manage.getParent() instanceof ViewGroup
                && !parents.contains(manage.getParent())) {
            parents.add((ViewGroup) manage.getParent());
        }
        for (ViewGroup parent : parents) {
            java.util.ArrayList<View> remove = new java.util.ArrayList<>();
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (!(child instanceof SyncCornerLayout)) continue;
                SyncCornerLayout row = (SyncCornerLayout) child;
                if (row.primary != null && row.primary.getParent() == row) continue;
                remove.add(child);
            }
            for (View child : remove) parent.removeView(child);
        }
    }

    /** Close exactly like dragging the bubble to the bottom dismiss target. */
    private static void dismissBubbleAsUserGesture(Object expandedView) {
        try {
            Object bubble = getField(expandedView, "mBubble");
            Object key = bubble != null ? getField(bubble, "mKey") : null;
            Object stackView = getField(expandedView, "mStackView");
            Object data = stackView != null ? getField(stackView, "mBubbleData") : null;
            if (data == null || !(key instanceof String)) {
                throw new IllegalStateException("BubbleData/key unavailable");
            }
            Method dismiss = null;
            for (Method method : data.getClass().getDeclaredMethods()) {
                if (!"dismissBubbleWithKey".equals(method.getName())
                        || method.getParameterCount() < 2
                        || method.getParameterTypes()[0] != int.class
                        || method.getParameterTypes()[method.getParameterCount() - 1] != String.class) {
                    continue;
                }
                method.setAccessible(true);
                dismiss = method;
                break;
            }
            if (dismiss == null) throw new NoSuchMethodException("dismissBubbleWithKey");
            Object[] args = new Object[dismiss.getParameterCount()];
            args[0] = 1; // BubbleData.DismissReason.USER_GESTURE on Android 17.
            for (int i = 1; i < args.length - 1; i++) {
                Class<?> type = dismiss.getParameterTypes()[i];
                args[i] = type.isPrimitive() ? primitiveDefault(type) : null;
            }
            args[args.length - 1] = key;
            dismiss.invoke(data, args);
            Log.i(TAG, "corner action: dismissed bubble key=" + key + " reason=USER_GESTURE");
        } catch (Throwable t) {
            Log.i(TAG, "corner action: dismiss bubble failed: " + t.getMessage(), t);
        }
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        return null;
    }

    /** Legacy collapse helper retained for non-button callers. */
    private static void collapseBubbleExpandedView(Object expandedView) {
        // 收起小窗 = BubbleData.setExpanded(false)：把展开的小窗收回到贴边气泡列表。
        // BubbleStackView 在 Android 17 并没有直接的 mBubbleController 字段，改为用
        // expandedView.mStackView.mBubbleData 这个在 smali 里确认存在的字段，直接调
        // BubbleData.setExpanded(false)（官方收起展开窗的原子操作）。
        try {
            // 主路径：经 mTaskViewController.this$0 拿到 BubbleController 后收起（官方收起展开窗）。
            Object bubble = getField(expandedView, "mBubble");
            Object bubbleTaskView = bubble != null ? getField(bubble, "mBubbleTaskView") : null;
            Object taskView = bubbleTaskView != null ? getField(bubbleTaskView, "taskView") : null;
            Object taskViewController = taskView != null ? getField(taskView, "mTaskViewController") : null;
            Object collapse = null;
            try {
                // BubbleController 是 BubbleController$BubbleTaskViewController 的内部类，this$0 即 controller。
                if (taskViewController != null) {
                    Object controller = getField(taskViewController, "this$0");
                    if (controller != null) {
                        java.lang.reflect.Method m = findMethodSystemUi(controller.getClass(), "collapseStack");
                        if (m != null) { m.invoke(controller); collapse = m; }
                    }
                }
            } catch (Throwable ignored) { }
            if (collapse != null) {
                Log.i(TAG, "corner action: collapse stack invoked (controller)");
                return;
            }
            // 备用：expandedView.mStackView.mBubbleData.setExpanded(false)（官方收起展开窗的原子状态）。
            Object stackView = getField(expandedView, "mStackView");
            Object data = stackView != null ? getField(stackView, "mBubbleData") : null;
            java.lang.reflect.Method setExpanded = data != null
                    ? findMethod(data.getClass(), "setExpanded", boolean.class) : null;
            if (setExpanded != null) {
                setExpanded.invoke(data, false);
                Log.i(TAG, "corner action: collapse stack invoked (BubbleData.setExpanded(false))");
                return;
            }
            throw new IllegalStateException("no collapse entry available");
        } catch (Throwable t) {
            Log.i(TAG, "corner action: collapse stack failed: " + t.getMessage(), t);
        }
    }

    /** Official Bubble -> fullscreen handoff, preserving the current task/page. */
    private static void expandBubbleFullscreen(Object expandedView) {
        try {
            Object bubble = getField(expandedView, "mBubble");
            Object bubbleTaskView = bubble != null ? getField(bubble, "mBubbleTaskView") : null;
            Object taskView = bubbleTaskView != null ? getField(bubbleTaskView, "taskView") : null;
            Object controller = taskView != null ? getField(taskView, "mTaskViewController") : null;
            Object taskController = controller != null ? getField(taskView, "mTaskViewTaskController") : null;
            if (controller == null || taskController == null) {
                throw new IllegalStateException("Bubble task view controller unavailable");
            }
            Method move = findMethod(controller.getClass(),
                    "moveTaskViewToFullscreen", taskController.getClass());
            if (move == null) throw new NoSuchMethodException("moveTaskViewToFullscreen");
            move.invoke(controller, taskController);
            Log.i(TAG, "corner action: official fullscreen handoff submitted");
        } catch (Throwable t) {
            Log.i(TAG, "corner action: official fullscreen handoff failed: " + t.getMessage(), t);
        }
    }

    /* Retained as a diagnostic fallback only; the corner button does not use it. */
    private static volatile long sLastCornerLaunchAt;

    /** Collapse the embedded bubble first, then launch its exact user/profile entry. */
    private static void launchBubbleApp(Object expandedView) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sLastCornerLaunchAt < 800L) return;
            sLastCornerLaunchAt = now;

            Object bubble = getField(expandedView, "mBubble");
            Object packageValue = bubble != null ? getField(bubble, "mPackageName") : null;
            Object userValue = bubble != null ? getField(bubble, "mUser") : null;
            if (!(packageValue instanceof String) || ((String) packageValue).trim().isEmpty()
                    || !(userValue instanceof UserHandle)) {
                throw new IllegalStateException("Bubble package/user unavailable");
            }
            String packageName = (String) packageValue;
            UserHandle user = (UserHandle) userValue;
            LauncherApps launcher = ((View) expandedView).getContext()
                    .getSystemService(LauncherApps.class);
            if (launcher == null) throw new IllegalStateException("LauncherApps unavailable");
            List<LauncherActivityInfo> activities = launcher.getActivityList(packageName, user);
            ComponentName target = null;
            if (activities != null) {
                for (LauncherActivityInfo activity : activities) {
                    if (activity == null || !user.equals(activity.getUser())) continue;
                    ComponentName component = activity.getComponentName();
                    if (component != null && packageName.equals(component.getPackageName())) {
                        target = component;
                        break;
                    }
                }
            }
            if (target == null) throw new IllegalStateException("No launcher entry for Bubble user");

            // LauncherApps accepts the request while the app is still embedded, but
            // Android 17 leaves that task behind the bubble window. Collapse first;
            // capture all launch data above because the view may be rebuilt/cleared.
            collapseBubbleExpandedView(expandedView);
            final ComponentName launchTarget = target;
            final UserHandle launchUser = user;
            final LauncherApps launchService = launcher;
            new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    launchService.startMainActivity(launchTarget, launchUser, null, null);
                    Log.i(TAG, "corner action: launcher start submitted package="
                            + packageName + " user=" + userIdOf(launchUser));
                } catch (Throwable t) {
                    Log.i(TAG, "corner action: delayed launcher start failed: " + t.getMessage(), t);
                }
            }, 280L);
        } catch (Throwable t) {
            Log.i(TAG, "corner action: launcher start failed: " + t.getMessage(), t);
        }
    }

    private void hookLauncher(PackageLoadedParam param) {
        ClassLoader cl = mLauncherClassLoader;

        // Hook OverviewActionsView.onFinishInflate
        try {
            hook(cl.loadClass("com.android.quickstep.views.OverviewActionsView")
                    .getMethod("onFinishInflate")).intercept(chain -> {
                Object ret = chain.proceed();
                try {
                    Context ctx = ((View) chain.getThisObject()).getContext();
                    if (ModuleSettings.isActionBarEnabled(ctx))
                        injectBubbleButton(chain.getThisObject(), cl);
                } catch (Throwable t) { Log.e(TAG, "inject failed", t); }
                return ret;
            });
        } catch (Throwable t) { Log.e(TAG, "Hook onFinishInflate: " + t.getMessage()); }

        // Hook OverviewActionsView.onClick
        try {
            hook(cl.loadClass("com.android.quickstep.views.OverviewActionsView")
                    .getMethod("onClick", View.class)).intercept(chain -> {
                View v = (View) chain.getArg(0);
                if (v != null && bubbleButton != null && v.getId() == bubbleButton.getId()) {
                    onBubbleButtonClick((View) chain.getThisObject());
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) { Log.e(TAG, "Hook onClick: " + t.getMessage()); }

        // Hook TaskMenuView.addMenuOptions
        try {
            hook(cl.loadClass("com.android.quickstep.views.TaskMenuView")
                    .getDeclaredMethod("addMenuOptions")).intercept(chain -> {
                chain.proceed();
                try {
                    Context ctx = ((View) chain.getThisObject()).getContext();
                    if (ModuleSettings.isMenuEnabled(ctx))
                        addBubbleMenuOption(chain.getThisObject(), cl);
                } catch (Throwable t) { Log.e(TAG, "addBubbleMenuOption: " + t.getMessage()); }
                return null;
            });
        } catch (Throwable t) { Log.e(TAG, "Hook addMenuOptions: " + t.getMessage()); }
    }

    // ==================== 操作栏按钮 ====================

    @SuppressLint("DiscouragedApi")
    private void injectBubbleButton(Object actionsView, ClassLoader cl) {
        Context ctx = ((View) actionsView).getContext();
        android.content.res.Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        ViewGroup actionsParent = (ViewGroup) actionsView;

        Button btn = createBubbleButton(ctx, res, pkg);
        addToActionButtons(actionsParent, btn, res, pkg);
    }

    /**
     * 直接加到 action_buttons 末尾（跟随系统原有按钮的位置）
     */
    private void addToActionButtons(ViewGroup actionsParent, Button btn,
            android.content.res.Resources res, String pkg) {
        int abId = res.getIdentifier("action_buttons", "id", pkg);
        LinearLayout actionButtons = (LinearLayout) actionsParent.findViewById(abId);
        if (actionButtons == null) return;

        // 去重
        for (int i = 0; i < actionButtons.getChildCount(); i++) {
            View child = actionButtons.getChildAt(i);
            if (child.getTag() != null && "bubble_button".equals(child.getTag().toString())) {
                bubbleButton = child;
                return;
            }
        }

        // 添加到末尾
        ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int spId = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
        if (spId != 0) mlp.setMarginStart(res.getDimensionPixelSize(spId));
        btn.setLayoutParams(mlp);
        actionButtons.addView(btn);
        bubbleButton = btn;

        // 捕获 RecentsView（从 actionsParent 的 parent 链查找）
        if (recentsViewInstance == null) {
            try {
                Class<?> rvCls = mLauncherClassLoader.loadClass(
                        "com.android.quickstep.views.RecentsView");
                View cur = actionsParent;
                while (cur != null) {
                    if (rvCls.isInstance(cur)) {
                        recentsViewInstance = cur;
                        Log.i(TAG, "Captured RecentsView in follow mode: " + cur);
                        break;
                    }
                    cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Capture RecentsView failed: " + t.getMessage());
            }
        }

        Log.i(TAG, "Bubble button added to action_buttons (follow mode)");
    }

    private boolean isClearAllButton(View child) {
        try { return mLauncherClassLoader.loadClass("com.android.quickstep.views.ClearAllButton").isInstance(child); }
        catch (Throwable t) { return false; }
    }

    @SuppressLint("DiscouragedApi")
    private Button createBubbleButton(Context ctx, android.content.res.Resources res, String pkg) {
        int styleId = res.getIdentifier("OverviewActionButton.Blur", "style", pkg);
        if (styleId == 0) styleId = res.getIdentifier("OverviewActionButton", "style", pkg);
        Button btn = (styleId != 0) ? new Button(ctx, null, 0, styleId) : new Button(ctx);
        btn.setText("消息气泡");
        btn.setContentDescription("消息气泡");
        btn.setId(View.generateViewId());
        btn.setTag("bubble_button");

        // 图标
        int iconId = res.getIdentifier("ic_bubble_button", "drawable", pkg);
        if (iconId == 0) iconId = res.getIdentifier("ic_bubble_bar", "drawable", pkg);
        if (iconId != 0) {
            android.graphics.drawable.Drawable icon = res.getDrawable(iconId, null);
            if (icon != null) btn.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
        }

        btn.setOnClickListener(v -> onBubbleButtonClick((View) btn.getParent().getParent()));
        return btn;
    }

    private void updateBubbleVisibility(Object av) {}

    // ==================== 菜单项注入 ====================

    @SuppressLint("DiscouragedApi")
    private void addBubbleMenuOption(Object menuView, ClassLoader cl) {
        try {
            Context ctx = ((View) menuView).getContext();
            android.content.res.Resources res = ctx.getResources();
            String pkg = ctx.getPackageName();

            ViewGroup optionLayout = (ViewGroup) findMethod(menuView.getClass(), "getOptionLayout").invoke(menuView);
            Object taskContainer = findMethod(menuView.getClass(), "getTaskContainer").invoke(menuView);
            if (optionLayout == null || taskContainer == null) return;

            // 捕获 RecentsView
            try {
                Object tv = findMethod(menuView.getClass(), "getTaskView").invoke(menuView);
                if (tv != null) {
                    recentsViewInstance = findMethod(tv.getClass(), "getRecentsView").invoke(tv);
                    Log.i(TAG, "Captured RecentsView: " + recentsViewInstance);
                }
            } catch (Throwable ignored) {}

            ViewGroup menuItem = (ViewGroup) android.view.LayoutInflater.from(ctx)
                    .inflate(res.getIdentifier("task_view_menu_option", "layout", pkg), optionLayout, false);

            int bgId = res.getIdentifier("app_chip_menu_item_bg", "drawable", pkg);
            if (bgId != 0) menuItem.setBackground(res.getDrawable(bgId, ctx.getTheme()));

            int iconId = res.getIdentifier("ic_bubble_button", "drawable", pkg);
            if (iconId == 0) iconId = res.getIdentifier("ic_bubble_bar", "drawable", pkg);
            View iconView = menuItem.findViewById(res.getIdentifier("icon", "id", pkg));
            if (iconView != null && iconId != 0) {
                android.graphics.drawable.Drawable icon = res.getDrawable(iconId, ctx.getTheme());
                int tintId = res.getIdentifier("materialColorOnSurface", "color", pkg);
                if (tintId != 0) icon.setTint(res.getColor(tintId, ctx.getTheme()));
                iconView.setBackground(icon);
            }

            View tv = menuItem.findViewById(res.getIdentifier("text", "id", pkg));
            if (tv instanceof android.widget.TextView)
                ((android.widget.TextView) tv).setText("消息气泡");

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) menuItem.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            menuItem.setLayoutParams(lp);

            menuItem.setOnClickListener(v -> {
                try {
                    Object task = invoke(taskContainer, "getTask");
                    if (task == null) return;
                    Object key = getField(task, "key");
                    Intent intent = (Intent) getField(key, "baseIntent");
                    int userId = getField(key, "userId") != null ? (int) getField(key, "userId") : 0;
                    if (intent != null) {
                        findMethod(menuView.getClass(), "close", boolean.class).invoke(menuView, true);
                        bubbleCurrentTask(ctx, intent, task, userId);
                        new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> dismissOverview(ctx), 200);
                    }
                } catch (Throwable t) { Log.e(TAG, "menu click: " + t.getMessage()); }
            });

            optionLayout.addView(menuItem);
        } catch (Throwable t) { Log.e(TAG, "addBubbleMenuOption: " + t.getMessage()); }
    }

    // ==================== 气泡触发 ====================

    private void onBubbleButtonClick(View actionsView) {
        Context ctx = actionsView.getContext();
        Object rv = recentsViewInstance;
        if (rv == null) rv = findRecentsViewFromHierarchy(actionsView);
        if (rv == null) { Log.w(TAG, "RecentsView not found"); return; }

        try {
            Object tv = findMethod(rv.getClass(), "getCurrentPageTaskView").invoke(rv);
            if (tv == null) return;
            List<?> tc = (List<?>) findMethod(tv.getClass(), "getTaskContainers").invoke(tv);
            if (tc == null || tc.isEmpty()) return;

            Object task = findMethod(tc.get(0).getClass(), "getTask").invoke(tc.get(0));
            Object key = getField(task, "key");
            Intent intent = (Intent) getField(key, "baseIntent");
            int userId = getField(key, "userId") != null ? (int) getField(key, "userId") : 0;
            if (intent == null) return;

            bubbleCurrentTask(ctx, intent, task, userId);
            new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> dismissOverview(ctx), 200);
        } catch (Throwable t) { Log.e(TAG, "onBubbleButtonClick: " + t.getMessage()); }
    }

    private boolean bubbleCurrentTask(Context ctx, Intent taskIntent, Object task, int userId) {
        try {
            Class<?> proxyCls = mLauncherClassLoader.loadClass("com.android.quickstep.SystemUiProxy");
            Object ds = proxyCls.getField("INSTANCE").get(null);
            Object proxy = ds.getClass().getMethod("get", Context.class).invoke(ds, ctx.getApplicationContext());
            if (proxy == null) return false;

            Intent bIntent = new Intent(taskIntent);
            // Always ensure package is set (BubbleData.getOrCreateBubble needs intent.getPackage())
            if (bIntent.getPackage() == null && bIntent.getComponent() != null)
                bIntent.setPackage(bIntent.getComponent().getPackageName());

            // Set the exact top component for precise bubble targeting (for deep-linked / secondary activities)
            try {
                Object topComponent = invoke(task, "getTopComponent");
                String className = (String) invoke(topComponent, "getClassName");
                if (className != null && bIntent.getPackage() != null) {
                    bIntent.setComponent(new ComponentName(bIntent.getPackage(), className));
                }
            } catch (Throwable ignored) {}

            Object userHandle = mLauncherClassLoader.loadClass("android.os.UserHandle")
                    .getMethod("of", int.class).invoke(null, userId);

            Class<?> epCls = mLauncherClassLoader.loadClass("com.android.wm.shell.shared.bubbles.logging.EntryPoint");
            Object ep = null;
            for (Object e : (Object[]) epCls.getDeclaredField("$VALUES").get(null))
                if ("NOTIFICATION".equals(e.toString())) { ep = e; break; }

            for (Method m : proxy.getClass().getMethods())
                if (m.getName().equals("showAppBubble")) {
                    m.invoke(proxy, bIntent, userHandle, ep, null);
                    Log.i(TAG, "showAppBubble OK");
                    return true;
                }
        } catch (Throwable t) { Log.e(TAG, "bubbleCurrentTask: " + t.getMessage()); }
        return false;
    }

    private Object findRecentsViewFromHierarchy(View view) {
        try {
            Class<?> rvCls = mLauncherClassLoader.loadClass("com.android.quickstep.views.RecentsView");
            // 先从 parent 链查找
            View cur = view;
            while (cur != null) {
                if (rvCls.isInstance(cur)) { recentsViewInstance = cur; return cur; }
                cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
            }
            // parent 链找不到，从 rootView 递归搜索
            View root = view.getRootView();
            if (root != null) {
                cur = findInTree(root, rvCls);
                if (cur != null) { recentsViewInstance = cur; return cur; }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private View findInTree(View view, Class<?> cls) {
        if (cls.isInstance(view)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View f = findInTree(vg.getChildAt(i), cls);
                if (f != null) return f;
            }
        }
        return null;
    }

    private void dismissOverview(Context ctx) {
        try {
            if (recentsViewInstance != null) {
                Object sm = findMethod(recentsViewInstance.getClass(), "getStateManager").invoke(recentsViewInstance);
                if (sm != null) {
                    findMethod(sm.getClass(), "moveToRestState").invoke(sm);
                    Log.i(TAG, "dismissed via moveToRestState");
                    return;
                }
            }
            Runtime.getRuntime().exec(new String[]{"am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"});
            Log.i(TAG, "dismissed via am start HOME");
        } catch (Throwable t) { Log.e(TAG, "dismiss: " + t.getMessage()); }
    }

    // ==================== 工具方法 ====================

    private static Object getFieldSystemUi(Object obj, String name) {
        try { java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true); return f.get(obj); }
        catch (Throwable t) { return null; }
    }

    private static Object invokeSystemUi(Object obj, String method) {
        try { Method m = findMethodSystemUi(obj.getClass(), method); return m != null ? m.invoke(obj) : null; }
        catch (Throwable t) { return null; }
    }

    private static java.lang.reflect.Field findFieldSystemUi(Class<?> c, String n) {
        while (c != null) { try { return c.getDeclaredField(n); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } return null;
    }

    private static Method findMethodSystemUi(Class<?> c, String n, Class<?>... p) {
        while (c != null) { try { Method m = c.getDeclaredMethod(n, p); m.setAccessible(true); return m; } catch (NoSuchMethodException e) { c = c.getSuperclass(); } } return null;
    }

    private static void showToast(Context ctx, String msg) {
        new android.os.Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }

    private static View findViewByTag(View view, String tag) {
        if (tag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findViewByTag(vg.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }
}
