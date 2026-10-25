package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the tvOS style dynamic glass band behind the dock row with a smooth,
 * responsive light pool and glowing rim that dynamically follows the current selection.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float currentFocusX = -1f;
    private float targetFocusX = -1f;
    private float glowAlpha = 0f;
    private float targetGlowAlpha = 0f;
    private int currentColor = 0xFFFFFFFF;
    private int targetColor = 0xFFFFFFFF;

    private ValueAnimator posAnimator;
    private ValueAnimator colorAnimator;
    private ValueAnimator alphaAnimator;
    private RecyclerView attachedParent;

    DockDecoration(int dockSize, float density, boolean translucent) {
        this.dockSize = dockSize;
        this.density = density;
        // Translucent dark glass rather than solid opaque black:
        // Allows wallpaper to show through subtly and elegantly (~35-40% alpha)
        band.setColor(0x550B0B10);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x28FFFFFF);

        edgeGlow.setStyle(Paint.Style.STROKE);
        edgeGlow.setStrokeWidth(1.5f * density);
    }

    public void setSelection(View card, int color, boolean animate) {
        if (card == null || !(card.getParent() instanceof View)) {
            clearSelection();
            return;
        }
        View child = (View) card.getParent();
        float targetX = child.getLeft() + card.getLeft() + card.getWidth() / 2f;
        targetColor = color != 0 ? color : 0xFFFFFFFF;
        targetGlowAlpha = 1f;

        if (!animate || currentFocusX < 0) {
            currentFocusX = targetX;
            targetFocusX = targetX;
            currentColor = targetColor;
            glowAlpha = 1f;
            if (attachedParent != null) attachedParent.invalidate();
            return;
        }

        if (Math.abs(targetFocusX - targetX) > 1f) {
            targetFocusX = targetX;
            if (posAnimator != null) posAnimator.cancel();
            posAnimator = ValueAnimator.ofFloat(currentFocusX, targetFocusX);
            posAnimator.setDuration(220);
            posAnimator.setInterpolator(new DecelerateInterpolator());
            posAnimator.addUpdateListener(anim -> {
                currentFocusX = (float) anim.getAnimatedValue();
                if (attachedParent != null) attachedParent.invalidate();
            });
            posAnimator.start();
        }

        if (colorAnimator != null) colorAnimator.cancel();
        colorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), currentColor, targetColor);
        colorAnimator.setDuration(220);
        colorAnimator.addUpdateListener(anim -> {
            currentColor = (int) anim.getAnimatedValue();
            if (attachedParent != null) attachedParent.invalidate();
        });
        colorAnimator.start();

        if (glowAlpha < 0.99f) {
            if (alphaAnimator != null) alphaAnimator.cancel();
            alphaAnimator = ValueAnimator.ofFloat(glowAlpha, 1f);
            alphaAnimator.setDuration(180);
            alphaAnimator.addUpdateListener(anim -> {
                glowAlpha = (float) anim.getAnimatedValue();
                if (attachedParent != null) attachedParent.invalidate();
            });
            alphaAnimator.start();
        }
    }

    public void clearSelection() {
        targetGlowAlpha = 0f;
        if (alphaAnimator != null) alphaAnimator.cancel();
        alphaAnimator = ValueAnimator.ofFloat(glowAlpha, 0f);
        alphaAnimator.setDuration(200);
        alphaAnimator.addUpdateListener(anim -> {
            glowAlpha = (float) anim.getAnimatedValue();
            if (attachedParent != null) attachedParent.invalidate();
        });
        alphaAnimator.start();
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        this.attachedParent = parent;
        boolean any = false;
        View focusedCard = null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            if (card.isFocused()) {
                focusedCard = card;
            }
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;

        // Auto-detect focus if not explicitly set via setSelection
        if (focusedCard != null && glowAlpha <= 0.01f && targetGlowAlpha <= 0.01f) {
            View child = (View) focusedCard.getParent();
            currentFocusX = child.getLeft() + focusedCard.getLeft() + focusedCard.getWidth() / 2f;
            targetFocusX = currentFocusX;
            glowAlpha = 1f;
        }

        float pad = 14 * density;
        float side = Math.max(0f, Math.min(pad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
        rect.inset(-side, -pad);
        float radius = 26 * density;

        // 1. Draw translucent dark glass background
        c.drawRoundRect(rect, radius, radius, band);

        // 2. Draw dynamic focus light pool inside the dock band
        if (glowAlpha > 0.01f && currentFocusX >= rect.left - 50f && currentFocusX <= rect.right + 50f) {
            c.save();
            c.clipRect(rect);

            float glowRadius = Math.max(rect.height() * 1.6f, 130f * density);
            int cR = (currentColor >> 16) & 0xFF;
            int cG = (currentColor >> 8) & 0xFF;
            int cB = currentColor & 0xFF;

            int alphaCore = (int) (0x55 * glowAlpha);
            int alphaMid = (int) (0x1F * glowAlpha);
            int colorCore = (alphaCore << 24) | (cR << 16) | (cG << 8) | cB;
            int colorMid = (alphaMid << 24) | (cR << 16) | (cG << 8) | cB;

            glow.setShader(new RadialGradient(
                    currentFocusX, rect.centerY(), glowRadius,
                    new int[]{colorCore, colorMid, 0x00000000},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, radius, radius, glow);

            // Specular top rim light that moves with focus
            float edgeSpan = 90f * density;
            int edgeAlpha = (int) (0x88 * glowAlpha);
            int edgeColor = (edgeAlpha << 24) | (cR << 16) | (cG << 8) | cB;
            edgeGlow.setShader(new LinearGradient(
                    currentFocusX - edgeSpan, rect.top,
                    currentFocusX + edgeSpan, rect.top,
                    new int[]{0x00FFFFFF, edgeColor, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, radius, radius, edgeGlow);

            c.restore();
        }

        // 3. Draw clean border
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
