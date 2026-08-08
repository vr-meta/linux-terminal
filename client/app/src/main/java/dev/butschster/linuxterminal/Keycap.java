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
 * A sculpted keycap in a recessed retainer: the most keyboard-like of the
 * shapes, and the one Enter now wears.
 *
 * <p>Three parts, and each does a job the others cannot. The <b>retainer</b> is a
 * shallow pocket in the plate with the cap sitting inside it, so the key is a
 * mounted part rather than a rectangle painted on. The <b>cap</b> carries a
 * three-stop gradient — bright across the top third, mid through the middle, dark
 * at the foot — which is what a dish-shaped moulding does to light coming from
 * above. And the <b>foot</b> below it is solid, unblurred, six pixels tall: that
 * is the height of the cap, and an unblurred copy of a shape offset downwards is
 * the only thing that reads as the side of a solid rather than as a shadow it
 * casts.
 *
 * <p>Pressed, the cap travels the full six pixels into the retainer and the foot
 * collapses to nothing, so the key ends flush with the pocket it lives in. That
 * is a longer throw than any other control on the panel and it is deliberate:
 * this is the key you commit with, and it should feel like it went somewhere.
 *
 * <p>The colours come from the skin rather than from this file. A cap on the flat
 * panel and a cap on the machined one are the same object under different light,
 * and hardcoding the greys here would have made Enter the one control that
 * ignores which panel it is mounted in.
 */
public class Keycap extends Drawable {

    private final int face;
    private final int edge;
    private final float radius;
    private final float travel;
    private final boolean pressed;

    private final Paint retainer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pocket = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ambient = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dish = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF well = new RectF();
    private final RectF cap = new RectF();
    private final RectF stand = new RectF();

    public Keycap(int face, int edge, int plate, float radiusPx, float travelPx,
                  boolean pressed) {
        this.face = face;
        this.edge = edge;
        this.radius = radiusPx;
        this.travel = travelPx;
        this.pressed = pressed;

        retainer.setColor(shade(plate, -10));
        foot.setColor(shade(plate, -18));
        pocket.setStyle(Paint.Style.STROKE);
        lip.setStyle(Paint.Style.STROKE);
        rim.setStyle(Paint.Style.STROKE);
        rim.setColor(edge);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        well.set(bounds.left + 0.5f, bounds.top + 0.5f,
                bounds.right - 0.5f, bounds.bottom - 0.5f);

        float inset = 5f;
        float drop = pressed ? travel : 0f;
        cap.set(well.left + inset, well.top + inset + drop,
                well.right - inset, well.bottom - inset - travel + drop);
        stand.set(cap.left, cap.top + travel, cap.right, cap.bottom + travel);

        // inset 0 2px 6px rgba(0,0,0,.9) — the pocket's own shadow.
        pocket.setShader(new LinearGradient(0, well.top, 0, well.bottom,
                new int[]{0xE6000000, Color.TRANSPARENT},
                new float[]{0f, 0.5f}, Shader.TileMode.CLAMP));

        // 180deg: bright top third, mid, dark foot — a dished moulding under a
        // light that is above and in front.
        body.setShader(new LinearGradient(0, cap.top, 0, cap.bottom,
                new int[]{lighten(face, 0.22f), face, shade(face, -22)},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));

        // inset 0 -12px 18px rgba(0,0,0,.35): the dish, darkening towards the foot
        // of the cap where the surface curves away.
        dish.setShader(new LinearGradient(0, cap.bottom - travel * 2.5f, 0, cap.bottom,
                new int[]{Color.TRANSPARENT, 0x59000000},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));

        lip.setShader(new LinearGradient(0, cap.top, 0, cap.top + 3f,
                new int[]{pressed ? 0x1A000000 : 0x29FFFFFF, Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawRoundRect(well, radius, radius, retainer);
        canvas.save();
        canvas.clipRect(well);
        pocket.setStrokeWidth(6f);
        canvas.drawRoundRect(well, radius, radius, pocket);
        canvas.restore();

        if (!pressed) {
            for (int i = 3; i >= 1; i--) {
                ambient.setColor(Color.argb(20, 0, 0, 0));
                RectF soft = new RectF(stand);
                soft.inset(-i * 1.5f, -i * 1.5f);
                soft.offset(0, i * 1.3f);
                canvas.drawRoundRect(soft, radius + i, radius + i, ambient);
            }
            canvas.drawRoundRect(stand, radius - 2f, radius - 2f, foot);
        }

        canvas.drawRoundRect(cap, radius - 2f, radius - 2f, body);
        canvas.save();
        canvas.clipRect(cap);
        canvas.drawRoundRect(cap, radius - 2f, radius - 2f, dish);
        lip.setStrokeWidth(3f);
        canvas.drawRoundRect(cap, radius - 2f, radius - 2f, lip);
        canvas.restore();

        rim.setStrokeWidth(1f);
        canvas.drawRoundRect(cap, radius - 2f, radius - 2f, rim);
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
