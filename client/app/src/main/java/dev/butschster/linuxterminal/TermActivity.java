package dev.butschster.linuxterminal;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A terminal window: a strip of tabs, and the active one's grid under it.
 *
 * <p>Tabs rather than more windows because the two are not the same thing. A window is
 * placed in the room and costs a place to put it; a tab costs a press. Both exist —
 * several windows still work, each with its own tabs — but stacking four shells for one
 * project belongs in one window.
 *
 * <p>The bar is not here. It is {@link BarActivity}, in a window of its own, so the shell
 * can size it independently; it follows whichever tab of whichever window is active.
 */
public class TermActivity extends Activity implements Terminals.Target {

    private static final String TAG = "linux-terminal";

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_NAME = "name";

    /**
     * Default cell height in px. From docs/readability.md: comfort begins near 0.39° per
     * glyph, which on a panel of this size lands around 20 px — and unlike the streamed
     * desktop, nothing downscales it afterwards.
     */
    private static final int DEFAULT_FONT_PX = 20;

    // The active tab is filled with the terminal's colour and the inactive ones with
    // the strip's, so a tab reads as part of what is below it rather than as a button
    // sitting above it — the same shape as the desktop terminal fork.
    private static final int CONTENT_BG = android.graphics.Color.rgb(16, 17, 21);
    private static final int STRIP_BG = android.graphics.Color.rgb(24, 25, 31);

    private final List<TermTab> tabs = new ArrayList<>();
    private int active = -1;

    private String host;
    private int port = ServersActivity.DEFAULT_PORT;
    private String serverName = "";
    private Buttons buttons;
    private LinearLayout tabStrip;
    private FrameLayout content;
    private int fontSize = DEFAULT_FONT_PX;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        host = getIntent().getStringExtra(EXTRA_HOST);
        port = getIntent().getIntExtra(EXTRA_PORT, ServersActivity.DEFAULT_PORT);
        serverName = getIntent().getStringExtra(EXTRA_NAME);
        if (serverName == null) serverName = host == null ? "" : host;
        Typeface icons = Typeface.createFromAsset(getAssets(), "MaterialIcons-Regular.ttf");
        buttons = new Buttons(this, icons);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CONTENT_BG);

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setBackgroundColor(STRIP_BG);
        strip.setPadding(buttons.dp(6), buttons.dp(5), buttons.dp(6), 0);
        root.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabStrip);
        strip.addView(tabScroll, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Every tab carries its own close, the way a browser does, so + is the only
        // thing left on the strip itself.
        View add = buttons.key("+", Buttons.COMMAND, "new tab here", v -> addTab(currentCwd()));
        strip.addView(add, sideParams());

        // A window that can be closed has to be openable again, and a bar that is
        // gone takes Escape and Enter with it.
        View bar = buttons.key("bar", Buttons.KEY, "show the key bar", v -> openBar());
        strip.addView(bar, sideParams());

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // Launched without a server — from the launcher, or by adb. The manager is
        // the front door; there is nothing sensible to do here without one.
        if (host == null) {
            startActivity(new Intent(this, ServersActivity.class));
            finish();
            return;
        }

        addTab(null);

        openBar();
    }

    /**
     * Bring up the bar, or bring the existing one forward.
     *
     * <p>NEW_DOCUMENT and not LAUNCH_ADJACENT. Meta documents the latter — "launched
     * next to the actively running activity from your app" — and on this device it
     * produced no window at all: the bar simply vanished. The documented flag lost to
     * the measured one, which is the order this project settles things in.
     */
    private void openBar() {
        if (BarActivity.isOpen()) return;
        Intent bar = new Intent(this, BarActivity.class);
        bar.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        startActivity(bar);
    }

    private LinearLayout.LayoutParams sideParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(buttons.gap() / 2, 0, buttons.gap() / 2, 0);
        return params;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Whichever terminal was resumed last is the one the bar drives. Horizon OS
        // does not tell an app which of its windows is being looked at, and this is
        // the closest fact available.
        Terminals.setActive(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Terminals.clearIf(this);
        for (TermTab tab : tabs) tab.session.close();
    }

    // -------------------------------------------------------------------- tabs

    private String currentCwd() {
        TermTab tab = activeTab();
        return tab == null ? null : tab.cwd();
    }

    private void addTab(String cwd) {
        if (host == null) return;

        TermView view = new TermView(this, fontSize);
        TermTab[] holder = new TermTab[1];
        HostSession session = new HostSession(host, port, new HostSession.Listener() {
            @Override
            public void onTextChanged() {
                view.onOutput();
            }

            @Override
            public void onContext(JSONObject context) {
                holder[0].setContext(context);
                refreshTitles();
                publish(holder[0]);
            }

            @Override
            public void onStatus(String message, boolean connected) {
                holder[0].setStatus(message);
                if (connected) view.logState();
                publish(holder[0]);
            }
        });
        view.attach(session);
        TermTab tab = new TermTab(view, session);
        holder[0] = tab;

        tabs.add(tab);
        content.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        select(tabs.size() - 1);
        refreshTitles();

        // The agent starts every shell in its own --cwd. A tab opened from another
        // one should land beside it, and typing the cd is how a person would do it:
        // it is visible, it is in the history, and it needs no new protocol.
        if (cwd != null && !cwd.isEmpty()) {
            view.postDelayed(() -> view.sendLine("cd " + shellQuote(cwd)), 700);
        }
    }

    private static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        TermTab tab = tabs.remove(index);
        tab.session.close();
        content.removeView(tab.view);
        if (tabs.isEmpty()) {
            finish();
            return;
        }
        select(Math.min(index, tabs.size() - 1));
        refreshTitles();
    }

    private void select(int index) {
        if (index < 0 || index >= tabs.size()) return;
        active = index;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).view.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        TermTab tab = tabs.get(index);
        tab.view.requestFocus();
        refreshTitles();
        publish(tab);
    }

    private TermTab activeTab() {
        return active >= 0 && active < tabs.size() ? tabs.get(active) : null;
    }

    /** Only the active tab's context reaches the bar. */
    private void publish(TermTab tab) {
        if (tab == activeTab() && Terminals.active() == this) Terminals.notifyWatchers();
    }

    private void refreshTitles() {
        tabStrip.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            TermTab tab = tabs.get(i);
            View view = buttons.tab(tab.title(i), i == active, CONTENT_BG, STRIP_BG,
                    v -> select(index), v -> closeTab(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(buttons.dp(2));
            tabStrip.addView(view, params);
        }
    }

    // ------------------------------------------------------------ Terminals.Target

    @Override
    public void send(String text, boolean enter) {
        TermTab tab = activeTab();
        if (tab == null) return;
        // Typing without running is the default for anything you would want to add
        // words to — a skill invocation, a command with a path still to come.
        if (enter) tab.view.sendLine(text);
        else tab.view.send(text);
    }

    @Override
    public void showKeyboard() {
        TermTab tab = activeTab();
        if (tab != null) tab.view.showKeyboard();
    }

    @Override
    public void fontStep(int delta) {
        fontSize = Math.max(10, Math.min(64, fontSize + delta));
        for (TermTab tab : tabs) tab.view.setFontSize(fontSize);
        TermTab tab = activeTab();
        if (tab != null) {
            tab.setStatus(fontSize + " px  ·  " + tab.view.getColumns() + "×" + tab.view.getRows());
            publish(tab);
        }
    }

    @Override
    public void scroll(int rows) {
        TermTab tab = activeTab();
        if (tab != null) tab.view.scrollRows(rows);
    }

    @Override
    public JSONObject context() {
        TermTab tab = activeTab();
        return tab == null ? null : tab.context();
    }

    @Override
    public String host() {
        return host;
    }

    @Override
    public String serverName() {
        return serverName;
    }

    @Override
    public String status() {
        TermTab tab = activeTab();
        return tab == null ? "no tab" : tab.status();
    }
}
