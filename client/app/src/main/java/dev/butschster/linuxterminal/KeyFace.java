package dev.butschster.linuxterminal;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A key drawn as an object rather than as a rectangle: a moulded cap with a
 * bevel, sitting on a shadow, with the grain and the scratches of the panel it
 * belongs to.
 *
 * <p>Everything here describes one light source, above and slightly in front,
 * which is what a bar mounted below eye level actually gets. That single decision
 * settles every gradient in the file: the face is lighter at the top, the bevel
 * catches light on its upper edge and shadow on its lower one, and the cast
 * shadow falls downwards. A shape lit two ways reads as a drawing of a shape.
 *
 * <p>Pressed inverts it. Not "lighter", which is what a flat design does — the
 * highlight moves to the bottom edge, the cast shadow disappears because the cap
 * is now flush with the deck, and the face darkens slightly because it has moved
 * away from the light. Those three together are what a pressed key looks like,
 * and each of them on its own is not.
 *
 * <p>The cost of all this is contrast spent on the object instead of on the
 * legend, and `docs/readability.md` is the reason to keep the amounts small: the
 * bevel is a single device-independent pixel, the texture sits under 5% alpha,
 * and the face gradient spans about 8%. On a monitor it reads as material; the
 * question the headset has to answer is whether it survives the lenses or turns
 * into mud.
 */
public class KeyFace extends Drawable {

    private final int fill;
    private final float radius;
    private final float shadow;
    private final boolean pressed;
    private final boolean textured;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bevel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texture = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cast = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF face = new RectF();
    private final RectF under = new RectF();

    /**
     * @param shadowPx how far the cap stands above the deck, in pixels. Drawn
     *                 inside the view's own bounds — an elevation shadow falls
     *                 outside the child, so every container in the bar would have
     *                 to stop clipping and one that was missed would crop it.
     */
    public KeyFace(int fill, float radiusPx, float shadowPx, boolean pressed, boolean textured) {
        this.fill = fill;
        this.radius = radiusPx;
        this.shadow = shadowPx;
        this.pressed = pressed;
        this.textured = textured;

        bevel.setStyle(Paint.Style.STROKE);
        cast.setColor(Color.argb(150, 0, 0, 0));
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect bounds) {
        // Half a pixel in, so a 1px stroke lands on the pixel rather than across
        // two of them: an antialiased edge straddling a boundary is drawn at half
        // strength on each side, which is exactly how a crisp bevel turns into a
        // grey smear.
        face.set(bounds.left + 0.5f, bounds.top + 0.5f,
                bounds.right - 0.5f, bounds.bottom - (pressed ? 0.5f : shadow + 0.5f));
        under.set(bounds.left + 0.5f, bounds.top + shadow,
                bounds.right - 0.5f, bounds.bottom - 0.5f);

        int top = shade(fill, pressed ? -6 : 12);
        int bottom = shade(fill, pressed ? -20 : -10);
        body.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                top, bottom, Shader.TileMode.CLAMP));

        // One shader for both edges of the bevel: white at the top fading out by
        // the middle, black arriving again at the bottom. Two paints would drift
        // apart the moment either was adjusted.
        bevel.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                new int[]{
                        pressed ? Color.argb(90, 0, 0, 0) : Color.argb(58, 255, 255, 255),
                        Color.TRANSPARENT,
                        pressed ? Color.argb(46, 255, 255, 255) : Color.argb(96, 0, 0, 0),
                },
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        // The deck showing under the cap. Skipped when pressed: the cap is down.
        if (!pressed && shadow >= 1f) {
            canvas.drawRoundRect(under, radius, radius, cast);
        }

        canvas.drawRoundRect(face, radius, radius, body);

        if (textured) {
            // Clipped to the cap, so the grain stops where the object does. Alpha
            // rather than a colour: the same two tiles then work over every fill
            // in the palette without tinting any of them.
            canvas.save();
            canvas.clipRect(face);
            texture.setShader(Wear.grain());
            texture.setAlpha(pressed ? 12 : 17);
            canvas.drawRoundRect(face, radius, radius, texture);
            texture.setShader(Wear.use());
            texture.setAlpha(pressed ? 18 : 27);
            canvas.drawRoundRect(face, radius, radius, texture);
            canvas.restore();
        }

        bevel.setStrokeWidth(Math.max(1f, shadow * 0.5f));
        canvas.drawRoundRect(face, radius, radius, bevel);
    }

    /** Lighter or darker by an equal step on every channel, so the hue does not drift. */
    private static int shade(int colour, int step) {
        return Color.rgb(
                Math.max(0, Math.min(255, Color.red(colour) + step)),
                Math.max(0, Math.min(255, Color.green(colour) + step)),
                Math.max(0, Math.min(255, Color.blue(colour) + step)));
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
