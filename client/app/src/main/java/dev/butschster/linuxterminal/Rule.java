package dev.butschster.linuxterminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;

/**
 * The line between one group of context buttons and the next — the same hairline,
 * in the same colour, as the vertical divider that separates the two panels, so
 * that the bar reads as one ruled sheet rather than two ideas about lines.
 *
 * <p>It is a class rather than a styled {@code View} for two reasons, both to do
 * with how {@link FlowLayout} measures. Children are offered {@code AT_MOST} the
 * available width and a view with no content answers zero, so an ordinary divider
 * collapsed and drifted to wherever the row happened to wrap. And the panel insets
 * its buttons, which would inset the rule with them; a rule that stops short of
 * the edge is a rule that looks like a mistake. This one measures to the full
 * width and draws out through its parent's padding — which is why the parent sets
 * {@code clipToPadding(false)}.
 */
public class Rule extends View {

    private final Paint paint = new Paint();
    private final int thickness;
    private final int spaceAbove;

    public Rule(Context context, Buttons buttons) {
        super(context);
        // One device pixel, matching the vertical divider exactly. Thin enough to
        // disappear in the headset's lenses on its own; it does not have to carry
        // meaning at a glance, only to be found when the eye goes looking.
        thickness = Math.max(1, buttons.dp(1));
        // The bar's one inset, not a number of its own. A rule spaced differently
        // from everything around it reads as carelessness long before anyone works
        // out why.
        spaceAbove = buttons.inset();
        paint.setColor(Buttons.RULE);
        setTag(FlowLayout.BREAK);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), spaceAbove + thickness);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Out through whatever the parent indents its buttons by, to the panel's
        // own edges. Reading it from the parent rather than repeating the number
        // keeps the two from drifting apart the next time padding is adjusted.
        int bleedLeft = 0;
        int bleedRight = 0;
        if (getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) getParent();
            bleedLeft = parent.getPaddingLeft();
            bleedRight = parent.getPaddingRight();
        }
        canvas.drawRect(-bleedLeft, spaceAbove,
                getWidth() + bleedRight, spaceAbove + thickness, paint);
    }
}
