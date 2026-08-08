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
 * A recessed region of the panel that a group of controls is mounted in.
 *
 * <p>It exists to give the panel four surfaces instead of two: panel → section →
 * housing → face. With only two, everything on the plate is the same distance
 * from the eye and the grouping has to be carried by whitespace and a caption,
 * which is how a dashboard of cards reads rather than how a device reads.
 *
 * <p>It is not a card. A card sits <em>on</em> the surface and casts a shadow
 * outwards; this is milled <em>into</em> it — the shadow is along the inside of
 * its own top edge, and the only light is the thin line where the lower wall
 * catches it. Cards would put every group at the same height as the keys standing
 * in them, which is the arrangement this is meant to avoid.
 */
public class Section extends Drawable {

    private final int fill;
    private final float radius;

    /** A lit rim along the top: this housing is furniture, not weather. */
    private boolean lit;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF face = new RectF();

    public Section lit() {
        this.lit = true;
        edge.setColor(0x1FFFFFFF);
        return this;
    }

    public Section(int fill, float radiusPx) {
        this.fill = fill;
        this.radius = radiusPx;
        body.setColor(fill);
        wall.setStyle(Paint.Style.STROKE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1f);
        edge.setColor(0x0AFFFFFF);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        face.set(bounds.left + 0.5f, bounds.top + 0.5f,
                bounds.right - 0.5f, bounds.bottom - 0.5f);
        wall.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                new int[]{0xBF000000, Color.TRANSPARENT, 0x08FFFFFF},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawRoundRect(face, radius, radius, body);
        canvas.save();
        canvas.clipRect(face);
        wall.setStrokeWidth(6f);
        canvas.drawRoundRect(face, radius, radius, wall);
        canvas.restore();
        canvas.drawRoundRect(face, radius, radius, edge);

        if (lit) {
            // One hairline along the top edge only. A machined recess that is part
            // of the chassis catches light where the plate was cut; a region the
            // software refills does not get one, so the difference reads as how
            // the panel was made rather than as a decoration applied to it.
            edge.setColor(0x2EFFFFFF);
            canvas.drawLine(face.left + radius, face.top + 0.5f,
                    face.right - radius, face.top + 0.5f, edge);
            edge.setColor(lit ? 0x0AFFFFFF : 0);
        }
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
