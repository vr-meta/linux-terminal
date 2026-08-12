package dev.butschster.linuxterminal;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * One slope of a rocker: half of a cap that is pitched like a shallow roof, with
 * the ridge between the two halves.
 *
 * <p>The two halves are lit as one object, not as two buttons that happen to
 * touch. Light comes from above, so the upper slope faces it and is bright, the
 * lower slope turns away and is dark, and the ridge between them is the brightest
 * line on the control. That difference is the whole point: it says the thing
 * pivots, and it says which way each end will go before it is touched. Two
 * identically shaded halves are a split rectangle and read as two keys.
 *
 * <p>Only the outer corners are rounded. The pair share a straight edge at the
 * ridge, because a rounded edge there would be a gap between two objects, and
 * these are two faces of one.
 *
 * <p>Pressing a half swaps its gradient: the slope that went down now points away
 * from the lamp, so it darkens, while the ridge slides towards it. The opposite
 * half is left alone — a real rocker's other end rises, and that is drawn by its
 * neighbour keeping the brightness it already had against a darkened partner.
 */
public class RockerHalf extends Drawable {

    private final boolean upper;
    private final int fill;
    private final float radius;
    private final boolean pressed;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ridge = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF face = new RectF();
    private final Path shape = new Path();

    public RockerHalf(boolean upper, int fill, float radiusPx, boolean pressed) {
        this.upper = upper;
        this.fill = fill;
        this.radius = radiusPx;
        this.pressed = pressed;
        ridge.setStyle(Paint.Style.STROKE);
        ridge.setStrokeWidth(1f);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        face.set(bounds.left, bounds.top, bounds.right, bounds.bottom);

        float r = radius;
        shape.reset();
        // Round away from the ridge only: the outer end of the control is a moulded
        // corner, the inner end is where the two faces meet.
        float[] corners = upper
                ? new float[]{r, r, r, r, 0, 0, 0, 0}
                : new float[]{0, 0, 0, 0, r, r, r, r};
        shape.addRoundRect(face, corners, Path.Direction.CW);

        int bright, dim;
        if (upper) {
            // Facing the lamp. Brightest at its outer edge, easing towards the ridge
            // — a slope is lit most where it is most square to the light.
            bright = lighten(fill, pressed ? 0.05f : 0.24f);
            dim = lighten(fill, pressed ? 0.00f : 0.08f);
            body.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                    bright, dim, Shader.TileMode.CLAMP));
        } else {
            // Turned away from it. Starts near the ridge at the material's own value
            // and falls off to well under it.
            bright = shade(fill, pressed ? -30 : -8);
            dim = shade(fill, pressed ? -46 : -30);
            body.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                    bright, dim, Shader.TileMode.CLAMP));
        }
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(shape, body);

        // The ridge itself. Drawn by the upper half only, along its lower edge, so
        // there is exactly one line and it cannot end up doubled or a pixel apart.
        if (upper) {
            ridge.setColor(pressed ? 0x26FFFFFF : 0x59FFFFFF);
            canvas.drawLine(face.left + radius * 0.5f, face.bottom - 0.5f,
                    face.right - radius * 0.5f, face.bottom - 0.5f, ridge);
        } else {
            ridge.setColor(0x8C000000);
            canvas.drawLine(face.left + radius * 0.5f, face.top + 0.5f,
                    face.right - radius * 0.5f, face.top + 0.5f, ridge);
        }
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
