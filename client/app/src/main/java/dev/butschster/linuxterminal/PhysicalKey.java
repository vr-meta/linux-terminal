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
 * The whole recipe for a shallow physical control, in the order the layers are
 * laid down:
 *
 * <pre>
 *   ambient blur        soft, wide, well below the cap
 *   solid extrusion     hard-edged, no blur — this is the cap's height
 *   face                three-stop vertical gradient
 *   top bevel           1px of white inside the upper edge
 *   bottom bevel        1px of black inside the lower edge
 *   border              1px, coloured by the control's role
 * </pre>
 *
 * <p>The solid extrusion is the layer that does the work and the one a flat design
 * leaves out. A blurred shadow says "this floats"; an unblurred copy of the shape,
 * offset downwards, says "this is a solid object of that height", because that is
 * what the side of an extruded cap actually looks like. The blur underneath is the
 * ambient light in the room, and it is the smaller effect of the two.
 *
 * <p>Pressing inverts the whole description at once rather than tinting it. The
 * cap travels down (see {@code Buttons.travel}), the extrusion collapses to almost
 * nothing, the face gradient flips so the light now comes from below, and a real
 * inner shadow appears along the top edge — which is the shadow of the housing
 * falling onto a cap that has gone into it.
 *
 * <p>Blur is faked by stacking translucent copies rather than by
 * {@link android.graphics.BlurMaskFilter}: a mask filter forces the view onto a
 * software layer, and the left half of this bar is rebuilt whenever the foreground
 * process changes. At this radius the stack is indistinguishable and costs nothing.
 */
public class PhysicalKey extends Drawable {

    private final int face;
    private final int border;
    private final int legend;
    private final float radius;
    private final float depth;
    private final boolean pressed;

    /** Extra illumination around the whole control: focus, or a primary action. */
    private final int halo;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ambient = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bevel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF cap = new RectF();
    private final RectF extrusion = new RectF();

    /**
     * Per-corner radii, when this key is one cell of a cluster.
     *
     * <p>A key sitting alone is rounded on all four corners. A key that is one of
     * several inside a single housing is rounded only where it meets the outside
     * world and square where it meets its neighbour — which is what makes a block
     * of keys read as one part with seams rather than as separate caps that happen
     * to be close together. Null means the ordinary all-round radius.
     */
    private float[] radii;

    private final android.graphics.Path shape = new android.graphics.Path();

    /** The same key, rounded only on the corners given. See {@link #radii}. */
    public PhysicalKey corners(float[] perCorner) {
        this.radii = perCorner;
        return this;
    }

    public PhysicalKey(int face, int border, int legend, float radiusPx, float depthPx,
                       boolean pressed, int halo) {
        this.face = face;
        this.border = border;
        this.legend = legend;
        this.radius = radiusPx;
        this.depth = depthPx;
        this.pressed = pressed;
        this.halo = halo;

        bevel.setStyle(Paint.Style.STROKE);
        edge.setStyle(Paint.Style.STROKE);
        inner.setStyle(Paint.Style.STROKE);
        glow.setStyle(Paint.Style.STROKE);
        solid.setColor(0xE0090D13);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        // The drawable is asked for the whole cell and gives the height back to the
        // shadow. The view's own translationY does the travelling, so the cap keeps
        // the same size in both states and only what is under it changes.
        float stand = pressed ? Math.min(depth, 1f) : depth;
        cap.set(bounds.left + 0.5f, bounds.top + 0.5f,
                bounds.right - 0.5f, bounds.bottom - depth - 0.5f);
        extrusion.set(cap.left, cap.top + stand, cap.right, cap.bottom + stand);

        int top, mid, low;
        if (pressed) {
            // 180deg #192330 → #222d3b: darker at the top now, because the cap has
            // gone into the housing and the light no longer reaches its upper edge.
            top = 0xFF192330;
            mid = 0xFF1D2735;
            low = 0xFF222D3B;
        } else {
            top = lighten(face, 0.14f);
            mid = face;
            low = shade(face, -12);
        }
        body.setShader(new LinearGradient(0, cap.top, 0, cap.bottom,
                new int[]{top, mid, low}, new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));

        bevel.setShader(new LinearGradient(0, cap.top, 0, cap.bottom,
                new int[]{
                        pressed ? Color.TRANSPARENT : 0x1CFFFFFF,
                        Color.TRANSPARENT,
                        pressed ? 0x0AFFFFFF : 0x73000000,
                },
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));

        // The housing's shadow falling onto a cap that is now inside it. Only when
        // pressed: an unpressed cap stands proud of the housing and has nothing
        // above it to cast one.
        inner.setShader(new LinearGradient(0, cap.top, 0, cap.top + depth * 2f,
                new int[]{0xA6000000, Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        if (!pressed) {
            // Ambient: three copies, each wider and fainter, standing in for a
            // 9px blur at 35%.
            for (int i = 3; i >= 1; i--) {
                ambient.setColor(Color.argb(20, 0, 0, 0));
                RectF soft = new RectF(extrusion);
                soft.inset(-i * 1.5f, -i * 1.5f);
                soft.offset(0, i * 1.2f);
                canvas.drawRoundRect(soft, radius + i, radius + i, ambient);
            }
        }

        // The height of the cap: hard-edged, so it reads as the side of a solid.
        drawShape(canvas, extrusion, solid);
        drawShape(canvas, cap, body);

        if (pressed) {
            canvas.save();
            canvas.clipRect(cap);
            inner.setStrokeWidth(depth);
            drawShape(canvas, cap, inner);
            canvas.restore();
        }

        bevel.setStrokeWidth(1f);
        drawShape(canvas, cap, bevel);

        if (halo != 0) {
            // Illumination is two rings, not a blur: the outer one is what a glow
            // reads as at this size, and it stays inside the cell so no container
            // has to stop clipping.
            // Halved from the first attempt. Illumination marks which control is
            // primary; at the old strength it was brighter than the legend it was
            // meant to draw attention to, and the panel read as decorated.
            glow.setStrokeWidth(3f);
            glow.setColor(withAlpha(halo, 16));
            canvas.drawRoundRect(cap, radius, radius, glow);
            glow.setStrokeWidth(1.5f);
            glow.setColor(withAlpha(halo, 38));
            canvas.drawRoundRect(cap, radius, radius, glow);
        }

        edge.setStrokeWidth(1f);
        edge.setColor(border);
        drawShape(canvas, cap, edge);
    }

    private void drawShape(Canvas canvas, RectF rect, Paint paint) {
        if (radii == null) {
            canvas.drawRoundRect(rect, radius, radius, paint);
            return;
        }
        shape.reset();
        shape.addRoundRect(rect, radii, android.graphics.Path.Direction.CW);
        canvas.drawPath(shape, paint);
    }

    /** The colour this control's legend is written in. */
    public int legend() {
        return legend;
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
