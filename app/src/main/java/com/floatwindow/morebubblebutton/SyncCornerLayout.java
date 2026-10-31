package com.floatwindow.morebubblebutton;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 气泡小窗底部按钮容器：官方「关闭小窗」（左）与自造「展开小窗」（右）放进同一个普通
 * {@link ViewGroup}，成为同一个父 View 的两个 child。
 *
 * <p>为什么还要自己对齐：官方对小窗做上滑 / 收起动画时，推的是「管理按钮那一行」——
 * 也就是改官方按钮的 position（layout）+ visibility，而不是只改容器。克隆按钮是另一颗 View，
 * 不会自动跟过去，于是会被小窗边界裁掉一半、右侧留在原地。所以每次官方按钮 layout 变化
 * （{@link OnLayoutChangeListener}，在 draw 之前同步回调），立刻把右侧按钮摆到同一行同一位置。
 *
 * <p>为什么容器自己量高度：官方 {@code BubbleExpandedView} 是垂直 LinearLayout，
 * 传给 addView 的布局参数不一定是 {@code LinearLayout.LayoutParams}，它拿不到高度时这一行会算成 0，
 * 两颗按钮一起消失。容器自己按「两颗按钮里最高的那颗」量出高度往父级报，不指望父级给。
 *
 * <p>为什么克隆按钮要套官方按钮的最小宽高：官方按钮尺寸来自布局文件，克隆体没有，
 * 只给 WRAP_CONTENT 时浅色胶囊背景只包住文字，看起来像「样式变了」。
 */
class SyncCornerLayout extends ViewGroup {
    View primary;   // 官方 mManageButton（关闭小窗）
    View secondary; // 隐藏的尺寸模板（只用来量文字宽度）
    View tertiary;  // 自造按钮（展开小窗）

    SyncCornerLayout(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
    }

    void bind(View primary, View secondary, View tertiary) {
        this.primary = primary;
        this.secondary = secondary;
        this.tertiary = tertiary;
        addView(primary);
        if (secondary != null) {
            // 模板只用于测量：全透明、放在原位，即使被画出来用户也看不到。
            secondary.setAlpha(0f);
            secondary.setVisibility(VISIBLE);
            addView(secondary);
        }
        if (tertiary != null) addView(tertiary);
        matchPrimarySize();
        if (primary != null && tertiary != null) {
            // 官方按钮每次被摆位置 / 改显隐都会走这里，克隆按钮在同一帧内跟上。
            primary.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                        int ol, int ot, int or, int ob) {
                    syncTertiary();
                }
            });
            // 官方按钮可能在容器布局之后才被摆到位（它是父级直接摆放的子 View），下一帧再对一次。
            primary.post(new Runnable() {
                @Override public void run() { syncTertiary(); }
            });
        }
        requestLayout();
    }

    /** 克隆按钮套用官方按钮的尺寸：最小宽度 + 高度，胶囊背景才不会只包住文字。 */
    private void matchPrimarySize() {
        if (primary == null) return;
        primary.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int w = primary.getMeasuredWidth();
        int h = primary.getMeasuredHeight();
        if (w <= 0) return;
        if (primary.getMinimumWidth() < w) primary.setMinimumWidth(w);
        if (tertiary != null) {
            if (tertiary.getMinimumWidth() < w) tertiary.setMinimumWidth(w);
            if (h > 0 && tertiary instanceof android.widget.TextView) {
                ((android.widget.TextView) tertiary).setMinHeight(h);
            }
        }
    }

    /** 把「展开小窗」摆到和官方按钮同一行：同一个 top、同一个高度、同一条底边。 */
    private void syncTertiary() {
        if (primary == null || tertiary == null) return;
        int width = getWidth();
        if (width <= 0) return;
        int bLeft = primary.getLeft();
        int bTop = primary.getTop();
        int bWidth = primary.getWidth();
        int bHeight = primary.getHeight();
        if (bWidth > 0 && tertiary.getMinimumWidth() < bWidth) tertiary.setMinimumWidth(bWidth);
        int tw = Math.max(tertiary.getMeasuredWidth(), primary.getMeasuredWidth());
        int th = Math.max(tertiary.getMeasuredHeight(), bHeight);
        int left = width - padEnd() - tw;
        if (left < 0) left = 0;
        int top = bTop + (bHeight - th); // 底边对齐
        if (tertiary.getLeft() != left || tertiary.getTop() != top
                || tertiary.getWidth() != tw || tertiary.getHeight() != th) {
            tertiary.layout(left, top, left + tw, top + th);
        }
        int vis = primary.getVisibility();
        if (tertiary.getVisibility() != vis) tertiary.setVisibility(vis);
    }

    /**
     * 父级是 LinearLayout 时，子 View 高度 WRAP_CONTENT 会被「两次测量」撑满剩余空间。
     * 这里量完给子 View 补上同样的标记，子 View 的布局参数保持最普通的那种。
     */
    private static void markMeasuredOnce(View v) {
        try {
            java.lang.reflect.Field f = View.class.getDeclaredField("mPrivateFlags3");
            f.setAccessible(true);
            int flags = (int) f.get(v);
            f.setInt(v, flags | 0x8); // View.PFLAG3_MEASURE_NEEDED_BEFORE_LAYOUT
        } catch (Throwable ignored) {
        }
    }

    int padStart() { return dp(20); }
    int padEnd() { return dp(14); }

    /** 底部一行需要的总高度（两颗按钮取大者）。 */
    int rowHeight() {
        int h = 0;
        if (primary != null) h = Math.max(h, primary.getMeasuredHeight());
        if (tertiary != null) h = Math.max(h, tertiary.getMeasuredHeight());
        return h;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int wChild = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST);
        int hChild = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        if (primary != null) { primary.measure(wChild, hChild); markMeasuredOnce(primary); }
        if (secondary != null) secondary.measure(wChild, hChild);
        if (tertiary != null) { tertiary.measure(wChild, hChild); markMeasuredOnce(tertiary); }
        int height = getPaddingTop() + rowHeight() + getPaddingBottom();
        setMeasuredDimension(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;
        if (primary != null) {
            int w = primary.getMeasuredWidth();
            int h = primary.getMeasuredHeight();
            primary.layout(padStart(), height - h, padStart() + w, height);
        }
        if (secondary != null) {
            secondary.layout(0, 0, secondary.getMeasuredWidth(), secondary.getMeasuredHeight());
        }
        if (tertiary != null) {
            int tw = tertiary.getMeasuredWidth();
            int th = tertiary.getMeasuredHeight();
            tertiary.layout(width - padEnd() - tw, height - th, width - padEnd(), height);
        }
        syncTertiary();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (primary != null && primary.getVisibility() != visibility) primary.setVisibility(visibility);
        if (tertiary != null && tertiary.getVisibility() != visibility) tertiary.setVisibility(visibility);
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
