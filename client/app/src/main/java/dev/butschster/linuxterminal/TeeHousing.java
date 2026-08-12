package dev.butschster.linuxterminal;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The cut-out an arrow cluster sits in: a wide well along the bottom with a
 * narrower one rising from the middle of it. The inverted T every keyboard has.
 *
 * <p>It exists because the first attempt squared off the bottom corners of the up
 * key and closed the gap under it, which is most of the geometry and none of the
 * conviction: the three lower keys were inside a housing and the fourth was
 * outside it, touching. A key block is a block because the <em>panel</em> was cut
 * that shape, so the cut has to include all four.
 *
 * <p>Drawn as one path — two rounded rectangles unioned — rather than as two
 * overlapping shapes. The difference shows at the join: two shapes drawn one over
 * the other leave the seam of the upper one crossing the lower's field, and a
 * union has no interior edge at all, which is what a milled recess looks like.
 */
public class TeeHousing extends Drawable {

    private final int fill;
    private final float radius;

    /** Which slice of the width the stem occupies: left and right as fractions. */
    private final float stemLeft;
    private final float stemRight;

    /** Where the arm begins, as a fraction of the height. */
    private final float armTop;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lip = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path shape = new Path();
    private final Path scratch = new Path();

    public TeeHousing(int fill, float radiusPx, float stemLeft, float stemRight, float armTop) {
        this.fill = fill;
        this.radius = radiusPx;
        this.stemLeft = stemLeft;
        this.stemRight = stemRight;
        this.armTop = armTop;
        body.setColor(fill);
        wall.setStyle(Paint.Style.STROKE);
        lip.setStyle(Paint.Style.STROKE);
        lip.setStrokeWidth(1f);
        lip.setColor(0x2EFFFFFF);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        float w = bounds.width();
        float h = bounds.height();

        RectF stem = new RectF(bounds.left + w * stemLeft, bounds.top + 0.5f,
                bounds.left + w * stemRight, bounds.top + h * armTop + radius);
        RectF arm = new RectF(bounds.left + 0.5f, bounds.top + h * armTop,
                bounds.right - 0.5f, bounds.bottom - 0.5f);

        shape.reset();
        shape.addRoundRect(stem, radius, radius, Path.Direction.CW);
        scratch.reset();
        scratch.addRoundRect(arm, radius, radius, Path.Direction.CW);
        shape.op(scratch, Path.Op.UNION);

        // The wall of the recess: shaded at the top, catching light at the bottom.
        // The same description Section uses, so a cut-out is a cut-out wherever it
        // appears on the panel.
        wall.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{0xBF000000, Color.TRANSPARENT, 0x08FFFFFF},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(shape, body);

        canvas.save();
        canvas.clipPath(shape);
        wall.setStrokeWidth(6f);
        canvas.drawPath(shape, wall);
        canvas.restore();

        canvas.drawPath(shape, lip);
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
