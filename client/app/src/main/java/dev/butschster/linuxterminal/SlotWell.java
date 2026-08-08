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
 * The slot Enter is sunk into: a shallow pocket milled in the plate, with the key
 * lying inside it.
 *
 * <p>Every other control on this panel stands proud of the surface. This one is
 * the opposite, and the inversion is the point — a key you press <em>into</em> the
 * plate rather than one that stands up to meet you reads as deliberate, the way a
 * recessed switch on real equipment means "not by accident". It costs nothing in
 * target size, because the pocket is only a few pixels wider than what it holds.
 *
 * <p>Drawn from the same description as every other recess here: shadow along the
 * top wall where the plate overhangs, a hairline of light along the bottom where
 * the lower wall catches it. One lamp, above and in front, for the whole panel.
 */
public class SlotWell extends Drawable {

    private final float radius;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF face = new RectF();

    public SlotWell(float radiusPx) {
        this.radius = radiusPx;
        wall.setStyle(Paint.Style.STROKE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1f);
        edge.setColor(0xFF232D3A);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        face.set(bounds.left + 0.5f, bounds.top + 0.5f,
                bounds.right - 0.5f, bounds.bottom - 0.5f);

        // linear-gradient(180deg,#0a0f16,#060a0e)
        body.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                0xFF0A0F16, 0xFF060A0E, Shader.TileMode.CLAMP));

        // inset 0 5px 12px rgba(0,0,0,.95) over the top, and the .03 white line
        // along the bottom.
        wall.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                new int[]{0xF2000000, Color.TRANSPARENT, 0x08FFFFFF},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawRoundRect(face, radius, radius, body);
        canvas.save();
        canvas.clipRect(face);
        wall.setStrokeWidth(10f);
        canvas.drawRoundRect(face, radius, radius, wall);
        canvas.restore();
        canvas.drawRoundRect(face, radius, radius, edge);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
