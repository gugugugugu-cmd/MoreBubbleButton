package com.floatwindow.morebubblebutton;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 气泡小窗底部按钮容器：「关闭小窗」（左）与「展开小窗」（右）放进同一个普通
 * {@link ViewGroup}，{@link #onLayout} 每次把两颗按钮排到相对本容器的最新位置。
 *
 * <p>为什么要容器：两颗按钮必须同一个父 View，官方对小窗做的位移（上滑手势、展开/收起动画）
 * 才会同时带上它们，右侧按钮不会停在原地。
 *
 * <p>为什么还要自己量：官方 {@code BubbleExpandedView} 是垂直 LinearLayout，而 {@code addView}
 * 传进去的布局参数不一定是 {@code LinearLayout.LayoutParams}。线性布局按子 View 的「竖直占位」
 * 累加高度，参数类型不对时它拿不到高度，那一行就被算成 0 —— 两颗按钮一起消失。所以容器自己
 * 按「两颗按钮里最高的那颗」量出高度并往父级报回去，不指望父级给。
 *
 * <p>为什么不靠子 View 的布局参数：官方 {@code mManageButton} 的布局参数来自其它父容器，
 * 直接塞进本容器可能类型对不上，所以容器自己量、自己报高度，子 View 的布局参数一律不碰。
 *
 * <p>为什么必须 setId(bubble_expanded_view)：官方有代码按这个 id 找底部容器
 * （{@code findViewById(R.id.bubble_expanded_view)}），容器接替了「管理按钮那一行」的位置，
 * id 跟着带过来，父级 findViewById 依然能找到它。
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
        requestLayout();
    }

    /**
     * 父级是 LinearLayout 时，子 View 高度 WRAP_CONTENT 会被「两次测量」撑满剩余空间：
     * 第一次量完后 LinearLayout 给自己置 PFLAG3_MEASURE_NEEDED_BEFORE_LAYOUT，下一次测量就用
     * heightMeasureSpec 再量一遍。容器里没有这个约束，所以量完给子 View 补上同一行为，
     * 子 View 的布局参数保持最普通的那一种，不带任何自定义类型。
     */
    private static void markMeasuredOnce(View v) {
        try {
            java.lang.reflect.Field f = View.class.getDeclaredField("mPrivateFlags3");
            f.setAccessible(true);
            int flags = (int) f.get(v);
            f.setInt(v, flags | 0x8); // View.PFLAG3_MEASURE_NEEDED_BEFORE_LAYOUT
        } catch (Throwable ignored) {
            // 反射受限时就算了：最多是子 View 在极窄情况下被撑高，不影响显隐。
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
        // 高度自己定：父级给的是 LinearLayout 的剩余空间，不能用。
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
            // 与左侧按钮共用底边：相同基线、同一个高度基准。
            tertiary.layout(width - padEnd() - tw, height - th, width - padEnd(), height);
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (primary != null && primary.getVisibility() != visibility) primary.setVisibility(visibility);
        if (tertiary != null && tertiary.getVisibility() != visibility) tertiary.setVisibility(visibility);
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
