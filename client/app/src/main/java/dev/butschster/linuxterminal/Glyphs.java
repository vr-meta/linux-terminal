package dev.butschster.linuxterminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

/**
 * The bar's own icon alphabet: shapes built from {@link Path}, not borrowed from
 * whatever font Android hands back for a Unicode code point.
 *
 * <p>{@code ← ↓ ↑ → ⌫} looked incoherent for a specific, fixable reason: each of
 * those five characters is a separate glyph in a separate typeface — the system
 * picks whichever installed font covers the code point, per character, with no
 * promise that the result agrees with its neighbours. Their stroke weight,
 * optical centre and corner treatment never matched each other or the rest of
 * the bar. That is what "несуразные" meant, and a font substitution problem
 * cannot be fixed by picking a different character; it has to stop being text.
 *
 * <p>So every shape here comes from one small set of geometric primitives drawn
 * with one {@link Paint}, built once per icon and reused for the whole shape:
 * one stroke width, one cap, one join, everywhere. The four arrows in particular
 * are the <em>same nine points</em>, rotated — see {@link #drawChevron} — so
 * they cannot drift from each other the way four separately hand-picked
 * characters did. Nothing here is filled; a stroked outline is what every glyph
 * on the reference Touch Bar sheet uses (the chevrons, the sun, the speaker),
 * and it is also the cheapest shape to keep readable when it shrinks: a filled
 * glyph loses its silhouette to blur at the edges, a stroke of consistent width
 * degrades gracefully because there is no thin/thick contrast to lose.
 */
public final class Glyphs {

    /**
     * Which shape to draw. LEFT/RIGHT/DOWN are UP's own path, rotated, and
     * PAGE_UP/PAGE_DOWN are that same path drawn twice.
     */
    public enum Kind { LEFT, RIGHT, UP, DOWN, PAGE_UP, PAGE_DOWN, BACKSPACE, ENTER }

    // The stroke is a fraction of the icon box, not a fixed dp number, so it
    // scales with the box instead of thinning out relative to a bigger one if
    // the box size ever changes (see Buttons.iconSizeDp()). 12.5% is heavier
    // than a typical flat-icon stroke — Material's own convention is close to
    // 8% (2dp on a 24dp box) — on purpose: docs/readability.md measured this
    // project's own monospace text falling to "borderline" and then
    // "unreadable" once its angular size dropped under roughly 0.31° through
    // the lenses. A chevron carries far less ink than a letterform's counters
    // and stems, so it has less margin to lose before the same blur erases it
    // completely; the extra weight is that margin, bought back deliberately.
    private static final float STROKE_FRACTION = 0.125f;

    private Glyphs() {}

    /**
     * @param sizeDp the icon's square box, in dp. Callers pass the button's own
     *               icon size (see {@code Buttons.iconSizeDp()}) so every glyph
     *               in the bar — drawn or font-based — sits at one optical size.
     */
    public static Drawable drawable(Context context, Kind kind, int colour, int sizeDp) {
        return new GlyphDrawable(context, kind, colour, sizeDp);
    }

    private static final class GlyphDrawable extends Drawable {
        private final Kind kind;
        private final Paint paint;
        private final int sizePx;

        GlyphDrawable(Context context, Kind kind, int colour, int sizeDp) {
            this.kind = kind;
            this.sizePx = Math.round(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, sizeDp, context.getResources().getDisplayMetrics()));
            this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            // The same shallow shadow the text legends carry, so a drawn key and a
            // typed one look cut by the same tool.
            // Above, so the shape reads as cut into the cap rather than stuck on it.
            paint.setShadowLayer(0.001f, 0f, -Math.max(1f, sizePx * 0.045f), Color.argb(190, 0, 0, 0));
            // Round, everywhere: a mitred chevron tip is a single pixel wide at
            // its very point, which is exactly the feature a lens blurs away
            // first. Rounding it costs a little sharpness up close and buys
            // reliability at distance, which is the trade this bar always makes.
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(colour);
            paint.setStrokeWidth(sizePx * STROKE_FRACTION);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY();
            float r = Math.min(bounds.width(), bounds.height()) * 0.30f;
            switch (kind) {
                case UP:        drawChevrons(canvas, cx, cy, r, 0f, 1);   break;
                case RIGHT:     drawChevrons(canvas, cx, cy, r, 90f, 1);  break;
                case DOWN:      drawChevrons(canvas, cx, cy, r, 180f, 1); break;
                case LEFT:      drawChevrons(canvas, cx, cy, r, 270f, 1); break;
                // Narrower than the single, so the pair occupies about the box one
                // chevron would: a doubled glyph drawn at full size reads as bigger
                // rather than as more, which is the wrong difference to signal.
                case PAGE_UP:   drawChevrons(canvas, cx, cy, r * 0.82f, 0f, 2);   break;
                case PAGE_DOWN: drawChevrons(canvas, cx, cy, r * 0.82f, 180f, 2); break;
                case BACKSPACE: drawBackspace(canvas, bounds);        break;
                case ENTER:     drawEnter(canvas, bounds);            break;
            }
        }

        /**
         * One "^" — three points, two segments — drawn once and rotated onto
         * the canvas for the other three directions. LEFT, RIGHT and DOWN are
         * never drawn as their own shapes; they are this same path turned in
         * 90° steps, which is what makes "identical stroke weight and optical
         * size across the set" a guarantee rather than something four separate
         * drawings have to agree on by hand.
         *
         * <p>Drawn {@code count} times, stacked along the direction it points,
         * for the same reason: paging is one chevron said twice, and the double
         * has to be the single repeated rather than a second drawing that
         * happens to resemble it. The two sit on the far-right strip directly
         * under the scroll rocker, so "one arrow, two arrows" is the whole
         * difference the eye gets between moving this app's transcript and
         * paging inside the program — it cannot afford to also be a difference
         * in weight or in how the tips are cut.
         */
        private void drawChevrons(Canvas canvas, float cx, float cy, float r, float rotation,
                                  int count) {
            canvas.save();
            canvas.rotate(rotation, cx, cy);
            // Spaced by the perpendicular distance between the strokes, not by the
            // vertical offset, because that is what the eye actually sees: the
            // chevron's arms sit at 47.7° (Δy of 1.1r over Δx of r), so a step
            // along y shows up as only cos(47.7°) ≈ 0.67 of itself between the
            // lines. 1.5r therefore leaves a gap of about one stroke width, and
            // the 1.35r it was first written at left roughly three quarters of
            // one — enough to fill in to a single thick mark through the lenses,
            // which is the failure mode this whole file exists to avoid.
            float step = r * 1.5f;
            float first = cy - step * (count - 1) / 2f;
            for (int i = 0; i < count; i++) {
                float y = first + step * i;
                Path path = new Path();
                path.moveTo(cx - r, y + r * 0.55f);
                path.lineTo(cx, y - r * 0.55f);
                path.lineTo(cx + r, y + r * 0.55f);
                canvas.drawPath(path, paint);
            }
            canvas.restore();
        }

        /**
         * The keycap's own outline — a tag pointing left, with a cross where
         * the character it just deleted used to be — rather than the
         * left-pointing chevron already spoken for by the left-arrow key.
         * Backspace moves the cursor left as a side effect, but its meaning is
         * "erase", and giving it the same shape as "move left" would recreate
         * the exact confusion this file exists to remove.
         */
        private void drawBackspace(Canvas canvas, Rect bounds) {
            float half = paint.getStrokeWidth() / 2f;
            float left = bounds.left + half;
            float right = bounds.right - half;
            float top = bounds.top + half + bounds.height() * 0.16f;
            float bottom = bounds.bottom - half - bounds.height() * 0.16f;
            float midY = (top + bottom) / 2f;
            float notchX = left + (right - left) * 0.34f;

            Path outline = new Path();
            outline.moveTo(left, midY);
            outline.lineTo(notchX, top);
            outline.lineTo(right, top);
            outline.lineTo(right, bottom);
            outline.lineTo(notchX, bottom);
            outline.close();
            canvas.drawPath(outline, paint);

            float crossLeft = notchX + (right - notchX) * 0.30f;
            float crossRight = right - (right - notchX) * 0.30f;
            float crossTop = top + (bottom - top) * 0.28f;
            float crossBottom = bottom - (bottom - top) * 0.28f;
            Path cross = new Path();
            cross.moveTo(crossLeft, crossTop);
            cross.lineTo(crossRight, crossBottom);
            cross.moveTo(crossRight, crossTop);
            cross.lineTo(crossLeft, crossBottom);
            canvas.drawPath(cross, paint);
        }

        /**
         * A hooked arrow: along the line, down, back onto the next one — the
         * same pictograph {@code ⏎} drew, replacing the one place outside
         * KeyPad (a context action marked "runs on press") that still typed a
         * Unicode glyph instead of drawing one.
         */
        private void drawEnter(Canvas canvas, Rect bounds) {
            float half = paint.getStrokeWidth() / 2f;
            float left = bounds.left + half + bounds.width() * 0.16f;
            float right = bounds.right - half - bounds.width() * 0.16f;
            float top = bounds.top + half + bounds.height() * 0.18f;
            float bottom = bounds.bottom - half - bounds.height() * 0.18f;
            float hookX = left + (right - left) * 0.32f;

            Path stem = new Path();
            stem.moveTo(right, top);
            stem.lineTo(right, bottom);
            stem.lineTo(hookX, bottom);
            canvas.drawPath(stem, paint);

            float armX = hookX + (right - hookX) * 0.42f;
            float armTopY = top + (bottom - top) * 0.30f;
            float armBottomY = bottom - (bottom - top) * 0.05f;
            Path head = new Path();
            head.moveTo(armX, armTopY);
            head.lineTo(hookX, bottom);
            head.lineTo(armX, armBottomY);
            canvas.drawPath(head, paint);
        }

        @Override public int getIntrinsicWidth() { return sizePx; }
        @Override public int getIntrinsicHeight() { return sizePx; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
