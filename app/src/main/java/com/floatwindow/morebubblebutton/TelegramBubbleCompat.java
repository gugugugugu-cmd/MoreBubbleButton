package com.floatwindow.morebubblebutton;

import android.content.Intent;
import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Telegram 进程内的兼容层。
 *
 * <p>Telegram 自带 {@code org.telegram.ui.BubbleActivity}（allowEmbedded + resizeableActivity），
 * 但它只接受 {@code com.tmessages.openchat*} 这条 action：
 *
 * <pre>
 *   action = intent.getAction()
 *   if (!action.startsWith("com.tmessages.openchat")) {
 *       if (p == null) finish();     // ← 从最近任务/手势进来时会直接自杀
 *   }
 * </pre>
 *
 * <p>没有会话上下文（没有通知来源）时，模块会在传入的 Intent 上加 {@code mbb_no_chat}。
 * 这里在 {@code BubbleActivity.onCreate} 之后补一个空会话参数，让它走正常分支，
 * 显示一个空的聊天界面而不是闪退。
 *
 * <p>只处理带我们自己标记的 Intent，不影响 Telegram 自身启动气泡的行为。
 */
public class TelegramBubbleCompat extends XposedModule {
    private static final String TAG = "MoreBubbleTgCompat";
    private static final String EXTRA_FLAG = "mbb_no_chat";

    @Override
    public void onModuleLoaded(io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam param) {
        Log.i(TAG, "loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!pkg.startsWith("org.telegram.")) return;
        ClassLoader cl = param.getDefaultClassLoader();
        try {
            Class<?> bubbleActivity = cl.loadClass("org.telegram.ui.BubbleActivity");
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : bubbleActivity.getDeclaredMethods()) {
                if (!"y".equals(m.getName()) || m.getParameterCount() != 5) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p[0] == Intent.class && p[1] == boolean.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                Log.w(TAG, "BubbleActivity.y(Intent,boolean,boolean,boolean,int) not found");
                return;
            }
            hook(target).intercept(chain -> {
                try {
                    Intent intent = (Intent) chain.getArg(0);
                    if (intent != null && intent.getBooleanExtra(EXTRA_FLAG, false)) {
                        // 没有 chatId：给出 action 和 0 号会话，让它走正常分支而不是 finish()
                        intent.setAction("com.tmessages.openchat");
                        intent.putExtra("chatId", 0L);
                        Log.i(TAG, "relaxed bubble: injected openchat action");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "relax failed: " + t.getMessage());
                }
                return chain.proceed();
            });
            Log.i(TAG, "hooked BubbleActivity.y for " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "hook failed: " + t.getMessage());
        }
    }
}
