package dev.butschster.linuxterminal;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The one key on the panel that is a different object: the launch key.
 *
 * <p>Every other control is a cap in a family of caps, told apart by colour and
 * legend. Enter is the key you press to commit — the end of every sentence typed
 * here — and on a real console that key is not a member of the grid. It is longer,
 * it is set in its own bezel, and it carries a groove around its face so the
 * finger finds it without the eye. A machine that has one irreversible action
 * gives it a shape nothing else has, so that "which one was it" is never a
 * question.
 *
 * <p>Three things make it that shape, and none of them is a colour:
 *
 * <ul>
 *   <li><b>A bezel.</b> The cap sits inside a ring of the panel's own material
 *       rather than flush against its neighbours — the key is mounted, not tiled.
 *   <li><b>An inlaid groove</b> a couple of pixels inside the face, lit on its
 *       lower edge like every other recess on this panel. It is the detail that
 *       reads as a moulded part rather than a rounded rectangle.
 *   <li><b>Shoulders.</b> The corner radius is half the cap's height, so the ends
 *       are round while the middle stays straight: a capsule, which is the profile
 *       of a bar key on every machine that has one.
 * </ul>
 */
public class LaunchKey extends Drawable {

    private final int face;
    private final int accent;
    private final float depth;
    private final boolean pressed;

    private final Paint bezel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groove = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint collarWall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knurl = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF housing = new RectF();
    private final RectF cap = new RectF();
    private final RectF extrusion = new RectF();
    private final RectF inlay = new RectF();

    private float radius;
    private float inlayRadius;

    public LaunchKey(int face, int accent, float depthPx, boolean pressed) {
        this.face = face;
        this.accent = accent;
        this.depth = depthPx;
        this.pressed = pressed;

        bezel.setColor(0xFF0A1119);
        solid.setColor(0xE0071009);
        groove.setStyle(Paint.Style.STROKE);
        rim.setStyle(Paint.Style.STROKE);
        glow.setStyle(Paint.Style.STROKE);
        collarWall.setStyle(Paint.Style.STROKE);
        knurl.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        housing.set(bounds.left + 0.5f, bounds.top + 0.5f,
                bounds.right - 0.5f, bounds.bottom - 0.5f);

        // A deep collar rather than a hairline bezel: this is a button seated in a
        // hole cut through the panel, the way a launch or arm control is mounted.
        // The collar is what your eye reads as thickness of plate, so it has to be
        // several pixels, not one.
        float collar = 7f;
        float stand = pressed ? Math.min(depth, 1f) : depth;
        cap.set(housing.left + collar, housing.top + collar,
                housing.right - collar, housing.bottom - collar - depth);
        extrusion.set(cap.left, cap.top + stand, cap.right, cap.bottom + stand);

        // Square-shouldered, not a capsule. A capsule reads as a bar key — a wide
        // thing you slap — and this is the opposite claim: one deliberate control,
        // seated, pressed once. Its corners are the panel's own radius so it is
        // still made of the same material as everything else.
        radius = 9f;

        inlay.set(cap.left + 7f, cap.top + 6f, cap.right - 7f, cap.bottom - 6f);
        inlayRadius = Math.max(2f, radius - 3f);

        body.setShader(new LinearGradient(0, cap.top, 0, cap.bottom,
                pressed
                        ? new int[]{shade(face, -14), shade(face, -4), shade(face, 6)}
                        : new int[]{lighten(face, 0.20f), face, shade(face, -14)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));

        // The groove: shadow on its upper wall, light on its lower one — the same
        // description every recess on this panel is drawn from.
        groove.setShader(new LinearGradient(0, inlay.top, 0, inlay.bottom,
                new int[]{0x8C000000, 0x1AFFFFFF},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));

        collarWall.setShader(new LinearGradient(0, housing.top, 0, housing.bottom,
                new int[]{0xCC000000, Color.TRANSPARENT, 0x0FFFFFFF},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        // The hole in the plate, and the shaded wall of it: the same recess this
        // panel draws everywhere, at the size a mounted control needs.
        canvas.drawRoundRect(housing, radius + 7f, radius + 7f, bezel);
        canvas.save();
        canvas.clipRect(housing);
        collarWall.setStrokeWidth(6f);
        canvas.drawRoundRect(housing, radius + 7f, radius + 7f, collarWall);
        canvas.restore();

        if (!pressed) {
            canvas.drawRoundRect(extrusion, radius, radius, solid);
        }

        canvas.drawRoundRect(cap, radius, radius, body);

        groove.setStrokeWidth(1.5f);
        canvas.drawRoundRect(inlay, inlayRadius, inlayRadius, groove);

        // Knurling down both shoulders — the grip a guarded control has so a glove
        // finds it. Four short strokes a side, inside the groove, at the same
        // alpha as the bevel so it reads as moulded rather than printed.
        knurl.setStrokeWidth(1f);
        float step = cap.height() / 5f;
        for (int i = 1; i <= 4; i++) {
            float y = cap.top + step * i;
            knurl.setColor(0x1AFFFFFF);
            canvas.drawLine(inlay.left + 3f, y, inlay.left + 9f, y, knurl);
            canvas.drawLine(inlay.right - 9f, y, inlay.right - 3f, y, knurl);
        }

        // Lit at rest, because this is the key the panel is built around. Two
        // rings rather than a blur, and quiet: it marks the primary control, it
        // does not shout over the legend on it.
        glow.setStrokeWidth(3f);
        glow.setColor(withAlpha(accent, pressed ? 30 : 20));
        canvas.drawRoundRect(cap, radius, radius, glow);

        rim.setStrokeWidth(1f);
        rim.setColor(withAlpha(accent, pressed ? 190 : 150));
        canvas.drawRoundRect(cap, radius, radius, rim);
    }

    private static int withAlpha(int colour, int alpha) {
        return Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour));
    }

    private static int shade(int colour, int step) {
        return Color.rgb(
                Math.max(0, Math.min(255, Color.red(colour) + step)),
                Math.max(0, Math.min(255, Color.green(colour) + step)),
                Math.max(0, Math.min(255, Color.blue(colour) + step)));
    }

    private static int lighten(int colour, float amount) {
        return Color.rgb(
                (int) (Color.red(colour) + (255 - Color.red(colour)) * amount),
                (int) (Color.green(colour) + (255 - Color.green(colour)) * amount),
                (int) (Color.blue(colour) + (255 - Color.blue(colour)) * amount));
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
