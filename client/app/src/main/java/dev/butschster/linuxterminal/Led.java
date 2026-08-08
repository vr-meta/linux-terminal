package dev.butschster.linuxterminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * A status lamp: a small dot that is either dark or lit with a halo around it.
 *
 * <p>It reports one bit and says so instantly, which is the whole reason a panel
 * has lamps rather than another line of text. Unlit it is not black but a dark
 * version of its own colour — an unlit LED still has a coloured lens, and a black
 * dot reads as a hole in the panel.
 */
public class Led extends View {

    private final int colour;
    private boolean on;

    private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Led(Context context, int colour, boolean on) {
        super(context);
        this.colour = colour;
        this.on = on;
    }

    public void setOn(boolean lit) {
        this.on = lit;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy);

        if (on) {
            // Two rings standing in for the bloom around a lit lamp. Drawn inside
            // the view, so no parent has to stop clipping to show it.
            halo.setColor(Color.argb(70, Color.red(colour), Color.green(colour),
                    Color.blue(colour)));
            canvas.drawCircle(cx, cy, r, halo);
            halo.setColor(Color.argb(120, Color.red(colour), Color.green(colour),
                    Color.blue(colour)));
            canvas.drawCircle(cx, cy, r * 0.78f, halo);
        }

        lens.setColor(on ? colour : dark(colour));
        canvas.drawCircle(cx, cy, r * 0.55f, lens);
    }

    /** The lens with the lamp behind it off: the colour at a fifth, over the panel. */
    private static int dark(int colour) {
        return Color.rgb(
                (int) (Color.red(colour) * 0.22f) + 20,
                (int) (Color.green(colour) * 0.22f) + 26,
                (int) (Color.blue(colour) * 0.22f) + 22);
    }
}
