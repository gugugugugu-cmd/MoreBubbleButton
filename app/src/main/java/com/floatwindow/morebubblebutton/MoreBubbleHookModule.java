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
    /** 编译标记：日志里能直接看出跑的是哪一版 */
    private static final String BUILD_TAG = "v1.25-telegram-launchpoint";
    private Object recentsViewInstance;
    private View bubbleButton;
    /** 第二行模式的容器（null = 跟随原按钮） */
    private static ViewGroup sSecondRow;
    private ClassLoader mLauncherClassLoader;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // 只用来确认模块真的进了进程：logcat -s MoreBubbleModule 之前看不到任何一行，
        // 说明 SystemUI 里要么没加载模块，要么热重载被拒（LSPosed 日志：refused to be hot reloaded）。
        Log.i(TAG, "module loaded: process=" + param.getProcessName()
                + " api=" + getApiVersion());
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
    /** SystemUI 的 Context：横屏判定与包名解析用 */
    private static android.content.Context sSystemUiContext;
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
        //    官方 mManageButton 一颗按钮切成左右两半：左半「关闭小窗」（收起气泡，不删除），
        //    右半「展开小窗」（moveTaskViewToFullscreen 反射链）。只改这一颗 View 自己。
        //    官方 updateManageButtonIfExists 每次调用都会 inflate -> addView -> setVisibility
        //    -> setOnClickListener -> post，因此 proceed 之后 mManageButton 已在 this 的 child 列表里，
        //    可直接读取并重设；背景与点击都在这一颗 View 上完成。
        try {
            Class<?> bevCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleExpandedView");
            hook(bevCls.getMethod("updateManageButtonIfExists")).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    java.lang.reflect.Field f = bevCls.getDeclaredField("mManageButton");
                    f.setAccessible(true);
                    Object btn = f.get(chain.getThisObject());
                    if (btn instanceof View) {
                        // 只改官方这一颗按钮：它的位移与裁剪就是官方行为，不存在同步问题。
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
                        if (appCtx != null) sSystemUiContext = appCtx;
                        BubbleBroadcastBridge.attach(appCtx, self);
                    } catch (Throwable t) { Log.w(TAG, "attach bridge: " + t.getMessage()); }
                    return result;
                });
            }
            Log.i(TAG, "Hooked BubblesManager ctor for gesture bridge");
        } catch (Throwable t) { Log.w(TAG, "Hook BubblesManager ctor: " + t.getMessage()); }

        // 最近任务拖进气泡这条路：BubbleData.getOrCreateBubble(TaskInfo) 直接把
        // TaskInfo.baseIntent 塞进 Bubble.mIntent，绕过了模块所有 Entry 级 hook。
        // 这里在它返回之后把 mIntent 换成应用的嵌入式 Activity（若有）。
        try {
            Class<?> dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData");
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method candidate : dataCls.getDeclaredMethods()) {
                if (!"getOrCreateBubble".equals(candidate.getName()) || candidate.getParameterCount() != 1) continue;
                if (candidate.getParameterTypes()[0].getName().equals("android.app.TaskInfo")) {
                    m = candidate;
                    break;
                }
            }
            if (m != null) {
                hook(m).intercept(chain -> {
                    Object bubble = chain.proceed();
                    try {
                        patchBubbleIntent(chain.getThisObject(), bubble);
                    } catch (Throwable t) {
                        Log.w(TAG, "patch bubble intent: " + t.getMessage());
                    }
                    return bubble;
                });
                Log.i(TAG, "Hooked BubbleData.getOrCreateBubble(TaskInfo) build=" + BUILD_TAG);
            } else {
                Log.w(TAG, "BubbleData.getOrCreateBubble(TaskInfo) not found");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Hook getOrCreateBubble: " + t.getMessage());
        }

        // 最终启动点：BubbleTaskViewListener 通过 TaskViewController.startActivity(...)
        // 真正把 PendingIntent 交给 TaskView。参数顺序：
        //   (TaskViewTaskController, PendingIntent, Intent, ActivityOptions, Rect)
        // 在这里把 PendingIntent 换成应用的嵌入式 Activity，前面所有 hook 漏掉的都在这一步兜住。
        try {
            Class<?> tvc = cl.loadClass("com.android.wm.shell.taskview.TaskViewController");
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : tvc.getDeclaredMethods()) {
                if (!"startActivity".equals(m.getName()) || m.getParameterCount() != 5) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p[1] == android.app.PendingIntent.class && p[2] == Intent.class) {
                    target = m;
                    break;
                }
            }
            if (target != null) {
                hook(target).intercept(chain -> {
                    try {
                        Object pending = chain.getArg(1);
                        Object intent = chain.getArg(2);
                        Context ctx = sSystemUiContext;
                        android.app.PendingIntent swapped =
                                swapForEmbeddable(ctx, (android.app.PendingIntent) pending, (Intent) intent);
                        if (swapped != null) {
                            try {
                                chain.setArg(1, swapped);
                            } catch (Throwable t) {
                                Log.w(TAG, "setArg unavailable: " + t.getMessage());
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "final start swap: " + t.getMessage());
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "Hooked TaskViewController.startActivity (final launch point) " + BUILD_TAG);
            } else {
                Log.w(TAG, "TaskViewController.startActivity(5 args) not found");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Hook TaskViewController.startActivity: " + t.getMessage());
        }

        installLandscapeLaunchBounds(cl);

        Log.i(TAG, "All SystemUI hooks installed, build=" + BUILD_TAG);
    }

    // ==================== 嵌入式友好的启动目标 ====================
    //
    // 有些应用的主界面是 singleTask（如 Telegram 的 LaunchActivity），塞进 TaskView 后
    // 手势返回会把唯一任务栈清空 → 宿主直接移除；展开全屏时任务 reparent 也会重建 → 闪退。
    // 这类应用通常会另外声明一个允许嵌入的 Activity（Telegram 的 org.telegram.ui.BubbleActivity，
    // allowEmbedded=true + resizeableActivity=true + documentLaunchMode=always）。
    // 这里在构造气泡 Intent 时优先选它，找不到就保持原样。

    /** 已解析过的包名 → 可用的嵌入式 Activity 全名（无则记为 ""）。 */
    private static final Map<String, String> sEmbeddableActivity = new ConcurrentHashMap<>();



    /**
     * 把「原始待启动的 PendingIntent」换成应用的嵌入式 Activity。
     * 拿不到包名或没有配置映射时返回 null（表示不替换）。
     */
    private static android.app.PendingIntent swapForEmbeddable(Context ctx,
            android.app.PendingIntent original, Intent fallbackIntent) {
        if (ctx == null) return null;
        String pkg = null;
        Intent base = null;
        try {
            if (original != null) {
                base = (Intent) original.getClass().getMethod("getIntent").invoke(original);
                if (base != null) pkg = base.getPackage();
            }
        } catch (Throwable ignored) {}
        if (pkg == null && fallbackIntent != null) {
            pkg = fallbackIntent.getPackage();
        }
        if (pkg == null) return null;
        String forced = EMBEDDABLE_OVERRIDES.get(pkg);
        Log.i(TAG, "final launch: pkg=" + pkg + " forced=" + forced
                + " base=" + (base != null ? base.getComponent() : null));
        if (forced == null) return null;
        Intent target = new Intent(base != null ? base : new Intent());
        target.setComponent(new ComponentName(pkg, forced));
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        Log.i(TAG, "final launch swapped -> " + forced);
        // flags 与 BubbleTaskViewListener 里的一致：FLAG_MUTABLE 必须保留，否则 PendingIntent 创建失败
        return android.app.PendingIntent.getActivity(ctx, 0, target,
                android.app.PendingIntent.FLAG_MUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * 把 Bubble.mIntent 换成「应用的嵌入式 Activity」（如果配置了）。
     * 只有最近任务这条路需要：BubbleData 直接复制 TaskInfo.baseIntent，不给外部改的机会。
     */
    private static void patchBubbleIntent(Object bubbleData, Object bubble) {
        if (bubble == null) return;
        Intent intent = (Intent) getField(bubble, "mIntent");
        if (intent == null) return;
        String pkg = intent.getPackage();
        if (pkg == null) {
            Object pkgName = getField(bubble, "mPackageName");
            if (pkgName instanceof String) pkg = (String) pkgName;
        }
        if (pkg == null) return;
        String forced = EMBEDDABLE_OVERRIDES.get(pkg);
        Log.i(TAG, "bubble data intent: pkg=" + pkg + " component=" + intent.getComponent()
                + " forced=" + forced);
        if (forced == null) return;
        Intent replacement = new Intent(intent);
        replacement.setComponent(new ComponentName(pkg, forced));
        replacement.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        try {
            java.lang.reflect.Field f = bubble.getClass().getDeclaredField("mIntent");
            f.setAccessible(true);
            f.set(bubble, replacement);
            Log.i(TAG, "bubble intent replaced: " + pkg + " -> " + forced);
        } catch (Throwable t) {
            Log.w(TAG, "replace bubble intent failed: " + t.getMessage());
        }
    }

    /** 把「应用主界面」换成它的嵌入式友好 Activity（若存在）。返回是否替换。 */
    /**
     * 明确知道要换的应用：主界面 singleTask、塞进 TaskView 会立刻 finish，
     * 但自带嵌入式 Activity。写死比探测可靠 —— 探测依赖 ActivityInfo 字段，
     * 而那个字段在部分系统版本里根本不存在。
     */
    private static final Map<String, String> EMBEDDABLE_OVERRIDES = new java.util.HashMap<>();
    static {
        EMBEDDABLE_OVERRIDES.put("org.telegram.messenger.web", "org.telegram.ui.BubbleActivity");
        EMBEDDABLE_OVERRIDES.put("org.telegram.messenger", "org.telegram.ui.BubbleActivity");
        EMBEDDABLE_OVERRIDES.put("org.telegram.plus", "org.telegram.ui.BubbleActivity");
    }

    private static boolean preferEmbeddableActivity(Context ctx, Intent intent) {
        // 无条件打一条：证明这段代码在当前进程里真的跑到了
        if (intent == null) {
            Log.i(TAG, "bubble intent: null");
            return false;
        }
        String pkg = intent.getPackage();
        Log.i(TAG, "bubble intent: pkg=" + pkg + " component=" + intent.getComponent());
        if (pkg == null) return false;

        String forced = EMBEDDABLE_OVERRIDES.get(pkg);
        if (forced != null) {
            intent.setComponent(new ComponentName(pkg, forced));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            Log.i(TAG, "bubble target(forced): " + pkg + " -> " + forced);
            return true;
        }
        if (intent.getComponent() != null
                && !intent.getComponent().getClassName().equals(pkg)) {
            // 已经指定了具体界面（通知 contentIntent / 最近任务顶栈）时默认不覆盖，
            // 但宿主界面若是 singleTask，塞进 TaskView 会立即自毁，此时仍要换成嵌入式 Activity。
            String cached = sEmbeddableActivity.get(pkg);
            if (cached != null && cached.isEmpty()) return false;
            if (cached == null && findEmbeddableActivity(ctx, pkg) == null) return false;
        }
        String activity = sEmbeddableActivity.get(pkg);
        if (activity == null) {
            activity = findEmbeddableActivity(ctx, pkg);
            sEmbeddableActivity.put(pkg, activity == null ? "" : activity);
        }
        if (activity.isEmpty()) return false;
        intent.setComponent(new ComponentName(pkg, activity));
        // singleTask 的任务若被复用会串味，这里明确要一个新任务实例
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        Log.i(TAG, "bubble target: " + pkg + " -> " + activity);
        return true;
    }


    /** 包内是否声明了 allowEmbedded + resizeableActivity 的 Activity，返回全类名。 */
    private static String findEmbeddableActivity(Context ctx, String pkg) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES);
            if (pi == null || pi.activities == null) return null;
            // ActivityInfo 里没有 allowEmbedded / resizeableActivity 这两个字段（只有 FLAG_* 常量），
            // 所以按「内部 Activity + 名字里带 bubble」来挑：这类 Activity 是应用为嵌入式
            // /气泡场景专门准备的（Telegram 的就是 org.telegram.ui.BubbleActivity）。
            StringBuilder all = new StringBuilder();
            String byName = null;
            for (android.content.pm.ActivityInfo ai : pi.activities) {
                if (ai == null || ai.name == null) continue;
                if (all.length() < 400) all.append(ai.name).append(ai.exported ? "(e) " : " ");
                if (ai.exported || !ai.enabled) continue;
                String simple = ai.name.substring(ai.name.lastIndexOf('.') + 1).toLowerCase();
                if (byName == null && simple.contains("bubble")) byName = ai.name;
            }
            Log.i(TAG, "activities of " + pkg + ": " + all);
            if (byName != null) {
                Log.i(TAG, "embeddable candidate: " + byName);
                return byName;
            }
        } catch (Throwable t) {
            Log.w(TAG, "embeddable lookup failed for " + pkg + ": " + t.getMessage());
        }
        return null;
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
            preferEmbeddableActivity(ctx, intent);

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
            Context sysCtx = (Context) getFieldSystemUi(controller, "mContext");
            if (sysCtx != null) preferEmbeddableActivity(sysCtx, targetIntent);
            Log.i(TAG, reason + ": target component = " + targetIntent.getComponent()
                    + " pkg=" + targetIntent.getPackage());
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
            // 所有创建应用气泡的路径最后都会到这里 —— 在这里做一次最终确认，
            // 上面几个入口（手势 / 通知 / 多任务）漏掉的也补上。
            if (intent != null && intent.getPackage() != null) {
                Context c = (Context) getFieldSystemUi(controller, "mContext");
                String forced = EMBEDDABLE_OVERRIDES.get(intent.getPackage());
                if (forced != null) {
                    boolean already = intent.getComponent() != null
                            && forced.equals(intent.getComponent().getClassName());
                    if (!already) {
                        intent.setComponent(new ComponentName(intent.getPackage(), forced));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        Log.i(TAG, "bubble target(final): " + intent.getPackage() + " -> " + forced
                                + " (" + reason + ")");
                    } else {
                        Log.i(TAG, "bubble target(final): already " + forced + " (" + reason + ")");
                    }
                }
            }
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

    // ===== 气泡小窗底部按钮：把官方「管理」按钮改造成「关闭 | 展开」双区域按钮 =====

    /** 标记我们装到官方按钮上的监听，避免每轮重复叠加。 */
    private static final int CORNER_TOUCH_TAG = 0x4d4f5245; // "MORE"

    /**
     * 为什么是一颗按钮的两个区域，而不是两颗按钮：
     *
     * <p>官方 {@code mExpandedViewContainer} 是 {@code setClipChildren(false)} 之外还带
     * {@code setClipToOutline(true)} 的 FrameLayout，展开窗上滑 / 收起时容器本身在被裁，
     * 「管理」按钮在容器内会被裁掉一半、再整条消失。任何挂在容器外面的自造按钮都跟不上这条
     * 裁剪边界 —— 这就是之前右侧按钮“原地不动”的根因。
     *
     * <p>所以只保留官方这一颗 View，用 {@code MirrorDrawable} 把它的背景画成左右两半
     * （左「关闭小窗」、右「展开小窗」，中间一条分隔线），触摸按 {@code getX()} 分派。
     * 同一颗 View 必然同一行、同一动画、同一条裁剪边界，不可能再脱节。
     *
     * <p>点击走 {@code OnTouchListener} 而不是 {@code OnClickListener}：TouchListener 先于
     * 官方自己的 click 处理执行，返回 true 直接吃掉 DOWN/UP，不会被官方「管理菜单」抢走。
     */
    private static void applyCornerButtonActions(Object expandedView, View manageButton) {
        try {
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

            if (!(manageButton instanceof android.widget.TextView)) {
                Log.i(TAG, "corner action: manage button is not a TextView; skipped");
                return;
            }
            final android.widget.TextView tv = (android.widget.TextView) manageButton;

            // 按钮自身的文字必须清空：TextView 会先把 setText 的文字画一遍，MirrorDrawable 再
            // 画一遍，同一个位置就出现重影。这里只保留等宽占位，让按钮尺寸够放两段文字，
            // 真正的两段文案全部由 MirrorDrawable 画。
            tv.setText("\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000");
            tv.setSingleLine(true);
            tv.setEllipsize(null);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setOnClickListener(null);
            tv.setClickable(true);

            // 背景换成「原背景镜像成左右两半」的包装；原始背景在每轮更新时重新取，
            // 主题切换后也能跟上。
            Object painted = tv.getTag(CORNER_TOUCH_TAG);
            MirrorDrawable mirror = painted instanceof MirrorDrawable ? (MirrorDrawable) painted : null;
            if (mirror == null || mirror.src != snapshotBackground(tv)) {
                mirror = new MirrorDrawable(tv.getBackground(), tv);
                tv.setTag(CORNER_TOUCH_TAG, mirror);
                tv.setBackground(mirror);
            }
            mirror.bind(tv);

            tv.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.setPressed(true);
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (event.getX() < v.getWidth() / 2f) {
                            Log.i(TAG, "corner action: left half -> dismiss");
                            dismissBubbleAsUserGesture(expandedView);
                        } else {
                            Log.i(TAG, "corner action: right half -> fullscreen");
                            expandBubbleFullscreen(expandedView);
                        }
                        return true;
                    default:
                        return true;
                }
            });
            Log.i(TAG, "corner action: single button split into close|expand");
        } catch (Throwable t) {
            Log.i(TAG, "corner action install failed: " + t.getMessage(), t);
        }
    }

    /** 取按钮当前背景（去掉我们自己包的那一层），用于判断是否需要重建包装。 */
    private static android.graphics.drawable.Drawable snapshotBackground(View v) {
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg instanceof MirrorDrawable) return ((MirrorDrawable) bg).src;
        return bg;
    }

    /**
     * 把官方按钮背景镜像成左右两半，各画一段文案：
     * 左半区原样画「关闭小窗」，右半区画「展开小窗」，中间一条竖分隔线；
     * 左右两半各自用原背景的对应一半，视觉上仍是同一颗胶囊。
     */
    static class MirrorDrawable extends android.graphics.drawable.Drawable {
        final android.graphics.drawable.Drawable src;
        android.widget.TextView host;
        final android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Paint dividerPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Rect bounds = new android.graphics.Rect();

        MirrorDrawable(android.graphics.drawable.Drawable src, android.widget.TextView host) {
            this.src = src;
            this.host = host;
            dividerPaint.setStyle(android.graphics.Paint.Style.STROKE);
        }

        void bind(android.widget.TextView host) {
            this.host = host;
            textPaint.setTextSize(host.getTextSize());
            textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
            textPaint.setTypeface(host.getTypeface());
            try {
                textPaint.setColor(host.getCurrentTextColor());
            } catch (Throwable ignored) {
                textPaint.setColor(0xff1b1b1b);
            }
            dividerPaint.setColor(0x33808080);
            dividerPaint.setStrokeWidth(Math.max(1f, host.getResources().getDisplayMetrics().density));
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            int mid = b.centerX();
            // 左半区
            int save = canvas.save();
            canvas.clipRect(b.left, b.top, mid, b.bottom);
            if (src != null) {
                src.setBounds(b);
                src.draw(canvas);
            }
            drawLabel(canvas, "关闭小窗", b.left, mid, b.top, b.bottom);
            canvas.restoreToCount(save);
            // 右半区
            save = canvas.save();
            canvas.clipRect(mid, b.top, b.right, b.bottom);
            if (src != null) {
                src.setBounds(b);
                src.draw(canvas);
            }
            drawLabel(canvas, "展开小窗", mid, b.right, b.top, b.bottom);
            canvas.restoreToCount(save);
            // 分隔线：顶部与底部都留白，看起来像两颗并排的胶囊而不是被一根划线劈开
            int inset = Math.round(b.height() * 0.30f);
            canvas.drawLine(mid, b.top + inset, mid, b.bottom - inset, dividerPaint);
        }

        private void drawLabel(android.graphics.Canvas canvas, String text, int left, int right, int top, int bottom) {
            android.graphics.Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f;
            // 半区窄于文字时等比缩小，避免两段文字互相压到分隔线上
            float avail = (right - left) * 0.86f;
            float w = textPaint.measureText(text);
            if (w > avail && w > 0) {
                float scale = avail / w;
                int save = canvas.save();
                canvas.scale(scale, scale, (left + right) / 2f, baseline);
                canvas.drawText(text, (left + right) / 2f, baseline, textPaint);
                canvas.restoreToCount(save);
            } else {
                canvas.drawText(text, (left + right) / 2f, baseline, textPaint);
            }
        }

        @Override public void setAlpha(int alpha) {
            if (src != null) src.setAlpha(alpha);
        }

        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            if (src != null) src.setColorFilter(cf);
        }

        @Override public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override protected void onBoundsChange(android.graphics.Rect r) {
            super.onBoundsChange(r);
            bounds.set(r);
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] state) {
            if (src != null) src.setState(state);
            return true;
        }

        @Override public int getIntrinsicWidth() { return src != null ? src.getIntrinsicWidth() : -1; }

        @Override public int getIntrinsicHeight() { return src != null ? src.getIntrinsicHeight() : -1; }
    }

        /**
     * 把官方按钮那一棵树的形状打进日志：父容器类型、父容器的父容器、每个兄弟 View 的
     * 类型 / id / 可见性 / 尺寸，以及官方按钮自己的可见性 / 布局参数。
     * 只在第一次遇到某棵树时打（同一棵树不重复刷屏），一次日志就能看出按钮为什么不见。
     */
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

    // ==================== 横屏应用进竖屏气泡：交换 launch bounds ====================
    //
    // TaskViewTransitions.prepareActivityOptions(options, bounds, controller) 只做三件事：
    //   setLaunchBounds(bounds) / setLaunchWindowingMode(6) / setRemoveWithTaskOrganizer(true)
    // 全程没有 rotation / viewport 换算。于是任务按「传入矩形的坐标系」排版：
    // 竖屏系统里气泡窗口是 1100×1700 这样的竖条，横屏游戏撑满后就被裁成「上半部分有内容、
    // 下半部分黑」——就是用户看到的样子。
    //
    // 这里的做法：在它执行完之后，对「目标 Activity 要求横屏 + 系统当前竖屏」的情况，
    // 把 launch bounds 换成宽高互换的矩形（宽=原高、高=原宽），任务就按横屏排版，
    // 再由 TaskView 的 crop 去适配窗口。设置里可以关掉。

    /** 已判定过的包名 → 是否要求横屏，避免每次都查一次 ActivityInfo。 */
    private static final Map<String, Boolean> sLandscapeApps = new ConcurrentHashMap<>();
    private static volatile Boolean sSystemLandscape;

    private void installLandscapeLaunchBounds(ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass("com.android.wm.shell.taskview.TaskViewTransitions");
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (!"prepareActivityOptions".equals(m.getName()) || m.getParameterCount() != 3) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p[0] == android.app.ActivityOptions.class && p[1] == android.graphics.Rect.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                Log.w(TAG, "landscape launch: prepareActivityOptions(ActivityOptions,Rect,*) not found");
                return;
            }
            hook(target).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object options = chain.getArg(0);
                    Object boundsObj = chain.getArg(1);
                    Object controller = chain.getArg(2);
                    if (options instanceof android.app.ActivityOptions
                            && boundsObj instanceof android.graphics.Rect
                            && controller != null) {
                        swapBoundsForLandscapeApp((android.app.ActivityOptions) options,
                                (android.graphics.Rect) boundsObj, controller);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "landscape launch: " + t.getMessage());
                }
                return result;
            });
            Log.i(TAG, "landscape launch bounds hook installed");
        } catch (Throwable t) {
            Log.w(TAG, "landscape launch hook failed: " + t.getMessage());
        }
    }

    private static void swapBoundsForLandscapeApp(android.app.ActivityOptions options,
            android.graphics.Rect bounds, Object controller) {
        if (isSystemLandscape()) return;
        String pkg = taskPackageOf(controller);
        if (pkg == null || !isLandscapeApp(pkg)) return;
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        android.graphics.Rect swapped = new android.graphics.Rect(
                bounds.left, bounds.top,
                bounds.left + bounds.height(), bounds.top + bounds.width());
        options.setLaunchBounds(swapped);
        Log.i(TAG, "landscape launch: " + pkg + " bounds " + bounds.width() + "x" + bounds.height()
                + " -> " + swapped.width() + "x" + swapped.height());
    }

    /** 从 TaskViewTaskController 里拿目标包名：mPendingInfo / mTaskInfo → baseActivity.componentName。 */
    private static String taskPackageOf(Object controller) {
        for (String field : new String[]{"mPendingInfo", "mTaskInfo"}) {
            try {
                Object info = getField(controller, field);
                if (info == null) continue;
                Object base = getField(info, "baseActivity");
                if (base != null) {
                    Object cmp = invoke(base, "getComponentName");
                    if (cmp instanceof android.content.ComponentName) {
                        return ((android.content.ComponentName) cmp).getPackageName();
                    }
                    Object pkgName = invoke(base, "getPackageName");
                    if (pkgName instanceof String) return (String) pkgName;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 目标应用是否把 Activity 固定在横屏（screenOrientation=landscape/sensorLandscape…）。 */
    private static boolean isLandscapeApp(String pkg) {
        String packageName = pkg;
        android.content.pm.ActivityInfo info = null;
        Boolean cached = sLandscapeApps.get(packageName);
        if (cached != null) return cached;
        try {
            android.content.Context sysui = currentSystemUiContext();
            if (sysui == null) return false;
            android.content.pm.PackageManager pm = sysui.getPackageManager();
            android.content.Intent launch = pm.getLaunchIntentForPackage(packageName);
            if (launch == null) return false;
            info = pm.getActivityInfo(launch.getComponent(), 0);
            int o = info.screenOrientation;
            boolean landscape = o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
            sLandscapeApps.put(packageName, landscape);
            return landscape;
        } catch (Throwable t) {
            sLandscapeApps.put(packageName, false);
            return false;
        }
    }

    private static android.content.Context currentSystemUiContext() {
        return sSystemUiContext;
    }

    /** 系统当前是否横屏。 */
    private static boolean isSystemLandscape() {
        android.content.Context ctx = sSystemUiContext;
        if (ctx == null) return false;
        try {
            return ctx.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 操作栏按钮 ====================

    @SuppressLint("DiscouragedApi")
    private void injectBubbleButton(Object actionsView, ClassLoader cl) {
        Context ctx = ((View) actionsView).getContext();
        android.content.res.Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        ViewGroup actionsParent = (ViewGroup) actionsView;

        Button btn = createBubbleButton(ctx, res, pkg);
        if (ModuleSettings.getPositionMode(ctx) == 1) {
            ensureSecondRow(actionsParent, btn, res, pkg);
        } else {
            addToActionButtons(actionsParent, btn, res, pkg);
        }
    }

    /** 设置页改了位置 / 模式后调用：把正在显示的按钮立刻挪过去。 */
    public static void applyPositionFromSettings(final Context ctx) {
        // 跟随模式没有独立行，位置就是系统原按钮的位置，不需要处理。
        final ViewGroup row = sSecondRow;
        if (row == null || row.getParent() == null) return;
        row.post(() -> {
            try {
                ViewGroup.LayoutParams raw = row.getLayoutParams();
                if (raw instanceof android.widget.FrameLayout.LayoutParams) {
                    layoutSecondRow(ctx, row, (android.widget.FrameLayout.LayoutParams) raw);
                }
            } catch (Throwable t) {
                Log.w(TAG, "applyPositionFromSettings: " + t.getMessage());
            }
        });
    }

    /**
     * 第二行模式：另起一行放在 action_buttons 下方，用 marginStart / bottomMargin
     * 把 X（0-100%）和 Y（0-100% → 0-48dp）映射成像素位置。
     */
    @SuppressLint("DiscouragedApi")
    private void ensureSecondRow(ViewGroup actionsParent, Button btn,
            android.content.res.Resources res, String pkg) {
        Context ctx = actionsParent.getContext();

        // 已经在第二行 → 只更新现有按钮，不重复添加
        if (sSecondRow != null && sSecondRow.getParent() == actionsParent) {
            for (int i = 0; i < sSecondRow.getChildCount(); i++) {
                View child = sSecondRow.getChildAt(i);
                if (child.getTag() != null && "bubble_button".equals(child.getTag().toString())) {
                    bubbleButton = child;
                    ViewGroup.LayoutParams lp = sSecondRow.getLayoutParams();
                    if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                        layoutSecondRow(ctx, sSecondRow, (android.widget.FrameLayout.LayoutParams) lp);
                    }
                    return;
                }
            }
        } else if (sSecondRow != null) {
            // 换了宿主界面（视图重建），旧行已经不在树上了
            sSecondRow = null;
        }

        LinearLayout row = new LinearLayout(ctx);
        row.setTag("bubble_second_row");
        row.setOrientation(LinearLayout.HORIZONTAL);

        ViewGroup.MarginLayoutParams btnLp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int spId = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
        if (spId != 0) btnLp.setMarginStart(res.getDimensionPixelSize(spId));
        btn.setLayoutParams(btnLp);
        row.addView(btn);
        bubbleButton = btn;

        android.widget.FrameLayout.LayoutParams rowLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.gravity = android.view.Gravity.START | android.view.Gravity.BOTTOM;

        int abId = res.getIdentifier("action_buttons", "id", pkg);
        int insertIndex = 0;
        View abv = actionsParent.findViewById(abId);
        if (abv != null) insertIndex = actionsParent.indexOfChild(abv) + 1;
        actionsParent.addView(row, insertIndex, rowLp);
        sSecondRow = row;

        layoutSecondRow(ctx, row, rowLp);
        row.post(() -> layoutSecondRow(ctx, row,
                (android.widget.FrameLayout.LayoutParams) row.getLayoutParams()));

        // 透明度跟着原按钮栏走，切换动画时不会突然显形
        final View abRef = abv;
        row.post(() -> actionsParent.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private int lastX = Integer.MIN_VALUE;
                    private int lastY = Integer.MIN_VALUE;

                    @Override public boolean onPreDraw() {
                        ViewGroup live = sSecondRow;
                        if (live == null || live.getWidth() <= 0) return true;
                        int px = ModuleSettings.getPosX(ctx);
                        int py = ModuleSettings.getPosY(ctx);
                        if (px != lastX || py != lastY) {
                            ViewGroup.LayoutParams lp = live.getLayoutParams();
                            if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                                layoutSecondRow(ctx, live, (android.widget.FrameLayout.LayoutParams) lp);
                            }
                            lastX = px;
                            lastY = py;
                        }
                        if (abRef != null) live.setAlpha(abRef.getAlpha());
                        return true;
                    }
                }));
        Log.i(TAG, "bubble button placed on second row");
    }

    /** 把 X/Y 百分比换算成 margin，把整行摆到操作栏下方的第二行。 */
    private static void layoutSecondRow(Context ctx, ViewGroup row,
            android.widget.FrameLayout.LayoutParams lp) {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        float density = dm.density;
        ViewGroup parent = (ViewGroup) row.getParent();
        if (parent == null) return;

        int parentWidth = parent.getWidth() > 0 ? parent.getWidth() : dm.widthPixels;
        int parentHeight = parent.getHeight() > 0 ? parent.getHeight() : dm.heightPixels;

        int rowWidth = row.getWidth() > 0 ? row.getWidth() : row.getMeasuredWidth();
        if (rowWidth <= 0 && row.getChildCount() > 0) rowWidth = row.getChildAt(0).getMeasuredWidth();
        int rowHeight = row.getHeight() > 0 ? row.getHeight() : row.getMeasuredHeight();

        // 底边基线：紧贴底部留 56dp（原操作栏的高度 + 间距）
        int baseTop = parentHeight - rowHeight - (int) (56 * density);
        // Y：0% 在基线，100% 再往上 48dp
        int up = (int) (Math.min(ModuleSettings.getPosY(ctx) * 1.4f, 100f) / 100f * (48 * density));
        lp.gravity = android.view.Gravity.START | android.view.Gravity.TOP;
        lp.topMargin = Math.max(0, baseTop - up);

        // X：0%=贴左，50%=居中，100%=贴右（减去图标左置造成的视觉偏移）
        int maxMargin = Math.max(0, parentWidth - rowWidth);
        int iconOffset = (int) (13 * density);
        int marginStart = (int) ((ModuleSettings.getPosX(ctx) / 100f) * maxMargin) - iconOffset;
        lp.setMarginStart(Math.max(0, Math.min(marginStart, maxMargin)));

        row.setLayoutParams(lp);
    }

    /**
     * 跟随模式：直接加到 action_buttons 末尾（跟随系统原有按钮的位置）。
     */
    @SuppressLint("DiscouragedApi")
    private void addToActionButtons(ViewGroup actionsParent, Button btn,
            android.content.res.Resources res, String pkg) {
        // 之前在第二行 → 先把那一行拆掉，避免两颗按钮同时出现
        if (sSecondRow != null) {
            if (bubbleButton != null && bubbleButton.getParent() == sSecondRow) {
                sSecondRow.removeView(bubbleButton);
            }
            if (sSecondRow.getParent() instanceof ViewGroup) {
                ((ViewGroup) sSecondRow.getParent()).removeView(sSecondRow);
            }
            sSecondRow = null;
        }

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

            // singleTask 应用（如 Telegram）在 TaskView 里会自己 finish，优先换成它自带的嵌入式 Activity
            preferEmbeddableActivity(ctx, bIntent);
            Log.i(TAG, "recents bubble target: " + bIntent.getComponent() + " pkg=" + bIntent.getPackage());

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
