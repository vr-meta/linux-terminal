package dev.butschster.linuxterminal;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The plate the keys are mounted on.
 *
 * <p>Lit by the same source as {@link KeyFace} — above and in front — so the top
 * of the plate is fractionally brighter than the bottom. The gradient is about
 * 6%, far too little to notice as a gradient and just enough that the plate stops
 * looking like a flat fill; a bar where the keys are lit and the plate is not
 * reads as keys pasted onto a background.
 *
 * <p>The brushing runs horizontally, the length of the panel, which is how a
 * plate this shape would actually be finished.
 */
public class Deck extends Drawable {

    private final int colour;
    private final boolean textured;

    private final Paint base = new Paint();
    private final Paint texture = new Paint();
    private final Paint edge = new Paint();

    public Deck(int colour, boolean textured) {
        this.colour = colour;
        this.textured = textured;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        base.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                shade(colour, 7), shade(colour, -5), Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawRect(getBounds(), base);

        // The housing's own edges: a hairline of light along the top of the panel
        // and a darker one along the bottom. One pixel each — this is the outermost
        // surface, and anything heavier here competes with the controls on it.
        Rect bounds = getBounds();
        edge.setColor(0x0FFFFFFF);
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + 1, edge);
        edge.setColor(0xA6000000);
        canvas.drawRect(bounds.left, bounds.bottom - 2, bounds.right, bounds.bottom, edge);

        if (!textured) return;

        // Fainter than on a key, deliberately. The plate is the largest surface in
        // the window, so the same alpha that reads as a finish on a 46dp cap reads
        // as dirt across 800dp of panel.
        texture.setShader(Wear.grain());
        texture.setAlpha(13);
        canvas.drawRect(getBounds(), texture);

        texture.setShader(Wear.use());
        texture.setAlpha(19);
        canvas.drawRect(getBounds(), texture);
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
        return PixelFormat.OPAQUE;
    }
}
