package com.floatwindow.morebubblebutton;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 气泡小窗底部按钮容器：「关闭小窗」（左）与「展开小窗」（右）放在同一个普通
 * {@link ViewGroup} 里，{@link #onLayout} 每次把两颗按钮排到相对本容器的最新位置。
 *
 * <p>为什么不直接 addView 到 BubbleExpandedView：那是垂直 LinearLayout，多一个 child 就多一行、
 * 把窗口撑高，自造按钮只能用负 topMargin 叠上去 —— 布局尺寸一没定下来就会错位，跟左侧按钮脱节。
 *
 * <p>为什么不用自定义测量/布局逻辑：容器要能接住官方 `measure` + `layout(l,t,r,b)` 那一套
 * （不依赖 onMeasure 是否被调），普通 ViewGroup 才能保证官方把它当普通 child 摆放、不会有意外。
 * 容器自己不占位置：onLayout 结束时把自身下沉到两颗按钮底部，于是布局阶段它垫在按钮下方，
 * 下一帧作为 l/t/r/b 传给子 View 时又是新位置 —— 按钮位置永远与容器一致。
 *
 * <p>官方下一轮 addView / setVisibility / 位移动画都落在容器上；容器把可见性代理给真正的按钮，
 * 所以官方推「管理」按钮（=推容器）时，两颗按钮在同一个平移里一起走。
 */
class SyncCornerLayout extends ViewGroup {
    View primary;   // 官方 mManageButton（关闭小窗）
    View secondary; // 隐藏的尺寸模板（只用来量文字尺寸，透明且不占位）
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
