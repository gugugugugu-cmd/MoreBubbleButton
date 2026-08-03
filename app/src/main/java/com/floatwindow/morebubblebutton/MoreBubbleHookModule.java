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

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class MoreBubbleHookModule extends XposedModule {
    private static final String TAG = "MoreBubbleModule";
    private Object recentsViewInstance;
    private View bubbleButton;
    private static View sSecondRow;
    private ClassLoader mLauncherClassLoader;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // 实例方法，使用 log
        log(Log.INFO, TAG, "MoreBubbleModule: " + param.getProcessName() + " | API " + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if ("com.google.android.apps.nexuslauncher".equals(pkg)
                || "com.android.launcher3".equals(pkg)) {
            mLauncherClassLoader = param.getDefaultClassLoader();
            hookLauncher(param);
        } else if ("com.android.systemui".equals(pkg)) {
            if (!param.isFirstPackage()) return;
            ClassLoader cl = param.getDefaultClassLoader();
            hookSystemUi(cl);
        }
    }

    private ClassLoader mSystemUiClassLoader;
    private static final Map<String, Long> sForcedBubbleKeys = new ConcurrentHashMap<>();
    private static final long FORCED_BUBBLE_GRACE_MS = 8000L;

    // 实例方法
    private void hookSystemUi(ClassLoader cl) {
        log(Log.INFO, TAG, "Hooking SystemUI...");
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
            log(Log.INFO, TAG, "Hook shouldShowBubbleButton: " + t.getMessage());
        }

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
            log(Log.INFO, TAG, "Hook RowBinder: " + t.getMessage());
        }

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
                        log(Log.INFO, TAG, "expand guard: " + t.getMessage());
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "Hooked BubblesManager.expandStackAndSelectBubble OK");
            } else {
                log(Log.INFO, TAG, "BubblesManager.expandStackAndSelectBubble(NotificationEntry) not found");
            }
        } catch (Throwable t) {
            log(Log.INFO, TAG, "Hook BubblesManager: " + t.getMessage());
        }

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
                        log(Log.INFO, TAG, "user change bubble: " + t.getMessage());
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "Hooked BubblesManager.onUserChangedBubble OK");
            } else {
                log(Log.INFO, TAG, "BubblesManager.onUserChangedBubble(NotificationEntry, boolean) not found");
            }
        } catch (Throwable t) {
            log(Log.INFO, TAG, "Hook onUserChangedBubble: " + t.getMessage());
        }

        try {
            Class<?> dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData");
            for (Method dm : dataCls.getDeclaredMethods()) {
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
                                log(Log.INFO, TAG, "keep forced bubble: skip dismiss reason=" + reason + " key=" + key);
                                return null;
                            }
                        } catch (Throwable t) {
                            log(Log.INFO, TAG, "dismiss guard: " + t.getMessage());
                        }
                        return chain.proceed();
                    });
                }
            }
            log(Log.INFO, TAG, "Hooked BubbleData.dismissBubbleWithKey OK");
        } catch (Throwable t) {
            log(Log.INFO, TAG, "Hook dismiss guard: " + t.getMessage());
        }

        try {
            Class<?> dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData");
            Method m = findMethodSystemUi(dataCls, "setSelectedBubbleInternal");
            if (m != null) {
                hook(m).intercept(chain -> {
                    try {
                        Object provider = chain.getArg(0);
                        Object bubbleData = chain.getThisObject();
                        if (provider != null && provider.getClass().getName().endsWith("BubbleEntry")) {
                            Field f = findFieldSystemUi(bubbleData.getClass(), "mBubbles");
                            if (f != null) {
                                f.setAccessible(true);
                                List list = (List) f.get(bubbleData);
                                if (list != null && !list.contains(provider)) {
                                    list.add(provider);
                                    log(Log.INFO, TAG, "BubbleData.mBubbles forcibly added BubbleEntry");
                                }
                            }
                        }
                    } catch (Throwable t) {
                        log(Log.INFO, TAG, "select guard: " + t.getMessage());
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "Hooked setSelectedBubbleInternal OK");
            }
        } catch (Throwable t) {
            log(Log.INFO, TAG, "Hook select guard: " + t.getMessage());
        }

        try {
            Class<?> controllerClass = cl.loadClass("com.android.wm.shell.bubbles.BubbleController");
            boolean found = false;
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!method.getName().equals("expandStackAndSelectBubble")) continue;
                log(Log.INFO, TAG, "BubbleController candidate=" + method.toGenericString());
                if (method.getParameterCount() == 4
                        && method.getParameterTypes()[0] == Intent.class) {
                    found = true;
                    hook(method).intercept(chain -> {
                        log(Log.INFO, TAG, "SYSTEMUI RECEIVED expandStackAndSelectBubble:"
                                + " intent=" + chain.getArg(0)
                                + " user=" + chain.getArg(1)
                                + " entryPoint=" + chain.getArg(2)
                                + " location=" + chain.getArg(3));
                        try {
                            Object result = chain.proceed();
                            log(Log.INFO, TAG, "SYSTEMUI expandStackAndSelectBubble returned=" + result);
                            return result;
                        } catch (Throwable t) {
                            log(Log.INFO, TAG, "SYSTEMUI expandStackAndSelectBubble failed: " + t.getMessage());
                            throw t;
                        }
                    });
                }
            }
            log(Log.INFO, TAG, "BubbleController receiver hook installed=" + found);
        } catch (Throwable t) {
            log(Log.INFO, TAG, "Hook BubbleController receiver failed: " + t.getMessage());
        }

        log(Log.INFO, TAG, "All SystemUI hooks installed");
    }

    // 静态方法 -> 使用 Log.i
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

    // 实例方法
    private void hookLauncher(PackageLoadedParam param) {
        ClassLoader cl = mLauncherClassLoader;

        try {
            hook(cl.loadClass("com.android.quickstep.views.OverviewActionsView")
                    .getMethod("onFinishInflate")).intercept(chain -> {
                Object ret = chain.proceed();
                try {
                    Context ctx = ((View) chain.getThisObject()).getContext();
                    if (ModuleSettings.isActionBarEnabled(ctx))
                        injectBubbleButton(chain.getThisObject(), cl);
                } catch (Throwable t) {
                    log(Log.INFO, TAG, "inject failed: " + t.getMessage());
                }
                return ret;
            });
        } catch (Throwable t) {
            log(Log.INFO, TAG, "Hook onFinishInflate: " + t.getMessage());
        }

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
        } catch (Throwable t) {
            log(Log.INFO, TAG, "Hook onClick: " + t.getMessage());
        }

        try {
            hook(cl.loadClass("com.android.quickstep.views.TaskMenuView")
                    .getDeclaredMethod("addMenuOptions")).intercept(chain -> {
                chain.proceed();
                try {
                    Context ctx = ((View) chain.getThisObject()).getContext();
                    if (ModuleSettings.isMenuEnabled(ctx))
                        addBubbleMenuOption(chain.getThisObject(), cl);
                } catch (Throwable t) {
                    log(Log.INFO, TAG, "addBubbleMenuOption: " + t.getMessage());
                }
                return null;
            });
        } catch (Throwable t) {
            log(Log.INFO, TAG, "Hook addMenuOptions: " + t.getMessage());
        }
    }

    // ==================== 操作栏按钮（实例方法，使用 log） ====================

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

    // 静态工具方法（被静态和实例共用，内部使用 Log.i）
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
                log(Log.INFO, TAG, "Capture RecentsView failed: " + t.getMessage());
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

        btn.setOnClickListener(v -> onBubbleButtonClick((View) btn.getParent().getParent()));
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

    // ==================== 菜单项注入（实例方法） ====================

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
                    log(Log.INFO, TAG, "menu click: " + t.getMessage());
                }
            });

            optionLayout.addView(menuItem);
        } catch (Throwable t) {
            log(Log.INFO, TAG, "addBubbleMenuOption: " + t.getMessage());
        }
    }

    // ==================== 气泡触发（实例方法） ====================

    private void onBubbleButtonClick(View actionsView) {
        Context ctx = actionsView.getContext();
        Object rv = recentsViewInstance;
        if (rv == null) rv = findRecentsViewFromHierarchy(actionsView);
        if (rv == null) {
            log(Log.INFO, TAG, "RecentsView not found");
            return;
        }

        try {
            Object tv = findMethod(rv.getClass(), "getCurrentPageTaskView").invoke(rv);
            if (tv == null) return;
            List<?> tc = (List<?>) findMethod(tv.getClass(), "getTaskContainers").invoke(tv);
            if (tc == null || tc.isEmpty()) return;

            log(Log.INFO, TAG, "current TaskView=" + tv);
            log(Log.INFO, TAG, "taskContainers count=" + tc.size());
            for (int i = 0; i < tc.size(); i++) {
                Object container = tc.get(i);
                Object candidateTask = findMethod(container.getClass(), "getTask").invoke(container);
                log(Log.INFO, TAG, "taskContainer[" + i + "]=" + container + " task=" + candidateTask);
            }

            Object task = findMethod(tc.get(0).getClass(), "getTask").invoke(tc.get(0));
            Object key = getField(task, "key");
            Intent intent = (Intent) getField(key, "baseIntent");
            int userId = getField(key, "userId") != null ? (int) getField(key, "userId") : 0;
            if (intent == null) return;

            boolean invoked = bubbleCurrentTask(ctx, intent, task, userId);
            log(Log.INFO, TAG, "bottom button invocation result=" + invoked);
        } catch (Throwable t) {
            log(Log.INFO, TAG, "onBubbleButtonClick: " + t.getMessage());
        }
    }

    // 实例方法 - 使用 log
    private boolean bubbleCurrentTask(
            Context ctx,
            Intent taskIntent,
            Object task,
            int userId
    ) {
        try {
            log(Log.INFO, TAG, "bubbleCurrentTask begin:"
                    + " userId=" + userId
                    + " task=" + task
                    + " taskIntent="
                    + (taskIntent == null
                    ? "null"
                    : taskIntent.toUri(Intent.URI_INTENT_SCHEME)));

            if (taskIntent == null) {
                log(Log.INFO, TAG, "bubbleCurrentTask: taskIntent is null");
                return false;
            }

            if (userId < 0) {
                log(Log.INFO, TAG, "bubbleCurrentTask: invalid userId=" + userId);
                return false;
            }

            Class<?> proxyCls = mLauncherClassLoader.loadClass(
                    "com.android.quickstep.SystemUiProxy");

            Object instanceHolder = proxyCls.getField("INSTANCE").get(null);

            log(Log.INFO, TAG, "SystemUiProxy.INSTANCE=" + instanceHolder);

            if (instanceHolder == null) {
                log(Log.INFO, TAG, "SystemUiProxy.INSTANCE is null");
                return false;
            }

            Object proxy = instanceHolder.getClass()
                    .getMethod("get", Context.class)
                    .invoke(instanceHolder, ctx.getApplicationContext());

            log(Log.INFO, TAG, "SystemUiProxy object=" + proxy);
            log(Log.INFO, TAG, "SystemUiProxy runtime class="
                    + (proxy == null ? "null" : proxy.getClass().getName()));

            if (proxy == null) {
                log(Log.INFO, TAG, "SystemUiProxy object is null");
                return false;
            }

            dumpSystemUiProxyFields(proxy);

            Intent bubbleIntent = new Intent(taskIntent);

            if (bubbleIntent.getPackage() == null
                    && bubbleIntent.getComponent() != null) {
                bubbleIntent.setPackage(
                        bubbleIntent.getComponent().getPackageName());
            }

            try {
                Object topComponent = invoke(task, "getTopComponent");

                log(Log.INFO, TAG, "task topComponent=" + topComponent);

                if (topComponent instanceof ComponentName) {
                    ComponentName component = (ComponentName) topComponent;
                    bubbleIntent.setComponent(component);

                    if (bubbleIntent.getPackage() == null) {
                        bubbleIntent.setPackage(component.getPackageName());
                    }
                }
            } catch (Throwable t) {
                log(Log.INFO, TAG, "getTopComponent failed: " + t.getMessage());
            }

            log(Log.INFO, TAG, "final bubble intent="
                    + bubbleIntent.toUri(Intent.URI_INTENT_SCHEME));
            log(Log.INFO, TAG, "final component=" + bubbleIntent.getComponent());
            log(Log.INFO, TAG, "final package=" + bubbleIntent.getPackage());
            log(Log.INFO, TAG, "final flags=0x"
                    + Integer.toHexString(bubbleIntent.getFlags()));

            if (bubbleIntent.getPackage() == null) {
                log(Log.INFO, TAG, "bubble intent has no package");
                return false;
            }

            try {
                android.content.pm.ResolveInfo resolveInfo =
                        ctx.getPackageManager().resolveActivity(
                                bubbleIntent,
                                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);

                log(Log.INFO, TAG, "resolvedActivity="
                        + (resolveInfo == null
                        ? "null"
                        : resolveInfo.activityInfo.packageName
                        + "/"
                        + resolveInfo.activityInfo.name));
            } catch (Throwable t) {
                log(Log.INFO, TAG, "resolveActivity failed: " + t.getMessage());
            }

            UserHandle userHandle;
            try {
                userHandle = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, userId);
            } catch (Throwable t) {
                log(Log.INFO, TAG, "Failed to create UserHandle via reflection: " + t.getMessage());
                return false;
            }

            log(Log.INFO, TAG, "userHandle=" + userHandle);

            Class<?> entryPointClass = mLauncherClassLoader.loadClass(
                    "com.android.wm.shell.shared.bubbles.logging.EntryPoint");

            Object entryPoint = findLauncherEntryPoint(entryPointClass);

            log(Log.INFO, TAG, "selected EntryPoint=" + entryPoint);

            Method target = null;

            for (Method method : proxy.getClass().getMethods()) {
                if (!method.getName().equals("showAppBubble")) {
                    continue;
                }

                log(Log.INFO, TAG, "showAppBubble candidate="
                        + method.toGenericString()
                        + " returnType="
                        + method.getReturnType().getName()
                        + " params="
                        + Arrays.toString(method.getParameterTypes()));

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

                if (entryPoint != null
                        && !p[2].isInstance(entryPoint)) {
                    continue;
                }

                target = method;
                break;
            }

            if (target == null) {
                log(Log.INFO, TAG, "No compatible showAppBubble method found");
                return false;
            }

            Object[] args = {
                    bubbleIntent,
                    userHandle,
                    entryPoint,
                    null
            };

            log(Log.INFO, TAG, "Invoking method=" + target.toGenericString());
            log(Log.INFO, TAG, "Invocation args=" + Arrays.toString(args));

            Object result = target.invoke(proxy, args);

            log(Log.INFO, TAG, "showAppBubble wrapper returned=" + result
                    + "; returnType=" + target.getReturnType().getName()
                    + "; this does NOT confirm bubble creation");

            return true;

        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            log(Log.INFO, TAG, "showAppBubble target exception: " + cause.getMessage());
        } catch (Throwable t) {
            log(Log.INFO, TAG, "bubbleCurrentTask failed: " + t.getMessage());
        }

        return false;
    }

    // 实例方法
    private Object findLauncherEntryPoint(Class<?> entryPointClass) {
        Object[] values = entryPointClass.getEnumConstants();
        if (values == null || values.length == 0) {
            log(Log.INFO, TAG, "EntryPoint has no enum constants");
            return null;
        }

        Object notificationFallback = null;
        Object firstFallback = values[0];

        for (Object value : values) {
            String name = String.valueOf(value);
            log(Log.INFO, TAG, "EntryPoint candidate=" + name);

            if ("OVERVIEW".equals(name)
                    || "RECENTS".equals(name)
                    || "LAUNCHER".equals(name)
                    || "TASKBAR".equals(name)) {
                return value;
            }

            if ("NOTIFICATION".equals(name)) {
                notificationFallback = value;
            }
        }

        return notificationFallback != null ? notificationFallback : firstFallback;
    }

    // 实例方法
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
                    log(Log.INFO, TAG, "SystemUiProxy field "
                            + c.getName()
                            + "."
                            + field.getName()
                            + "="
                            + value);
                } catch (Throwable t) {
                    log(Log.INFO, TAG, "Cannot read proxy field "
                            + field.getName()
                            + ": "
                            + t.getClass().getSimpleName()
                            + ": "
                            + t.getMessage());
                }
            }
        }
    }

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
            log(Log.INFO, TAG, "dismiss: " + t.getMessage());
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

    /**
     * 静态方法：从模块 Activity 调用，重新应用位置设置
     * 静态方法中无法使用实例 log，保留 android.util.Log 仅用于 logcat（不影响 LSPosed 主日志）
     */
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
}