package com.ash.attendance;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
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

    // Material kinds. SMALL/LARGE are the plain smoky glass, LIFT is the raised selection pill, TINT is accent glass.
    static final int SMALL = 0, LARGE = 1, LIFT = 2, TINT = 3;

    /** Small glass (buttons, chips, pills, bars). Pass a big radius for a fully rounded pill. */
    static Drawable surface(Context c, float radiusDp) { return new Shape(c, SMALL, radiusDp, 0, false); }

    /** Large glass (cards, panels, sheets). */
    static Drawable card(Context c, float radiusDp) { return new Shape(c, LARGE, radiusDp, 0, false); }

    /** Selected state: glass in the accent colour. */
    static Drawable selected(Context c, float radiusDp, int accent) { return tinted(c, radiusDp, accent); }

    /** Coloured glass. */
    static Drawable tinted(Context c, float radiusDp, int color) { return new Shape(c, TINT, radiusDp, color, false); }

    /** The lifted pill that slides behind the selected tab. */
    static Drawable lift(Context c, float radiusDp) { return new Shape(c, LIFT, radiusDp, 0, false); }

    /**
     * Rounded rect of liquid glass: smoky translucent body, a diagonal rim highlight (bright top-left and
     * bottom-right) and a soft drop shadow. Below API 31 it falls back to a flat fill with a thin border.
     */
    static final class Shape extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG),
                rim = new Paint(Paint.ANTI_ALIAS_FLAG), soft = new Paint(Paint.ANTI_ALIAS_FLAG),
                edge = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF box = new RectF();
        private final float radius, stroke, blur, dy;
        private final int fillColor, shadowColor, rimPeak;
        private final boolean real, withSheen;

        Shape(Context c, int kind, float radiusDp, int color, boolean flat) {
            radius = dp(c, radiusDp);
            real = Build.VERSION.SDK_INT >= 31 && !flat;
            stroke = real ? dp(c, 0.75f) : dp(c, 1);
            float density = c.getResources().getDisplayMetrics().density;
            int fc, sc, rp;
            float bl = 18, off = 3;
            if (dark) {
                switch (kind) {
                    case LIFT: fc = 0x33FFFFFF; sc = 0x4D000000; rp = 0x61; bl = 12; off = 2; break;
                    case TINT: fc = withAlpha(color, real ? 0x47 : 0x66); sc = 0x38000000; rp = 0x80; break;
                    case LARGE: fc = real ? 0x991C1C22 : 0xE02C2C34; sc = 0x38000000; rp = 0x80; break;
                    default: fc = real ? 0x6B1C1C22 : 0xE02C2C34; sc = 0x38000000; rp = 0x80; break;
                }
            } else {
                switch (kind) {
                    case LIFT: fc = 0xF2FFFFFF; sc = 0x24000000; rp = 0xE6; bl = 12; off = 2; break;
                    case TINT: fc = withAlpha(color, 0xF0); sc = 0x1A000000; rp = 0xB0; break;
                    case LARGE: fc = real ? 0xD9FFFFFF : 0xEBFFFFFF; sc = 0x1A000000; rp = 0xE6; break;
                    default: fc = real ? 0xB3FFFFFF : 0xEBFFFFFF; sc = 0x1A000000; rp = 0xE6; break;
                }
            }
            fillColor = fc; shadowColor = sc; rimPeak = rp; blur = bl * density; dy = off * density;
            withSheen = dark && real && kind != LIFT;
            fill.setColor(fillColor);
            for (Paint p : new Paint[]{rim, soft, edge}) p.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(stroke);
            soft.setStrokeWidth(stroke * 2.2f);
            edge.setStrokeWidth(stroke);
            edge.setColor(0x140B1B3B);
            shadow.setColor(Color.BLACK);
            if (!real) {
                rim.setColor(dark ? 0x1FFFFFFF : 0x240B1B3B);
                rim.setStrokeWidth(dp(c, 1));
            }
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            if (withSheen) sheen.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            if (real) {
                // True 45 degree gradient from the top-left corner to the bottom-right one.
                float len = (b.width() + b.height()) / 2f;
                int hi = rimPeak << 24 | 0xFFFFFF, lo = (rimPeak / 9) << 24 | 0xFFFFFF;
                Shader sh = new LinearGradient(b.left, b.top, b.left + len, b.top + len, new int[]{hi, lo, lo, hi},
                        new float[]{0f, 0.4f, 0.6f, 1f}, Shader.TileMode.CLAMP);
                rim.setShader(sh);
                soft.setShader(sh);
                soft.setAlpha(70);
            }
        }

        private float corner() { return Math.min(radius, Math.min(box.width(), box.height()) / 2f); }

        /** Drop shadow only (outside the shape, so it doesn't darken the glass). API 28+; harmless elsewhere. */
        void drawShadow(Canvas c) {
            if (Build.VERSION.SDK_INT < 28 || Color.alpha(shadowColor) == 0) return;
            box.set(getBounds());
            float r = corner();
            path.reset();
            path.addRoundRect(box, r, r, Path.Direction.CW);
            c.save();
            c.clipOutPath(path);
            shadow.setShadowLayer(blur, 0, dy, shadowColor);
            c.drawRoundRect(box, r, r, shadow);
            c.restore();
        }

        /** Tint, sheen and rim. */
        void drawBody(Canvas c) {
            box.set(getBounds());
            float r = corner();
            c.drawRoundRect(box, r, r, fill);
            if (withSheen) c.drawRoundRect(box, r, r, sheen);
            if (real && !dark) {
                c.drawRoundRect(box, r, r, edge);
                box.inset(stroke, stroke);
                r = Math.max(0, r - stroke);
            }
            float h = stroke / 2f;
            box.inset(h, h);
            if (real) c.drawRoundRect(box, r - h, r - h, soft);
            c.drawRoundRect(box, r - h, r - h, rim);
        }

        @Override public void draw(Canvas c) { drawShadow(c); drawBody(c); }

        @Override public void setAlpha(int a) { fill.setAlpha(a * Color.alpha(fillColor) / 255); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Opaque-ish glass for dialog windows, where there is nothing behind to refract. */
    static Drawable sheet(Context c) { return new Shape(c, LARGE, 28, 0, true); }

    /** Soft pill used for status labels: tinted fill and rim in the status colour. */
    static Drawable pill(Context c, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(withAlpha(color, 0x26));
        g.setCornerRadius(dp(c, 99));
        g.setStroke(Math.max(1, dp(c, 1)), withAlpha(color, 0x59));
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
                    x.animate().scaleX(0.96f).scaleY(0.96f).setInterpolator(new DecelerateInterpolator())
                            .setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    x.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(2f))
                            .setDuration(260).start();
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
            v.animate().alpha(target).translationY(0f).setStartDelay(Math.min(i, 9) * 50L).setDuration(350)
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
                glows[i] = new RadialGradient(0, 0, radius, withAlpha(colors[i], dark ? 0x2E : 0x50), withAlpha(colors[i], 0),
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
            track.setColor(dark ? 0x1AFFFFFF : 0x1A12142B);
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
            p.setColor(dark ? 0x1AFFFFFF : 0x1A12142B);
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
                animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(new DecelerateInterpolator(1.6f)).start();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------

    /**
     * Glass bar that blurs whatever {@code source} shows behind it (API 31+, hardware canvas only). The source must
     * not contain the bar itself. Everywhere else it is just the plain glass shape.
     */
    static class BlurLayer extends FrameLayout {
        private final Shape glass, flat;
        private final float blurDp;
        private View source, exclude;
        private Live live;
        private final android.view.ViewTreeObserver.OnPreDrawListener tick = () -> { invalidate(); return true; };
        private boolean ticking;

        BlurLayer(Context c, float radiusDp, boolean large, float blurDp) {
            super(c);
            this.blurDp = blurDp;
            glass = new Shape(c, large ? LARGE : SMALL, radiusDp, 0, false);
            flat = new Shape(c, large ? LARGE : SMALL, radiusDp, 0, true);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
        }

        /** {@code src}: what to blur; {@code hide}: a view (the WebView) that can't be captured, which turns the blur off while visible. */
        void setSource(View src, View hide) {
            source = src;
            exclude = hide;
            if (Build.VERSION.SDK_INT >= 31 && src != null && live == null) live = new Live();
            updateTicking();
        }

        private void updateTicking() {
            boolean want = source != null && live != null && isAttachedToWindow();
            if (want && !ticking) { getViewTreeObserver().addOnPreDrawListener(tick); ticking = true; }
            else if (!want && ticking) { getViewTreeObserver().removeOnPreDrawListener(tick); ticking = false; }
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateTicking(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); updateTicking(); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            glass.setBounds(0, 0, w, h);
            flat.setBounds(0, 0, w, h);
        }

        @Override
        protected void onDraw(Canvas c) {
            boolean blurred = false;
            Shape s = source == null ? glass : flat;
            if (live != null && source != null && getWidth() > 0 && c.isHardwareAccelerated()
                    && source.getWidth() > 0 && (exclude == null || exclude.getVisibility() != VISIBLE)) {
                glass.drawShadow(c);
                try { blurred = live.draw(this, c, source, glass); } catch (RuntimeException e) { live = null; }
            }
            if (blurred) glass.drawBody(c);
            else s.draw(c);
        }

        /** API 31+ part, kept apart so older runtimes never load it. */
        private static final class Live {
            private RenderNode node;
            private RenderEffect effect;
            private int ew, eh;
            private final int[] a = new int[2], b = new int[2];

            boolean draw(BlurLayer bar, Canvas c, View source, Shape shape) {
                Context ctx = bar.getContext();
                float blur = dp(ctx, bar.blurDp);
                int w = bar.getWidth(), h = bar.getHeight(), pad = Math.round(blur * 2) + 1;
                int nw = w + 2 * pad, nh = h + 2 * pad;
                if (node == null) node = new RenderNode("glass");
                node.setPosition(0, 0, nw, nh);
                if (effect == null || ew != nw || eh != nh) { effect = makeEffect(ctx, bar, blur, nw, nh, pad, w, h); ew = nw; eh = nh; }
                node.setRenderEffect(effect);
                bar.getLocationInWindow(a);
                source.getLocationInWindow(b);
                android.graphics.RecordingCanvas rc = node.beginRecording();
                try {
                    rc.translate(pad - (a[0] - b[0]), pad - (a[1] - b[1]));
                    source.draw(rc);
                } finally {
                    node.endRecording();
                }
                Rect r = shape.getBounds();
                float rad = Math.min(shape.radius, Math.min(r.width(), r.height()) / 2f);
                Path clip = new Path();
                clip.addRoundRect(0, 0, w, h, rad, rad, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                c.translate(-pad, -pad);
                c.drawRenderNode(node);
                c.restore();
                return true;
            }

            private static RenderEffect makeEffect(Context ctx, BlurLayer bar, float blur, int nw, int nh, int pad, int w, int h) {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(1.6f);
                cm.postConcat(new ColorMatrix(new float[]{1, 0, 0, 0, -7.65f, 0, 1, 0, 0, -7.65f, 0, 0, 1, 0, -7.65f, 0, 0, 0, 1, 0}));
                RenderEffect fx = RenderEffect.createChainEffect(
                        RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm)),
                        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
                if (Build.VERSION.SDK_INT >= 33 && h > 0) {
                    try {
                        fx = Lens.wrap(fx, ctx, nw, nh, pad, w, h, Math.min(bar.glass.radius, h / 2f));
                    } catch (Throwable ignored) { /* no lens */ }
                }
                return fx;
            }
        }

        /** API 33+: bends the blurred content inward near the rounded edge, with a hint of colour fringing. */
        private static final class Lens {
            static final String SRC = "uniform shader content; uniform float2 size; uniform float2 origin;"
                    + "uniform float rad; uniform float edge; uniform float amt;"
                    + "half4 main(float2 p){"
                    + "float2 c = origin + size * 0.5; float2 q = abs(p - c) - (size * 0.5 - float2(rad));"
                    + "float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rad;"
                    + "float k = 1.0 - clamp(-d / edge, 0.0, 1.0);"
                    + "float2 s = sign(p - c); float2 m = max(q, float2(0.0));"
                    + "float2 g = length(m) > 0.0 ? normalize(m) * s : (q.x > q.y ? float2(s.x, 0.0) : float2(0.0, s.y));"
                    + "float2 o = g * k * k * amt;"
                    + "half4 base = content.eval(p - o);"
                    + "half r = content.eval(p - o * 1.06).r; half b = content.eval(p - o * 0.94).b;"
                    + "return half4(r, base.g, b, base.a);}";

            static RenderEffect wrap(RenderEffect inner, Context ctx, int nw, int nh, int pad, int w, int h, float rad) {
                android.graphics.RuntimeShader sh = new android.graphics.RuntimeShader(SRC);
                float edge = dp(ctx, 22);
                sh.setFloatUniform("size", w, h);
                sh.setFloatUniform("origin", pad, pad);
                sh.setFloatUniform("rad", rad);
                sh.setFloatUniform("edge", edge);
                sh.setFloatUniform("amt", Math.min(h, edge * 2) * 0.5f);
                return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(sh, "content"), inner);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------

    /** Row of equal tabs with one lifted glass pill that slides to the selected tab. */
    static final class Segmented extends BlurLayer {
        private final View pill;
        private final LinearLayout row;
        private int sel;
        private int lastTabW = -1;

        Segmented(Context c, float radiusDp, boolean large, float blurDp, float padDp) {
            super(c, radiusDp, large, blurDp);
            int p = dp(c, padDp);
            setPadding(p, p, p, p);
            pill = new View(c);
            pill.setBackground(lift(c, 99));
            addView(pill, new LayoutParams(0, 0));
            row = new LinearLayout(c);
            addView(row, new LayoutParams(-1, -2));
        }

        void addTab(TextView t) { row.addView(t, new LinearLayout.LayoutParams(0, -2, 1)); }

        void select(int i, boolean animate) {
            sel = i;
            for (int k = 0; k < row.getChildCount(); k++)
                ((TextView) row.getChildAt(k)).setTextColor(k == i ? MainActivity.TXT : withAlpha(MainActivity.TXT, 0x99));
            if (lastTabW < 0) return; // not laid out yet: onLayout puts the pill in place
            float to = tabX(i);
            pill.animate().cancel();
            if (animate) pill.animate().translationX(to).setDuration(320).setInterpolator(new OvershootInterpolator(1.2f)).start();
            else pill.setTranslationX(to);
        }

        private float tabX(int i) {
            View t = row.getChildAt(i), t0 = row.getChildAt(0);
            return t == null ? 0 : t.getLeft() - t0.getLeft();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            View t0 = row.getChildAt(0);
            if (t0 == null || t0.getWidth() == 0) return;
            pill.layout(row.getLeft() + t0.getLeft(), row.getTop(), row.getLeft() + t0.getRight(), row.getBottom());
            if (lastTabW != t0.getWidth()) {
                lastTabW = t0.getWidth();
                pill.animate().cancel();
                pill.setTranslationX(tabX(sel));
            }
        }
    }
}
