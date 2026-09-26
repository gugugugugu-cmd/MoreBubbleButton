package com.floatwindow.morebubblebutton;

import android.app.Notification;
import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class MoreBubbleHookModule extends XposedModule {
    private static final String TAG = "MoreBubbleModule";
    private Object recentsViewInstance;
    private View bubbleButton;
    private static View sSecondRow;
    private ClassLoader mLauncherClassLoader;
    private ClassLoader mSystemUiClassLoader;
    private boolean mSystemUiHooksInstalled = false;
    private final AtomicLong mRequestSequence = new AtomicLong();
    private final java.util.Set<String> mHookedBubbleMethods =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ==================== 异常日志工具 ====================
    private void logThrowable(String stage, Throwable throwable) {
        Throwable real = unwrapThrowable(throwable);
        log(Log.ERROR, TAG,
                "MBDBG ERROR stage=" + stage
                        + " type=" + real.getClass().getName()
                        + " message=" + real.getMessage()
                        + "\n" + Log.getStackTraceString(real));
    }

    private static Throwable unwrapThrowable(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getTargetException() != null) {
            current = ((InvocationTargetException) current).getTargetException();
        }
        return current;
    }

    // ==================== 模块生命周期 ====================
    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "MoreBubbleModule: " + param.getProcessName() + " | API " + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();

        log(Log.INFO, TAG,
                "MBDBG PACKAGE_LOADED"
                        + " package=" + pkg
                        + " firstPackage=" + param.isFirstPackage()
                        + " classLoader=" + param.getDefaultClassLoader());

        if ("com.google.android.apps.nexuslauncher".equals(pkg)
                || "com.android.launcher3".equals(pkg)) {
            mLauncherClassLoader = param.getDefaultClassLoader();

            log(Log.INFO, TAG,
                    "MBDBG entering Launcher hooks:"
                            + " package=" + pkg);

            hookLauncher(param);
        } else if ("com.android.systemui".equals(pkg)) {
            log(Log.INFO, TAG,
                    "MBDBG entering SystemUI hooks:"
                            + " firstPackage=" + param.isFirstPackage());

            mSystemUiClassLoader = param.getDefaultClassLoader();
            hookSystemUiOnce(mSystemUiClassLoader);
        }
    }

    private synchronized void hookSystemUiOnce(ClassLoader cl) {
        if (mSystemUiHooksInstalled) {
            log(Log.INFO, TAG, "MBDBG SystemUI hooks already installed, skip");
            return;
        }

        mSystemUiHooksInstalled = true;

        try {
            hookSystemUi(cl);
        } catch (Throwable t) {
            mSystemUiHooksInstalled = false;
            logThrowable("hookSystemUiOnce", t);
        }
    }

    // ==================== SystemUI Hook 主要入口 ====================
    private void hookSystemUi(ClassLoader cl) {
        log(Log.INFO, TAG, "Hooking SystemUI...");

        // Android 17 App Bubble 窗口大小
        hookAndroid17BubbleBounds(cl);

        // 诊断：dump BubbleController 结构
        dumpBubbleControllerStructure(cl);

        // 诊断：Hook 所有可能的接收方法
        hookBubbleReceiverDiagnostics(cl);

        // 诊断：Hook BubbleData
        hookBubbleDataDiagnostics(cl);

        // 诊断：dismissBubbleWithKey 只记录不阻止
        hookDismissLogger(cl);

        // 诊断：dump IBubbles Stub 结构
        dumpIBubblesStubStructure(cl);

        // 诊断：Hook IBubbles.Stub.onTransact
        hookIBubblesStubOnTransact(cl);

        // 原有的功能性 Hook：shouldShowBubbleButton
        try {
            Class<?> clazz = cl.loadClass(
                    "com.android.systemui.statusbar.notification.row.NotificationContentView");
            hook(clazz.getMethod("shouldShowBubbleButton")).intercept(chain -> {
                boolean original;
                try {
                    original = (boolean) chain.proceed();
                } catch (Throwable t) {
                    log(Log.INFO, TAG, "shouldShowBubbleButton original failed: " + t.getMessage());
                    return false;
                }
                try {
                    Context ctx = null;
                    try { ctx = ((View) chain.getThisObject()).getContext(); } catch (Throwable ignored) {}
                    if (ctx != null && !ModuleSettings.isSystemUiBubbleEnabled(ctx)) return original;
                } catch (Throwable ignored) {}
                if (original) return true;
                try {
                    Object contentView = chain.getThisObject();
                    Object row = getFieldSystemUi(contentView, "mContainingNotification");
                    if (row == null) return original;
                    Object adapter = getFieldSystemUi(row, "mEntryAdapter");
                    if (adapter == null) return original;
                    Object sbn = invokeSystemUi(adapter, "getSbn");
                    if (sbn == null) return original;
                    Notification notif = (Notification) invokeSystemUi(sbn, "getNotification");
                    if (notif == null) return original;
                    if ((notif.flags & 0x40) != 0) return false;
                    String pkg = (String) sbn.getClass().getMethod("getPackageName").invoke(sbn);
                    Context viewCtx = ((View) contentView).getContext();
                    if (pkg == null || viewCtx.getPackageManager().getLaunchIntentForPackage(pkg) == null) return false;
                    return true;
                } catch (Throwable t) {
                    log(Log.INFO, TAG, "shouldShowBubbleButton custom check failed: " + t.getMessage());
                    return original;
                }
            });
            log(Log.INFO, TAG, "Hooked shouldShowBubbleButton OK");
        } catch (Throwable t) {
            logThrowable("shouldShowBubbleButton hook", t);
        }

        // injectBubbleMetadata at bind time
        try {
            Class<?> binderClass = cl.loadClass(
                    "com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl");
            Method target = null;
            for (Method m : binderClass.getDeclaredMethods()) {
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
                    } catch (Throwable t) {
                        log(Log.INFO, TAG, "bindRow meta inject: " + t.getMessage());
                    }
                    return result;
                });
                log(Log.INFO, TAG, "Hooked NotificationRowBinderImpl OK");
            }
        } catch (Throwable t) {
            logThrowable("RowBinder hook", t);
        }

        // 原有的 expandStackAndSelectBubble 拦截（保留，但诊断期间不修改行为，只记录）
        try {
            Class<?> bubblesCls = cl.loadClass("com.android.systemui.wmshell.BubblesManager");
            Method expand = null;
            for (Method m : bubblesCls.getDeclaredMethods()) {
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
                    } catch (Throwable t) {
                        logThrowable("expand guard", t);
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "Hooked BubblesManager.expandStackAndSelectBubble OK");
            } else {
                log(Log.INFO, TAG, "BubblesManager.expandStackAndSelectBubble(NotificationEntry) not found");
            }
        } catch (Throwable t) {
            logThrowable("Hook BubblesManager", t);
        }

        // onUserChangedBubble 同样保留
        try {
            Class<?> bubblesCls = cl.loadClass("com.android.systemui.wmshell.BubblesManager");
            Method onUserChanged = null;
            for (Method m : bubblesCls.getDeclaredMethods()) {
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
                    try {
                        boolean enabled = (boolean) chain.getArg(1);
                        Object entry = chain.getArg(0);
                        if (enabled && entry != null && expandAppBubbleFromNotification(chain.getThisObject(), entry, "user change")) {
                            return null;
                        }
                    } catch (Throwable t) {
                        logThrowable("user change bubble", t);
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "Hooked BubblesManager.onUserChangedBubble OK");
            } else {
                log(Log.INFO, TAG, "BubblesManager.onUserChangedBubble(NotificationEntry, boolean) not found");
            }
        } catch (Throwable t) {
            logThrowable("Hook onUserChangedBubble", t);
        }

        // 警告：以下原有的 setSelectedBubbleInternal 和 dismissBubbleWithKey 的修改行为被禁用，改为纯日志
        // 不再添加强制修改 mBubbles 的逻辑，避免干扰诊断

        log(Log.INFO, TAG, "All SystemUI hooks installed");
    }

    // ==================== Android 17 App Bubble 尺寸 ====================
    // 气泡浮窗由「容器视图」和「任务窗口」两部分组成：
    //   * 容器视图（BubbleExpandedView / BubbleBarExpandedView）负责圆角轮廓、阴影、指针；
    //   * 任务窗口（承载 app 内容）的 bounds 由 BubblePositioner.getTaskViewRestBounds() 计算，
    //     而它在气泡栏模式下会直接复用 getBubbleBarExpandedViewBounds() 的结果。
    // 这两部分各自从下面的尺寸源头取值。只改 getTaskViewRestBounds() 会出现
    // 「内容变小、轮廓不变」的空白区域，而且拖动贴边后容器重新布局会把内容尺寸改回原值。
    // 因此这里统一在尺寸源头缩放，保证轮廓与内容始终一致。
    private static volatile int sLoggedWidthPercent = -1;
    private static volatile int sLoggedHeightPercent = -1;
    private static final java.util.Set<String> sProbedSources =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private void hookAndroid17BubbleBounds(ClassLoader cl) {
        try {
            Class<?> positioner = cl.loadClass("com.android.wm.shell.bubbles.BubblePositioner");
            Method showVertically = positioner.getDeclaredMethod("showBubblesVertically");
            Method containerPadding = positioner.getDeclaredMethod("getExpandedViewContainerPadding",
                    boolean.class, boolean.class);

            // 旧式浮窗宽度：容器宽度与任务窗口宽度共用
            Method contentWidth = positioner.getDeclaredMethod("getTaskViewContentWidth", boolean.class);
            hook(contentWidth).intercept(chain -> {
                int raw = (int) chain.proceed();
                Object positionerObj = chain.getThisObject();
                bubblesizeProbe(positionerObj, "contentWidth");
                int percent = bubbleSizePercent(positionerObj, true);
                if (percent == 100 || raw <= 0) return raw;
                // getTaskViewContentWidth() 内部会减掉容器左内边距，而居中逻辑刚好加宽了它，
                // 这里先把那部分补回来，保证缩放比例与设置一致。
                boolean onLeft = Boolean.TRUE.equals(chain.getArg(0));
                int delta = centeringDelta(positionerObj, showVertically, containerPadding, onLeft);
                int span = raw + delta;
                int scaled = clampBubbleLength(positionerObj,
                        (int) Math.round(span * percent / 100.0), true);
                logBubbleSize("contentWidth", true, percent, span, scaled);
                return scaled;
            });

            // 旧式浮窗高度上限：容器高度上限与任务窗口高度共用
            Method maxHeight = positioner.getDeclaredMethod("getMaxExpandedViewHeight", boolean.class);
            hook(maxHeight).intercept(chain -> {
                int original = (int) chain.proceed();
                bubblesizeProbe(chain.getThisObject(), "maxHeight");
                return scaleBubbleLength(chain.getThisObject(), original, false, "maxHeight");
            });

            // 旧式浮窗资源高度：影响容器高度与 Y 轴定位
            Class<?> viewProvider = cl.loadClass("com.android.wm.shell.bubbles.BubbleViewProvider");
            Method expandedHeight = positioner.getDeclaredMethod("getExpandedViewHeight", viewProvider);
            hook(expandedHeight).intercept(chain -> {
                float original = (float) chain.proceed();
                Object positionerObj = chain.getThisObject();
                bubblesizeProbe(positionerObj, "expandedHeight");
                int percent = bubbleSizePercent(positionerObj, false);
                if (percent == 100 || original <= 0f) return original;
                return original * percent / 100f;
            });

            // 旧式浮窗水平居中：展开视图在容器内左对齐，宽度变小后余量全落在一侧会显得贴边，
            // 这里把余量的一半补到左侧内边距，使窗口始终位于原来空间的中间。
            // 仅用于「横向气泡行」布局（手机竖屏）：此时图标行居中于屏幕，窗口居中后指针仍能指向图标。
            // 横屏/大屏是侧边竖向气泡列，窗口贴列摆放，居中会让指针够不到图标，因此保持系统默认。
            hook(containerPadding).intercept(chain -> {
                int[] result = (int[]) chain.proceed();
                try {
                    if (result == null || result.length < 4) return result;
                    Object positionerObj = chain.getThisObject();
                    bubblesizeProbe(positionerObj, "containerPadding");
                    int percent = bubbleSizePercent(positionerObj, true);
                    if (percent == 100) return result;
                    if (Boolean.TRUE.equals(showVertically.invoke(positionerObj))) return result;
                    android.graphics.Rect screen = (android.graphics.Rect)
                            getFieldSystemUi(positionerObj, "mScreenRect");
                    if (screen == null) return result;
                    int span = screen.width() - result[0] - result[2];
                    if (span <= 0) return result;
                    int delta = (int) Math.round(span * (100.0 - percent) / 100.0 / 2.0);
                    if (delta <= 0) return result;
                    result[0] += delta;
                    logBubbleSize("containerPadding", true, percent, span, delta);
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "bubble centre padding skipped: " + t.getMessage());
                }
                return result;
            });

            // 指针跟随居中：窗口整体右移后，指针的本地 X 仍以旧布局为原点，
            // 需按同样的偏移量回退，小突出才会继续对准上方的气泡图标。
            Method pointerPosition = positioner.getDeclaredMethod("getPointerPosition", float.class);
            hook(pointerPosition).intercept(chain -> {
                float original = (float) chain.proceed();
                Object positionerObj = chain.getThisObject();
                bubblesizeProbe(positionerObj, "pointerPosition");
                int delta = centeringDelta(positionerObj, showVertically, containerPadding, false);
                if (delta <= 0) return original;
                float adjusted = original - delta;
                logBubbleSize("pointerOffset", true, bubbleSizePercent(positionerObj, true),
                        (int) original, (int) adjusted);
                return adjusted;
            });

            // 气泡栏模式：容器与任务窗口共用同一个 Rect，按底边锚点缩放并水平居中
            Method barBounds = positioner.getDeclaredMethod("getBubbleBarExpandedViewBounds",
                    boolean.class, boolean.class, android.graphics.Rect.class);
            hook(barBounds).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object target = chain.getArg(2);
                    if (!(target instanceof android.graphics.Rect)) return result;
                    Object positionerObj = chain.getThisObject();
                    bubblesizeProbe(positionerObj, "barBounds");
                    int widthPercent = bubbleSizePercent(positionerObj, true);
                    int heightPercent = bubbleSizePercent(positionerObj, false);
                    if (widthPercent == 100 && heightPercent == 100) return result;

                    android.graphics.Rect rect = (android.graphics.Rect) target;
                    int oldWidth = rect.width();
                    int oldHeight = rect.height();
                    if (oldWidth <= 0 || oldHeight <= 0) return result;
                    int width = clampBubbleLength(positionerObj,
                            (int) ((long) oldWidth * widthPercent / 100L), true);
                    int height = clampBubbleLength(positionerObj,
                            (int) ((long) oldHeight * heightPercent / 100L), false);
                    android.graphics.Rect screen = (android.graphics.Rect)
                            getFieldSystemUi(positionerObj, "mScreenRect");
                    int left;
                    if (widthPercent == 100 || screen == null) {
                        boolean onLeft = Boolean.TRUE.equals(chain.getArg(0));
                        left = onLeft ? rect.left : rect.right - width;
                    } else {
                        left = screen.left + (screen.width() - width) / 2;
                    }
                    int bottom = rect.bottom;
                    rect.set(left, bottom - height, left + width, bottom);
                    logBubbleSize("barBounds", true, widthPercent, oldWidth, width);
                    logBubbleSize("barBounds", false, heightPercent, oldHeight, height);
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Android 17 bubble bar size skipped: " + t.getMessage());
                }
                return result;
            });

            log(Log.INFO, TAG, "Hooked Android 17 BubblePositioner size sources");
        } catch (Throwable t) {
            logThrowable("hook Android 17 bubble size sources", t);
        }
    }

    /** 按设置百分比缩放一个长度值；100% 时原样返回。 */
    private static int scaleBubbleLength(Object positionerObj, int original, boolean width, String tag) {
        if (original <= 0) return original;
        int percent = bubbleSizePercent(positionerObj, width);
        if (percent == 100) return original;
        int scaled = clampBubbleLength(positionerObj, (int) ((long) original * percent / 100L), width);
        logBubbleSize(tag, width, percent, original, scaled);
        return scaled;
    }

    /**
     * 水平居中会让容器左内边距变大，这里从实际内边距反推出我们额外加的那部分。
     * 未居中（100%）或竖向气泡列布局时返回 0，因此不依赖任何跨调用的状态。
     */
    private static int centeringDelta(Object positionerObj, Method showVertically,
            Method containerPadding, boolean onLeft) {
        try {
            if (Boolean.TRUE.equals(showVertically.invoke(positionerObj))) return 0;
            if (bubbleSizePercent(positionerObj, true) == 100) return 0;
            int[] padding = (int[]) containerPadding.invoke(positionerObj, onLeft, false);
            if (padding == null || padding.length < 4) return 0;
            android.graphics.Insets insets = (android.graphics.Insets)
                    getFieldSystemUi(positionerObj, "mInsets");
            int base = (insets != null ? insets.left : 0)
                    + getIntFieldSystemUi(positionerObj, "mExpandedViewPadding", 0);
            return Math.max(0, padding[0] - base);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 缩放结果必须落在屏幕范围内。 */
    private static int clampBubbleLength(Object positionerObj, int value, boolean width) {
        android.graphics.Rect screen = (android.graphics.Rect)
                getFieldSystemUi(positionerObj, "mScreenRect");
        long limit = width
                ? (screen != null ? screen.width() : value)
                : (screen != null ? screen.height() : value);
        return (int) Math.max(1L, Math.min(limit, value));
    }

    /** 读取 int 类型字段，取不到或类型不符时返回兜底值。 */
    private static int getIntFieldSystemUi(Object obj, String name, int fallback) {
        Object value = getFieldSystemUi(obj, name);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    /** 读取用户在模块设置里配置的百分比，取不到时按系统默认 100%。 */
    private static int bubbleSizePercent(Object positionerObj, boolean width) {
        Context ctx = (Context) getFieldSystemUi(positionerObj, "mContext");
        if (ctx == null) return 100;
        return width
                ? ModuleSettings.getBubbleWidthPercent(ctx)
                : ModuleSettings.getBubbleHeightPercent(ctx);
    }

    /** 每个尺寸源头在每个进程里只打印一次，用来确认设备实际走的是哪套布局。 */
    private static void bubblesizeProbe(Object positionerObj, String source) {
        if (sProbedSources.contains(source)) return;
        if (!sProbedSources.add(source)) return;
        Log.i(TAG, "MBDBG bubble size probe source=" + source
                + " widthPercent=" + bubbleSizePercent(positionerObj, true)
                + " heightPercent=" + bubbleSizePercent(positionerObj, false));
    }

    /** 同一方向的百分比变化时才打印一次，避免布局回调刷屏。 */
    private static void logBubbleSize(String tag, boolean width, int percent, int before, int after) {
        if (width ? sLoggedWidthPercent == percent : sLoggedHeightPercent == percent) return;
        if (width) {
            sLoggedWidthPercent = percent;
        } else {
            sLoggedHeightPercent = percent;
        }
        Log.i(TAG, "MBDBG bubble size apply source=" + tag
                + " axis=" + (width ? "width" : "height")
                + " percent=" + percent
                + " " + before + " -> " + after);
    }

    // ==================== 诊断工具：dump BubbleController 结构 ====================
    private void dumpBubbleControllerStructure(ClassLoader cl) {
        try {
            Class<?> controllerClass = cl.loadClass(
                    "com.android.wm.shell.bubbles.BubbleController");

            log(Log.INFO, TAG,
                    "MBDBG BubbleController loaded:"
                            + " class=" + controllerClass.getName()
                            + " loader=" + controllerClass.getClassLoader());

            for (Method method : controllerClass.getDeclaredMethods()) {
                String lower = method.getName().toLowerCase();
                if (lower.contains("bubble")
                        || lower.contains("expand")
                        || lower.contains("select")) {
                    log(Log.INFO, TAG,
                            "MBDBG BubbleController method="
                                    + method.toGenericString());
                }
            }

            Class<?>[] nestedClasses = controllerClass.getDeclaredClasses();
            log(Log.INFO, TAG,
                    "MBDBG BubbleController nested class count="
                            + nestedClasses.length);

            for (Class<?> nested : nestedClasses) {
                log(Log.INFO, TAG,
                        "MBDBG BubbleController nested class="
                                + nested.getName());

                for (Method method : nested.getDeclaredMethods()) {
                    String lower = method.getName().toLowerCase();
                    if (lower.contains("bubble")
                            || lower.contains("expand")
                            || lower.contains("show")
                            || lower.contains("select")) {
                        log(Log.INFO, TAG,
                                "MBDBG nested method "
                                        + nested.getName()
                                        + " -> "
                                        + method.toGenericString());
                    }
                }
            }
        } catch (Throwable t) {
            logThrowable("dump BubbleController structure", t);
        }
    }

    // ==================== 诊断：Hook 所有 WMShell 接收方法 ====================
    private void hookBubbleReceiverDiagnostics(ClassLoader cl) {
        try {
            Class<?> controllerClass = cl.loadClass(
                    "com.android.wm.shell.bubbles.BubbleController");

            hookBubbleMethodsInClass(controllerClass);

            for (Class<?> nested : controllerClass.getDeclaredClasses()) {
                hookBubbleMethodsInClass(nested);
            }
        } catch (Throwable t) {
            logThrowable("hookBubbleReceiverDiagnostics", t);
        }
    }

    private void hookBubbleMethodsInClass(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            String methodName = method.getName();
            boolean relevant =
                    "showAppBubble".equals(methodName)
                            || "expandStackAndSelectBubble".equals(methodName);

            if (!relevant) {
                continue;
            }

            String signature = method.toGenericString();

            if (!mHookedBubbleMethods.add(signature)) {
                continue;
            }

            try {
                method.setAccessible(true);

                log(Log.INFO, TAG,
                        "MBDBG installing WMShell receiver hook: " + signature);

                hook(method).intercept(chain -> {
                    StringBuilder args = new StringBuilder();

                    for (int i = 0; i < method.getParameterCount(); i++) {
                        if (i > 0) args.append(", ");

                        try {
                            args.append("arg")
                                    .append(i)
                                    .append("=")
                                    .append(chain.getArg(i));
                        } catch (Throwable t) {
                            args.append("arg")
                                    .append(i)
                                    .append("=<read failed>");
                        }
                    }

                    log(Log.INFO, TAG,
                            "MBDBG WMSHELL ENTER:"
                                    + " method=" + method.toGenericString()
                                    + " this=" + chain.getThisObject()
                                    + " " + args);

                    try {
                        Object result = chain.proceed();

                        log(Log.INFO, TAG,
                                "MBDBG WMSHELL EXIT:"
                                        + " method=" + method.getName()
                                        + " result=" + result);

                        return result;
                    } catch (Throwable t) {
                        logThrowable(
                                "WMShell " + clazz.getName() + "." + method.getName(),
                                t);
                        throw t;
                    }
                });
            } catch (Throwable t) {
                logThrowable("hook WMShell method " + signature, t);
            }
        }
    }

    // ==================== 诊断：Hook BubbleData ====================
    private void hookBubbleDataDiagnostics(ClassLoader cl) {
        try {
            Class<?> dataClass = cl.loadClass(
                    "com.android.wm.shell.bubbles.BubbleData");

            for (Method method : dataClass.getDeclaredMethods()) {
                String name = method.getName().toLowerCase();

                boolean relevant =
                        name.contains("notificationentryupdated")
                                || name.contains("pendingbubble")
                                || name.contains("selectedbubble")
                                || name.contains("expand")
                                || name.contains("dismissbubble");

                if (!relevant) {
                    continue;
                }

                log(Log.INFO, TAG,
                        "MBDBG BubbleData diagnostic candidate="
                                + method.toGenericString());

                method.setAccessible(true);

                hook(method).intercept(chain -> {
                    StringBuilder args = new StringBuilder();

                    for (int i = 0; i < method.getParameterCount(); i++) {
                        if (i > 0) args.append(", ");

                        try {
                            args.append(chain.getArg(i));
                        } catch (Throwable t) {
                            args.append("<unavailable>");
                        }
                    }

                    log(Log.INFO, TAG,
                            "MBDBG BUBBLEDATA ENTER:"
                                    + " method=" + method.getName()
                                    + " args=[" + args + "]");

                    try {
                        Object result = chain.proceed();

                        log(Log.INFO, TAG,
                                "MBDBG BUBBLEDATA EXIT:"
                                        + " method=" + method.getName()
                                        + " result=" + result);

                        return result;
                    } catch (Throwable t) {
                        logThrowable("BubbleData." + method.getName(), t);
                        throw t;
                    }
                });
            }
        } catch (Throwable t) {
            logThrowable("hookBubbleDataDiagnostics", t);
        }
    }

    // ==================== 诊断：dismissBubbleWithKey 纯日志 ====================
    private void hookDismissLogger(ClassLoader cl) {
        try {
            Class<?> dataCls = cl.loadClass(
                    "com.android.wm.shell.bubbles.BubbleData");

            for (Method method : dataCls.getDeclaredMethods()) {
                if (!method.getName().equals("dismissBubbleWithKey")) {
                    continue;
                }

                method.setAccessible(true);

                hook(method).intercept(chain -> {
                    StringBuilder args = new StringBuilder();

                    for (int i = 0; i < method.getParameterCount(); i++) {
                        if (i > 0) args.append(", ");
                        args.append("arg").append(i).append("=");

                        try {
                            args.append(chain.getArg(i));
                        } catch (Throwable t) {
                            args.append("<read failed>");
                        }
                    }

                    log(Log.INFO, TAG,
                            "MBDBG DISMISS_BUBBLE:"
                                    + " method=" + method.toGenericString()
                                    + " " + args);

                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            logThrowable("install dismissBubbleWithKey logger", t);
        }
    }

    // ==================== 新增诊断：dump IBubbles Stub 结构 ====================
    private void dumpIBubblesStubStructure(ClassLoader cl) {
        try {
            Class<?> stubClass = cl.loadClass(
                    "com.android.wm.shell.bubbles.IBubbles$Stub");

            log(Log.INFO, TAG,
                    "MBDBG IBubbles Stub loaded:"
                            + " class=" + stubClass.getName()
                            + " loader=" + stubClass.getClassLoader());

            // 输出所有方法
            for (Method method : stubClass.getDeclaredMethods()) {
                log(Log.INFO, TAG,
                        "MBDBG IBubbles Stub method="
                                + method.toGenericString());
            }

            // 输出所有常量（TRANSACTION_*）
            for (Field field : stubClass.getDeclaredFields()) {
                if (field.getName().startsWith("TRANSACTION_")) {
                    try {
                        field.setAccessible(true);
                        int code = field.getInt(null);
                        log(Log.INFO, TAG,
                                "MBDBG IBubbles transaction:"
                                        + " " + field.getName()
                                        + "=" + code);
                    } catch (Throwable ignored) {
                    }
                }
            }

            for (Class<?> nested : stubClass.getDeclaredClasses()) {
                log(Log.INFO, TAG,
                        "MBDBG IBubbles Stub nested class="
                                + nested.getName());

                for (Method method : nested.getDeclaredMethods()) {
                    log(Log.INFO, TAG,
                            "MBDBG IBubbles Stub nested method="
                                    + method.toGenericString());
                }
            }
        } catch (Throwable t) {
            logThrowable("dumpIBubblesStubStructure", t);
        }
    }

    // ==================== 新增诊断：Hook IBubbles.Stub.onTransact ====================
    private void hookIBubblesStubOnTransact(ClassLoader cl) {
        try {
            Class<?> stubClass = cl.loadClass(
                    "com.android.wm.shell.bubbles.IBubbles$Stub");

            Method onTransact = findMethodSystemUi(
                    stubClass,
                    "onTransact",
                    int.class,
                    Parcel.class,
                    Parcel.class,
                    int.class);

            if (onTransact == null) {
                log(Log.INFO, TAG,
                        "MBDBG IBubbles.Stub.onTransact not found");
                return;
            }

            hook(onTransact).intercept(chain -> {
                int code = (int) chain.getArg(0);

                log(Log.INFO, TAG,
                        "MBDBG IBUBBLES_STUB ON_TRANSACT ENTER:"
                                + " code=" + code
                                + " flags=" + chain.getArg(3)
                                + " this=" + chain.getThisObject());

                try {
                    Object result = chain.proceed();

                    log(Log.INFO, TAG,
                            "MBDBG IBUBBLES_STUB ON_TRANSACT EXIT:"
                                    + " code=" + code
                                    + " result=" + result);

                    return result;
                } catch (Throwable t) {
                    logThrowable(
                            "IBubbles.Stub.onTransact code=" + code,
                            t);
                    throw t;
                }
            });

            log(Log.INFO, TAG,
                    "MBDBG IBubbles.Stub.onTransact hook installed");

        } catch (Throwable t) {
            logThrowable("hookIBubblesStubOnTransact", t);
        }
    }

    // ==================== 原有的静态辅助方法（保持不变） ====================
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
                Log.i(TAG, reason + ": skip app bubble, no target intent for " + pkg);
                collapseShadeFromManager(bubblesManager);
                return true;
            }
            if (targetIntent.getPackage() == null) {
                targetIntent.setPackage(pkg);
            }
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Object entryPoint = findEntryPoint(controller.getClass().getClassLoader(), "NOTIFICATION");
            final Intent finalIntent = targetIntent;
            final UserHandle finalUser = user;
            Runnable work = () -> {
                try {
                    Method expand = findMethodSystemUi(controller.getClass(), "expandStackAndSelectBubble",
                            Intent.class, UserHandle.class, entryPoint != null ? entryPoint.getClass() : Object.class,
                            clOrNull(controller.getClass().getClassLoader(), "com.android.wm.shell.shared.bubbles.BubbleBarLocation"));
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
                    if (expand != null) {
                        expand.invoke(controller, finalIntent, finalUser, entryPoint, null);
                        Log.i(TAG, reason + ": expanded app bubble for " + pkg);
                        runOnSysuiMain(bubblesManager, () -> dismissClickedNotificationIfAutoCancel(bubblesManager, entry));
                        runOnSysuiMain(bubblesManager, () -> collapseShadeFromManager(bubblesManager));
                    } else {
                        Log.i(TAG, reason + ": app bubble expand method not found");
                    }
                } catch (Throwable t) {
                    Log.i(TAG, reason + ": app bubble expand failed: " + t.getMessage());
                }
            };
            Object executor = getFieldSystemUi(controller, "mMainExecutor");
            Method execute = executor != null ? findMethodSystemUi(executor.getClass(), "execute", Runnable.class) : null;
            if (execute != null) execute.invoke(executor, work); else work.run();
            return true;
        } catch (Throwable t) {
            Log.i(TAG, reason + ": app bubble schedule failed: " + t.getMessage());
            return false;
        }
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
            Log.i(TAG, "notification target intent: " + t.getMessage());
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
            if (callbacks instanceof List) {
                for (Object cb : (List<?>) callbacks) {
                    Method remove = findMethodByNameAndCount(cb.getClass(), "removeNotification", 2);
                    if (remove != null) remove.invoke(cb, entry, stats);
                }
                Log.i(TAG, "dismissed clicked auto-cancel notification");
            }
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            Log.i(TAG, "dismiss clicked notification: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
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
            Log.i(TAG, "collapseShade: " + t.getMessage());
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
        } catch (Throwable t) {
            Log.i(TAG, "findEntryPoint: " + t.getMessage());
        }
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
            Field metaField = findFieldSystemUi(entry.getClass(), "mBubbleMetadata");
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
                Method getSmall = notif.getClass().getMethod("getSmallIcon");
                Object smallIcon = getSmall.invoke(notif);
                if (smallIcon != null) {
                    Method getRes = smallIcon.getClass().getMethod("getResId");
                    iconResId = (int) getRes.invoke(smallIcon);
                }
            } catch (Throwable ignore) {}
            String pkg = null;
            try {
                Method m = notif.getClass().getMethod("getPackageName");
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
                Log.i(TAG, "icon/shortcut set failed: " + t.getMessage());
                return;
            }
            Notification.BubbleMetadata metadata = builder.build();
            setNotificationBubbleMetadata(notif, metadata);
            metaField.set(entry, metadata);
            Log.i(TAG, "Set bubble metadata OK for " + pkg);
        } catch (Throwable t) {
            Log.i(TAG, "injectBubbleMetadata: " + t.getMessage());
        }
    }

    private static void setNotificationBubbleMetadata(Notification notif, Notification.BubbleMetadata metadata) {
        try {
            Method m = notif.getClass().getMethod("setBubbleMetadata", Notification.BubbleMetadata.class);
            m.invoke(notif, metadata);
            return;
        } catch (Throwable ignored) {}
        try {
            Field f = findFieldSystemUi(notif.getClass(), "mBubbleMetadata");
            if (f == null) f = findFieldSystemUi(notif.getClass(), "bubbleMetadata");
            if (f != null) {
                f.setAccessible(true);
                f.set(notif, metadata);
            }
        } catch (Throwable t) {
            Log.i(TAG, "setNotificationBubbleMetadata: " + t.getMessage());
        }
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field f = findField(obj.getClass(), name);
            if (f == null) {
                Log.i(TAG, "Field not found: " + obj.getClass().getName() + "." + name);
                return null;
            }
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            Log.i(TAG, "Read field failed: " + obj.getClass().getName() + "." + name + ": " + t.getMessage());
            return null;
        }
    }

    private static Object invoke(Object obj, String method) {
        try {
            Method m = findMethodSystemUi(obj.getClass(), method);
            return m != null ? m.invoke(obj) : null;
        } catch (Throwable t) {
            Log.i(TAG, "invoke failed: " + method + ": " + t.getMessage());
            return null;
        }
    }

    private static Field findField(Class<?> c, String n) {
        while (c != null) {
            try { return c.getDeclaredField(n); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String n, Class<?>... p) {
        while (c != null) {
            try { Method m = c.getDeclaredMethod(n, p); m.setAccessible(true); return m; } catch (NoSuchMethodException e) { c = c.getSuperclass(); }
        }
        return null;
    }

    // ==================== Launcher Hook ====================
    private void hookLauncher(PackageLoadedParam param) {
        ClassLoader cl = mLauncherClassLoader;

        // ---- 新增：Hook IBubbles$Stub$Proxy 用于确认 Binder 调用 ----
        hookLauncherBubblesProxyDiagnostics(cl);

        // ---- 新增：dump SystemUiProxy 中 bubble 相关方法 ----
        dumpSystemUiProxyBubbleMethods(cl);

        // 原有的 Hook：SystemUiProxy.showAppBubble 本身（诊断）
        try {
            Class<?> proxyClass = cl.loadClass("com.android.quickstep.SystemUiProxy");
            boolean hooked = false;

            for (Method method : proxyClass.getDeclaredMethods()) {
                if (!"showAppBubble".equals(method.getName())) {
                    continue;
                }

                log(Log.INFO, TAG,
                        "MBDBG Launcher showAppBubble method found: "
                                + method.toGenericString());

                method.setAccessible(true);
                hooked = true;

                hook(method).intercept(chain -> {
                    log(Log.INFO, TAG,
                            "MBDBG LAUNCHER ENTER SystemUiProxy.showAppBubble:"
                                    + " arg0=" + chain.getArg(0)
                                    + " arg1=" + chain.getArg(1)
                                    + " arg2=" + chain.getArg(2)
                                    + " arg3=" + chain.getArg(3));

                    try {
                        Object result = chain.proceed();

                        log(Log.INFO, TAG,
                                "MBDBG LAUNCHER EXIT SystemUiProxy.showAppBubble:"
                                        + " result=" + result
                                        + " returnType=" + method.getReturnType().getName());

                        return result;
                    } catch (Throwable t) {
                        logThrowable("Launcher hooked SystemUiProxy.showAppBubble", t);
                        throw t;
                    }
                });
            }

            log(Log.INFO, TAG,
                    "MBDBG Launcher SystemUiProxy.showAppBubble hook installed=" + hooked);
        } catch (Throwable t) {
            logThrowable("install Launcher SystemUiProxy.showAppBubble hook", t);
        }

        // 原有的 Hook：onFinishInflate
        try {
            hook(cl.loadClass("com.android.quickstep.views.OverviewActionsView")
                    .getMethod("onFinishInflate")).intercept(chain -> {
                Object ret = chain.proceed();
                try {
                    Context ctx = ((View) chain.getThisObject()).getContext();
                    if (ModuleSettings.isActionBarEnabled(ctx))
                        injectBubbleButton(chain.getThisObject(), cl);
                } catch (Throwable t) {
                    logThrowable("inject failed", t);
                }
                return ret;
            });
            log(Log.INFO, TAG, "Hooked OverviewActionsView.onFinishInflate OK");
        } catch (Throwable t) {
            logThrowable("Hook onFinishInflate", t);
        }

        // 原有的 Hook：onClick（保留但可能不会触发，因为按钮有自己的 OnClickListener）
        try {
            hook(cl.loadClass("com.android.quickstep.views.OverviewActionsView")
                    .getMethod("onClick", View.class)).intercept(chain -> {
                View v = (View) chain.getArg(0);
                log(Log.INFO, TAG,
                        "MBDBG OverviewActionsView.onClick triggered:"
                                + " clickedView=" + v
                                + " bubbleButton=" + bubbleButton);
                if (v != null && bubbleButton != null && v.getId() == bubbleButton.getId()) {
                    onBubbleButtonClick((View) chain.getThisObject());
                    return null;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "Hooked OverviewActionsView.onClick OK");
        } catch (Throwable t) {
            logThrowable("Hook onClick", t);
        }

        // 原有的 Hook：TaskMenuView.addMenuOptions
        try {
            hook(cl.loadClass("com.android.quickstep.views.TaskMenuView")
                    .getDeclaredMethod("addMenuOptions")).intercept(chain -> {
                chain.proceed();
                try {
                    Context ctx = ((View) chain.getThisObject()).getContext();
                    if (ModuleSettings.isMenuEnabled(ctx))
                        addBubbleMenuOption(chain.getThisObject(), cl);
                } catch (Throwable t) {
                    logThrowable("addBubbleMenuOption", t);
                }
                return null;
            });
            log(Log.INFO, TAG, "Hooked TaskMenuView.addMenuOptions OK");
        } catch (Throwable t) {
            logThrowable("Hook addMenuOptions", t);
        }
    }

    // ==================== 新增：Hook IBubbles$Stub$Proxy.showAppBubble ====================
    private void hookLauncherBubblesProxyDiagnostics(ClassLoader cl) {
        try {
            Class<?> proxyClass = cl.loadClass(
                    "com.android.wm.shell.bubbles.IBubbles$Stub$Proxy");

            log(Log.INFO, TAG,
                    "MBDBG loaded IBubbles Proxy:"
                            + " class=" + proxyClass.getName()
                            + " loader=" + proxyClass.getClassLoader());

            boolean found = false;

            for (Method method : proxyClass.getDeclaredMethods()) {
                String lower = method.getName().toLowerCase();

                if (lower.contains("bubble")
                        || lower.contains("show")
                        || lower.contains("expand")) {
                    log(Log.INFO, TAG,
                            "MBDBG IBubbles Proxy method="
                                    + method.toGenericString());
                }

                if (!"showAppBubble".equals(method.getName())) {
                    continue;
                }

                found = true;
                method.setAccessible(true);

                log(Log.INFO, TAG,
                        "MBDBG installing IBubbles Proxy hook: "
                                + method.toGenericString());

                hook(method).intercept(chain -> {
                    StringBuilder args = new StringBuilder();

                    for (int i = 0; i < method.getParameterCount(); i++) {
                        if (i > 0) args.append(", ");

                        try {
                            args.append("arg")
                                    .append(i)
                                    .append("=")
                                    .append(chain.getArg(i));
                        } catch (Throwable t) {
                            args.append("arg")
                                    .append(i)
                                    .append("=<unavailable>");
                        }
                    }

                    log(Log.INFO, TAG,
                            "MBDBG IBUBBLES_PROXY ENTER:"
                                    + " method=" + method.toGenericString()
                                    + " this=" + chain.getThisObject()
                                    + " " + args);

                    try {
                        Object result = chain.proceed();

                        log(Log.INFO, TAG,
                                "MBDBG IBUBBLES_PROXY EXIT:"
                                        + " result=" + result
                                        + " returnType="
                                        + method.getReturnType().getName());

                        return result;
                    } catch (Throwable t) {
                        logThrowable("IBubbles Proxy showAppBubble", t);
                        throw t;
                    }
                });
            }

            log(Log.INFO, TAG,
                    "MBDBG IBubbles Proxy showAppBubble hook installed=" + found);

        } catch (Throwable t) {
            logThrowable("hookLauncherBubblesProxyDiagnostics", t);
        }
    }

    // ==================== 新增：dump SystemUiProxy 中 bubble 相关方法 ====================
    private void dumpSystemUiProxyBubbleMethods(ClassLoader cl) {
        try {
            Class<?> proxyClass = cl.loadClass(
                    "com.android.quickstep.SystemUiProxy");

            for (Method method : proxyClass.getDeclaredMethods()) {
                String lower = method.getName().toLowerCase();

                if (lower.contains("bubble")) {
                    log(Log.INFO, TAG,
                            "MBDBG SystemUiProxy bubble method="
                                    + method.toGenericString());
                }
            }
        } catch (Throwable t) {
            logThrowable("dumpSystemUiProxyBubbleMethods", t);
        }
    }

    // ==================== 新增：检查 Binder 状态 ====================
    private void dumpBubblesBinderState(Object bubbles) {
        if (bubbles == null) {
            log(Log.INFO, TAG, "MBDBG IBubbles object is null");
            return;
        }

        try {
            Method asBinder = bubbles.getClass().getMethod("asBinder");
            Object binderObject = asBinder.invoke(bubbles);

            log(Log.INFO, TAG,
                    "MBDBG IBubbles asBinder=" + binderObject);

            if (binderObject instanceof IBinder) {
                IBinder binder = (IBinder) binderObject;

                log(Log.INFO, TAG,
                        "MBDBG IBubbles binder state:"
                                + " pingBinder=" + binder.pingBinder()
                                + " isBinderAlive=" + binder.isBinderAlive()
                                + " descriptor=" + binder.getInterfaceDescriptor());
            }
        } catch (Throwable t) {
            logThrowable("dumpBubblesBinderState", t);
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
        int positionMode = ModuleSettings.getPositionMode(ctx);
        if (positionMode == 0) {
            addToActionButtons(actionsParent, btn, res, pkg);
        } else {
            ensureSecondRow(actionsParent, btn, res, pkg);
        }
    }

    private void updateSecondRowPosition(Context ctx, FrameLayout.LayoutParams lp) {
        int posX = ModuleSettings.getPosX(ctx);
        int posY = ModuleSettings.getPosY(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;

        int maxOffset = (int)(48 * density);
        lp.bottomMargin = (int)(Math.min(posY * 1.4f, 100f) / 100f * maxOffset);

        if (sSecondRow != null && sSecondRow.getWidth() > 0) {
            applyXMargin(ctx, lp);
        } else if (sSecondRow != null) {
            sSecondRow.post(() -> {
                if (sSecondRow != null && sSecondRow.getWidth() > 0) {
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                    applyXMargin(ctx, p);
                    sSecondRow.setLayoutParams(p);
                }
            });
        }

        if (sSecondRow != null) {
            sSecondRow.setLayoutParams(lp);
            log(Log.INFO, TAG, "Position updated: X=" + posX + " Y=" + posY);
        }
    }

    private static void applyXMargin(Context ctx, FrameLayout.LayoutParams lp) {
        int posX = ModuleSettings.getPosX(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        ViewGroup parent = (ViewGroup) sSecondRow.getParent();
        if (parent == null) return;
        int parentWidth = parent.getWidth();
        int btnWidth = sSecondRow.getWidth();
        if (btnWidth <= 0 || parentWidth <= 0) return;

        int iconOffset = (int)(13 * density);
        float maxMargin = parentWidth - btnWidth;
        int marginStart = (int)((posX / 100f) * maxMargin) - iconOffset;
        lp.setMarginStart((int) Math.max(0, Math.min(marginStart, maxMargin)));
        Log.i(TAG, "applyXMargin: posX=" + posX + " marginStart=" + marginStart
                + " maxMargin=" + maxMargin + " parentW=" + parentWidth + " btnW=" + btnWidth);
    }

    private void addToActionButtons(ViewGroup actionsParent, Button btn,
            android.content.res.Resources res, String pkg) {
        if (sSecondRow != null) {
            if (bubbleButton != null) ((ViewGroup) sSecondRow).removeView(bubbleButton);
            if (((ViewGroup) sSecondRow).getChildCount() == 0) {
                actionsParent.removeView(sSecondRow);
            }
            sSecondRow = null;
        }

        int abId = res.getIdentifier("action_buttons", "id", pkg);
        LinearLayout actionButtons = (LinearLayout) actionsParent.findViewById(abId);
        if (actionButtons == null) return;

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

        if (recentsViewInstance == null) {
            try {
                Class<?> rvCls = mLauncherClassLoader.loadClass(
                        "com.android.quickstep.views.RecentsView");
                View cur = actionsParent;
                while (cur != null) {
                    if (rvCls.isInstance(cur)) {
                        recentsViewInstance = cur;
                        log(Log.INFO, TAG, "Captured RecentsView in follow mode: " + cur);
                        break;
                    }
                    cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
                }
            } catch (Throwable t) {
                logThrowable("Capture RecentsView failed", t);
            }
        }

        log(Log.INFO, TAG, "Bubble button added to action_buttons (follow mode)");
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

        int iconId = res.getIdentifier("ic_bubble_button", "drawable", pkg);
        if (iconId == 0) iconId = res.getIdentifier("ic_bubble_bar", "drawable", pkg);
        if (iconId != 0) {
            android.graphics.drawable.Drawable icon = res.getDrawable(iconId, null);
            if (icon != null) btn.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
        }

        btn.setOnClickListener(v -> {
            log(Log.INFO, TAG, "MBDBG bottom button clicked, view=" + v);
            // 传入按钮自身，而不是 parent 的 parent
            onBubbleButtonClick(v);
        });
        return btn;
    }

    @SuppressLint("DiscouragedApi")
    private void ensureSecondRow(ViewGroup actionsParent, Button btn,
            android.content.res.Resources res, String pkg) {
        Context ctx = actionsParent.getContext();

        if (sSecondRow != null && sSecondRow.getParent() == actionsParent) {
            for (int i = 0; i < ((ViewGroup) sSecondRow).getChildCount(); i++) {
                if (((ViewGroup) sSecondRow).getChildAt(i).getTag() != null
                        && "bubble_button".equals(((ViewGroup) sSecondRow).getChildAt(i).getTag().toString())) {
                    bubbleButton = ((ViewGroup) sSecondRow).getChildAt(i);
                    return;
                }
            }
            ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int spId = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
            if (spId != 0) mlp.setMarginStart(res.getDimensionPixelSize(spId));
            btn.setLayoutParams(mlp);
            ((ViewGroup) sSecondRow).addView(btn);
            bubbleButton = btn;
            return;
        }

        LinearLayout newSecondRow = new LinearLayout(ctx);
        newSecondRow.setTag("bubble_second_row");
        newSecondRow.setOrientation(LinearLayout.HORIZONTAL);

        int posX = ModuleSettings.getPosX(ctx);
        int posY = ModuleSettings.getPosY(ctx);

        float density = ctx.getResources().getDisplayMetrics().density;

        ViewGroup.MarginLayoutParams btnMlp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int spId = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
        if (spId != 0) btnMlp.setMarginStart(res.getDimensionPixelSize(spId));
        btn.setLayoutParams(btnMlp);
        newSecondRow.addView(btn);
        bubbleButton = btn;

        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.gravity = android.view.Gravity.START | android.view.Gravity.BOTTOM;

        int maxOffset = (int)(48 * density);
        int bottomOffset = (int)(Math.min(posY * 1.4f, 100f) / 100f * maxOffset);
        rowLp.bottomMargin = bottomOffset;

        int insertIndex = 0;
        int abId = res.getIdentifier("action_buttons", "id", pkg);
        View abv = actionsParent.findViewById(abId);
        if (abv != null) insertIndex = actionsParent.indexOfChild(abv) + 1;

        actionsParent.addView(newSecondRow, insertIndex, rowLp);
        sSecondRow = newSecondRow;

        newSecondRow.post(() -> {
            View abv2 = actionsParent.findViewById(
                    res.getIdentifier("action_buttons", "id", pkg));
            actionsParent.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        private int lastX = Integer.MIN_VALUE;
                        private int lastY = Integer.MIN_VALUE;

                        @Override public boolean onPreDraw() {
                            if (sSecondRow != null && sSecondRow.getWidth() > 0) {
                                int posX1 = ModuleSettings.getPosX(ctx);
                                int posY1 = ModuleSettings.getPosY(ctx);
                                if (posX1 != lastX || posY1 != lastY) {
                                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                                    int maxOffset1 = (int)(48 * ctx.getResources().getDisplayMetrics().density);
                                    p.bottomMargin = (int)(Math.min(posY1 * 1.4f, 100f) / 100f * maxOffset1);
                                    applyXMargin(ctx, p);
                                    sSecondRow.setLayoutParams(p);
                                    lastX = posX1;
                                    lastY = posY1;
                                    log(Log.INFO, TAG, "live position applied: X=" + posX1 + " Y=" + posY1);
                                }
                            }
                            if (sSecondRow != null && abv2 != null)
                                sSecondRow.setAlpha(abv2.getAlpha());
                            return true;
                        }
                    });
        });
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

            try {
                Object tv = findMethod(menuView.getClass(), "getTaskView").invoke(menuView);
                if (tv != null) {
                    recentsViewInstance = findMethod(tv.getClass(), "getRecentsView").invoke(tv);
                    log(Log.INFO, TAG, "Captured RecentsView: " + recentsViewInstance);
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
                        boolean invoked = bubbleCurrentTask(ctx, intent, task, userId);
                        log(Log.INFO, TAG, "menu invocation result=" + invoked);
                    }
                } catch (Throwable t) {
                    logThrowable("menu click", t);
                }
            });

            optionLayout.addView(menuItem);
        } catch (Throwable t) {
            logThrowable("addBubbleMenuOption", t);
        }
    }

    // ==================== 气泡触发核心逻辑 ====================

    private void onBubbleButtonClick(View sourceView) {
        Context ctx = sourceView.getContext();
        Object rv = recentsViewInstance;
        if (rv == null) rv = findRecentsViewFromHierarchy(sourceView);
        if (rv == null) {
            log(Log.INFO, TAG, "MBDBG RecentsView not found");
            return;
        }

        try {
            Object tv = findMethod(rv.getClass(), "getCurrentPageTaskView").invoke(rv);
            if (tv == null) return;
            List<?> tc = (List<?>) findMethod(tv.getClass(), "getTaskContainers").invoke(tv);
            if (tc == null || tc.isEmpty()) return;

            log(Log.INFO, TAG, "MBDBG current TaskView=" + tv);
            log(Log.INFO, TAG, "MBDBG taskContainers count=" + tc.size());
            for (int i = 0; i < tc.size(); i++) {
                Object container = tc.get(i);
                Object candidateTask = findMethod(container.getClass(), "getTask").invoke(container);
                log(Log.INFO, TAG, "MBDBG taskContainer[" + i + "]=" + container + " task=" + candidateTask);
            }

            Object task = findMethod(tc.get(0).getClass(), "getTask").invoke(tc.get(0));
            Object key = getField(task, "key");
            Intent intent = (Intent) getField(key, "baseIntent");
            int userId = getField(key, "userId") != null ? (int) getField(key, "userId") : 0;
            if (intent == null) return;

            boolean invoked = bubbleCurrentTask(ctx, intent, task, userId);
            log(Log.INFO, TAG, "MBDBG bottom button invocation result=" + invoked);
        } catch (Throwable t) {
            logThrowable("onBubbleButtonClick", t);
        }
    }

    // ==================== 修正后的 EntryPoint 选择 ====================
    private Object findLauncherEntryPoint(Class<?> entryPointClass) {
        Object[] values = entryPointClass.getEnumConstants();

        if (values == null || values.length == 0) {
            log(Log.INFO, TAG, "MBDBG EntryPoint has no enum constants");
            return null;
        }

        Object launcher = null;
        Object taskbar = null;
        Object notification = null;
        Object first = values[0];

        for (Object value : values) {
            String name;

            if (value instanceof Enum) {
                name = ((Enum<?>) value).name();
            } else {
                name = String.valueOf(value);
            }

            log(Log.INFO, TAG,
                    "MBDBG EntryPoint candidate:"
                            + " name=" + name
                            + " class=" + value.getClass().getName());

            if ("LAUNCHER_ICON_MENU".equals(name)) {
                launcher = value;
            } else if ("TASKBAR_ICON_MENU".equals(name)) {
                taskbar = value;
            } else if ("NOTIFICATION".equals(name)) {
                notification = value;
            }
        }

        Object selected;

        if (launcher != null) {
            selected = launcher;
        } else if (taskbar != null) {
            selected = taskbar;
        } else if (notification != null) {
            selected = notification;
        } else {
            selected = first;
        }

        log(Log.INFO, TAG, "MBDBG selected EntryPoint=" + selected);
        return selected;
    }

    // ==================== BubbleBarLocation 选择 ====================
    private Object findBubbleBarLocation(Class<?> locationClass) {
        if (locationClass == null) {
            log(Log.INFO, TAG, "MBDBG BubbleBarLocation class is null");
            return null;
        }

        Object[] values = locationClass.getEnumConstants();

        if (values == null || values.length == 0) {
            log(Log.INFO, TAG,
                    "MBDBG BubbleBarLocation is not enum or has no values:"
                            + locationClass.getName());
            return null;
        }

        Object defaultValue = null;
        Object rightValue = null;
        Object leftValue = null;

        for (Object value : values) {
            String name;

            if (value instanceof Enum) {
                name = ((Enum<?>) value).name();
            } else {
                name = String.valueOf(value);
            }

            log(Log.INFO, TAG,
                    "MBDBG BubbleBarLocation candidate:"
                            + " name=" + name
                            + " value=" + value);

            if ("DEFAULT".equals(name)) {
                defaultValue = value;
            } else if ("RIGHT".equals(name)) {
                rightValue = value;
            } else if ("LEFT".equals(name)) {
                leftValue = value;
            }
        }

        Object selected = defaultValue != null
                ? defaultValue
                : rightValue != null
                ? rightValue
                : leftValue != null
                ? leftValue
                : values[0];

        log(Log.INFO, TAG, "MBDBG selected BubbleBarLocation=" + selected);
        return selected;
    }

    // ==================== 核心调用方法 ====================
    private boolean bubbleCurrentTask(
            Context ctx,
            Intent taskIntent,
            Object task,
            int userId
    ) {
        long requestId = mRequestSequence.incrementAndGet();

        log(Log.INFO, TAG,
                "MBDBG REQUEST_BEGIN"
                        + " requestId=" + requestId
                        + " userId=" + userId
                        + " task=" + task
                        + " taskIntent=" + taskIntent);

        try {
            if (taskIntent == null) {
                log(Log.INFO, TAG, "MBDBG REQUEST_ABORT taskIntent is null");
                return false;
            }

            if (userId < 0) {
                log(Log.INFO, TAG, "MBDBG REQUEST_ABORT invalid userId=" + userId);
                return false;
            }

            Class<?> proxyCls = mLauncherClassLoader.loadClass(
                    "com.android.quickstep.SystemUiProxy");

            Object instanceHolder = proxyCls.getField("INSTANCE").get(null);

            log(Log.INFO, TAG,
                    "MBDBG SystemUiProxy.INSTANCE=" + instanceHolder);

            if (instanceHolder == null) {
                log(Log.INFO, TAG, "MBDBG SystemUiProxy.INSTANCE is null");
                return false;
            }

            Object proxy = instanceHolder.getClass()
                    .getMethod("get", Context.class)
                    .invoke(instanceHolder, ctx.getApplicationContext());

            log(Log.INFO, TAG,
                    "MBDBG SystemUiProxy object=" + proxy);

            if (proxy == null) {
                log(Log.INFO, TAG, "MBDBG SystemUiProxy object is null");
                return false;
            }

            dumpSystemUiProxyFields(proxy);

            // ---- 新增：检查 IBubbles Binder 状态 ----
            Object bubbles = getField(proxy, "bubbles");
            dumpBubblesBinderState(bubbles);

            // --- Intent 构造（不再强制覆盖为 topComponent） ---
            String targetPackage = taskIntent.getPackage();
            if (targetPackage == null && taskIntent.getComponent() != null) {
                targetPackage = taskIntent.getComponent().getPackageName();
            }

            Intent packageLaunchIntent = targetPackage != null
                    ? ctx.getPackageManager().getLaunchIntentForPackage(targetPackage)
                    : null;

            // 获取 topComponent 仅用于日志
            Object topComponent = invoke(task, "getTopComponent");
            log(Log.INFO, TAG,
                    "MBDBG task components:"
                            + " baseComponent=" + taskIntent.getComponent()
                            + " topComponent=" + topComponent);

            log(Log.INFO, TAG,
                    "MBDBG intent comparison:"
                            + " taskIntent=" + taskIntent
                            + " packageLaunchIntent=" + packageLaunchIntent
                            + " topComponent=" + topComponent);

            // 优先使用 packageLaunchIntent
            Intent bubbleIntent = packageLaunchIntent != null
                    ? new Intent(packageLaunchIntent)
                    : new Intent(taskIntent);

            // 确保 package 存在
            if (bubbleIntent.getPackage() == null && bubbleIntent.getComponent() != null) {
                bubbleIntent.setPackage(bubbleIntent.getComponent().getPackageName());
            }

            // 添加必要的 flags（与标准 Launcher 启动一致）
            bubbleIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            log(Log.INFO, TAG,
                    "MBDBG final bubble intent="
                            + bubbleIntent.toUri(Intent.URI_INTENT_SCHEME));
            log(Log.INFO, TAG, "MBDBG final component=" + bubbleIntent.getComponent());
            log(Log.INFO, TAG, "MBDBG final package=" + bubbleIntent.getPackage());
            log(Log.INFO, TAG, "MBDBG final flags=0x"
                    + Integer.toHexString(bubbleIntent.getFlags()));

            if (bubbleIntent.getPackage() == null) {
                log(Log.INFO, TAG, "MBDBG REQUEST_ABORT bubble intent has no package");
                return false;
            }

            // 验证可解析
            try {
                android.content.pm.ResolveInfo resolveInfo =
                        ctx.getPackageManager().resolveActivity(
                                bubbleIntent,
                                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);

                log(Log.INFO, TAG,
                        "MBDBG resolvedActivity="
                                + (resolveInfo == null
                                ? "null"
                                : resolveInfo.activityInfo.packageName
                                + "/"
                                + resolveInfo.activityInfo.name));
            } catch (Throwable t) {
                logThrowable("resolveActivity", t);
            }

            UserHandle userHandle;
            try {
                userHandle = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, userId);
            } catch (Throwable t) {
                logThrowable("UserHandle creation", t);
                return false;
            }

            log(Log.INFO, TAG, "MBDBG userHandle=" + userHandle);

            // 选择 EntryPoint
            Class<?> entryPointClass = mLauncherClassLoader.loadClass(
                    "com.android.wm.shell.shared.bubbles.logging.EntryPoint");

            Object entryPoint = findLauncherEntryPoint(entryPointClass);
            log(Log.INFO, TAG, "MBDBG selected EntryPoint=" + entryPoint);

            // 查找兼容方法
            Method target = null;
            for (Method method : proxy.getClass().getMethods()) {
                if (!method.getName().equals("showAppBubble")) {
                    continue;
                }

                log(Log.INFO, TAG,
                        "MBDBG showAppBubble candidate="
                                + method.toGenericString());

                if (method.getParameterCount() != 4) {
                    continue;
                }

                Class<?>[] p = method.getParameterTypes();

                if (!p[0].isAssignableFrom(Intent.class)) {
                    continue;
                }

                if (!p[1].isAssignableFrom(UserHandle.class)) {
                    continue;
                }

                if (entryPoint != null && !p[2].isInstance(entryPoint)) {
                    continue;
                }

                target = method;
                break;
            }

            if (target == null) {
                log(Log.INFO, TAG, "MBDBG No compatible showAppBubble method found");
                return false;
            }

            // 获取 BubbleBarLocation
            Class<?> locationClass = target.getParameterTypes()[3];
            Object bubbleBarLocation = findBubbleBarLocation(locationClass);

            Object[] args = {
                    bubbleIntent,
                    userHandle,
                    entryPoint,
                    bubbleBarLocation
            };

            log(Log.INFO, TAG,
                    "MBDBG REQUEST_DISPATCH"
                            + " requestId=" + requestId
                            + " method=" + target.toGenericString()
                            + " args=" + Arrays.toString(args));

            log(Log.INFO, TAG,
                    "MBDBG showAppBubble final arguments:"
                            + " intent=" + bubbleIntent
                            + " user=" + userHandle
                            + " entryPoint=" + entryPoint
                            + " location=" + bubbleBarLocation);

            Object result = target.invoke(proxy, args);

            log(Log.INFO, TAG,
                    "MBDBG REQUEST_WRAPPER_RETURNED"
                            + " requestId=" + requestId
                            + " result=" + result
                            + " note=asynchronous_unconfirmed");

            return true;

        } catch (InvocationTargetException e) {
            logThrowable("Launcher SystemUiProxy.showAppBubble invocation", e);
        } catch (Throwable t) {
            logThrowable("bubbleCurrentTask", t);
        }

        return false;
    }

    // ==================== 辅助诊断：dump SystemUiProxy 字段 ====================
    private void dumpSystemUiProxyFields(Object proxy) {
        if (proxy == null) return;

        for (Class<?> c = proxy.getClass();
             c != null;
             c = c.getSuperclass()) {

            for (Field field : c.getDeclaredFields()) {
                String lowerName = field.getName().toLowerCase();

                if (!lowerName.contains("proxy")
                        && !lowerName.contains("systemui")
                        && !lowerName.contains("shell")
                        && !lowerName.contains("bubble")) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = field.get(proxy);
                    log(Log.INFO, TAG,
                            "MBDBG SystemUiProxy field "
                                    + c.getName()
                                    + "."
                                    + field.getName()
                                    + "="
                                    + value);
                } catch (Throwable t) {
                    log(Log.INFO, TAG,
                            "MBDBG Cannot read proxy field "
                                    + field.getName()
                                    + ": "
                                    + t.getClass().getSimpleName()
                                    + ": "
                                    + t.getMessage());
                }
            }
        }
    }

    // ==================== 查找 RecentsView ====================
    private Object findRecentsViewFromHierarchy(View view) {
        try {
            Class<?> rvCls = mLauncherClassLoader.loadClass("com.android.quickstep.views.RecentsView");
            View cur = view;
            while (cur != null) {
                if (rvCls.isInstance(cur)) { recentsViewInstance = cur; return cur; }
                cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
            }
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
                    log(Log.INFO, TAG, "dismissed via moveToRestState");
                    return;
                }
            }
            Runtime.getRuntime().exec(new String[]{"am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"});
            log(Log.INFO, TAG, "dismissed via am start HOME");
        } catch (Throwable t) {
            logThrowable("dismiss", t);
        }
    }

    // ==================== 工具方法（静态） ====================

    private static Object getFieldSystemUi(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field f = findFieldSystemUi(obj.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeSystemUi(Object obj, String method) {
        try {
            Method m = findMethodSystemUi(obj.getClass(), method);
            return m != null ? m.invoke(obj) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findFieldSystemUi(Class<?> c, String n) {
        while (c != null) {
            try { return c.getDeclaredField(n); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        return null;
    }

    private static Method findMethodSystemUi(Class<?> c, String n, Class<?>... p) {
        while (c != null) {
            try { Method m = c.getDeclaredMethod(n, p); m.setAccessible(true); return m; } catch (NoSuchMethodException e) { c = c.getSuperclass(); }
        }
        return null;
    }

    private static void showToast(Context ctx, String msg) {
        new android.os.Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }

    // ==================== 静态方法：位置应用 ====================
    public static void applyPositionFromSettings(Context ctx) {
        if (sSecondRow == null) {
            Log.i(TAG, "applyPositionFromSettings: sSecondRow is null, settings saved for next load");
            return;
        }

        try {
            Log.i(TAG, "applyPositionFromSettings: X=" + ModuleSettings.getPosX(ctx)
                    + " Y=" + ModuleSettings.getPosY(ctx));

            if (sSecondRow.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                float density = ctx.getResources().getDisplayMetrics().density;

                int posY = ModuleSettings.getPosY(ctx);
                int maxOffset = (int)(48 * density);
                lp.bottomMargin = (int)(Math.min(posY * 1.4f, 100f) / 100f * maxOffset);

                if (sSecondRow.getWidth() > 0) {
                    applyXMargin(ctx, lp);
                } else {
                    sSecondRow.post(() -> {
                        if (sSecondRow != null && sSecondRow.getWidth() > 0) {
                            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                            applyXMargin(ctx, p);
                            sSecondRow.requestLayout();
                            Log.i(TAG, "X margin applied via post");
                        }
                    });
                }

                sSecondRow.setLayoutParams(lp);
                sSecondRow.requestLayout();
                Log.i(TAG, "Position applied: X=" + ModuleSettings.getPosX(ctx) + " Y=" + posY);
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyPositionFromSettings failed: " + t.getMessage(), t);
        }
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

    // 原有的静态字段保持
    private static final Map<String, Long> sForcedBubbleKeys = new ConcurrentHashMap<>();
    private static final long FORCED_BUBBLE_GRACE_MS = 8000L;
}