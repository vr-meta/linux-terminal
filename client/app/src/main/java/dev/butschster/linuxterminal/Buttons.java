package dev.butschster.linuxterminal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * How a button in the bar is built, sized and coloured.
 *
 * <p>Sized for a controller ray rather than a fingertip: at arm's length the ray carries
 * roughly a degree of error at the moment the trigger is pulled, so targets need room and
 * the space between them has to be genuinely dead. The first version used phone-sized
 * buttons with 6dp gaps and missed on both counts.
 *
 * <p>But not as much room as the arithmetic asks for. A derivation from that error budget
 * wanted ~100dp cells; in the headset that read as oversized, and the person wearing it is
 * the measurement. These numbers are the middle: comfortably larger than the phone-sized
 * original, comfortably smaller than the theory. Adjust them here and nowhere else.
 */
public class Buttons {

    public static final int KEY = 0;
    public static final int DESTINATION = 1;
    public static final int COMMAND = 2;
    public static final int WARN = 3;
    public static final int ENTER = 4;
    public static final int VOICE = 5;
    public static final int ARROW = 6;

    // Four roles, not eight decorative shades. The previous palette had two greys
    // sixteen units apart, which through pancake lenses is one grey.
    private static final int[] FILL = {
            Color.rgb(58, 60, 70),      // KEY — a literal keystroke
            Color.rgb(52, 58, 74),      // DESTINATION — somewhere to go
            Color.rgb(40, 82, 128),     // COMMAND — a line that runs
            Color.rgb(122, 58, 30),     // WARN — destructive
            Color.rgb(38, 86, 62),      // ENTER
            Color.rgb(52, 118, 84),     // VOICE
            Color.rgb(74, 80, 104),     // ARROW — the cluster the hand finds without looking
    };

    public static final int TEXT = Color.rgb(224, 226, 232);
    public static final int MUTED = Color.rgb(150, 155, 168);

    /**
     * Every line in the bar, horizontal or vertical. One constant because they are
     * one idea: the rules between groups and the divider between the panels have to
     * be the same weight and the same colour, or the bar looks like two designs.
     */
    public static final int RULE = Color.rgb(52, 54, 64);

    /**
     * The keyboard mark, from the bundled Material font.
     *
     * <p>Lives here rather than in one screen because two windows now need the same
     * one: the bar's own on-screen-keyboard control, and the button in the terminal
     * that brings the bar back. They mean the same thing to a person — "the keys" —
     * and two copies of a codepoint is how they would quietly stop matching.
     */
    public static final String ICON_KEYBOARD = "\ue312";

    /** Auto-repeat, for arrows and paging. */
    private static final long REPEAT_DELAY_MS = 400;
    private static final long REPEAT_INTERVAL_MS = 90;

    /** A trigger pull can bounce; a doubled Enter is not undoable. */
    private static final long DEBOUNCE_MS = 250;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Typeface icons;

    private int textDp = 15;
    private int padH = 11;
    private int padV = 8;
    private int minWidth = 46;
    private int minHeight = 40;
    private int gap = 8;

    public Buttons(Context context, Typeface icons) {
        this.context = context;
        this.icons = icons;
    }

    public int gap() {
        return dp(gap);
    }

    /**
     * The gap beside a destructive control — twice the ordinary one. Next to
     * another harmless key, a ray that drifts a little lands on a second
     * harmless key; next to one that forgets a server or wipes a conversation,
     * the same drift has to land on nothing instead. One number, so a card and
     * a bar that both need it stay in step.
     */
    public int dangerGap() {
        return dp(gap * 2);
    }

    /**
     * How far anything sits from the edge of the panel it is in — one number for
     * the whole bar, because an inset that differs between two blocks reads as a
     * misalignment however defensible each half is on its own.
     *
     * <p>Watch for the cells that carry their own margin: {@link KeyPad} gives
     * every key half a gap on each side, so a container holding those pads by
     * {@code inset() - gap() / 2} and the key still lands on this line.
     */
    public int inset() {
        return dp(10);
    }

    /**
     * The optical size every icon in the bar draws at, drawn or font-based alike —
     * {@link #icon} already sizes the Material Icons font this many dp above the key
     * text, so {@link Glyphs} icons take the same number instead of inventing their
     * own, and the two systems read as one alphabet rather than two adjacent ones.
     */
    public int iconSizeDp() {
        return textDp + 3;
    }

    // ------------------------------------------------------------------ making

    public TextView key(String label, int style, String hint, View.OnClickListener onClick) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextColor(TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp);
        view.setTypeface(Typeface.MONOSPACE);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        view.setMinWidth(dp(minWidth));
        view.setMinHeight(dp(minHeight));
        view.setBackground(background(FILL[style]));
        engrave(view);
        view.setClickable(true);
        view.setFocusable(false);
        debounced(view, onClick);
        if (hint != null && !hint.isEmpty()) view.setContentDescription(hint);
        return view;
    }

    /**
     * The shallow shadow that makes a legend look cut into the cap rather than
     * printed on it.
     *
     * <p>Cut one way rather than the other, deliberately. A true engraving darkens
     * the letter and lights its lower lip, and darkening is the one thing this bar
     * cannot spend: `docs/readability.md` measures where text stops being readable
     * through the lenses and the sizes here are already near it. A shadow below
     * full-strength text buys the same depth and costs no contrast.
     */
    private void engrave(TextView view) {
        // Above the letter, not below it. A shadow underneath means the letter
        // stands on the cap and throws its shadow down — a sticker. A groove is
        // shadowed by its own upper edge, so the shadow goes on top and the letter
        // reads as cut in. This also settles what looked like a trade earlier:
        // direction alone gives the engraving, and none of the contrast that
        // darkening the letter would have cost.
        //
        // Radius almost zero rather than zero: a blur radius of 0 is not drawn at
        // all on a hardware canvas, and a hair above it is the hard-edged copy that
        // reads as an edge rather than a glow.
        view.setShadowLayer(0.001f, 0f, -dp(1), Color.argb(190, 0, 0, 0));
    }

    public TextView icon(String glyph, int style, String hint, View.OnClickListener onClick) {
        TextView view = key(glyph, style, hint, onClick);
        view.setTypeface(icons);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconSizeDp());
        return view;
    }

    /**
     * A key whose face is drawn with {@link Glyphs} instead of typed as a Unicode
     * character: the arrow keys and backspace. The label stays empty — the drawable
     * is the label — so {@link #key}'s own centring puts the shape dead centre of
     * the cell with no text to share the box with.
     */
    public TextView glyphKey(Glyphs.Kind kind, int style, String hint, View.OnClickListener onClick) {
        TextView view = key("", style, hint, onClick);
        centre(view, kind);
        return view;
    }

    /**
     * Puts the glyph dead centre of the key, which a compound drawable does not.
     * A compound drawable is laid out beside the text and the pair is centred
     * together — with no text that is a box of one item plus the gap where the
     * text would have been, so every drawn key sat visibly left of centre. A
     * foreground is drawn over the cap at its own gravity and takes no part in
     * layout at all.
     */
    private void centre(TextView view, Glyphs.Kind kind) {
        view.setCompoundDrawables(null, null, null, null);
        Drawable glyph = Glyphs.drawable(context, kind, TEXT, iconSizeDp());
        LayerDrawable stack = new LayerDrawable(new Drawable[]{view.getBackground(), glyph});
        stack.setLayerGravity(1, Gravity.CENTER);
        stack.setLayerSize(1, glyph.getIntrinsicWidth(), glyph.getIntrinsicHeight());
        view.setBackground(stack);
    }

    /**
     * Two keys under one cap, split by a hairline — bigger and smaller, up and
     * down. They are one control because they are one decision taken twice, and a
     * rocker says so the way two loose buttons never do: the pair cannot drift
     * apart, and the hand learns one place instead of two.
     *
     * <p>The cap owns the shape and clips to it, so each half can be a plain
     * rectangle that lights up when pressed and still comes out round at the
     * corners it shares with the cap.
     */
    public LinearLayout rocker(View top, View bottom) {
        LinearLayout cap = new LinearLayout(context);
        cap.setOrientation(LinearLayout.VERTICAL);
        cap.setBackground(rocked());
        cap.setClipToOutline(true);

        cap.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Two lines, not one: a groove is dark on its upper side and catches light
        // on its lower one, which is what tells the eye there is a step here at all.
        // A single hairline in the cap's own colour family disappeared against it.
        View shadowLine = new View(context);
        shadowLine.setBackgroundColor(shade(FILL[KEY], -34));
        cap.addView(shadowLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        View lightLine = new View(context);
        lightLine.setBackgroundColor(shade(FILL[KEY], 26));
        cap.addView(lightLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        cap.addView(bottom, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return cap;
    }

    /**
     * The cap of a rocker, lit the way a rocker is lit: brighter along the top and
     * bottom edges, falling away towards the split in the middle. That is what the
     * shape does to light — both halves tilt up at their outer edge — so the
     * gradient is a description of the object rather than decoration on it, and it
     * says which way each half will go before it is pressed.
     */
    private GradientDrawable rocked() {
        GradientDrawable shape = new GradientDrawable();
        shape.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        shape.setColors(new int[]{shade(FILL[KEY], 16), shade(FILL[KEY], -10), shade(FILL[KEY], 16)});
        shape.setCornerRadius(dp(8));
        return shape;
    }

    /** Lighter or darker by an equal step on every channel, so the hue does not drift. */
    private static int shade(int colour, int step) {
        return Color.rgb(
                Math.max(0, Math.min(255, Color.red(colour) + step)),
                Math.max(0, Math.min(255, Color.green(colour) + step)),
                Math.max(0, Math.min(255, Color.blue(colour) + step)));
    }

    /**
     * One half of a {@link #rocker}: a key with no cap of its own, so the cap's
     * corners are the only ones in play.
     */
    public TextView half(String label, String hint, View.OnClickListener onClick) {
        TextView view = key(label, KEY, hint, onClick);
        view.setBackground(pressOnly());
        return view;
    }

    public TextView glyphHalf(Glyphs.Kind kind, String hint, View.OnClickListener onClick) {
        TextView view = half("", hint, onClick);
        centre(view, kind);
        return view;
    }

    /** Transparent until pressed, and then only a wash: the cap underneath is the button. */
    private StateListDrawable pressOnly() {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Color.argb(70, 255, 255, 255));
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(Color.TRANSPARENT);
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, idle);
        return states;
    }

    /**
     * A browser tab: a title that selects, and a close of its own.
     *
     * <p>Shaped the way a browser shapes them — rounded at the top, square at the bottom,
     * and the active one filled with the terminal's own colour so it reads as continuous
     * with what is below it. That is not decoration: with several shells open, "which one
     * am I typing into" has to be answerable without reading.
     *
     * <p>The close sits at the far end of the tab with a gap before it, and it is the only
     * thing on the strip that can destroy something. A ray that lands short selects the
     * tab, which is harmless.
     */
    public LinearLayout tab(String title, boolean active, int contentColour, int stripColour,
                            View.OnClickListener onSelect, View.OnClickListener onClose) {
        LinearLayout tab = new LinearLayout(context);
        tab.setOrientation(LinearLayout.HORIZONTAL);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setPadding(dp(padH), dp(padV - 2), dp(8), dp(padV - 2));
        tab.setMinimumHeight(dp(minHeight));
        // Active takes the terminal's colour, inactive takes the strip's: the same
        // trick the desktop terminal fork uses, and the reason a Chrome tab reads
        // as part of the page rather than as a button sitting above it.
        tab.setBackground(tabBackground(active ? contentColour : stripColour, active));
        tab.setClickable(true);
        debounced(tab, onSelect);

        TextView label = new TextView(context);
        label.setText(title);
        label.setTextColor(active ? TEXT : MUTED);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp);
        label.setTypeface(Typeface.MONOSPACE);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        label.setMaxWidth(dp(220));
        tab.addView(label);

        TextView close = new TextView(context);
        close.setText("×");
        close.setTextColor(MUTED);
        close.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp + 2);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(8), dp(3), dp(8), dp(5));
        close.setMinWidth(dp(34));
        close.setMinHeight(dp(30));
        close.setBackground(background(Color.argb(0, 0, 0, 0)));
        close.setClickable(true);
        debounced(close, onClose);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(8));
        tab.addView(close, params);

        return tab;
    }

    /** Rounded at the top, square at the bottom, the way a tab meets its content. */
    private StateListDrawable tabBackground(int fill, boolean active) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_hovered},
                tabShape(lighten(fill, active ? 0.08f : 0.14f)));
        states.addState(new int[]{}, tabShape(fill));
        return states;
    }

    private GradientDrawable tabShape(int colour) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        float r = dp(10);
        shape.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return shape;
    }

    /**
     * Pressed and hovered states. With no touch and no mouse, the highlight under the ray
     * is the only thing that says where a press would land before it lands there.
     */
    private StateListDrawable background(int fill) {
        StateListDrawable states = new StateListDrawable();
        // Pressed is the one state with no lip: the cap sits down flush with the
        // deck, which is what pressing a key does and costs nothing to draw.
        states.addState(new int[]{android.R.attr.state_pressed}, solid(lighten(fill, 0.35f)));
        states.addState(new int[]{android.R.attr.state_hovered}, capped(lighten(fill, 0.18f)));
        states.addState(new int[]{}, capped(fill));
        return states;
    }

    /**
     * A key with a lip: the cap, and under it a darker copy showing along the
     * bottom edge.
     *
     * <p>The shadow lives inside the view's own bounds rather than being cast
     * outside it by elevation. Elevation would be the obvious way and is the wrong
     * one here — the shadow falls outside the child, so every container the keys
     * sit in would have to stop clipping, and one that was missed would clip a
     * shadow off mid-row. Drawn within, it cannot be cropped by anything.
     */
    private LayerDrawable capped(int fill) {
        GradientDrawable lip = new GradientDrawable();
        lip.setColor(shade(fill, -26));
        lip.setCornerRadius(dp(8));

        // The face is flat. The volume comes from the lip below it and nothing
        // else: a gradient across the cap was doing a second job the lip already
        // does, and two cues for one fact read as a style rather than as a shape.
        GradientDrawable cap = new GradientDrawable();
        cap.setColor(fill);
        cap.setCornerRadius(dp(8));

        LayerDrawable stack = new LayerDrawable(new Drawable[]{lip, cap});
        // One device pixel, written as 1 rather than dp(1): on a dense panel a dp
        // is two or three pixels and the lip stops being a lip and starts being a
        // step. The volume here is meant to be felt rather than seen.
        stack.setLayerInset(1, 0, 0, 0, 1);
        return stack;
    }

    private GradientDrawable solid(int colour) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(dp(8));
        return shape;
    }

    private static int lighten(int colour, float amount) {
        int r = (int) (Color.red(colour) + (255 - Color.red(colour)) * amount);
        int g = (int) (Color.green(colour) + (255 - Color.green(colour)) * amount);
        int b = (int) (Color.blue(colour) + (255 - Color.blue(colour)) * amount);
        return Color.rgb(r, g, b);
    }

    // ---------------------------------------------------------------- behaviour

    private void debounced(View view, View.OnClickListener onClick) {
        final long[] last = {0};
        view.setOnClickListener(v -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - last[0] < DEBOUNCE_MS) return;
            last[0] = now;
            onClick.onClick(v);
        });
    }

    /** Hold to repeat. The first press is the click; the repeat starts after a delay. */
    public void repeatOnHold(View view, Runnable action) {
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            action.run();
            handler.postDelayed(tick[0], REPEAT_INTERVAL_MS);
        };
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handler.postDelayed(tick[0], REPEAT_DELAY_MS);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(tick[0]);
                    break;
                default:
                    break;
            }
            return false;   // the click still happens
        });
    }

    public int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
