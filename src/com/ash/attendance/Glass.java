package com.ash.attendance;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small toolkit for the app's frosted-glass look. Spacing follows an 8-13-21-34 (Fibonacci) rhythm and the
 * type scale steps by roughly the golden ratio (14 → 23 → 37), so everything sits in the same proportions.
 */
final class Glass {
    private Glass() { }

    /** Set once at start-up from the phone's theme: pure black with smoky glass in dark mode, white glass in light mode. */
    static int BASE = 0xFF000000;
    static boolean dark = true;

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** Frosted surface: smoky translucent fill with a specular rim (bright top-left and bottom-right). */
    static Drawable surface(Context c, float radiusDp) {
        return dark ? new Shape(dp(c, radiusDp), dp(c, 1), 0x24FFFFFF, 0x10FFFFFF, RIM)
                    : new Shape(dp(c, radiusDp), dp(c, 1), 0xE6FFFFFF, 0xB8FFFFFF, new int[]{0x1F0B1B3B});
    }

    /** Selected state. Dark: lighter grey glass (the label carries the accent). Light: glass in the accent colour. */
    static Drawable selected(Context c, float radiusDp, int accent) {
        return dark ? new Shape(dp(c, radiusDp), dp(c, 1), 0x3DFFFFFF, 0x24FFFFFF, RIM_BRIGHT)
                    : tinted(c, radiusDp, accent);
    }

    /** Coloured glass. */
    static Drawable tinted(Context c, float radiusDp, int color) {
        return new Shape(dp(c, radiusDp), dp(c, 1), withAlpha(color, 0xF0), withAlpha(color, 0xB8), new int[]{0x66FFFFFF});
    }

    private static final int[] RIM = {0x8CFFFFFF, 0x1AFFFFFF, 0x0AFFFFFF, 0x4DFFFFFF};
    private static final int[] RIM_BRIGHT = {0xCCFFFFFF, 0x26FFFFFF, 0x14FFFFFF, 0x80FFFFFF};

    /** Rounded rect with a vertical fill gradient and a rim whose brightness runs diagonally across it. */
    static final class Shape extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float radius, stroke;
        private final int top, bottom;
        private final int[] rimColors;

        Shape(float radius, float stroke, int top, int bottom, int[] rimColors) {
            this.radius = radius; this.stroke = stroke; this.top = top; this.bottom = bottom; this.rimColors = rimColors;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(stroke);
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, top, bottom, Shader.TileMode.CLAMP));
            if (rimColors.length == 1) rim.setColor(rimColors[0]);
            else rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, rimColors,
                    new float[]{0f, 0.38f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas c) {
            float h = stroke / 2f;
            box.set(getBounds());
            float r = Math.min(radius, box.height() / 2f);
            c.drawRoundRect(box, r, r, fill);
            box.inset(h, h);
            c.drawRoundRect(box, r - h, r - h, rim);
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); rim.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Soft pill used for status labels: tinted fill and rim in the status colour. */
    static Drawable pill(Context c, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(withAlpha(color, 0x1F));
        g.setCornerRadius(dp(c, 21));
        g.setStroke(Math.max(1, dp(c, 1)), withAlpha(color, 0x4D));
        return g;
    }

    static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

    static int lighten(int color, float f) {
        return Color.rgb(
                (int) (Color.red(color) + (255 - Color.red(color)) * f),
                (int) (Color.green(color) + (255 - Color.green(color)) * f),
                (int) (Color.blue(color) + (255 - Color.blue(color)) * f));
    }

    static Typeface light() { return Typeface.create("sans-serif-light", Typeface.NORMAL); }

    static Typeface medium() { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }

    /** Press feedback: the view dips slightly while touched and springs back. Does not consume the touch. */
    static void pressable(View v) {
        v.setOnTouchListener((x, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x.animate().scaleX(0.97f).scaleY(0.97f).setInterpolator(new DecelerateInterpolator())
                            .setDuration(110).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    x.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(2.2f))
                            .setDuration(240).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Staggered entrance: children rise and fade in one after another. */
    static void enter(ViewGroup parent) {
        int n = parent.getChildCount();
        float lift = dp(parent.getContext(), 21);
        for (int i = 0; i < n; i++) {
            View v = parent.getChildAt(i);
            float target = v.getAlpha();
            v.setAlpha(0f);
            v.setTranslationY(lift);
            v.animate().alpha(target).translationY(0f).setStartDelay(Math.min(i, 9) * 55L).setDuration(380)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
    }

    /** Counts a percentage up from zero. */
    static void countUp(final TextView tv, double to, int color) {
        tv.setTextColor(color);
        ValueAnimator a = ValueAnimator.ofFloat(0f, (float) to);
        a.setDuration(800);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(x -> tv.setText(String.format("%.2f%%", (float) x.getAnimatedValue())));
        a.start();
    }

    // ---------------------------------------------------------------------------------------------------

    /** Dark gradient with three soft coloured glows that drift slowly, so the glass above has something to refract. */
    static final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colors = dark ? new int[]{0xFFFF8A3D, 0xFF6E6E80, 0xFF8A5CFF}
                                          : new int[]{0xFF9DB5FF, 0xFFC4CBDB, 0xFF9DB5FF};
        private final float[][] origin = {{0.12f, 0.10f}, {0.92f, 0.46f}, {0.22f, 0.90f}};
        private final float[] speed = {1f, -1.3f, 0.8f};
        private final RadialGradient[] glows = new RadialGradient[3];
        private float radius;
        private float phase;
        private ValueAnimator anim;

        Backdrop(Context c) { super(c); }

        void start() {
            if (anim != null) return;
            anim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
            anim.setDuration(30000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }

        void stop() {
            if (anim != null) { anim.cancel(); anim = null; }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            radius = Math.max(w, h) * 0.62f;
            for (int i = 0; i < 3; i++)
                glows[i] = new RadialGradient(0, 0, radius, withAlpha(colors[i], dark ? 0x16 : 0x50), withAlpha(colors[i], 0),
                        Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(BASE);
            int w = getWidth(), h = getHeight();
            for (int i = 0; i < 3; i++) {
                if (glows[i] == null) continue;
                float cx = (origin[i][0] + 0.12f * (float) Math.sin(phase * speed[i] + i * 2.1f)) * w;
                float cy = (origin[i][1] + 0.08f * (float) Math.cos(phase * speed[i] * 0.8f + i * 1.7f)) * h;
                paint.setShader(glows[i]);
                c.save();
                c.translate(cx, cy);
                c.drawCircle(0, 0, radius, paint);
                c.restore();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------

    /** Circular progress with the value in the middle; animates from empty. */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float shown;
        private int color = 0xFF3DDC97;

        Ring(Context c) {
            super(c);
            float stroke = dp(c, 8);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(dark ? 0x22FFFFFF : 0x1A12142B);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(MainActivity.TXT);
            label.setTypeface(light());
            label.setTextSize(dp(c, 21));
        }

        void set(double pct, int col) {
            color = col;
            ValueAnimator a = ValueAnimator.ofFloat(0f, (float) pct);
            a.setDuration(1000);
            a.setInterpolator(new DecelerateInterpolator(1.8f));
            a.addUpdateListener(x -> { shown = (float) x.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = track.getStrokeWidth() / 2f + dp(getContext(), 2);
            box.set(pad, pad, getWidth() - pad, getHeight() - pad);
            c.drawArc(box, 0, 360, false, track);
            arc.setShader(new LinearGradient(0, 0, getWidth(), getHeight(), lighten(color, 0.35f), color,
                    Shader.TileMode.CLAMP));
            c.drawArc(box, -90, 360f * Math.min(shown, 100f) / 100f, false, arc);
            float y = getHeight() / 2f - (label.descent() + label.ascent()) / 2f;
            c.drawText(String.format("%.1f%%", shown), getWidth() / 2f, y, label);
        }
    }

    // ---------------------------------------------------------------------------------------------------

    /** Rounded progress bar that fills with an animation and marks the attendance target. */
    static final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float shown;
        private int color = 0xFF3DDC97;
        private final float tick;

        Bar(Context c, float tickPct) {
            super(c);
            tick = tickPct;
        }

        void set(double pct, int col) {
            color = col;
            ValueAnimator a = ValueAnimator.ofFloat(0f, (float) pct);
            a.setDuration(900);
            a.setInterpolator(new DecelerateInterpolator(1.8f));
            a.addUpdateListener(x -> { shown = (float) x.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            p.setShader(null);
            p.setColor(dark ? 0x22FFFFFF : 0x1A12142B);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, p);
            float fw = Math.max(h, w * Math.min(shown, 100f) / 100f);
            p.setColor(Color.BLACK); // opaque, so the track's alpha doesn't dim the fill
            p.setShader(new LinearGradient(0, 0, fw, 0, lighten(color, 0.3f), color, Shader.TileMode.CLAMP));
            r.set(0, 0, fw, h);
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            p.setColor(dark ? 0x99FFFFFF : 0x9912142B);
            float x = w * tick / 100f;
            c.drawRect(x - dp(getContext(), 1), 0, x + dp(getContext(), 1), h, p);
        }
    }

    // ---------------------------------------------------------------------------------------------------

    /** Vertical panel that eases in (fade + slide) whenever it becomes visible. */
    static final class Panel extends LinearLayout {
        Panel(Context c) { super(c); }

        @Override
        public void setVisibility(int v) {
            boolean was = getVisibility() == VISIBLE;
            super.setVisibility(v);
            if (!was && v == VISIBLE) {
                setAlpha(0f);
                setTranslationY(-dp(getContext(), 13));
                animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(new DecelerateInterpolator(1.6f)).start();
            }
        }
    }
}
