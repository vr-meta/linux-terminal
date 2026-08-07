package dev.butschster.linuxterminal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Six digits, shown as six boxes, typed and pasted into one field.
 *
 * <p>A row of six independently-focusable {@code EditText}s is the obvious build and
 * the wrong one: advancing focus after each character, catching backspace out of an
 * empty box, and unpacking a six-digit paste across six separate views are three
 * pieces of platform behaviour that Android's own single-field {@code EditText}
 * already gets right by itself. So there is exactly one real field here — sized to
 * the whole row and made invisible — and the six boxes underneath are a rendering of
 * its text, nothing more. Tap the row once, type or paste, and the boxes just show
 * what arrived; there is no second control to keep in step with the first.
 */
public class PairCodeInput extends FrameLayout {

    public interface Listener {
        /** Called on every change. {@code complete} is true once all six digits are in. */
        void onChanged(String code, boolean complete);
    }

    private static final int DIGITS = 6;

    // Bigger than an ordinary key, on both axes. This is the one field the whole
    // screen exists for: what lands here gets read back against a fingerprint on a
    // different machine, so every digit has to be unambiguous at a glance through
    // the lenses, not merely hittable. Width matches Buttons' own minimum key
    // width — the same unit the rest of the app already uses for "a target" — and
    // height goes beyond it because a lone digit needs vertical room to stop 8
    // from reading as 3, or 1 from reading as 7, at this size.
    public static final int BOX_WIDTH_DP = 46;
    public static final int BOX_HEIGHT_DP = 58;

    private static final int BOX_FILL = Color.rgb(30, 32, 39);
    private static final int BOX_FILLED = Color.rgb(42, 45, 55);
    private static final int BOX_BORDER = Buttons.RULE;
    private static final int BOX_BORDER_ACTIVE = Color.rgb(96, 148, 208);

    private final Buttons buttons;
    private final EditText input;
    private final TextView[] boxes = new TextView[DIGITS];
    private final TextWatcher watcher;

    public PairCodeInput(Context context, Buttons buttons, Listener listener) {
        super(context);
        this.buttons = buttons;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        for (int i = 0; i < DIGITS; i++) {
            TextView box = new TextView(context);
            box.setTextColor(Buttons.TEXT);
            box.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
            box.setTypeface(Typeface.MONOSPACE);
            box.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    buttons.dp(BOX_WIDTH_DP), buttons.dp(BOX_HEIGHT_DP));
            // Dead space bigger than a bare gap() — the same reasoning
            // ServersActivity gives its own air(): six boxes shoulder to shoulder
            // is exactly the layout where a glance slides from one digit onto its
            // neighbour, and misreading a digit is the entire failure mode this
            // screen exists to prevent.
            if (i > 0) params.leftMargin = buttons.gap() * 2;
            row.addView(box, params);
            boxes[i] = box;
        }
        render("");

        // The real field: transparent, cursor hidden, sized to the whole row so a
        // tap anywhere on it opens the keyboard. It is not decoration — it is what
        // actually holds focus and receives every keystroke and every paste.
        input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{digitsOnly()});
        input.setTextColor(Color.TRANSPARENT);
        input.setCursorVisible(false);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setPadding(0, 0, 0, 0);
        addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                String code = text.toString();
                render(code);
                listener.onChanged(code, code.length() == DIGITS);
            }
        };
        input.addTextChangedListener(watcher);
    }

    /**
     * Keeps digits and drops everything else a paste might carry, and never lets
     * the result run past six. A person copying the code off a console is likely
     * to select whitespace around it too; stripping non-digits here means that
     * still lands cleanly instead of overflowing the field with punctuation.
     */
    private InputFilter digitsOnly() {
        return (source, start, end, dest, dstart, dend) -> {
            StringBuilder kept = new StringBuilder(end - start);
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (Character.isDigit(c)) kept.append(c);
            }
            int room = DIGITS - (dest.length() - (dend - dstart));
            if (kept.length() > room) kept.setLength(Math.max(0, room));
            return kept.toString();
        };
    }

    private void render(String code) {
        int active = code.length() < DIGITS ? code.length() : -1;
        for (int i = 0; i < DIGITS; i++) {
            boolean filled = i < code.length();
            boxes[i].setText(filled ? String.valueOf(code.charAt(i)) : "");
            boxes[i].setBackground(boxShape(filled ? BOX_FILLED : BOX_FILL, i == active));
        }
    }

    private GradientDrawable boxShape(int fill, boolean active) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(buttons.dp(10));
        shape.setStroke(active ? buttons.dp(2) : Math.max(1, buttons.dp(1)),
                active ? BOX_BORDER_ACTIVE : BOX_BORDER);
        return shape;
    }

    /** The digits typed so far — fewer than six while the person is still typing. */
    public String code() {
        return input.getText().toString();
    }

    /**
     * Wipes the field for a retry without echoing that as a change: the caller is
     * about to show the reason the last code failed, and firing the listener here
     * would immediately overwrite that message with the empty-field state.
     */
    public void clear() {
        input.removeTextChangedListener(watcher);
        input.setText("");
        render("");
        input.addTextChangedListener(watcher);
    }

    public void requestCodeFocus() {
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }
}
