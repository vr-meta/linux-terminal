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
 *
 * <p>Every card answers one question: would pressing connect, right now, actually open a
 * shell? A server that just answered the discovery probe will. A remembered server that
 * did not answer this sweep might still — it may be a machine reached only by address,
 * which discovery was never going to hear from — but the screen has no business implying
 * certainty it does not have, so it says only what it knows: seen just now, or not.
 */
public class ServersActivity extends Activity {

    static final int DEFAULT_PORT = 9103;

    private static final int BG = Color.rgb(16, 17, 21);
    // Two card fills, not one, because "live" and "unconfirmed" are different facts
    // and a pill alone is too small a detail to carry that through the lenses. A card
    // that just answered the probe sits at full contrast; one that did not recedes
    // towards the background, so the eye sorts the list before it has read a word.
    private static final int CARD = Color.rgb(30, 32, 39);
    private static final int CARD_QUIET = Color.rgb(22, 23, 28);
    private static final int FOUND = Color.rgb(34, 62, 48);
    private static final int QUIET = Color.rgb(46, 48, 57);

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
        // Rule bleeds out to whichever padding its direct parent carries — see Rule's
        // own note on this. It sits directly in root for exactly that reason.
        root.setClipToPadding(false);
        root.setClipChildren(false);

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

        // The line that makes "pick a machine" and "add one by hand" read as two
        // blocks rather than one list that happens to grow a text field at the end.
        root.addView(new Rule(this, buttons));
        root.addView(addBlock());
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

    /**
     * Dead space bigger than a bare {@link Buttons#gap()} — the unit for places where a
     * miss must not slide onto a neighbour, rather than just look tidy. Forget sitting
     * away from connect, and the add button sitting away from the fields above it, both
     * measure from here.
     */
    private int air() {
        return buttons.gap() * 2;
    }

    private View card(Server server) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(buttons.dp(18), buttons.dp(16), buttons.dp(18), buttons.dp(16));

        GradientDrawable background = new GradientDrawable();
        background.setColor(server.discovered ? CARD : CARD_QUIET);
        background.setCornerRadius(buttons.dp(10));
        card.setBackground(background);
        card.setClickable(true);
        card.setOnClickListener(v -> connect(server));

        // ---------------------------------------------------- identity row

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        // Full width, deliberately: labels claims the middle with weight 1, which
        // only has anything to claim once the row is as wide as the card. Without
        // this the row shrink-wraps its children and forget drifts back in next
        // to the name instead of sitting at the card's own edge.
        card.addView(identity, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Which Linux, before the name of the machine. On a list of several boxes
        // the distribution is the fastest way to tell them apart, and it is the
        // one thing here that reads without being read.
        Distro distro = Distro.of(server.os);
        TextView mark = text(distro.letter, Buttons.TEXT, 20);
        mark.setTypeface(Typeface.MONOSPACE);
        mark.setGravity(Gravity.CENTER);
        mark.setWidth(buttons.dp(40));
        mark.setHeight(buttons.dp(40));
        GradientDrawable badgeShape = new GradientDrawable();
        badgeShape.setColor(distro.colour);
        badgeShape.setCornerRadius(buttons.dp(10));
        mark.setBackground(badgeShape);
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        markParams.setMarginEnd(buttons.dp(14));
        identity.addView(mark, markParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        identity.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(heading);

        TextView name = text(server.name, Buttons.TEXT, 19);
        name.setTypeface(Typeface.MONOSPACE);
        heading.addView(name);

        // Every card carries this pill, not just the discovered ones — "found just
        // now" and "not heard from in this sweep" are both facts worth a glance,
        // and only showing the good news half the time is what made a stale saved
        // entry look exactly like a live one.
        heading.addView(statusPill(server), pillParams());

        // Who, and where you would land, straight from this sweep. A saved entry
        // that did not answer carries neither — that absence is itself the honest
        // report, not a blank left by an oversight.
        String line = server.discovered ? liveLine(server) : quietLine(server);
        TextView detail = text(line, Buttons.MUTED, 15);
        detail.setTypeface(Typeface.MONOSPACE);
        labels.addView(detail);

        if (server.discovered && !server.cwd.isEmpty()) {
            TextView cwd = text(server.cwd, Buttons.MUTED, 15);
            cwd.setTypeface(Typeface.MONOSPACE);
            cwd.setPadding(0, buttons.dp(2), 0, 0);
            labels.addView(cwd);
        }

        // Forget lives up here, at the far end of the identity row — as far from
        // the connect button below as the card allows, so a ray that overshoots
        // connect lands in the card's own dead space rather than on the one
        // control here that cannot be undone.
        if (!server.discovered) {
            LinearLayout.LayoutParams forgetParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            forgetParams.setMarginStart(air());
            identity.addView(buttons.key("forget", Buttons.WARN, "forget this server", v -> {
                Server.forget(this, server);
                render();
            }), forgetParams);
        }

        // connect sits at the end of the identity row rather than spanning the card.
        // A list of machines is read down its left edge, and a full-width button on
        // every one of them turns that list into a staircase; the whole card is
        // clickable anyway, so this is a target for the deliberate press, not the
        // only way in. It goes after forget, which puts the destructive control
        // between the name and a button nobody minds hitting by accident.
        TextView connect = buttons.key("connect", Buttons.COMMAND, "open a terminal here",
                v -> connect(server));
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        connectParams.setMarginStart(air());
        identity.addView(connect, connectParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = air();
        card.setLayoutParams(params);
        return card;
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(buttons.gap());
        return params;
    }

    private TextView statusPill(Server server) {
        TextView badge = text(server.discovered ? "on this network" : "not seen right now", Buttons.TEXT, 13);
        badge.setPadding(buttons.dp(8), buttons.dp(2), buttons.dp(8), buttons.dp(3));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(server.discovered ? FOUND : QUIET);
        pill.setCornerRadius(buttons.dp(10));
        badge.setBackground(pill);
        return badge;
    }

    /** who@host, the port only when it is not the one everyone is already on. */
    private String liveLine(Server server) {
        StringBuilder line = new StringBuilder();
        line.append(server.user.isEmpty() ? server.host : server.user + "@" + server.host);
        if (server.port != DEFAULT_PORT) line.append(':').append(server.port);
        if (!server.os.isEmpty()) line.append("   ").append(server.os);
        return line.toString();
    }

    /** A remembered address is all a stale entry has left to show. */
    private String quietLine(Server server) {
        StringBuilder line = new StringBuilder(server.host);
        if (server.port != DEFAULT_PORT) line.append(':').append(server.port);
        return line.toString();
    }

    // ---------------------------------------------------------------- adding

    private View addBlock() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, buttons.inset(), 0, 0);

        TextView heading = text("ADD A SERVER BY ADDRESS", Buttons.MUTED, 13);
        heading.setTypeface(Typeface.MONOSPACE);
        heading.setLetterSpacing(0.12f);
        block.addView(heading);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.HORIZONTAL);
        fields.setGravity(Gravity.CENTER_VERTICAL);
        fields.setPadding(0, buttons.gap(), 0, 0);
        block.addView(fields);

        EditText host = field("address or hostname", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fields.addView(host, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText port = field("port", InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(DEFAULT_PORT));
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(
                buttons.dp(110), ViewGroup.LayoutParams.WRAP_CONTENT);
        portParams.setMarginStart(buttons.gap());
        fields.addView(port, portParams);

        // Its own row, below the fields rather than squeezed beside the port box —
        // the button that used to touch the port field now has a full row and the
        // same width the fields above it span, so it reads as the block's one
        // action rather than a third field that happened to be a button.
        TextView add = buttons.key("add server", Buttons.COMMAND, "remember this server", v -> {
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
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.topMargin = air();
        block.addView(add, addParams);

        return block;
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
