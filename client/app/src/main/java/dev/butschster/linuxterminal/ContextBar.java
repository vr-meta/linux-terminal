package dev.butschster.linuxterminal;

import android.content.Context;
import android.graphics.Color;
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

    private static final int BG = Color.rgb(24, 25, 31);
    private static final int BG_FIXED = Color.rgb(31, 33, 40);

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
        setBackgroundColor(BG);

        // ---------------------------------------------------------- left: dynamic

        // No horizontal padding here, so a group's rule can run the full width of
        // the panel and meet the vertical divider the way a ruled sheet does. What
        // needs breathing room asks for it individually, below.
        left = new LinearLayout(context);
        left.setOrientation(VERTICAL);
        left.setPadding(0, buttons.dp(8), 0, buttons.dp(8));
        addView(left, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        LinearLayout status = new LinearLayout(context);
        status.setOrientation(HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(buttons.dp(10), 0, buttons.dp(6), 0);
        left.addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Text only — nothing here is pressable. A status line that can be clicked
        // is a row of accidental targets above the row you were aiming at.
        where = label(path, Buttons.TEXT, 16);
        where.setTypeface(Typeface.MONOSPACE);
        where.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        where.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        status.addView(where, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        what = label("", Buttons.MUTED, 15);
        what.setPadding(buttons.dp(10), 0, 0, 0);
        status.addView(what);

        dynamic = new FlowLayout(context, buttons.gap());
        dynamic.setPadding(buttons.inset(), buttons.dp(2), buttons.inset(), 0);
        // The rules are drawn past this padding, out to the panel's own edges.
        // Both flags are needed and they are not the same thing: clipToPadding
        // stops the padded area being cut, clipChildren is what lets a child draw
        // outside its own bounds at all. With only the first, the rules came up
        // short at both ends.
        dynamic.setClipToPadding(false);
        dynamic.setClipChildren(false);
        ScrollView dynamicScroll = new ScrollView(context);
        dynamicScroll.addView(dynamic, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        left.addView(dynamicScroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------------------------------------------------------- right: fixed

        divider = new View(context);
        divider.setBackgroundColor(Buttons.RULE);
        addView(divider, new LayoutParams(buttons.dp(1), LayoutParams.MATCH_PARENT));

        right = new LinearLayout(context);
        right.setOrientation(VERTICAL);
        right.setBackgroundColor(BG_FIXED);
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
        toolKeys = new FlowLayout(context, buttons.gap());
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
        controls.setBackgroundColor(BG_FIXED);
        controls.setPadding(buttons.dp(6), buttons.dp(8), buttons.dp(6), buttons.dp(8));
        addView(controls, new LayoutParams(buttons.dp(STRIP_WIDTH_DP), LayoutParams.MATCH_PARENT));

        addControl(controls, buttons.icon(ICON_MIC, Buttons.VOICE, "dictate", v -> host.onDictate()));
        addControl(controls, buttons.icon(ICON_KEYBOARD, Buttons.COMMAND, "on-screen keyboard",
                v -> host.onKeyboard()));
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
        timer.setTypeface(Typeface.MONOSPACE);
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
                    Buttons.MUTED, 9);
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
