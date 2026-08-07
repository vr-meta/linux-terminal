package dev.butschster.linuxterminal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The connection manager: where you pick a machine to work on.
 *
 * <p>This is the app's front door, and it exists because the terminal is a client-server
 * product rather than a companion to one particular desktop. Servers on this network
 * announce themselves and appear here without anyone typing anything; a server elsewhere
 * is typed in once and remembered.
 */
public class ServersActivity extends Activity {

    static final int DEFAULT_PORT = 9103;

    private static final int BG = Color.rgb(16, 17, 21);
    private static final int CARD = Color.rgb(30, 32, 39);
    private static final int FOUND = Color.rgb(34, 62, 48);

    private Buttons buttons;
    private LinearLayout list;
    private TextView status;
    private final Discovery discovery = new Discovery();
    private List<Server> found = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Typeface icons = Typeface.createFromAsset(getAssets(), "MaterialIcons-Regular.ttf");
        buttons = new Buttons(this, icons);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(buttons.dp(24), buttons.dp(20), buttons.dp(24), buttons.dp(20));

        TextView title = text("linux terminal", Buttons.TEXT, 24);
        title.setTypeface(Typeface.MONOSPACE);
        root.addView(title);

        status = text("looking for servers…", Buttons.MUTED, 15);
        status.setPadding(0, buttons.dp(4), 0, buttons.dp(14));
        root.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(addRow());
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        render();
        status.setText("looking for servers…");
        discovery.sweep(servers -> {
            found = servers;
            status.setText(servers.isEmpty()
                    ? "nothing answered on this network — add a server by address"
                    : servers.size() + " found on this network");
            render();
        });
    }

    // -------------------------------------------------------------------- list

    private void render() {
        list.removeAllViews();

        List<Server> saved = Server.saved(this);
        for (Server server : found) {
            markSaved(server, saved);
            list.addView(card(server));
        }
        for (Server server : saved) {
            if (!isIn(found, server)) list.addView(card(server));
        }
        if (found.isEmpty() && saved.isEmpty()) {
            TextView empty = text("Run the server on your Linux machine:\n\n"
                    + "    linux-terminal-server\n\n"
                    + "and it will appear here.", Buttons.MUTED, 16);
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setPadding(0, buttons.dp(20), 0, 0);
            list.addView(empty);
        }
    }

    private static void markSaved(Server server, List<Server> saved) {
        for (Server known : saved) {
            if (known.host.equals(server.host) && known.port == server.port) {
                server.name = known.name;
                return;
            }
        }
    }

    private static boolean isIn(List<Server> servers, Server candidate) {
        for (Server server : servers) {
            if (server.host.equals(candidate.host) && server.port == candidate.port) return true;
        }
        return false;
    }

    private View card(Server server) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(buttons.dp(18), buttons.dp(14), buttons.dp(14), buttons.dp(14));

        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(buttons.dp(10));
        card.setBackground(background);
        card.setClickable(true);
        card.setOnClickListener(v -> connect(server));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        card.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(heading);

        TextView name = text(server.name, Buttons.TEXT, 19);
        name.setTypeface(Typeface.MONOSPACE);
        heading.addView(name);

        if (server.discovered) {
            TextView badge = text("on this network", Buttons.TEXT, 13);
            badge.setPadding(buttons.dp(8), buttons.dp(2), buttons.dp(8), buttons.dp(3));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(FOUND);
            pill.setCornerRadius(buttons.dp(10));
            badge.setBackground(pill);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(buttons.dp(12));
            heading.addView(badge, params);
        }

        TextView detail = text(server.detail(), Buttons.MUTED, 15);
        detail.setTypeface(Typeface.MONOSPACE);
        labels.addView(detail);

        card.addView(buttons.key("connect", Buttons.COMMAND, "open a terminal here",
                v -> connect(server)));

        if (!server.discovered) {
            card.addView(buttons.key("×", Buttons.WARN, "forget this server", v -> {
                Server.forget(this, server);
                render();
            }));
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = buttons.dp(10);
        card.setLayoutParams(params);
        return card;
    }

    // ---------------------------------------------------------------- adding

    private View addRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, buttons.dp(14), 0, 0);

        EditText host = field("address or hostname", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        row.addView(host, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText port = field("port", InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(DEFAULT_PORT));
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(
                buttons.dp(110), ViewGroup.LayoutParams.WRAP_CONTENT);
        portParams.setMarginStart(buttons.dp(10));
        row.addView(port, portParams);

        row.addView(buttons.key("add", Buttons.COMMAND, "remember this server", v -> {
            String address = host.getText().toString().trim();
            if (address.isEmpty()) return;
            int number = DEFAULT_PORT;
            try {
                number = Integer.parseInt(port.getText().toString().trim());
            } catch (NumberFormatException ignored) {
            }
            Server server = new Server(address, address, number);
            Server.remember(this, server);
            host.setText("");
            render();
        }));

        return row;
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setTextColor(Buttons.TEXT);
        field.setHintTextColor(Buttons.MUTED);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        field.setTypeface(Typeface.MONOSPACE);
        field.setPadding(buttons.dp(14), buttons.dp(12), buttons.dp(14), buttons.dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(buttons.dp(8));
        field.setBackground(background);
        return field;
    }

    // -------------------------------------------------------------- connecting

    private void connect(Server server) {
        Server.remember(this, server);
        Intent intent = new Intent(this, TermActivity.class);
        intent.putExtra(TermActivity.EXTRA_HOST, server.host);
        intent.putExtra(TermActivity.EXTRA_PORT, server.port);
        intent.putExtra(TermActivity.EXTRA_NAME, server.name);
        // Its own task, so the terminal is a window beside this one rather than
        // replacing it: picking a second server opens a second terminal.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        startActivity(intent);
    }

    private TextView text(String value, int colour, int sizeDp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(colour);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        return view;
    }

    static Context appContext(Activity activity) {
        return activity.getApplicationContext();
    }
}
