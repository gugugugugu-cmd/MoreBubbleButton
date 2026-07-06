package com.floatwindow.morebubblebutton;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends android.app.Activity {
    private final SharedPreferences prefs = getSharedPreferences(ModuleSettings.PREFS_NAME, MODE_PRIVATE);

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(16), pad, dp(16));

        // 任务卡片菜单
        layout.addView(createRow("任务卡片菜单", "在多任务界面点击 app 图标弹出的菜单中显示「消息气泡」",
                ModuleSettings.isMenuEnabled(this), v -> ModuleSettings.setMenuEnabled(this, !ModuleSettings.isMenuEnabled(this))));

        // 底部操作栏
        layout.addView(createRow("底部操作栏", "在多任务界面底部显示「消息气泡」按钮",
                ModuleSettings.isActionBarEnabled(this), v -> ModuleSettings.setActionBarEnabled(this, !ModuleSettings.isActionBarEnabled(this))));

        // 通知横幅气泡
        layout.addView(createRow("通知横幅气泡", "所有应用通知横幅右下角显示气泡图标",
                ModuleSettings.isSystemUiBubbleEnabled(this), v -> ModuleSettings.setSystemUiBubbleEnabled(this, !ModuleSettings.isSystemUiBubbleEnabled(this))));

        // 分隔标题：位置
        layout.addView(createSectionLabel("位置微调"));

        // X 轴
        layout.addView(createSlider("X 轴（← 左 | 右 →）", ModuleSettings.getPosX(this), v -> {
            ModuleSettings.setPosX(this, Integer.parseInt(((TextView)v).getText().toString()));
            MoreBubbleHookModule.applyPositionFromSettings(this);
        }));

        // Y 轴
        layout.addView(createSlider("Y 轴（↑ 上 | 下 ↓）", ModuleSettings.getPosY(this), v -> {
            ModuleSettings.setPosY(this, Integer.parseInt(((TextView)v).getText().toString()));
            MoreBubbleHookModule.applyPositionFromSettings(this);
        }));

        // 重启启动器按钮
        layout.addView(createRestartButton());

        setContentView(layout);
        setTitle("消息气泡设置");
    }

    private View createRow(String title, String summary, boolean checked, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(getColor(android.R.color.white));
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnClickListener(listener);

        header.addView(t);
        header.addView(sw);

        TextView s = new TextView(this);
        s.setText(summary);
        s.setTextSize(12);
        s.setTextColor(0xAAFFFFFF);

        row.addView(header);
        row.addView(s);
        return row;
    }

    private View createSectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(14);
        label.setTextColor(0xAAFFFFFF);
        label.setPadding(0, dp(10), 0, dp(4));
        return label;
    }

    private View createSlider(String label, int curValue, final android.view.View.OnClickListener resetListener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(13);
        lbl.setTextColor(0xCCFFFFFF);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setTextSize(12);
        val.setTextColor(0xAAFFFFFF);
        val.setText(curValue + "%");

        header.addView(lbl);
        header.addView(val);
        row.addView(header);

        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView minus = new TextView(this);
        minus.setText("−");
        minus.setTextSize(20);
        minus.setTextColor(getColor(android.R.color.white));
        minus.setPadding(dp(12), dp(4), dp(12), dp(4));
        minus.setGravity(Gravity.CENTER);

        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(curValue);
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextSize(20);
        plus.setTextColor(getColor(android.R.color.white));
        plus.setPadding(dp(12), dp(4), dp(12), dp(4));
        plus.setGravity(Gravity.CENTER);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    val.setText(progress + "%");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        minus.setOnClickListener(v -> {
            int next = Math.max(0, bar.getProgress() - 1);
            bar.setProgress(next);
            val.setText(next + "%");
        });
        plus.setOnClickListener(v -> {
            int next = Math.min(100, bar.getProgress() + 1);
            bar.setProgress(next);
            val.setText(next + "%");
        });

        sliderRow.addView(minus);
        sliderRow.addView(bar);
        sliderRow.addView(plus);
        row.addView(sliderRow);
        return row;
    }

    private View createRestartButton() {
        TextView btn = new TextView(this);
        btn.setText("重启启动器");
        btn.setTextSize(15);
        btn.setTextColor(getColor(android.R.color.holo_red_light));
        btn.setPadding(dp(16), dp(12), dp(16), dp(12));
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
        btn.setPadding(dp(16), dp(12), dp(16), dp(12));
        btn.setOnClickListener(v -> {
            new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager)
                            getSystemService(Context.ACTIVITY_SERVICE);
                    for (android.app.ActivityManager.RunningAppProcessInfo proc : am.getRunningAppProcesses()) {
                        if (proc.processName.contains("nexuslauncher") || proc.processName.contains("launcher3")) {
                            android.os.Process.killProcess(proc.pid);
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }, 300);
        });
        return btn;
    }

    private int dp(int d) { return (int)(d * getResources().getDisplayMetrics().density); }
}