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
 * A window cut into the panel: dark glass, set below the surface, with a phosphor
 * lit behind it.
 *
 * <p>The opposite object to {@link KeyFace} and lit by the same lamp, which is the
 * whole reason it reads as recessed rather than merely dark. A cap standing above
 * the plate catches light on its top edge and casts a shadow below it; a well sunk
 * into the plate is shadowed by its own upper wall and catches light on the lower
 * one. Same light, inverted geometry — and that inversion is what the eye reads,
 * not the darkness of the fill.
 *
 * <p>Why a display and not simply another group of keys: a key is a thing you press
 * and a display is a thing you read, and the bar does both. Where you are, and what
 * you could go to, are information — putting them behind glass says so before a
 * word is read, and it takes them out of the population of things that look
 * pressable. What stays a moulded cap is what is genuinely a key.
 *
 * <p>The glass carries a faint diagonal sheen across the top. It is the one purely
 * decorative thing in this file and it earns its place by killing the flatness that
 * a plain black rectangle has: a real cover glass reflects the room, and without any
 * reflection at all the well reads as a hole rather than as a window.
 */
public class Display extends Drawable {

    private final int glass;
    private final int depth;
    private final Skin.Screen manner;
    private final int edge;
    private final boolean crt;

    /** A hairline around the glass, drawn over the wall. Zero means none. */
    private int bezel;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scan = new Paint();
    private final Paint vignette = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF face = new RectF();
    private final float radius;

    /**
     * @param glass    the colour of the unlit screen — near black, never black. A
     *                 true black cannot be shaded, so the well would have no walls.
     * @param depthPx  how deep the recess is; the width of the shaded wall.
     * @param radiusPx corner radius, matched to the keys so the panel reads as one
     *                 design rather than as two.
     */
    public Display(int glass, float depthPx, float radiusPx, Skin.Screen manner, int edge) {
        this(glass, depthPx, radiusPx, manner, edge, false);
    }

    public Display withBezel(int colour) {
        this.bezel = colour;
        return this;
    }

    public Display(int glass, float depthPx, float radiusPx, Skin.Screen manner, int edge,
                   boolean crt) {
        this.crt = crt;
        this.glass = glass;
        this.manner = manner;
        this.edge = edge;
        // A framed screen has no wall to speak of: the line around it is a line,
        // the same weight as the line around a key, not a modelled thickness.
        this.depth = manner == Skin.Screen.FRAMED ? 1 : (int) Math.max(1f, depthPx);
        this.radius = radiusPx;
        wall.setStyle(Paint.Style.STROKE);
        wall.setStrokeWidth(this.depth);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        face.set(bounds.left + depth * 0.5f, bounds.top + depth * 0.5f,
                bounds.right - depth * 0.5f, bounds.bottom - depth * 0.5f);

        // Darker at the top, where the recess is deepest in shadow, lifting very
        // slightly towards the bottom where the lower wall bounces light back in.
        body.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                shade(glass, -3), shade(glass, 6), Shader.TileMode.CLAMP));

        switch (manner) {
            case FRAMED:
                // One flat line in the accent, at the alpha the panel's own keys are
                // outlined with. Nothing is being modelled here, so nothing gradates.
                wall.setShader(null);
                wall.setColor(Color.argb(150, Color.red(edge), Color.green(edge),
                        Color.blue(edge)));
                break;
            case INSET:
                // Only the shadow along the top wall. The reflected light on the
                // lower one is what sells a deep recess, and claiming a deep recess
                // in a skin whose keys are 1px proud is claiming too much.
                wall.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                        new int[]{Color.argb(120, 0, 0, 0), Color.TRANSPARENT},
                        new float[]{0f, 0.6f}, Shader.TileMode.CLAMP));
                break;
            default:
                // The wall of the well: shadow along the top, light along the bottom.
                // The exact inverse of a key's bevel, deliberately — the two are
                // drawn from one description of where the lamp is.
                wall.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                        new int[]{Color.argb(150, 0, 0, 0), Color.argb(20, 0, 0, 0),
                                Color.argb(40, 255, 255, 255)},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                break;
        }

        sheen.setShader(new LinearGradient(face.left, face.top, face.right, face.bottom * 0.5f,
                new int[]{Color.argb(16, 255, 255, 255), Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));

        if (crt) {
            // Darkening towards the edges, the way a tube falls off away from the
            // centre of its deflection. Radial rather than linear, because the
            // falloff is about distance from the middle in both axes.
            float cx = face.centerX();
            float cy = face.centerY();
            float r = Math.max(face.width(), face.height()) * 0.62f;
            vignette.setShader(new android.graphics.RadialGradient(cx, cy, Math.max(1f, r),
                    new int[]{Color.TRANSPARENT, Color.argb(58, 0, 0, 0)},
                    new float[]{0.55f, 1f}, Shader.TileMode.CLAMP));
        }
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawRoundRect(face, radius, radius, body);
        // No reflection on a framed screen: there is no glass over it to reflect
        // anything, only an outlined region of the deck.
        if (manner != Skin.Screen.FRAMED) {
            canvas.drawRoundRect(face, radius, radius, sheen);
        }
        if (crt) {
            // Every third device pixel, at 6% black. Fine enough that at any
            // sensible text size it reads as a surface the letters sit on rather
            // than as stripes across them — `docs/readability.md` is the reason it
            // is not the fat, obvious scanline of a filter.
            canvas.save();
            canvas.clipRect(face);
            scan.setColor(Color.argb(15, 0, 0, 0));
            for (float y = face.top; y < face.bottom; y += 3f) {
                canvas.drawRect(face.left, y, face.right, y + 1f, scan);
            }
            canvas.drawRoundRect(face, radius, radius, vignette);
            canvas.restore();
        }

        canvas.drawRoundRect(face, radius, radius, wall);

        if (bezel != 0) {
            // The frame of the glass, one pixel, in the screen's own colour family.
            // The shaded wall above does the depth; this only says where the glass
            // ends — and it has to be a hairline, because a thick frame around a
            // panel of text is read as a border and starts competing with it.
            wall.setShader(null);
            wall.setStrokeWidth(1f);
            wall.setColor(bezel);
            canvas.drawRoundRect(face, radius, radius, wall);
        }
    }

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
