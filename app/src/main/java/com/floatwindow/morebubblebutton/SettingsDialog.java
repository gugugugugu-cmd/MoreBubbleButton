package com.floatwindow.morebubblebutton;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsDialog {

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    public static void show(Context ctx, Runnable onDismiss) {
        Log.i("MoreBubbleModule", "SettingsDialog.show called, onDismiss=" + (onDismiss != null ? "non-null" : "null"));
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        layout.setPadding(pad, dp(ctx, 16), pad, dp(ctx, 8));

        // 1. 任务卡片菜单开关
        layout.addView(createRow(ctx, "任务卡片菜单",
                "在多任务界面点击 app 图标弹出的菜单中显示「消息气泡」",
                ModuleSettings.isMenuEnabled(ctx),
                (btn, checked) -> ModuleSettings.setMenuEnabled(ctx, checked)));

        // 2. 底部操作栏开关
        layout.addView(createRow(ctx, "底部操作栏",
                "在多任务界面底部显示「消息气泡」按钮",
                ModuleSettings.isActionBarEnabled(ctx),
                (btn, checked) -> ModuleSettings.setActionBarEnabled(ctx, checked)));

        // 2.5. 通知横幅气泡按钮开关
        layout.addView(createRow(ctx, "通知横幅气泡",
                "所有应用通知横幅右下角显示气泡图标（非短信独有）",
                ModuleSettings.isSystemUiBubbleEnabled(ctx),
                (btn, checked) -> ModuleSettings.setSystemUiBubbleEnabled(ctx, checked)));

        new AlertDialog.Builder(ctx)
                .setTitle("消息气泡设置")
                .setView(layout)
                .setPositiveButton("确定", (d, w) -> {
                    Log.i("MoreBubbleModule", "Dialog positive button clicked");
                    if (onDismiss != null) onDismiss.run();
                })
                .setOnDismissListener(d -> {
                    Log.i("MoreBubbleModule", "Dialog dismissed");
                    if (onDismiss != null) onDismiss.run();
                })
                .show();
    }

    /** 给 View 添加圆角背景框 */
    private static void applyBackground(Context ctx, View view) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(ctx, 8));
        bg.setColor(0x22FFFFFF);
        bg.setStroke(dp(ctx, 1), 0x44FFFFFF);
        view.setBackground(bg);
    }

    private static TextView createSectionLabel(Context ctx, String text) {
        TextView label = new TextView(ctx);
        label.setText(text);
        label.setTextSize(14);
        label.setTextColor(0xAAFFFFFF);
        label.setPadding(0, dp(ctx, 10), 0, dp(ctx, 4));
        return label;
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private static View createRow(Context ctx, String title, String subtitle,
            boolean checked, android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(ctx.getColor(android.R.color.white));
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);

        header.addView(t);
        header.addView(sw);

        TextView s = new TextView(ctx);
        s.setText(subtitle);
        s.setTextSize(12);
        s.setTextColor(0xAAFFFFFF);
        s.setPadding(0, dp(ctx, 2), 0, 0);

        row.addView(header);
        row.addView(s);
        row.setClickable(true);
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
        return row;
    }

    private static int dp(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }
}
