package dev.butschster.linuxterminal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

    // Four roles, not eight decorative shades. The previous palette had two greys
    // sixteen units apart, which through pancake lenses is one grey.
    private static final int[] FILL = {
            Color.rgb(58, 60, 70),      // KEY — a literal keystroke
            Color.rgb(52, 58, 74),      // DESTINATION — somewhere to go
            Color.rgb(40, 82, 128),     // COMMAND — a line that runs
            Color.rgb(122, 58, 30),     // WARN — destructive
            Color.rgb(38, 86, 62),      // ENTER
            Color.rgb(52, 118, 84),     // VOICE
    };

    public static final int TEXT = Color.rgb(224, 226, 232);
    public static final int MUTED = Color.rgb(150, 155, 168);

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
        view.setClickable(true);
        view.setFocusable(false);
        debounced(view, onClick);
        if (hint != null && !hint.isEmpty()) view.setContentDescription(hint);
        return view;
    }

    public TextView icon(String glyph, int style, String hint, View.OnClickListener onClick) {
        TextView view = key(glyph, style, hint, onClick);
        view.setTypeface(icons);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp + 3);
        return view;
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
        states.addState(new int[]{android.R.attr.state_pressed}, solid(lighten(fill, 0.35f)));
        states.addState(new int[]{android.R.attr.state_hovered}, solid(lighten(fill, 0.18f)));
        states.addState(new int[]{}, solid(fill));
        return states;
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
