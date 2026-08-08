package dev.butschster.linuxterminal;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
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
 * The hazard-striped face: diagonal yellow and near-black bands, the marking a
 * machine puts on the control that does the irreversible thing.
 *
 * <p>It is the loudest object on the panel and that is the point. Everything else
 * is graphite with a coloured edge, so a striped bar is not a louder version of a
 * key — it is a different category of thing, which is exactly the claim Enter
 * needs to make. The stripes are also the one pattern here that survives being
 * looked at indirectly: you can find it in peripheral vision, which no legend can.
 *
 * <p>The bands are generated into a small tile and repeated by a shader rather
 * than drawn as rotated rectangles. A tile costs one allocation and tiles exactly;
 * rotated rectangles have to be over-drawn past the corners and clipped, and the
 * clip is where the diagonal ends up with a stair-stepped edge.
 *
 * <p>Pressed, the cap travels into the plate and the extrusion under it collapses
 * — the same description every other key on this panel uses. A control that
 * announces itself differently should still <em>behave</em> like the rest.
 */
public class HazardFace extends Drawable {

    /** -45°, twelve pixels of each band: the reference sheet's own numbers. */
    private static final int BAND = 12;
    private static final int TILE = BAND * 2;

    private static final int YELLOW = 0xFFF2B613;
    private static final int DARK = 0xFF1A1508;
    private static final int EDGE = 0xFF8A6A12;
    private static final int FOOT = 0xFF4A3A08;

    private static Bitmap stripes;

    private final float radius;
    private final float depth;
    private final boolean pressed;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ambient = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF cap = new RectF();
    private final RectF extrusion = new RectF();

    public HazardFace(float radiusPx, float depthPx, boolean pressed) {
        this.radius = radiusPx;
        this.depth = depthPx;
        this.pressed = pressed;

        body.setShader(tile());
        solid.setColor(FOOT);
        sheen.setStyle(Paint.Style.STROKE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(EDGE);
        inner.setStyle(Paint.Style.STROKE);
    }

    /** One tile of banding, made once for the life of the process. */
    private static synchronized Shader tile() {
        if (stripes == null) {
            stripes = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[TILE * TILE];
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    // (x - y) mod period leans the bands to the left, which is the
                    // direction -45deg means; (x + y) would lean them the other way
                    // and read as a different marking entirely.
                    int band = Math.floorMod(x - y, TILE);
                    pixels[y * TILE + x] = band < BAND ? YELLOW : DARK;
                }
            }
            stripes.setPixels(pixels, 0, TILE, 0, 0, TILE, TILE);
        }
        return new BitmapShader(stripes, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        float stand = pressed ? 1f : depth;
        cap.set(bounds.left + 0.5f, bounds.top + 0.5f,
                bounds.right - 0.5f, bounds.bottom - depth - 0.5f);
        extrusion.set(cap.left, cap.top + stand, cap.right, cap.bottom + stand);

        // inset 0 1px 0 rgba(255,255,255,.3) — a hard lit line along the top edge,
        // which is what a moulded plastic bar has where the mould parts.
        sheen.setShader(new LinearGradient(0, cap.top, 0, cap.top + 2f,
                new int[]{0x4DFFFFFF, Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));

        inner.setShader(new LinearGradient(0, cap.top, 0, cap.top + depth * 2f,
                new int[]{0x80000000, Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        if (!pressed) {
            for (int i = 3; i >= 1; i--) {
                ambient.setColor(Color.argb(22, 0, 0, 0));
                RectF soft = new RectF(extrusion);
                soft.inset(-i * 1.5f, -i * 1.5f);
                soft.offset(0, i * 1.4f);
                canvas.drawRoundRect(soft, radius + i, radius + i, ambient);
            }
            canvas.drawRoundRect(extrusion, radius, radius, solid);
        }

        canvas.drawRoundRect(cap, radius, radius, body);

        if (pressed) {
            canvas.save();
            canvas.clipRect(cap);
            inner.setStrokeWidth(depth);
            canvas.drawRoundRect(cap, radius, radius, inner);
            canvas.restore();
        } else {
            canvas.save();
            canvas.clipRect(cap);
            sheen.setStrokeWidth(2f);
            canvas.drawRoundRect(cap, radius, radius, sheen);
            canvas.restore();
        }

        edge.setStrokeWidth(1f);
        canvas.drawRoundRect(cap, radius, radius, edge);
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
