package dev.butschster.linuxterminal;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The bar, off its leash: the real {@link ContextBar}, fed a recorded context, with
 * the skin switchable underneath it.
 *
 * <p><b>Debug builds only.</b> This class lives in {@code src/debug} and so does the
 * manifest entry that launches it — a release build contains neither, which is the
 * point. A concept screen that shipped would be one more thing for a store reviewer
 * to find and ask about.
 *
 * <p>Why it exists: the bar cannot otherwise be looked at without a paired server, a
 * running shell and a headset on a head. That loop is minutes long and it is the
 * wrong loop for a question about colour. The contexts below are verbatim output of
 * {@code --dump-context}, trimmed, so what is drawn here is what the server actually
 * sends rather than a designer's idea of it.
 *
 * <p>What it cannot answer: how any of this reads through the lenses.
 * `docs/readability.md` measured that threshold on the hardware and an emulator has
 * no opinion about it. This screen settles layout, weight and palette; the headset
 * settles whether a 1dp lit edge survives at all.
 */
public class GalleryActivity extends Activity implements ContextBar.Host {

    /** At a prompt: somewhere to go, projects, things to run. */
    private static final String SHELL_CONTEXT = "{"
            + "\"cwd_label\":\"~/repos/home/linux-terminal\","
            + "\"tool\":null,\"tool_name\":\"shell\","
            + "\"git\":{\"branch\":\"concept/panel-look\",\"dirty\":4},"
            + "\"groups\":["
            + "{\"name\":\"go to\",\"actions\":["
            + "{\"label\":\"..\",\"send\":\"cd ..\",\"style\":\"up\",\"enter\":true,"
            + "\"hint\":\"~/repos/home\"},"
            + "{\"label\":\".claude\",\"send\":\"cd .claude\",\"style\":\"dir\",\"enter\":true},"
            + "{\"label\":\"client\",\"send\":\"cd client\",\"style\":\"dir\",\"enter\":true},"
            + "{\"label\":\"docs\",\"send\":\"cd docs\",\"style\":\"dir\",\"enter\":true},"
            + "{\"label\":\"packaging\",\"send\":\"cd packaging\",\"style\":\"dir\",\"enter\":true},"
            + "{\"label\":\"server\",\"send\":\"cd server\",\"style\":\"git\",\"enter\":true}]},"
            + "{\"name\":\"projects\",\"actions\":["
            + "{\"label\":\"docker-infra\",\"send\":\"cd x\",\"style\":\"fav\",\"enter\":true,"
            + "\"hint\":\"~/repos/intruforce/docker-infra  ·  34×\"},"
            + "{\"label\":\"cve-analyzer\",\"send\":\"cd x\",\"style\":\"fav\",\"enter\":true},"
            + "{\"label\":\"go-services\",\"send\":\"cd x\",\"style\":\"fav\",\"enter\":true},"
            + "{\"label\":\"k8s\",\"send\":\"cd x\",\"style\":\"fav\",\"enter\":true},"
            + "{\"label\":\"ai-agents\",\"send\":\"cd x\",\"style\":\"fav\",\"enter\":true},"
            + "{\"label\":\"pentax\",\"send\":\"cd x\",\"style\":\"fav\",\"enter\":true},"
            + "{\"label\":\"home/terminal\",\"send\":\"cd x\",\"style\":\"fav\",\"enter\":true},"
            + "{\"label\":\"shared\",\"send\":\"cd x\",\"style\":\"fav\",\"enter\":true}]},"
            + "{\"name\":\"run\",\"actions\":["
            + "{\"label\":\"claude\",\"send\":\"claude\",\"style\":\"cmd\",\"enter\":true},"
            + "{\"label\":\"codex\",\"send\":\"codex\",\"style\":\"cmd\",\"enter\":true},"
            + "{\"label\":\"ls -la\",\"send\":\"ls -la\",\"style\":\"cmd\",\"enter\":true},"
            + "{\"label\":\"^R\",\"send\":\"\\u0012\",\"style\":\"key\",\"enter\":false,"
            + "\"hint\":\"history search\"},"
            + "{\"label\":\"git status\",\"send\":\"git status\",\"style\":\"cmd\",\"enter\":true},"
            + "{\"label\":\"git diff\",\"send\":\"git diff\",\"style\":\"cmd\",\"enter\":true}]}]}";

    /** With Claude Code in front: its own keys on the right, its skills on the left. */
    private static final String CLAUDE_CONTEXT = "{"
            + "\"cwd_label\":\"~/repos/home/linux-terminal\","
            + "\"tool\":\"claude\",\"tool_name\":\"claude\","
            + "\"git\":{\"branch\":\"concept/panel-look\",\"dirty\":4},"
            + "\"groups\":["
            + "{\"name\":\"claude\",\"actions\":["
            + "{\"label\":\"⇧Tab\",\"send\":\"\\u001b[Z\",\"style\":\"key\",\"enter\":false,"
            + "\"hint\":\"cycle permission mode\"},"
            + "{\"label\":\"1\",\"send\":\"1\",\"style\":\"key\",\"enter\":false},"
            + "{\"label\":\"2\",\"send\":\"2\",\"style\":\"key\",\"enter\":false},"
            + "{\"label\":\"3\",\"send\":\"3\",\"style\":\"key\",\"enter\":false},"
            + "{\"label\":\"Esc Esc\",\"send\":\"\\u001b\\u001b\",\"style\":\"warn\",\"enter\":false},"
            + "{\"label\":\"^T\",\"send\":\"\\u0014\",\"style\":\"key\",\"enter\":false},"
            + "{\"label\":\"^O\",\"send\":\"\\u000f\",\"style\":\"key\",\"enter\":false},"
            + "{\"label\":\"^B\",\"send\":\"\\u0002\",\"style\":\"key\",\"enter\":false},"
            + "{\"label\":\"^S\",\"send\":\"\\u0013\",\"style\":\"key\",\"enter\":false},"
            + "{\"label\":\"/\",\"send\":\"/\",\"style\":\"key\",\"enter\":false},"
            + "{\"label\":\"!\",\"send\":\"!\",\"style\":\"key\",\"enter\":false},"
            + "{\"label\":\"#\",\"send\":\"#\",\"style\":\"key\",\"enter\":false},"
            + "{\"label\":\"/plan\",\"send\":\"/plan\",\"style\":\"cmd\",\"enter\":true},"
            + "{\"label\":\"/rewind\",\"send\":\"/rewind\",\"style\":\"cmd\",\"enter\":true},"
            + "{\"label\":\"/context\",\"send\":\"/context\",\"style\":\"cmd\",\"enter\":true},"
            + "{\"label\":\"/review\",\"send\":\"/review\",\"style\":\"cmd\",\"enter\":true},"
            + "{\"label\":\"/compact\",\"send\":\"/compact\",\"style\":\"cmd\",\"enter\":true},"
            + "{\"label\":\"/usage\",\"send\":\"/usage\",\"style\":\"cmd\",\"enter\":true},"
            + "{\"label\":\"/clear\",\"send\":\"/clear\",\"style\":\"warn\",\"enter\":true,"
            + "\"hint\":\"wipe the conversation\"}]},"
            + "{\"name\":\"skills\",\"actions\":["
            + "{\"label\":\"/diagnose\",\"send\":\"/diagnose \",\"style\":\"skill\",\"enter\":false},"
            + "{\"label\":\"/headset\",\"send\":\"/headset \",\"style\":\"skill\",\"enter\":false},"
            + "{\"label\":\"/install\",\"send\":\"/install \",\"style\":\"skill\",\"enter\":false},"
            + "{\"label\":\"/new-feature\",\"send\":\"/new-feature \",\"style\":\"skill\",\"enter\":false},"
            + "{\"label\":\"/pairing\",\"send\":\"/pairing \",\"style\":\"skill\",\"enter\":false},"
            + "{\"label\":\"/release\",\"send\":\"/release \",\"style\":\"skill\",\"enter\":false},"
            + "{\"label\":\"/minimalist-ui\",\"send\":\"/minimalist-ui \",\"style\":\"skill-global\","
            + "\"enter\":false},"
            + "{\"label\":\"/infraq\",\"send\":\"/infraq \",\"style\":\"skill-global\",\"enter\":false}]}]}";

    private static final Skin[] SKINS =
            {Skin.capped(), Skin.outline(), Skin.machined(), Skin.tactile(), Skin.console()};
    private static final String[] SKIN_NAMES =
            {"capped", "outline", "machined", "tactile", "console"};

    // The font switcher is gone: DejaVu won, and it is what docs/readability.md
    // measured with. JetBrains Mono stays in assets until the headset says which
    // it prefers; if DejaVu holds, the other should be deleted rather than kept.
    private LinearLayout root;
    private int chosen;
    private boolean claude;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root);
        build();
    }

    /**
     * Everything is rebuilt on every switch, deliberately.
     *
     * <p>{@link Buttons} reads the skin while it builds a view, so an existing bar
     * keeps the colours it was born with. Rebuilding is also the honest test: it is
     * exactly what the bar does whenever the foreground process changes.
     */
    private void build() {
        Buttons.applySkin(SKINS[chosen]);
        Fonts.choose(Fonts.DEJAVU);
        root.removeAllViews();
        root.setBackgroundColor(Buttons.skin().bg);

        root.addView(switcher(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Every skin, console included, is the real ContextBar. The console one
        // rearranges the same parts rather than replacing them, so what is looked
        // at here is what would ship.
        ContextBar bar = new ContextBar(this, this);
        try {
            bar.setContext(new JSONObject(claude ? CLAUDE_CONTEXT : SHELL_CONTEXT));
        } catch (JSONException e) {
            bar.setStatus("bad mock context: " + e.getMessage());
        }
        View panel = bar;
        root.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** The one row that is not part of the concept: what to draw, and in which skin. */
    private LinearLayout switcher() {
        Typeface icons = Typeface.createFromAsset(getAssets(), "MaterialIcons-Regular.ttf");
        Buttons buttons = new Buttons(this, icons);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Buttons.skin().bgPanel);
        row.setPadding(buttons.inset(), buttons.dp(6), buttons.inset(), buttons.dp(6));

        row.addView(caption(buttons, "SKIN"));
        for (int i = 0; i < SKINS.length; i++) {
            final int which = i;
            row.addView(choice(buttons, SKIN_NAMES[i], chosen == i, v -> {
                chosen = which;
                build();
            }));
        }

        row.addView(caption(buttons, "CONTEXT"));
        row.addView(choice(buttons, "shell", !claude, v -> {
            claude = false;
            build();
        }));
        row.addView(choice(buttons, "claude", claude, v -> {
            claude = true;
            build();
        }));

        TextView note = new TextView(this);
        note.setText("debug · mocked context · lenses not simulated");
        note.setTextColor(Buttons.MUTED);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        fill.setMarginStart(buttons.dp(16));
        note.setGravity(Gravity.END);
        row.addView(note, fill);

        return row;
    }

    private TextView caption(Buttons buttons, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Buttons.MUTED);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        view.setLetterSpacing(0.12f);
        view.setPadding(buttons.dp(14), 0, buttons.dp(8), 0);
        return view;
    }

    private View choice(Buttons buttons, String label, boolean active, View.OnClickListener click) {
        View view = buttons.key(label, active ? Buttons.COMMAND : Buttons.KEY, null, click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(buttons.gap());
        view.setLayoutParams(params);
        return view;
    }

    // ------------------------------------------------------- nothing is connected

    @Override
    public void onAction(String send, boolean enter) {
    }

    @Override
    public void onDictate() {
    }

    @Override
    public void onStopDictating() {
    }

    @Override
    public void onKeyboard() {
    }

    @Override
    public void onFontStep(int delta) {
    }

    @Override
    public void onScroll(int rows) {
    }
}
