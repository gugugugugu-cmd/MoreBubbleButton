package com.floatwindow.morebubblebutton;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 气泡小窗底部按钮容器：「关闭小窗」（左）与「展开小窗」（右）放进同一个普通
 * {@link ViewGroup}，{@link #onLayout} 每次把两颗按钮排到相对本容器的最新位置。
 *
 * <p>为什么要容器：直接 addView 到 BubbleExpandedView（垂直 LinearLayout）时，多一个 child 就多一行，
 * 自造按钮只能靠负 topMargin 硬叠、再 post 里补 translationY —— 布局一没定下来就错位，
 * 而且官方动画只推「管理」按钮那一行，右侧按钮会停在原地。两颗按钮共用一个父 View，
 * 官方的位移 / 显隐 / 展开动画都会同时带上它们。
 *
 * <p>为什么不用自定义测量、布局：容器会被父容器按官方的 measure 规则测量，并且可能被随时
 * addView / removeView（官方 updateOverflow、setManageButtonVisibility 都会动它的子 View）。
 * 这里只重写 onLayout，测量完全交给 ViewGroup 默认实现，避免在布局流程里出任何幺蛾子。
 *
 * <p>为什么必须 setId(bubble_expanded_view)：官方不少代码按这个 id 找底部容器
 * （{@code findViewById(R.id.bubble_expanded_view)}），历史上真的有过容器内部堆叠的版本。
 * 容器现在接替了「管理按钮那一行」在 BubbleExpandedView 里的位置，把同一个 id 带过来，
 * 父级的 findViewById 依然能找到容器 —— 找不到时官方会把右侧按钮当成自己要显示/隐藏的对象。
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
