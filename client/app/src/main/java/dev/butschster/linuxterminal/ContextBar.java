package dev.butschster.linuxterminal;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The bar, in two halves, and the split is the point.
 *
 * <p>The <b>right</b> half never changes: {@link KeyPad}, built once, plus the client's own
 * controls. The <b>left</b> half is whatever the moment is about — the directories you
 * could cd into at a prompt, the program's own keys once something is running, the
 * project's skills when Claude Code is in front.
 *
 * <p>Nothing on the left is decided here. The host owns the pty, a pty has a foreground
 * process group, so the host reads from /proc what is actually running and sends the
 * buttons with it. Adding a tool is an edit to a Python table, not a new APK.
 *
 * <p>Buttons wrap rather than scroll sideways, so the window's size decides how much is
 * visible: wider shows more per row, taller shows more rows.
 */
public class ContextBar extends LinearLayout {

    public interface Host {
        /** A button was pressed: type this, and press Enter if the host said so. */
        void onAction(String send, boolean enter);

        void onDictate();

        void onStopDictating();

        void onKeyboard();

        void onFontStep(int delta);

        void onScroll(int rows);
    }

    /** Width of the fixed half. Constant, so the keys on it never move. */
    private static final int FIXED_WIDTH_DP = 360;

    /** The app's own strip. One button wide: it is a margin, not a third of the bar. */
    private static final int STRIP_WIDTH_DP = 64;

    // The two plates come from the skin at build time rather than from constants
    // here: the whole point of a skin is that a plate changes together with the
    // keys standing on it, and a deck that stayed grey under lit keys would
    // compare nothing.

    private static final String ICON_MIC = "\ue029";
    private static final String ICON_KEYBOARD = "\ue312";
    private static final String ICON_STOP = "\ue047";

    /**
     * The only bytes this file sends on its own. Everything else it types came
     * from the host, which is the rule — but see the paging rocker: PgUp and PgDn
     * are fixed controls that exist whatever is running, and a fixed control
     * cannot depend on the host having sent it.
     */
    private static final String ESC = "\u001b";

    private final Host host;
    private final Buttons buttons;

    private final TextView where;
    private final TextView what;
    private final FlowLayout dynamic;
    private final FlowLayout toolKeys;
    private View divider;

    /** Non-null only on a skin with displays: the column the screens are stacked in. */
    private LinearLayout stack;

    /** Console layout only: the listing, the chip column and the two mini screens. */
    private FlowLayout rows;
    private FlowLayout projects;
    private View browserBox;
    private View projectBox;
    private LinearLayout chipColumn;
    private LinearLayout shortcuts;

    private LinearLayout left;
    private LinearLayout right;
    private LinearLayout recordingRow;
    private TextView stop;
    private LevelMeter meter;
    private TextView timer;
    private TextView note;

    /** Restored when the ray leaves a button whose hint replaced it. */
    private String path = "no terminal";

    public ContextBar(Context context, Host host) {
        super(context);
        this.host = host;
        Typeface icons = Typeface.createFromAsset(context.getAssets(), "MaterialIcons-Regular.ttf");
        this.buttons = new Buttons(context, icons);

        setOrientation(HORIZONTAL);
        setBackground(Buttons.deck(false));

        // The four views every layout needs, built before either branch claims
        // them: the two lines of the status, the flow of tool keys, and the flow
        // the older skins put their groups in. Which container they end up inside
        // is what the layouts disagree about; that they exist is not.
        where = label(path, Buttons.TEXT, 16);
        where.setTypeface(Fonts.mono(context));
        where.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        where.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        what = label("", Buttons.MUTED, 15);
        what.setTypeface(Fonts.mono(context));
        what.setPadding(buttons.dp(10), 0, buttons.dp(8), 0);

        dynamic = new FlowLayout(context, buttons.gap());
        toolKeys = new FlowLayout(context, buttons.gap());

        if (Buttons.skin().kind == Skin.Kind.CONSOLE) {
            buildConsole(context);
            return;
        }

        // ---------------------------------------------------------- left: dynamic

        // No horizontal padding here, so a group's rule can run the full width of
        // the panel and meet the vertical divider the way a ruled sheet does. What
        // needs breathing room asks for it individually, below.
        left = new LinearLayout(context);
        left.setOrientation(VERTICAL);
        left.setPadding(0, buttons.dp(8), 0, buttons.dp(8));
        addView(left, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        boolean screens = Buttons.skin().displays;

        LinearLayout status = new LinearLayout(context);
        status.setOrientation(HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(buttons.dp(10), 0, buttons.dp(6), 0);
        if (screens) {
            // The first display, and the one that is only ever read: where you are.
            // It was already text-only — nothing on this line has ever been
            // pressable — so putting it behind glass says out loud what the layout
            // was already doing quietly.
            status.setBackground(buttons.display());
            status.setPadding(buttons.dp(14), buttons.dp(9), buttons.dp(14), buttons.dp(9));
            LayoutParams inset = new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            inset.setMargins(buttons.inset(), 0, buttons.inset(), buttons.dp(8));
            left.addView(status, inset);
        } else {
            left.addView(status,
                    new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        // Text only — nothing here is pressable. A status line that can be clicked
        // is a row of accidental targets above the row you were aiming at.
        where.setTextColor(screens ? Buttons.skin().phosphor : Buttons.TEXT);
        status.addView(where, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        // Dimmer phosphor rather than grey: a second colour inside one display
        // would read as a second display. Same lamp, less of it.
        what.setTextColor(screens ? dim(Buttons.skin().phosphor, 0.62f) : Buttons.MUTED);
        status.addView(what);

        dynamic.setPadding(buttons.inset(), buttons.dp(2), buttons.inset(), 0);
        // The rules are drawn past this padding, out to the panel's own edges.
        // Both flags are needed and they are not the same thing: clipToPadding
        // stops the padded area being cut, clipChildren is what lets a child draw
        // outside its own bounds at all. With only the first, the rules came up
        // short at both ends.
        dynamic.setClipToPadding(false);
        dynamic.setClipChildren(false);
        ScrollView dynamicScroll = new ScrollView(context);
        if (screens) {
            // One display per group, stacked. The flow layout is still built and
            // still holds the buttons — it just moves inside a screen instead of
            // sitting on the plate, so setContext keeps one way of filling it.
            stack = new LinearLayout(context);
            stack.setOrientation(VERTICAL);
            stack.setPadding(buttons.inset(), 0, buttons.inset(), 0);
            dynamicScroll.addView(stack, new ScrollView.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        } else {
            dynamicScroll.addView(dynamic, new ScrollView.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        left.addView(dynamicScroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------------------------------------------------------- right: fixed

        divider = new View(context);
        divider.setBackgroundColor(Buttons.RULE);
        addView(divider, new LayoutParams(buttons.dp(1), LayoutParams.MATCH_PARENT));

        right = new LinearLayout(context);
        right.setOrientation(VERTICAL);
        right.setBackground(Buttons.deck(true));
        // No horizontal padding on the panel, for the same reason the left one has
        // none: a rule that stops short of the edge looks like a mistake. What
        // needs the inset takes it individually, and then everything below a rule
        // lines up with everything above it.
        // Nothing horizontal, so the rules run to the edges; vertically, the inset
        // less the margin KeyPad's own cells already carry.
        right.setPadding(0, buttons.inset() - buttons.gap() / 2, 0,
                buttons.inset() - buttons.gap() / 2);
        addView(right, new LayoutParams(buttons.dp(FIXED_WIDTH_DP), LayoutParams.MATCH_PARENT));

        KeyPad pad = new KeyPad(context, buttons, text -> host.onAction(text, false));
        // Its cells already carry half a gap each side, so this makes up the rest.
        pad.setPadding(buttons.inset() - buttons.gap() / 2, 0,
                buttons.inset() - buttons.gap() / 2, 0);
        right.addView(pad, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // The keys the running program adds, under the ones every program has.
        //
        // They belong on this side because they are keys: pressing them types
        // something. Everything on the left is content — where to go, what to run,
        // which skill — and mixing the two meant "a key" lived in two places, so
        // the hand could learn neither. The pad above never moves whatever appears
        // here, which is the part that must not break.
        // The same rule the groups on the left are divided by, marking where the
        // keys every program has end and the ones this program added begin.
        right.addView(new Rule(context, buttons),
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // The same inset the keys above it have, so the two blocks share an edge.
        // The same gap below the rule as above it. Two above and eight below is
        // not a rhythm, it is a rule that has slid into the row underneath.
        // Its children carry no margins of their own, so it takes the inset whole.
        toolKeys.setPadding(buttons.inset(), buttons.inset(), buttons.inset(), 0);
        ScrollView toolScroll = new ScrollView(context);
        toolScroll.addView(toolKeys, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        right.addView(toolScroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // ------------------------------------------------- far right: the app's own

        // A strip of its own: everything on it is about getting to what you want to
        // see, rather than about what you want to type.
        //
        // That is a weaker line than the one it replaced. This strip used to be
        // "nothing here is sent to the shell", which was clean and was wrong about
        // the hand: paging was put on the keypad first, worn, and the reach for it
        // kept going to the scroll rocker — because "move me through this text" is
        // one intention, and whether the bytes end at this app's transcript or at
        // the program's own screen is an implementation detail of where the text
        // happens to live. PgUp and PgDn do go to the shell, and they sit here
        // anyway, next to the scrolling they are the other half of.
        View stripDivider = new View(context);
        stripDivider.setBackgroundColor(Buttons.RULE);
        addView(stripDivider, new LayoutParams(buttons.dp(1), LayoutParams.MATCH_PARENT));

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(VERTICAL);
        controls.setBackground(Buttons.deck(true));
        controls.setPadding(buttons.dp(6), buttons.dp(8), buttons.dp(6), buttons.dp(8));
        addView(controls, new LayoutParams(buttons.dp(STRIP_WIDTH_DP), LayoutParams.MATCH_PARENT));

        // Dictation and the keyboard as one block on every skin, not only on the
        // console one. They answer the same question — how does text get into the
        // line — and a cluster says so without implying, as a rocker would, that
        // one is a direction of the other.
        addControl(controls, buttons.cluster(true,
                buttons.icon(ICON_MIC, Buttons.VOICE, "dictate", v -> host.onDictate()),
                buttons.icon(ICON_KEYBOARD, Buttons.COMMAND, "on-screen keyboard",
                        v -> host.onKeyboard())));
        // Up over down, everywhere on this strip: in every rocker here the upper
        // half is the one that moves away from where you are.
        //
        // Paging first, then scrolling, and text size last. The order is how often
        // the hand comes back: moving through text is the whole reason to look at
        // this strip, and the font is set once in a session and then left alone —
        // it was in the middle only because it was there first.
        View pageUp = pageKey(Glyphs.Kind.PAGE_UP, ESC + "[5~", "page up, inside the program");
        View pageDown = pageKey(Glyphs.Kind.PAGE_DOWN, ESC + "[6~", "page down, inside the program");
        addControl(controls, buttons.rocker(pageUp, pageDown));

        // Directly under the paging pair, and the difference between them is one
        // chevron against two. They are the same intention aimed at two different
        // texts: this moves what the shell has printed, and it cannot reach inside
        // a program that draws its own screen, because that text was never printed
        // as scrollback in the first place.
        View scrollBack = buttons.glyphHalf(Glyphs.Kind.UP, "scroll this window back",
                v -> host.onScroll(-10));
        buttons.repeatOnHold(scrollBack, () -> host.onScroll(-10));
        View scrollForward = buttons.glyphHalf(Glyphs.Kind.DOWN, "scroll this window forward",
                v -> host.onScroll(10));
        buttons.repeatOnHold(scrollForward, () -> host.onScroll(10));
        addControl(controls, buttons.rocker(scrollBack, scrollForward));

        addControl(controls, buttons.rocker(
                buttons.half("A+", "larger text", v -> host.onFontStep(2)),
                buttons.half("A\u2212", "smaller text", v -> host.onFontStep(-2))));

        // Descriptions ride on the buttons themselves. A hint printed somewhere
        // else — a line under the keys, the path at the top of another panel — is a
        // hint in the one place the eye is not while it is aiming.
        hint(pad, null);
        hint(controls, null);

        // ------------------------------------------------- dictation takes over

        // While recording there is exactly one thing to decide — stop or keep
        // talking — so everything else goes away. Leaving the keys up would put a
        // row of live targets around the only button that matters, and pressing
        // one of them mid-sentence is not recoverable.
        recordingRow = new LinearLayout(context);
        recordingRow.setOrientation(VERTICAL);
        recordingRow.setGravity(Gravity.CENTER);
        recordingRow.setVisibility(GONE);
        recordingRow.setPadding(buttons.dp(24), buttons.dp(10), buttons.dp(24), buttons.dp(10));
        addView(recordingRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        meter = new LevelMeter(context);
        recordingRow.addView(meter, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout under = new LinearLayout(context);
        under.setOrientation(HORIZONTAL);
        under.setGravity(Gravity.CENTER);
        recordingRow.addView(under,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        timer = label("0:00", Buttons.TEXT, 22);
        timer.setTypeface(Fonts.mono(context));
        timer.setPadding(0, 0, buttons.dp(20), 0);
        under.addView(timer);

        stop = buttons.icon(ICON_STOP, Buttons.WARN, "stop and transcribe",
                v -> host.onStopDictating());
        stop.setPadding(buttons.dp(40), buttons.dp(12), buttons.dp(40), buttons.dp(12));
        under.addView(stop);

        note = label("", Buttons.MUTED, 16);
        note.setPadding(buttons.dp(20), 0, 0, 0);
        under.addView(note);
    }

    /**
     * Half of the paging rocker: a drawn glyph that types into the pty and repeats
     * on hold, the way an arrow on {@link KeyPad} does.
     *
     * <p>It goes through {@code onAction} rather than through a new callback on
     * purpose — that path already sends bytes without pressing Enter, and it is
     * the same one every key on the pad takes, so a held PgDn behaves exactly like
     * a held arrow rather than like a second implementation of the same idea.
     */
    private View pageKey(Glyphs.Kind kind, String bytes, String hint) {
        View view = buttons.glyphHalf(kind, hint, v -> host.onAction(bytes, false));
        buttons.repeatOnHold(view, () -> host.onAction(bytes, false));
        return view;
    }

    private void addControl(LinearLayout strip, View view) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        int half = buttons.gap() / 2;
        params.setMargins(0, half, 0, half);
        strip.addView(view, params);
    }

    // ------------------------------------------------------------------ status

    public void setStatus(String message) {
        path = message;
        where.setText(message);
        what.setText("");
    }

    /** Nothing to drive: no terminal window is in front. */
    public void clearContext() {
        setStatus("no terminal window");
        dynamic.removeAllViews();
    }

    /** Rebuild the left half from what the host reported. The right half is untouched. */
    public void setContext(JSONObject ctx) {
        String tool = ctx.isNull("tool") ? null : ctx.optString("tool");
        String toolName = ctx.optString("tool_name", "");
        JSONObject git = ctx.optJSONObject("git");

        path = ctx.optString("cwd_label", "?");
        where.setText(path);

        StringBuilder right = new StringBuilder();
        if (git != null) {
            right.append("⎇ ").append(git.optString("branch"));
            int dirty = git.optInt("dirty");
            if (dirty > 0) right.append(String.format(Locale.US, " ·%d", dirty));
        }
        if (tool != null) {
            if (right.length() > 0) right.append("   ");
            right.append("▶ ").append(toolName);
        }
        what.setText(right.toString());

        dynamic.removeAllViews();
        toolKeys.removeAllViews();

        JSONArray groups = ctx.optJSONArray("groups");
        if (groups == null) return;

        // Keystrokes first, on the keyboard side, whichever group sent them.
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            JSONArray actions = group.optJSONArray("actions");
            if (actions == null) continue;
            for (int j = 0; j < actions.length(); j++) {
                JSONObject action = actions.optJSONObject(j);
                if (action != null && isKeystroke(action)) toolKeys.addView(actionButton(action));
            }
        }
        hint(toolKeys, null);

        if (rows != null) {
            fillConsole(groups);
            return;
        }

        if (stack != null) {
            buildScreens(groups);
            return;
        }

        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            JSONArray actions = group.optJSONArray("actions");
            if (actions == null || actions.length() == 0) continue;

            // A rule, then the group's name directly under it, then its buttons.
            //
            // An earlier version had no rule, for a reason that was true and is now
            // fixed: an ordinary View measures to nothing in a FlowLayout, so a
            // divider landed wherever the row happened to wrap. Rule measures itself
            // to the full available width, which puts it where it belongs and forces
            // the break as a side effect.
            dynamic.addView(new Rule(getContext(), buttons));

            // Small, spaced and upper-case, sitting right under its rule: a label
            // for what follows rather than a line competing with the buttons. The
            // rule carries the separation, so the heading does not have to.
            TextView heading = label(group.optString("name").toUpperCase(Locale.ROOT),
                    Buttons.skin().heading, 9);
            heading.setPadding(0, buttons.dp(1), buttons.dp(10), buttons.dp(1));
            heading.setLetterSpacing(0.12f);
            heading.setTag(FlowLayout.BREAK);
            dynamic.addView(heading);

            // The heading gets the row to itself. BREAK only says "start a new row",
            // so without breaking again the first buttons filled the space beside the
            // name and the heading stopped looking like a heading.
            boolean startsRow = true;

            for (int j = 0; j < actions.length(); j++) {
                JSONObject action = actions.optJSONObject(j);
                if (action == null) continue;
                if (isKeystroke(action)) continue;      // drawn on the other side
                View button = actionButton(action);
                if (startsRow) {
                    button.setTag(FlowLayout.BREAK);
                    startsRow = false;
                }
                dynamic.addView(button);
            }
        }
        hint(dynamic, where);
    }

    // --------------------------------------------------------------- console

    /**
     * The console arrangement, built from this bar's own parts.
     *
     * <p>The layout is borrowed; the contents are not. The keypad here is the real
     * {@link KeyPad} — the same keys, built once, that never move — and the
     * shortcuts are whatever the host sent for the program in front, not a fixed
     * list of Claude's chords. A concept that hardcoded either would prove nothing
     * about this bar: the whole design rests on the host deciding what is offered,
     * and a mock that decides for itself is a different product.
     *
     * <p>What the arrangement changes is where a thing lives, and the rule is what
     * kind of thing it is. Read on the left, pressed in the middle, and the app's
     * own controls on a rail at the edge.
     */
    private void buildConsole(Context context) {
        setPadding(buttons.dp(12), buttons.dp(12), buttons.dp(12), buttons.dp(12));

        // ---- read
        LinearLayout reading = new LinearLayout(context);
        reading.setOrientation(VERTICAL);
        addView(reading, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1.35f));

        // The head is its own screen, the full width of the column and with no
        // section around it. It answers a different question from the listing —
        // "where am I" against "where could I go" — and wrapping the two in one
        // housing said they were one instrument. Nothing else on the panel is
        // unwrapped, which is what makes this one read as the panel's own readout.
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackground(buttons.display());
        head.setPadding(buttons.dp(14), buttons.dp(10), buttons.dp(14), buttons.dp(10));
        // The header is a screen, so it is written by the same beam as the screens
        // under it. It was inheriting the panel's neutral text and dim grey from
        // the constructor, which put two colours behind one piece of glass — the
        // exact fault fixed once already for the typeface, arriving again through
        // the palette.
        where.setTextColor(Buttons.skin().phosphor);
        what.setTextColor(Buttons.skin().phosphorDim);
        head.addView(where, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        head.addView(what);
        head.addView(new Led(context, Buttons.skin().accentGo, true),
                new LayoutParams(buttons.dp(9), buttons.dp(9)));
        LayoutParams headParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        headParams.bottomMargin = buttons.dp(12);
        reading.addView(head, headParams);

        // No plate around the listings. The glass is already a housing, and a box
        // around a box at nearly the same radius reads as a framing error — two
        // walls 10dp apart, the outer one only 2% brighter than the panel, which
        // through the lenses is the same colour. The caption stays; it is engraved
        // on the panel, which is where a label belongs.
        LinearLayout browser = captioned(context, "directory browser",
                consoleScreen(context));
        addSection(reading, browser, 0f);
        browserBox = browser;

        projects = new FlowLayout(context, 0);
        projects.setPadding(buttons.dp(6), buttons.dp(6), buttons.dp(6), buttons.dp(6));
        ScrollView projectScroll = elasticScroll(context);
        projectScroll.addView(projects, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LinearLayout projectBox = captioned(context, "projects", projectScroll);
        addSection(reading, projectBox, 0f);
        this.projectBox = projectBox;

        LinearLayout commandBox = captioned(context, "commands", null);
        chipColumn = new LinearLayout(context);
        chipColumn.setOrientation(VERTICAL);
        ScrollView chipScroll = new ScrollView(context);
        chipScroll.addView(chipColumn, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        commandBox.addView(chipScroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addSection(reading, commandBox, 1f);

        addView(gap(context, 12), new LayoutParams(buttons.dp(12), LayoutParams.MATCH_PARENT));

        // ---- press
        //
        // The keypad is OUTSIDE the scroller and the shortcuts are inside it, and
        // that split is the whole point of this column.
        //
        // The first version put both in one ScrollView. The keypad is still built
        // once and never rebuilt — the rule everyone checks — and it still moved:
        // when the host sent enough shortcuts to overflow the column, scrolling
        // down to reach a chord carried Enter and ^C up and off the panel. That is
        // the Touch Bar's failure arriving through the scroll container instead of
        // through the layout order, and the fixed half's guarantee is about where
        // the keys ARE, not about how often they are constructed.
        LinearLayout pressing = new LinearLayout(context);
        pressing.setOrientation(VERTICAL);
        addView(pressing, new LayoutParams(0, LayoutParams.MATCH_PARENT, 0.95f));

        LinearLayout control = consoleSection(context, "terminal control", true);
        KeyPad pad = new KeyPad(context, buttons, text -> host.onAction(text, false));
        control.addView(pad, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addSection(pressing, control, 0f);

        shortcuts = consoleSection(context, "shortcuts", false);
        ScrollView shortcutScroll = new ScrollView(context);
        shortcutScroll.addView(toolKeys, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        shortcuts.addView(shortcutScroll, new LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        LayoutParams shortcutParams = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        shortcutParams.bottomMargin = buttons.dp(12);
        pressing.addView(shortcuts, shortcutParams);

        addView(gap(context, 12), new LayoutParams(buttons.dp(12), LayoutParams.MATCH_PARENT));

        // ---- the app's own
        addView(consoleRail(context), new LayoutParams(
                buttons.dp(86), LayoutParams.MATCH_PARENT));
    }

    /** The screen: nothing but the listing, in columns. */
    private View consoleScreen(Context context) {
        rows = new FlowLayout(context, 0);
        rows.setPadding(buttons.dp(6), buttons.dp(6), buttons.dp(6), buttons.dp(6));
        ScrollView scroll = elasticScroll(context);
        scroll.addView(rows, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /**
     * A screen that is as tall as what is on it, up to a ceiling.
     *
     * <p>Two directories should not be given the same height as twenty. A weight
     * would divide the column by a ratio decided when the panel was built, which is
     * exactly the wrong time to decide it — how many entries a directory has is
     * known only when the host says so, and it changes on every `cd`.
     *
     * <p>The ceiling matters as much as the elasticity: a home directory with sixty
     * entries would otherwise push the commands and the readouts off the panel
     * entirely. Past it the listing scrolls, which is the behaviour a listing
     * should have had anyway.
     */
    private ScrollView elasticScroll(Context context) {
        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(
                        buttons.dp(230), MeasureSpec.AT_MOST));
            }
        };
        scroll.setBackground(buttons.squareDisplay());
        return scroll;
    }

    /**
     * The rail: everything that moves you through text, as rockers.
     *
     * <p>All three pairs live here rather than being split between an edge rail and
     * a navigation block in the middle. Paging, scrolling and text size are one
     * intention — move me through this, or change how much of it I see — and a
     * rocker is what says two actions are the same decision taken twice.
     */
    private View consoleRail(Context context) {
        LinearLayout rail = new LinearLayout(context);
        rail.setOrientation(VERTICAL);
        rail.setBackground(buttons.section(true));
        rail.setPadding(buttons.dp(8), buttons.dp(10), buttons.dp(8), buttons.dp(10));

        // Dictation and the keyboard are buttons, not levers.
        //
        // A lever states a position you set and leave; these two are momentary —
        // you press to start talking, and you press to summon a keyboard that
        // dismisses itself. Drawing them as switches promised a state they do not
        // hold. The sticky-modifier lever is gone outright: nothing in the client
        // implements it, and a control that latches nothing is a lie with a hinge.
        // One block, because they are one question: how does text get into the
        // line. Not a rocker — neither is a direction of the other, and pressing
        // one must not suggest the pair tilts. A cluster says "these belong
        // together" while leaving each cap its own face to be pressed.
        TextView mic = buttons.icon(ICON_MIC, Buttons.VOICE, "dictate", v -> host.onDictate());
        mic.setTextSize(TypedValue.COMPLEX_UNIT_DIP, buttons.iconSizeDp() + 6);
        TextView keyboard = buttons.icon(ICON_KEYBOARD, Buttons.DESTINATION,
                "on-screen keyboard", v -> host.onKeyboard());
        keyboard.setTextSize(TypedValue.COMPLEX_UNIT_DIP, buttons.iconSizeDp() + 4);

        View input = buttons.cluster(true, mic, keyboard);
        LayoutParams inputParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, buttons.dp(104));
        inputParams.bottomMargin = buttons.dp(10);
        rail.addView(input, inputParams);

        View pageUp = pageKey(Glyphs.Kind.PAGE_UP, ESC + "[5~", "page up, inside the program");
        View pageDown = pageKey(Glyphs.Kind.PAGE_DOWN, ESC + "[6~", "page down, inside the program");
        rail.addView(railRocker(context, pageUp, pageDown));

        View back = buttons.glyphHalf(Glyphs.Kind.UP, "scroll this window back",
                v -> host.onScroll(-10));
        buttons.repeatOnHold(back, () -> host.onScroll(-10));
        View forward = buttons.glyphHalf(Glyphs.Kind.DOWN, "scroll this window forward",
                v -> host.onScroll(10));
        buttons.repeatOnHold(forward, () -> host.onScroll(10));
        rail.addView(railRocker(context, back, forward));

        rail.addView(railRocker(context,
                buttons.half("A+", "larger text", v -> host.onFontStep(2)),
                buttons.half("A\u2212", "smaller text", v -> host.onFontStep(-2))));

        hint(rail, null);
        return rail;
    }

    /** A single control on the rail, at the height its job deserves. */
    private View railControl(View view, int heightDp) {
        LayoutParams params = new LayoutParams(
                LayoutParams.MATCH_PARENT, buttons.dp(heightDp));
        params.bottomMargin = buttons.dp(10);
        view.setLayoutParams(params);
        return view;
    }

    private View railRocker(Context context, View top, View bottom) {
        View rocker = buttons.rocker(top, bottom);
        LayoutParams params = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.bottomMargin = buttons.dp(10);
        rocker.setLayoutParams(params);
        return rocker;
    }

    /**
     * Add a section to a column, with the gap between sections.
     *
     * <p>It exists because the obvious way silently does not work: a section sets
     * its own {@code bottomMargin} in the constructor, and then {@code addView}
     * with fresh {@code LayoutParams} throws those away — the parameters passed at
     * add time replace the view's own. Every section on the panel was flush
     * against the next one for exactly that reason. Routing every add through here
     * means the gap is applied where it survives.
     */
    private void addSection(LinearLayout column, View section, float weight) {
        LayoutParams params = weight > 0
                ? new LayoutParams(LayoutParams.MATCH_PARENT, 0, weight)
                : new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.bottomMargin = buttons.dp(12);
        column.addView(section, params);
    }

    /**
     * A caption engraved on the panel with its content beneath it, and no plate.
     *
     * <p>For anything that is already a housing of its own — a screen, a scroller
     * full of chips. What a plate buys is the statement "these belong together",
     * and a screen makes that statement by being a screen.
     */
    private LinearLayout captioned(Context context, String caption, View content) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(VERTICAL);

        TextView label = label(caption.toUpperCase(Locale.ROOT), Buttons.skin().heading, 12);
        label.setLetterSpacing(0.10f);
        label.setPadding(buttons.dp(2), buttons.dp(2), 0, buttons.dp(7));
        box.addView(label);

        if (content != null) {
            box.addView(content, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        return box;
    }

    private LinearLayout consoleSection(Context context, String caption, boolean fixed) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(VERTICAL);
        box.setBackground(buttons.section(fixed));
        box.setPadding(buttons.dp(10), buttons.dp(10), buttons.dp(10), buttons.dp(10));

        // 12dp and half the tracking. At 9dp with 0.18 tracking these were the
        // least readable text on a surface whose regions they exist to name — and
        // wide tracking makes it worse, because it destroys word shape, which is
        // the only cue left once a glyph is too small to read letter by letter.
        TextView label = label(caption.toUpperCase(Locale.ROOT), Buttons.skin().heading, 12);
        label.setLetterSpacing(0.10f);
        label.setPadding(buttons.dp(2), buttons.dp(2), 0, buttons.dp(9));
        box.addView(label);

        return box;
    }

    private View gap(Context context, int dp) {
        View view = new View(context);
        view.setLayoutParams(new LayoutParams(buttons.dp(dp), buttons.dp(1)));
        return view;
    }

    /**
     * Fill the console layout: two listings and a column of chips.
     *
     * <p>The split between the two listings is by what the action <em>is</em>, not
     * by which group it arrived in. A directory is a step down from where you are;
     * a favourite is a jump to somewhere else entirely — different distances,
     * different risk of hitting the wrong one, and the host already marks them
     * apart as {@code dir} against {@code fav}. Reading that instead of the group
     * name also means a host that renames a group does not silently move its
     * contents onto the wrong screen.
     */
    private void fillConsole(JSONArray groups) {
        rows.removeAllViews();
        projects.removeAllViews();
        chipColumn.removeAllViews();

        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            JSONArray actions = group.optJSONArray("actions");
            if (actions == null || actions.length() == 0) continue;

            boolean places = true;
            for (int j = 0; j < actions.length(); j++) {
                JSONObject action = actions.optJSONObject(j);
                if (action != null && !isKeystroke(action)) places &= isPlace(action);
            }

            if (places) {
                for (int j = 0; j < actions.length(); j++) {
                    JSONObject action = actions.optJSONObject(j);
                    if (action == null || isKeystroke(action)) continue;
                    boolean bookmark = "fav".equals(action.optString("style", "key"));
                    (bookmark ? projects : rows).addView(consoleRow(action));
                }
                continue;
            }

            TextView caption = label(group.optString("name").toUpperCase(Locale.ROOT),
                    Buttons.skin().heading, 12);
            caption.setLetterSpacing(0.10f);
            caption.setPadding(buttons.dp(2), buttons.dp(6), 0, buttons.dp(6));
            chipColumn.addView(caption);

            FlowLayout flow = new FlowLayout(getContext(), buttons.gap());
            for (int j = 0; j < actions.length(); j++) {
                JSONObject action = actions.optJSONObject(j);
                if (action == null || isKeystroke(action)) continue;
                String label = action.optString("label");
                String send = action.optString("send");
                boolean enter = action.optBoolean("enter");
                boolean danger = "warn".equals(action.optString("style", "key"));
                View chip = buttons.chip(label, danger, v -> host.onAction(send, enter));
                String hint = action.isNull("hint") ? null : action.optString("hint");
                if (hint != null && !hint.isEmpty()) chip.setContentDescription(hint);
                flow.addView(chip);
            }
            chipColumn.addView(flow, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        // A section with nothing in it is not a section, it is a hole.
        //
        // With a program in front the host sends its keys and its skills and stops
        // sending directories — there is nowhere to cd to from inside Claude Code —
        // so the two reading screens would sit empty, taking the height the chips
        // need. Hiding them gives that height back, and it happens by itself
        // because it follows from what arrived rather than from knowing the name
        // of the tool.
        boolean anywhereToGo = rows.getChildCount() > 0;
        boolean anyProjects = projects.getChildCount() > 0;
        browserBox.setVisibility(anywhereToGo ? VISIBLE : GONE);
        projectBox.setVisibility(anyProjects ? VISIBLE : GONE);

        hint(chipColumn, where);
    }

    /** One line of the listing: a mark, the name, and what it is. */
    private View consoleRow(JSONObject action) {
        String send = action.optString("send");
        boolean enter = action.optBoolean("enter");
        String style = action.optString("style", "key");
        boolean up = "up".equals(style);
        String name = up ? ".." : action.optString("label");

        // A cell, not a full-width line. `ls` prints a short listing in columns and
        // so does this: one name per line filled the screen with six directories
        // and pushed everything under it off the panel. The cell has a minimum
        // width so the columns line up; a longer name takes what it needs and the
        // next column starts later, which is again what `ls` does.
        LinearLayout row = new LinearLayout(getContext()) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                int min = buttons.dp(150);
                if (getMeasuredWidth() < min) {
                    setMeasuredDimension(min, getMeasuredHeight());
                }
            }
        };
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(buttons.dp(8), buttons.dp(4), buttons.dp(8), buttons.dp(4));
        row.setClickable(true);
        row.setBackground(buttons.rowSelection());

        TextView mark = label(up ? "\u2191" : "\u25b8", Buttons.skin().phosphorDim, 11);
        mark.setTypeface(Fonts.mono(getContext()));
        mark.setWidth(buttons.dp(16));
        row.addView(mark);

        TextView title = label(name, 0xFF39D863, 14);
        title.setTypeface(Fonts.mono(getContext()));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LayoutParams grow = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        grow.setMarginStart(buttons.dp(8));
        row.addView(title, grow);

        // No "dir" / "proj" column any more. It was there when one listing held
        // both kinds; with a screen each, the label repeated what the heading above
        // it had already said — and in a narrow column it ended up jammed against
        // the name it was describing.

        String hint = action.isNull("hint") ? null : action.optString("hint");
        if (hint != null && !hint.isEmpty()) row.setContentDescription(hint);

        row.setOnClickListener(v -> host.onAction(send, enter));
        return row;
    }

    // ---------------------------------------------------------------- screens

    /**
     * One display per group of places, and ordinary keys for everything else.
     *
     * <p>The split is by what the group is, not by which group it happens to be.
     * A directory, a project, the parent — those are read and then chosen from,
     * which is a list on a screen. `claude`, `git status`, `^R` are things that
     * happen when pressed, and a thing that happens is a key. Putting the second
     * kind behind glass would be the same mistake as making the first kind look
     * pressable, in the other direction.
     */
    private void buildScreens(JSONArray groups) {
        stack.removeAllViews();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            JSONArray actions = group.optJSONArray("actions");
            if (actions == null || actions.length() == 0) continue;

            java.util.List<JSONObject> shown = new java.util.ArrayList<>();
            boolean allPlaces = true;
            for (int j = 0; j < actions.length(); j++) {
                JSONObject action = actions.optJSONObject(j);
                if (action == null || isKeystroke(action)) continue;   // drawn on the other side
                shown.add(action);
                allPlaces &= isPlace(action);
            }
            if (shown.isEmpty()) continue;

            // Engraved into the plate above the screen, not printed inside it. A
            // label inside the glass would be one more lit thing to read past on
            // the way to the list it names.
            TextView heading = label(group.optString("name").toUpperCase(Locale.ROOT),
                    Buttons.MUTED, 9);
            heading.setLetterSpacing(0.14f);
            heading.setPadding(buttons.dp(3), buttons.dp(4), 0, buttons.dp(3));
            stack.addView(heading);

            boolean asListing = allPlaces && Buttons.skin().listing;

            android.view.ViewGroup items;
            if (asListing) {
                // Columns, the way `ls` prints them, not one name per line. A
                // single column of six directories filled the whole panel and
                // pushed the projects off the bottom — and it was not even what a
                // terminal does with a short listing.
                FlowLayout column = new FlowLayout(getContext(), 0);
                for (JSONObject action : shown) {
                    column.addView(listingRow(action));
                }
                items = column;
            } else {
                FlowLayout flow = new FlowLayout(getContext(), buttons.gap());
                for (JSONObject action : shown) {
                    flow.addView(allPlaces ? readout(action) : actionButton(action));
                }
                items = flow;
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            params.bottomMargin = buttons.dp(8);
            if (allPlaces) {
                items.setBackground(buttons.display());
                int pad = asListing ? buttons.dp(6) : buttons.dp(10);
                items.setPadding(buttons.dp(8), pad, buttons.dp(8), pad);
            } else if (Buttons.skin().sections) {
                // Keys are mounted in a milled region rather than standing on the
                // bare panel, so a group of them reads as one block of controls.
                items.setBackground(buttons.section());
                items.setPadding(buttons.dp(10), buttons.dp(10),
                        buttons.dp(10), buttons.dp(10));
            }
            stack.addView(items, params);
        }
        hint(stack, where);
    }

    /** One line of a listing: the cursor mark, then the name. */
    private View listingRow(JSONObject action) {
        String send = action.optString("send");
        boolean enter = action.optBoolean("enter");
        String hint = action.isNull("hint") ? null : action.optString("hint");
        // The parent directory keeps its own mark. Everything else is a plain name
        // with room in front of it for the cursor the highlight provides.
        String label = "up".equals(action.optString("style", "key"))
                ? ".." : action.optString("label");
        return buttons.row(label, hint, v -> host.onAction(send, enter));
    }

    /** A line on a screen: lit text, no cap, and the selection is an inversion. */
    private View readout(JSONObject action) {
        String send = action.optString("send");
        boolean enter = action.optBoolean("enter");
        String hint = action.isNull("hint") ? null : action.optString("hint");
        View view = buttons.readout(action.optString("label"), hint,
                v -> host.onAction(send, enter));

        if ("up".equals(action.optString("style", "key")) && view instanceof TextView) {
            ((TextView) view).setCompoundDrawablesWithIntrinsicBounds(
                    Glyphs.drawable(getContext(), Glyphs.Kind.UP, Buttons.skin().phosphor,
                            buttons.iconSizeDp()), null, null, null);
            ((TextView) view).setCompoundDrawablePadding(buttons.dp(6));
        }
        return view;
    }

    /** Somewhere to go, as opposed to something to do. */
    private static boolean isPlace(JSONObject action) {
        String style = action.optString("style", "key");
        return "up".equals(style) || "dir".equals(style) || "git".equals(style)
                || "fav".equals(style);
    }

    private static int dim(int colour, float amount) {
        return android.graphics.Color.rgb(
                (int) (android.graphics.Color.red(colour) * amount),
                (int) (android.graphics.Color.green(colour) * amount),
                (int) (android.graphics.Color.blue(colour) * amount));
    }

    // -------------------------------------------------------------- dictation

    public void showRecording() {
        left.setVisibility(GONE);
        right.setVisibility(GONE);
        divider.setVisibility(GONE);
        recordingRow.setVisibility(VISIBLE);
        meter.setVisibility(VISIBLE);
        timer.setVisibility(VISIBLE);
        stop.setVisibility(VISIBLE);
        meter.reset();
        timer.setText("0:00");
        note.setText("");
    }

    /** Stopped, waiting on Whisper: the meter is meaningless now, the wait is not. */
    public void showRecognising() {
        meter.setVisibility(GONE);
        timer.setVisibility(GONE);
        stop.setVisibility(GONE);
        note.setText("recognising…");
    }

    public void hideRecording() {
        recordingRow.setVisibility(GONE);
        left.setVisibility(VISIBLE);
        right.setVisibility(VISIBLE);
        divider.setVisibility(VISIBLE);
    }

    public void pushLevel(float level) {
        meter.push(level);
    }

    public void setTimer(String text) {
        timer.setText(text);
    }

    public void setNote(String text) {
        note.setText(text);
    }

    // -------------------------------------------------------------- building

    private View actionButton(JSONObject action) {
        String text = action.optString("label");
        String send = action.optString("send");
        boolean enter = action.optBoolean("enter");
        String hint = action.isNull("hint") ? null : action.optString("hint");

        String rawStyle = action.optString("style", "key");
        int style = styleFor(rawStyle);

        // A button that runs on press and one that only types look identical
        // otherwise, and the difference is /clear wiping a conversation.
        TextView view = buttons.key(text, style, hint, v -> host.onAction(send, enter));

        // Going up is a direction, so it gets the same chevron the arrow keys do,
        // drawn rather than typed. The server used to put an arrow in the label and
        // it was the last font glyph left standing next to a drawn set.
        if ("up".equals(rawStyle)) {
            view.setCompoundDrawablesWithIntrinsicBounds(
                    Glyphs.drawable(getContext(), Glyphs.Kind.UP, Buttons.TEXT, buttons.iconSizeDp()),
                    null, null, null);
            view.setCompoundDrawablePadding(buttons.dp(6));
        }

        // The mark is for the exception, not the rule — which is what the tables
        // actually say. Every "key" types and never runs; every "cmd" but one runs.
        // So on those the hook repeated what the colour had already said, and a
        // whole section called RUN wore it on every button.
        //
        // "warn" is the one style that is genuinely mixed: `q` in a pager only
        // types, `/clear` and `:q!` go off the moment they are pressed. There the
        // mark is the difference between leaving a pager and wiping a conversation,
        // so there it stays.
        if (enter && style == Buttons.WARN) {
            view.setCompoundDrawablesWithIntrinsicBounds(null, null,
                    Glyphs.drawable(getContext(), Glyphs.Kind.ENTER, Buttons.TEXT,
                            buttons.iconSizeDp() + 4), null);
            view.setCompoundDrawablePadding(buttons.dp(7));
        }

        return view;
    }

    /**
     * Whether this button types rather than does. The tables already carry the
     * distinction and always have: a "key" or a "warn" that does not press Enter is
     * a keystroke, and everything else — a directory, a command, a skill — is a
     * thing the bar goes and does.
     */
    private static boolean isKeystroke(JSONObject action) {
        if (action.optBoolean("enter")) return false;
        String style = action.optString("style", "key");
        return "key".equals(style) || "warn".equals(style);
    }

    private static int styleFor(String style) {
        switch (style) {
            case "up":
            case "dir":
            case "git":
            case "fav":
                return Buttons.DESTINATION;
            case "cmd":
            case "skill":
                return Buttons.COMMAND;
            case "warn":
                return Buttons.WARN;
            case "skill-global":
                // Muted, the way it always was — a skill from elsewhere rather than
                // from this project. Only its filing changed, not its face.
                return Buttons.KEY;
            default:
                return Buttons.KEY;
        }
    }

    /**
     * Hints on hover, not on long press.
     *
     * <p>Long press used to show them, and it had two faults: a long press cancels the
     * click, so holding slightly too long silently did nothing, and the hint replaced the
     * path permanently — the host only pushes context when the directory or the foreground
     * program changes, so it stayed gone until the next `cd`.
     */
    private void hint(View view, TextView target) {
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hint(group.getChildAt(i), target);
            return;
        }
        CharSequence description = view.getContentDescription();
        if (description == null || description.length() == 0) return;

        // Next to the button as well as in the status line. The status line is at
        // the top of the other panel, so a hint about a key on the right appeared
        // at the far end of the bar and went unread; a tooltip shows up where the
        // ray already is.
        view.setTooltipText(description);

        view.setOnHoverListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_HOVER_ENTER:
                    if (target != null) target.setText(description);
                    break;
                case android.view.MotionEvent.ACTION_HOVER_EXIT:
                    if (target != null) target.setText(path);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private TextView label(String text, int colour, int sizeDp) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(colour);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
}
