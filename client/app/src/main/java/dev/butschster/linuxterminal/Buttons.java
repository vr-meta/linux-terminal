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

    /**
     * Which set of colours and shapes the whole bar is currently drawn from.
     *
     * <p>Static, and read at the moment a view is built rather than captured once:
     * every screen in the app builds its keys through this class, so swapping the
     * skin and rebuilding is enough to see the whole app in the other treatment.
     * That is what makes the two comparable at all — a redesign of the bar alone,
     * with the connection manager still in the old palette, compares nothing.
     */
    private static Skin skin = Skin.capped();

    public static void applySkin(Skin chosen) {
        skin = chosen;
        FILL = chosen.fill;
        TEXT = chosen.text;
        MUTED = chosen.muted;
        RULE = chosen.rule;
    }

    public static Skin skin() {
        return skin;
    }

    /**
     * Whether this skin's controls are drawn as physical objects — a moulded cap
     * with an extrusion under it, and rockers pitched like a roof.
     *
     * <p>Two skins share the treatment and differ in their numbers, which is the
     * arrangement worth having: the recipe lives in one place and a skin says how
     * tall, how round and how far it travels. Asking for the kind by name at each
     * call site is how the two would drift apart.
     */
    private static boolean physicalSkin() {
        return skin.kind == Skin.Kind.TACTILE || skin.kind == Skin.Kind.CONSOLE;
    }

    /**
     * The surface a group of keys is mounted on: the dynamic half's plate, or the
     * slightly different one under the fixed half.
     *
     * <p>A drawable rather than a colour because a lit skin needs the plate lit
     * too — see {@link Deck}. On the skins that have no material it is exactly the
     * flat fill it always was.
     */
    public static Drawable deck(boolean fixedHalf) {
        int colour = fixedHalf ? skin.bgPanel : skin.bg;
        return new Deck(colour, skin.textured);
    }

    // Four roles, not eight decorative shades. The previous palette had two greys
    // sixteen units apart, which through pancake lenses is one grey.
    private static int[] FILL = skin.fill;

    // Not final, because the skin replaces them. Anything reading these must read
    // them while building a view, never copy them into a constant of its own — a
    // static final int initialised from one of these is inlined by the compiler
    // and would keep the colour it was compiled with.
    public static int TEXT = skin.text;
    public static int MUTED = skin.muted;

    /**
     * Every line in the bar, horizontal or vertical. One value because they are
     * one idea: the rules between groups and the divider between the panels have to
     * be the same weight and the same colour, or the bar looks like two designs.
     */
    public static int RULE = skin.rule;

    /** Auto-repeat, for arrows and paging. */
    private static final long REPEAT_DELAY_MS = 400;
    private static final long REPEAT_INTERVAL_MS = 90;

    /** A trigger pull can bounce; a doubled Enter is not undoable. */
    private static final long DEBOUNCE_MS = 250;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Typeface icons;

    /**
     * The drawn glyph on each key that has one, kept so a background can be
     * replaced without losing it. Weak, because the bar rebuilds its left half
     * whenever the foreground process changes and nothing should outlive that.
     */
    private final java.util.Map<View, Drawable> glyphs = new java.util.WeakHashMap<>();

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
        view.setTextColor(labelColour(style));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp);
        view.setTypeface(Fonts.mono(context));
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        // The legend is centred in the cap, not in the cell.
        //
        // A physical key hands the bottom of its cell to the extrusion under it, so
        // the cap occupies the upper part and text centred on the whole cell sits
        // visibly low — worst on the short chips, where the extrusion is a fifth of
        // the height. Padding the bottom by the extrusion's depth moves the text
        // back to the middle of the thing it is written on.
        int sink = physicalSkin() ? dp(skin.depthDp) : 0;
        view.setPadding(dp(padH), dp(padV), dp(padH), dp(padV) + sink);
        view.setMinWidth(dp(minWidth));
        view.setMinHeight(dp(minHeight) + sink);
        view.setBackground(background(style));
        if (skin.engrave) engrave(view);
        travel(view);
        view.setClickable(true);
        view.setFocusable(false);
        debounced(view, onClick);
        if (hint != null && !hint.isEmpty()) view.setContentDescription(hint);
        return view;
    }

    /**
     * The glass of a display, sunk into the plate.
     *
     * <p>Deeper than a key stands proud — 4dp against 3 — because a recess is read
     * from its shaded wall and a wall thinner than the corner radius disappears
     * into the rounding. The radius itself is the key's, so the two shapes belong
     * to one panel.
     */
    /**
     * How thick the wall of a screen is. Three device-independent pixels, always,
     * and deliberately not derived from how far the keys stand proud.
     *
     * <p>It used to be {@code depthDp + 1}, which is defensible on a skin whose
     * keys lift 3dp and absurd on one whose keys lift 7: the console skin drew an
     * 8dp wall around every screen, and what should have read as a pane of glass
     * read as a picture frame. A recess only needs enough wall to shade.
     */
    private static final int SCREEN_WALL_DP = 3;

    public Drawable display() {
        return screen(dp(screenCornerDp()));
    }

    /**
     * A screen with square corners, for one that scrolls.
     *
     * <p>A rounded screen and a scrollbar do not get along: the bar runs down the
     * straight part of the edge and then the corner curves away from underneath
     * it, which reads as the bar hanging off the glass. A listing is the one place
     * on this panel where the content is longer than the window, so it is the one
     * place the corner has to go.
     */
    public Drawable squareDisplay() {
        return screen(0f);
    }

    private Drawable screen(float radiusPx) {
        Display display = new Display(skin.glass, dp(SCREEN_WALL_DP), radiusPx,
                skin.screen, skin.accent, skin.crt);
        // A frame in the phosphor's own family, not in the panel's. The glass
        // belongs to the display, and a grey frame would attach it to the plate.
        return skin.crt ? display.withBezel(0xFF173425) : display;
    }

    /**
     * A screen's corner. Smaller than the section holding it, because glass is cut
     * and a moulded housing is not: 10dp against the section's 12 is the same
     * relationship a real panel has between a bezel and the plate around it.
     */
    public int screenCornerDp() {
        return Math.min(10, containerCornerDp());
    }

    /**
     * A command chip: shorter and tighter than a key, because it is a line to run
     * rather than a key to hold down.
     *
     * <p>The distinction is worth drawing. A keypad key is pressed by feel and
     * repeatedly; a chip is read first and pressed once. Making them the same size
     * would spend the panel's most valuable space — the reachable middle — on
     * things that are chosen with the eyes anyway.
     */
    public TextView chip(String label, boolean danger, View.OnClickListener onClick) {
        TextView view = key(label, danger ? WARN : KEY, null, onClick);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        view.setPadding(dp(12), dp(4), dp(12), dp(4));
        view.setMinHeight(dp(34));
        view.setMinWidth(0);
        return view;
    }

    /** The selection bar behind a row of a listing. */
    public Drawable rowSelection() {
        return selection();
    }

    /** The recessed region a group of controls is mounted in. */
    /**
     * The radius a container takes so that what sits inside it looks concentric.
     *
     * <p>A box and the thing mounted in it cannot share a radius: with equal
     * corners the gap between them is widest exactly at the corner, and the inner
     * part looks pushed into a corner it does not fit. The container's radius is
     * the inner one plus the space between them, which keeps the gap even the
     * whole way round. One rule, so every housing on the panel obeys it.
     */
    public int containerCornerDp() {
        return skin.cornerDp + 3;
    }

    public Drawable section() {
        return new Section(0xFF0D141E, dp(containerCornerDp()));
    }

    /**
     * A line inside a display: lit text on the glass, with no cap of its own.
     *
     * <p>Selection is an inversion — the phosphor fills the row and the text goes
     * dark — rather than a highlight laid over it. That is what a character cell
     * display does when it selects, it is what the terminal underneath this bar
     * does, and it is the only "pressed" that does not imply the row moved.
     */
    public TextView readout(String label, String hint, View.OnClickListener onClick) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextColor(skin.phosphor);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp);
        view.setTypeface(Fonts.mono(context));
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        view.setMinWidth(dp(minWidth));
        view.setMinHeight(dp(minHeight));
        view.setBackground(selection());
        if (skin.crt) {
            // A little bloom, the way a phosphor spreads past the beam. Small
            // enough to soften the edge of a stroke and not enough to close the
            // counters — that is the line between "lit" and "out of focus".
            // Halved. A phosphor does spread, but the spread was landing on the
            // stroke rather than around it, and every letter came out a weight
            // heavier than the face actually is — which reads as a fat font rather
            // than as a lit one.
            view.setShadowLayer(dp(2), 0f, 0f, Color.argb(70, Color.red(skin.phosphor),
                    Color.green(skin.phosphor), Color.blue(skin.phosphor)));
        }
        view.setClickable(true);
        view.setFocusable(false);
        debounced(view, onClick);
        if (hint != null && !hint.isEmpty()) view.setContentDescription(hint);

        // The lit row inverts, so its text has to invert with it. A ColorStateList
        // is the only way that stays in step — setting the colour on press would
        // leave it dark on any state the listener did not fire for.
        view.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_pressed}, {}},
                new int[]{skin.glass, skin.phosphor}));
        return view;
    }

    /**
     * A row in a listing: the full width of the screen, a cursor mark, and the
     * name after it.
     *
     * <p>This is the difference between an embedded display and a dark toolbar. A
     * listing is one control with rows — the eye tracks down a single column, and
     * the highlight is a bar across the row, which is what every terminal file
     * browser has done since before any of this was on a headset. Separate cells
     * would be N controls that happen to be inside a rectangle.
     */
    public TextView row(String label, String hint, View.OnClickListener onClick) {
        TextView view = readout("  " + label, hint, onClick);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setPadding(dp(10), dp(6), dp(10), dp(6));
        view.setMinHeight(dp(minHeight - 6));
        // A column wide enough for most names, so the rows below line up into
        // columns rather than into a ragged left edge with holes in it. A name
        // longer than this takes the space it needs and the column after it
        // starts later, which is exactly what `ls` does.
        view.setMinWidth(dp(150));
        return view;
    }

    /** Transparent, then a wash of phosphor, then full inversion. */
    private StateListDrawable selection() {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(skin.phosphor);
        pressed.setCornerRadius(dp(skin.cornerDp));

        GradientDrawable hovered = new GradientDrawable();
        hovered.setColor(Color.argb(46, Color.red(skin.phosphor), Color.green(skin.phosphor),
                Color.blue(skin.phosphor)));
        hovered.setCornerRadius(dp(skin.cornerDp));

        GradientDrawable idle = new GradientDrawable();
        idle.setColor(Color.TRANSPARENT);

        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_hovered}, hovered);
        states.addState(new int[]{}, idle);
        return states;
    }

    /**
     * The key physically enters its housing when pressed.
     *
     * <p>A {@link android.animation.StateListAnimator} rather than a second
     * drawable: the drawable can only redraw the face, and what is wanted is the
     * whole cap — legend included — moving down. 90ms is inside the 80–120ms a
     * real key takes to bottom out, and the release is faster than the press
     * because a spring returns quicker than a finger pushes.
     */
    private void travel(View view) {
        if (skin.travelDp <= 0) return;
        android.animation.StateListAnimator states = new android.animation.StateListAnimator();
        states.addState(new int[]{android.R.attr.state_pressed},
                android.animation.ObjectAnimator.ofFloat(view, "translationY", dp(skin.travelDp))
                        .setDuration(90));
        states.addState(new int[]{},
                android.animation.ObjectAnimator.ofFloat(view, "translationY", 0f)
                        .setDuration(70));
        view.setStateListAnimator(states);
    }

    /**
     * A lit key writes its legend in its own edge colour.
     *
     * <p>On a moulded cap the fill already carries the role and the legend stays
     * white; on an outlined one the fill is nearly the deck, so the colour has
     * nowhere else to live. It is the same information either way.
     */
    private static int labelColour(int style) {
        if (physicalSkin()) return skin.legend[style];
        return skin.border > 0 && lit(style) ? skin.edge[style] : TEXT;
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
        // Layered into the background, and also remembered.
        //
        // Both halves of that matter and both were learned the hard way. A
        // compound drawable is laid out beside the text and centred with it, so on
        // a key with no text it sits visibly left of centre; a foreground would be
        // ideal and is simply not drawn on these views. That leaves a layer of the
        // background — which works until something replaces that background, as
        // the rocker halves do, and the glyph goes with it. So the glyph is kept
        // here as well, and whoever swaps a background can put it back.
        glyphs.put(view, glyph);
        view.setBackground(stack(view.getBackground(), glyph));
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

        if (physicalSkin()) {
            // The pair is one pitched cap sitting in a recessed housing, so the
            // halves are shaded as slopes and the housing is what they sit in.
            // Nothing else here changes: the two views, the groove and the order
            // are the same on every skin.
            // The same housing colour and the same concentric radius as every
            // other recessed region. It used to be near-black with a corner of its
            // own, which read as a part borrowed from a different panel — the only
            // hard black outline in a bar that has none anywhere else.
            cap.setBackground(new Section(0xFF0D141E, dp(containerCornerDp())));
            cap.setPadding(dp(3), dp(3), dp(3), dp(3));
            mountSlope(top, true);
            mountSlope(bottom, false);
            cap.addView(top, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            cap.addView(bottom, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            return cap;
        }

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
    private Drawable rocked() {
        if (skin.kind == Skin.Kind.MACHINED) {
            // The cap of a real rocker is one moulding, so it is one KeyFace; the
            // groove drawn across it by rocker() is what makes it two controls.
            return new KeyFace(FILL[KEY], dp(skin.cornerDp), dp(skin.depthDp), false,
                    skin.textured);
        }
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(skin.cornerDp));
        if (skin.border > 0) {
            // No lighting to describe: this cap is a drawn region like every other
            // key in this skin, so it takes the same flat face and the same edge.
            // What still says "rocker" is the groove, which is a fact about the
            // control rather than about how light falls on it.
            shape.setColor(FILL[KEY]);
            shape.setStroke(dp(skin.border), skin.edge[KEY]);
            return shape;
        }
        shape.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        shape.setColors(new int[]{shade(FILL[KEY], 16), shade(FILL[KEY], -10), shade(FILL[KEY], 16)});
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
     * Puts a slope under a half without losing the glyph drawn on it.
     *
     * <p>The glyph comes from {@link #glyphs} rather than off the view, because
     * there is nowhere on the view it survives: a foreground is not drawn on these
     * views at all — trying that emptied every arrow and the backspace key on the
     * keypad — and the background is the thing being replaced here.
     */
    private void mountSlope(View half, boolean upper) {
        Drawable glyph = glyphs.get(half);
        if (glyph == null) {
            half.setBackground(slope(upper));
            return;
        }
        half.setBackground(slopeWithGlyph(upper, glyph));
    }

    private StateListDrawable slopeWithGlyph(boolean upper, Drawable glyph) {
        StateListDrawable states = new StateListDrawable();
        int fill = FILL[KEY];
        float r = dp(skin.cornerDp);
        states.addState(new int[]{android.R.attr.state_pressed},
                stack(new RockerHalf(upper, fill, r, true), glyph));
        states.addState(new int[]{android.R.attr.state_hovered},
                stack(new RockerHalf(upper, lighten(fill, 0.08f), r, false), glyph));
        states.addState(new int[]{}, stack(new RockerHalf(upper, fill, r, false), glyph));
        return states;
    }

    /** A face with a glyph centred over it, taking no part in layout. */
    private LayerDrawable stack(Drawable face, Drawable glyph) {
        LayerDrawable layers = new LayerDrawable(new Drawable[]{face, glyph});
        layers.setLayerGravity(1, Gravity.CENTER);
        layers.setLayerSize(1, glyph.getIntrinsicWidth(), glyph.getIntrinsicHeight());
        return layers;
    }

    /** One slope of a pitched rocker, in its three states. */
    private StateListDrawable slope(boolean upper) {
        StateListDrawable states = new StateListDrawable();
        int fill = FILL[KEY];
        float r = dp(skin.cornerDp);
        states.addState(new int[]{android.R.attr.state_pressed},
                new RockerHalf(upper, fill, r, true));
        states.addState(new int[]{android.R.attr.state_hovered},
                new RockerHalf(upper, lighten(fill, 0.08f), r, false));
        states.addState(new int[]{}, new RockerHalf(upper, fill, r, false));
        return states;
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
        // The accent rather than white where there is an accent: a white wash on a
        // near-black deck reads as a different material lighting up, which is the
        // one thing a half of a rocker is not.
        pressed.setColor(skin.border > 0
                ? Color.argb(64, Color.red(skin.accent), Color.green(skin.accent),
                        Color.blue(skin.accent))
                : Color.argb(70, 255, 255, 255));
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
        label.setTypeface(Fonts.mono(context));
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
        float r = dp(5);
        shape.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return shape;
    }

    /**
     * Pressed and hovered states. With no touch and no mouse, the highlight under the ray
     * is the only thing that says where a press would land before it lands there.
     */
    private StateListDrawable background(int style) {
        if (physicalSkin()) return physical(style);
        if (skin.kind == Skin.Kind.MACHINED) return moulded(style);
        if (skin.border > 0) return outlined(style);

        int fill = FILL[style];
        StateListDrawable states = new StateListDrawable();
        // Pressed is the one state with no lip: the cap sits down flush with the
        // deck, which is what pressing a key does and costs nothing to draw.
        states.addState(new int[]{android.R.attr.state_pressed}, solid(lighten(fill, 0.35f)));
        states.addState(new int[]{android.R.attr.state_hovered}, capped(lighten(fill, 0.18f)));
        states.addState(new int[]{}, capped(fill));
        return states;
    }

    /**
     * The full physical recipe, per state. {@link PhysicalKey} holds the layers;
     * this decides what each state changes.
     *
     * <p>Hover brightens the border and lights a halo, and does not move the cap.
     * Press moves it, and that movement is the whole feedback — a hover that also
     * moved would leave the press with nothing left to say.
     */
    private StateListDrawable physical(int style) {
        StateListDrawable states = new StateListDrawable();
        float radius = dp(skin.cornerDp);
        float depth = dp(skin.depthDp);
        int face = FILL[style];
        int border = skin.edge[style];
        int legend = skin.legend[style];

        // Primary actions stay illuminated at rest. Everything else lights only
        // under the ray, so a bar sitting untouched has one lit control, not forty.
        int resting = style == ENTER ? skin.accentGo : 0;

        states.addState(new int[]{android.R.attr.state_pressed},
                new PhysicalKey(face, border, legend, radius, depth, true, resting));
        states.addState(new int[]{android.R.attr.state_hovered},
                new PhysicalKey(lighten(face, 0.06f), blend(border, skin.accentNav, 0.6f),
                        legend, radius, depth, false, skin.accentNav));
        states.addState(new int[]{},
                new PhysicalKey(face, border, legend, radius, depth, false, resting));
        return states;
    }

    /** Part-way between two colours, for a border that warms up under the ray. */
    private static int blend(int from, int to, float amount) {
        return Color.argb(
                (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * amount),
                (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }

    /**
     * A moulded cap: {@link KeyFace} does the drawing, this only says which of
     * its three states is which.
     *
     * <p>Hover lightens the face and leaves the cap standing. Press puts it down —
     * a different drawable rather than a lighter copy of the same one, because
     * what changes is where the light lands, not how much of it there is.
     */
    private StateListDrawable moulded(int style) {
        StateListDrawable states = new StateListDrawable();
        float radius = dp(skin.cornerDp);
        float depth = dp(skin.depthDp);
        states.addState(new int[]{android.R.attr.state_pressed},
                new KeyFace(FILL[style], radius, depth, true, skin.textured));
        states.addState(new int[]{android.R.attr.state_hovered},
                new KeyFace(lighten(FILL[style], 0.14f), radius, depth, false, skin.textured));
        states.addState(new int[]{},
                new KeyFace(FILL[style], radius, depth, false, skin.textured));
        return states;
    }

    /**
     * A key with no volume at all: a face barely above the deck and a lit edge.
     *
     * <p>Press and hover are shown by the edge and the face brightening together
     * rather than by the key moving. There is nowhere for it to move to — the
     * whole point of this treatment is that the key is a drawn region, not an
     * object, so a press that mimicked depth would be describing something that
     * is not there.
     */
    private StateListDrawable outlined(int style) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                face(lighten(FILL[style], 0.22f), skin.edge[style], 1.0f));
        states.addState(new int[]{android.R.attr.state_hovered},
                face(lighten(FILL[style], 0.10f), skin.edge[style], 0.7f));
        states.addState(new int[]{}, face(FILL[style], skin.edge[style], lit(style) ? 0.42f : 0f));
        return states;
    }

    /**
     * Which roles are drawn as lit rather than merely outlined.
     *
     * <p>Everything lit either runs on its own or is destructive. A bar where
     * every key glows is a bar where the glow means nothing, and the keys that
     * most need to be told apart from their neighbours are exactly these.
     */
    private static boolean lit(int style) {
        return style == COMMAND || style == ENTER || style == WARN || style == VOICE;
    }

    /**
     * One face of an outlined key: fill, then rings of falling alpha just inside
     * the edge, then the edge itself.
     *
     * <p>The rings are three concentric strokes rather than a blur. A real blur
     * means {@link android.graphics.BlurMaskFilter}, which only works on a
     * software layer, and the left half of this bar is rebuilt whenever the
     * foreground process changes; at this size three strokes read the same and
     * cost nothing.
     */
    private LayerDrawable face(int fill, int edgeColour, float glowStrength) {
        GradientDrawable body = new GradientDrawable();
        body.setColor(fill);
        body.setCornerRadius(dp(skin.cornerDp));

        if (!skin.glow || glowStrength <= 0f) {
            body.setStroke(dp(skin.border), edgeColour);
            return new LayerDrawable(new Drawable[]{body});
        }

        // Widest and faintest first: each later ring is drawn over the previous
        // one and reaches less far in, so the alpha climbs towards the edge.
        GradientDrawable wide = ring(edgeColour, skin.border + 3, (int) (34 * glowStrength));
        GradientDrawable mid = ring(edgeColour, skin.border + 1, (int) (72 * glowStrength));
        GradientDrawable line = ring(edgeColour, skin.border, 255);
        return new LayerDrawable(new Drawable[]{body, wide, mid, line});
    }

    private GradientDrawable ring(int colour, int widthDp, int alpha) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.TRANSPARENT);
        shape.setCornerRadius(dp(skin.cornerDp));
        shape.setStroke(dp(widthDp), Color.argb(Math.min(255, alpha),
                Color.red(colour), Color.green(colour), Color.blue(colour)));
        return shape;
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
        lip.setCornerRadius(dp(skin.cornerDp));

        // The face is flat. The volume comes from the lip below it and nothing
        // else: a gradient across the cap was doing a second job the lip already
        // does, and two cues for one fact read as a style rather than as a shape.
        GradientDrawable cap = new GradientDrawable();
        cap.setColor(fill);
        cap.setCornerRadius(dp(skin.cornerDp));

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
        shape.setCornerRadius(dp(skin.cornerDp));
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
