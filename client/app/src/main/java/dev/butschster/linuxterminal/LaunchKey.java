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
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        housing.set(bounds.left + 0.5f, bounds.top + 0.5f,
                bounds.right - 0.5f, bounds.bottom - 0.5f);

        float inset = 3f;
        float stand = pressed ? Math.min(depth, 1f) : depth;
        cap.set(housing.left + inset, housing.top + inset,
                housing.right - inset, housing.bottom - inset - depth);
        extrusion.set(cap.left, cap.top + stand, cap.right, cap.bottom + stand);

        // Half the cap's height: round ends, straight middle.
        radius = cap.height() / 2f;

        inlay.set(cap.left + 5f, cap.top + 4f, cap.right - 5f, cap.bottom - 4f);
        inlayRadius = Math.max(2f, inlay.height() / 2f);

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
    }

    @Override
    public void draw(Canvas canvas) {
        // The bezel it is mounted in.
        canvas.drawRoundRect(housing, radius + 3f, radius + 3f, bezel);

        if (!pressed) {
            canvas.drawRoundRect(extrusion, radius, radius, solid);
        }

        canvas.drawRoundRect(cap, radius, radius, body);

        groove.setStrokeWidth(1.5f);
        canvas.drawRoundRect(inlay, inlayRadius, inlayRadius, groove);

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
