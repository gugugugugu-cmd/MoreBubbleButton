package com.floatwindow.morebubblebutton;

import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(ModuleSettings.PREFS_NAME, MODE_PRIVATE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(16), dp(20), dp(16));

        // 任务卡片菜单
        layout.addView(createSwitch("任务卡片菜单",
                "在多任务界面点击 app 图标弹出的菜单中显示「消息气泡」",
                ModuleSettings.KEY_MENU_ENABLED,
                ModuleSettings.isMenuEnabled(this)));

        // 底部操作栏
        layout.addView(createSwitch("底部操作栏",
                "在多任务界面底部显示「消息气泡」按钮",
                ModuleSettings.KEY_ACTION_BAR_ENABLED,
                ModuleSettings.isActionBarEnabled(this)));

        // 通知横幅气泡
        layout.addView(createSwitch("通知横幅气泡",
                "所有应用通知横幅右下角显示气泡图标",
                ModuleSettings.KEY_SYSTEMUI_BUBBLE_ENABLED,
                ModuleSettings.isSystemUiBubbleEnabled(this)));

        // 分隔标题：位置
        layout.addView(createSectionLabel("位置微调"));

        // X 轴
        layout.addView(createSlider("X 轴", ModuleSettings.KEY_POS_X, ModuleSettings.getPosX(this)));

        // Y 轴
        layout.addView(createSlider("Y 轴", ModuleSettings.KEY_POS_Y, ModuleSettings.getPosY(this)));

        // 重启启动器按钮
        layout.addView(createRestartButton());

        setContentView(layout);
        getSupportActionBar().setTitle("消息气泡设置");
    }

    private View createSwitch(String title, String summary, String key, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(getColor(android.R.color.white));
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).apply();
        });

        header.addView(t);
        header.addView(sw);

        TextView s = new TextView(this);
        s.setText(summary);
        s.setTextSize(12);
        s.setTextColor(0xAAFFFFFF);

        row.addView(header);
        row.addView(s);
        row.setClickable(true);
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
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

    private View createSlider(String label, String key, int defaultValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(13);
        lbl.setTextColor(0xCCFFFFFF);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setTextSize(12);
        val.setTextColor(0xAAFFFFFF);
        val.setText(defaultValue + "%");

        header.addView(lbl);
        header.addView(val);
        row.addView(header);

        // [-] 滑动条 [+] 行
        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView minus = new TextView(this);
        minus.setText("−");
        minus.setTextSize(20);
        minus.setTextColor(getColor(android.R.color.white));
        minus.setPadding(dp(12), dp(4), dp(12), dp(4));
        minus.setGravity(android.view.Gravity.CENTER);

        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(defaultValue);
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextSize(20);
        plus.setTextColor(getColor(android.R.color.white));
        plus.setPadding(dp(12), dp(4), dp(12), dp(4));
        plus.setGravity(android.view.Gravity.CENTER);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    prefs.edit().putInt(key, progress).apply();
                    val.setText(progress + "%");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        minus.setOnClickListener(v -> {
            int next = Math.max(0, bar.getProgress() - 1);
            bar.setProgress(next);
            prefs.edit().putInt(key, next).apply();
            val.setText(next + "%");
        });
        plus.setOnClickListener(v -> {
            int next = Math.min(100, bar.getProgress() + 1);
            bar.setProgress(next);
            prefs.edit().putInt(key, next).apply();
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
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setOnClickListener(v -> {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager)
                            getSystemService(ACTIVITY_SERVICE);
                    for (android.app.ActivityManager.RunningAppProcessInfo proc : am.getRunningAppProcesses()) {
                        if (proc.processName.contains("nexuslauncher") || proc.processName.contains("launcher3")) {
                            android.os.Process.killProcess(proc.pid);
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                    Toast.makeText(this, "重启失败，请手动重启启动器", Toast.LENGTH_SHORT).show();
                }
            }, 300);
        });
        return btn;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}