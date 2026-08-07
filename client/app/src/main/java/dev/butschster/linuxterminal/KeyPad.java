package dev.butschster.linuxterminal;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

/**
 * The keys that never move.
 *
 * <p>This is not an accelerator strip — it is the keyboard. The Quest's own on-screen
 * keyboard has no Escape and no Control, so without these keys a terminal cannot be
 * used at all, and FR-17 says they must always be one press away.
 *
 * <p>Which means they must obey keyboard rules: a key never moves, never disappears and
 * never changes meaning. It is built once, here, in a grid of fixed slots, and the
 * context that rebuilds the rest of the bar four times a second never touches it. The
 * previous version placed these keys last in a list whose earlier groups changed length,
 * so typing `claude` moved Enter and ^C to a different row — the exact failure the Touch
 * Bar is remembered for, committed against the keys that matter most.
 *
 * <p>Everything here is universally meaningful. Nothing tool-specific is allowed in, or
 * slots would have to empty out and the grid would reshuffle again.
 */
public class KeyPad extends LinearLayout {

    /** What a key does when pressed: send these bytes to the pty. */
    public interface Send {
        void send(String bytes);
    }

    private static final String ESC = "\u001b";

    private final Send send;
    private final Buttons buttons;

    public KeyPad(Context context, Buttons buttons, Send send) {
        super(context);
        this.send = send;
        this.buttons = buttons;
        setOrientation(VERTICAL);

        // Three columns, not four. Wider keys are easier to hit with a ray, and
        // three is what the arrow cluster below needs to sit square.
        addView(row(
                key("Esc", ESC, Buttons.WARN, "cancel, everywhere"),
                key("^C", "\u0003", Buttons.WARN, "interrupt"),
                key("^D", "\u0004", Buttons.WARN, "end of input — closes a shell")));

        addView(row(
                key("^L", "\u000c", Buttons.KEY, "clear the screen"),
                key("Tab", "\t", Buttons.KEY, "complete"),
                glyphKey(Glyphs.Kind.BACKSPACE, "\u007f", Buttons.KEY, "backspace")));

        // ^W and ^U flank the up arrow rather than sitting in a row of their own.
        // Nothing is left empty and the cluster still reads as the inverted T every
        // keyboard has, because the arrows carry a colour of their own — the shape
        // survives having neighbours.
        addView(row(
                key("^W", "\u0017", Buttons.KEY, "delete the word before the cursor"),
                repeating(Glyphs.Kind.UP, ESC + "[A", Buttons.ARROW, "up — previous command"),
                key("^U", "\u0015", Buttons.KEY, "delete to the start of the line")));

        addView(row(
                repeating(Glyphs.Kind.LEFT, ESC + "[D", Buttons.ARROW, "left"),
                repeating(Glyphs.Kind.DOWN, ESC + "[B", Buttons.ARROW, "down"),
                repeating(Glyphs.Kind.RIGHT, ESC + "[C", Buttons.ARROW, "right")));

        // Enter takes the full width at the bottom, where two of its four error
        // directions are the window edge and a miss costs nothing.
        addView(row(key("Enter", "\r", Buttons.ENTER, "run it")));
    }

    /**
     * An empty cell, so a row of three can hold fewer keys and still line up.
     *
     * <p>{@link android.widget.Space} rather than a bare {@code View}, and the
     * difference is not cosmetic: a plain View asked to WRAP_CONTENT answers with
     * the whole space offered to it, because {@code getDefaultSize} returns the
     * spec size for AT_MOST. The first version of this row therefore measured 1216
     * pixels tall and left nothing for the three rows below it — the arrows and
     * Enter were built, added, and given zero height.
     */
    private View spacer() {
        return new android.widget.Space(getContext());
    }

    private View key(String label, String bytes, int style, String hint) {
        return buttons.key(label, style, hint, v -> send.send(bytes));
    }

    private View glyphKey(Glyphs.Kind kind, String bytes, int style, String hint) {
        return buttons.glyphKey(kind, style, hint, v -> send.send(bytes));
    }

    /** Arrows and paging: one press per line makes a list unusable. */
    private View repeating(Glyphs.Kind kind, String bytes, int style, String hint) {
        View view = buttons.glyphKey(kind, style, hint, v -> send.send(bytes));
        buttons.repeatOnHold(view, () -> send.send(bytes));
        return view;
    }

    private LinearLayout row(View... cells) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        for (View cell : cells) {
            LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(buttons.gap() / 2, buttons.gap() / 2,
                    buttons.gap() / 2, buttons.gap() / 2);
            row.addView(cell, params);
        }
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        setLayoutParams(params);
        return row;
    }
}
