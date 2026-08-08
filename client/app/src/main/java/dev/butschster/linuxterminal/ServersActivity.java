package dev.butschster.linuxterminal;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
    // A lighter foreground version of Buttons' own WARN fill, not a colour of its
    // own invention — a rejected code and a destructive button are both "look at
    // this before you continue", and the palette says so the same way twice.
    private static final int WARN_TEXT = Color.rgb(224, 140, 96);

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

        // The app's own version sits beside the name, not in an about box nobody
        // opens. It is half of the answer whenever the two halves disagree — the
        // other half is on each server's card — and a sideloaded app has no store
        // page to look it up on.
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.BOTTOM);

        TextView title = text("linux terminal", Buttons.TEXT, 24);
        title.setTypeface(Typeface.MONOSPACE);
        heading.addView(title);

        TextView release = text(appVersion(), Buttons.MUTED, 14);
        release.setPadding(buttons.dp(10), 0, 0, buttons.dp(3));
        heading.addView(release);

        root.addView(heading);

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
            // A broadcast answer no longer carries who, what or where — the server
            // stopped telling strangers. What a card shows about a machine is what
            // that machine said over a session after it was paired, remembered
            // here; an unpaired one stays a name and an address, which is all we
            // are entitled to know about it.
            remember(server, saved);
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
        // Offered for anything this app remembers, whether or not it is answering
        // right now. It used to appear only on machines that had gone quiet, which
        // meant a paired machine sitting on the network could never be unpaired —
        // the one state where somebody is most likely to want to.
        Server stored = Server.find(this, server.host, server.port);
        if (stored != null) {
            boolean wasPaired = stored.paired();
            LinearLayout.LayoutParams forgetParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            forgetParams.setMarginStart(air());
            identity.addView(buttons.key(wasPaired ? "unpair" : "forget", Buttons.WARN,
                    wasPaired
                            ? "forget this machine and the secret agreed with it"
                            : "forget this server",
                    v -> {
                        Server.forget(this, server);
                        // The card is rebuilt from what is left, so a machine still
                        // on the network comes back offering to pair again rather
                        // than vanishing and confusing everyone.
                        server.token = "";
                        server.fingerprint = "";
                        render();
                    }), forgetParams);
        }

        // connect sits at the end of the identity row rather than spanning the card.
        // A list of machines is read down its left edge, and a full-width button on
        // every one of them turns that list into a staircase; the whole card is
        // clickable anyway, so this is a target for the deliberate press, not the
        // only way in. It goes after forget, which puts the destructive control
        // between the name and a button nobody minds hitting by accident.
        // A machine that has never been paired offers pairing instead of a shell.
        // Connecting first and asking later would mean the one connection that
        // establishes trust is the one nobody checked.
        boolean known = Server.find(this, server.host, server.port) != null
                && Server.find(this, server.host, server.port).paired();
        // A machine with a window already open offers nothing to press. Pressing
        // connect again used to hand that window a new intent, which brought it
        // forward — and the shell placed it wherever it wanted, so a terminal
        // being worked in jumped across the room. Saying "open" is the difference
        // between a button that does nothing and one that looks broken.
        TextView connect;
        if (!known) {
            connect = buttons.key("pair", Buttons.DESTINATION,
                    "check this machine and take its token", v -> pair(server));
        } else if (Terminals.isOpen(server.host, server.port)) {
            connect = buttons.key("open", Buttons.KEY,
                    "this machine already has a terminal window", v -> {
                    });
            connect.setAlpha(0.55f);
        } else {
            connect = buttons.key("connect", Buttons.COMMAND, "open a terminal here",
                    v -> connect(server));
        }
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
        // Last, because it is the thing you look for only once something is odd —
        // and then it is the first thing worth knowing.
        if (!server.version.isEmpty()) line.append("   ").append(server.build());
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

    /**
     * Pairing: read the certificate the machine is presenting, show its
     * fingerprint, and take the token.
     *
     * <p>The fingerprint is shown rather than silently accepted because trusting
     * the first answer is exactly the moment an attacker wants. It is the same
     * string the server prints at startup and shows in its console; comparing
     * them is a two-second act that turns a guess into a fact.
     */
    private void pair(Server server) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // The stock dialog window paints its own rectangle behind whatever view is
        // set; left alone it shows through the card's own rounded corners as a
        // square white ghost behind them. Transparent, the card is the whole window.
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = buttons.inset() * 2;
        root.setPadding(pad, pad, pad, pad);
        root.setClipToPadding(false);
        root.setClipChildren(false);
        GradientDrawable card = new GradientDrawable();
        card.setColor(CARD);
        card.setCornerRadius(buttons.dp(16));
        root.setBackground(card);

        TextView title = text("pair with " + server.name, Buttons.TEXT, 19);
        title.setTypeface(Typeface.MONOSPACE);
        root.addView(title);

        TextView address = text(server.address(), Buttons.MUTED, 14);
        address.setTypeface(Typeface.MONOSPACE);
        address.setPadding(0, buttons.dp(2), 0, 0);
        root.addView(address);

        root.addView(new Rule(this, buttons));
        root.addView(sectionHeading("SERVER FINGERPRINT"));

        TextView fingerprint = text("reading the certificate…", Buttons.MUTED, 13);
        fingerprint.setTypeface(Typeface.MONOSPACE);
        GradientDrawable fingerprintBox = new GradientDrawable();
        fingerprintBox.setColor(CARD_QUIET);
        fingerprintBox.setCornerRadius(buttons.dp(8));
        fingerprint.setBackground(fingerprintBox);
        fingerprint.setPadding(buttons.dp(12), buttons.dp(10), buttons.dp(12), buttons.dp(10));
        root.addView(fingerprint);

        root.addView(new Rule(this, buttons));
        root.addView(sectionHeading("SIX-DIGIT CODE FROM THE CONSOLE"));

        TextView outcome = text("", Buttons.MUTED, 14);
        outcome.setTypeface(Typeface.MONOSPACE);
        outcome.setGravity(Gravity.CENTER);

        // Held rather than read straight off the two background threads below: the
        // pairing attempt can be triggered by either one — the sixth digit landing,
        // or the fingerprint arriving late — and whichever fires second needs to see
        // what the other already found.
        String[] fingerprintHolder = {""};
        boolean[] fingerprintSettled = {false};
        PairCodeInput[] codeInputRef = new PairCodeInput[1];

        Runnable attempt = () -> {
            String typed = codeInputRef[0].code();
            if (typed.length() != 6) return;
            if (fingerprintHolder[0].isEmpty()) {
                outcome.setTextColor(fingerprintSettled[0] ? WARN_TEXT : Buttons.MUTED);
                outcome.setText(fingerprintSettled[0]
                        ? "no certificate to check against — is the server running?"
                        : "waiting for the certificate…");
                return;
            }
            outcome.setTextColor(Buttons.MUTED);
            outcome.setText("pairing…");
            new Thread(() -> {
                String token = "";
                String problem = "";
                try {
                    token = Pairing.exchange(server.host, server.port, fingerprintHolder[0], typed);
                } catch (Exception failure) {
                    problem = failure.getMessage() == null ? "could not pair" : failure.getMessage();
                }
                String gotToken = token;
                String gotProblem = problem;
                runOnUiThread(() -> {
                    if (gotToken.isEmpty()) {
                        outcome.setTextColor(WARN_TEXT);
                        outcome.setText(gotProblem);
                        // Open and empty, not open and still full of the six digits
                        // that were just refused — retyping them would not be a
                        // retry, it would be resubmitting the same wrong answer.
                        codeInputRef[0].clear();
                        codeInputRef[0].requestCodeFocus();
                        return;
                    }
                    server.token = gotToken;
                    server.fingerprint = fingerprintHolder[0];
                    Server.remember(this, server);
                    dialog.dismiss();
                    render();
                });
            }, "pair-exchange").start();
        };

        PairCodeInput codeInput = new PairCodeInput(this, buttons, (typed, complete) -> {
            outcome.setTextColor(Buttons.MUTED);
            outcome.setText("");
            if (complete) attempt.run();
        });
        codeInputRef[0] = codeInput;
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, buttons.dp(PairCodeInput.BOX_HEIGHT_DP));
        codeParams.topMargin = buttons.gap();
        root.addView(codeInput, codeParams);

        LinearLayout.LayoutParams outcomeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        outcomeParams.topMargin = buttons.gap();
        root.addView(outcome, outcomeParams);

        root.addView(new Rule(this, buttons));

        TextView cancel = buttons.key("cancel", Buttons.KEY, "close without pairing",
                v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelParams.topMargin = buttons.inset();
        root.addView(cancel, cancelParams);

        dialog.setContentView(root);

        // Read off the main thread: this opens a socket and finishes a handshake,
        // and doing either on the UI thread is how an app stops drawing.
        new Thread(() -> {
            String read;
            try {
                read = Pinned.peekFingerprint(server.host, server.port, 4000);
            } catch (Exception failure) {
                read = "";
            }
            String shown = read;
            fingerprintHolder[0] = shown;
            runOnUiThread(() -> {
                fingerprintSettled[0] = true;
                fingerprint.setText(shown.isEmpty()
                        ? "no answer — is the server running?"
                        : shown);
                // In case all six digits were already typed while this was in
                // flight — the person reading them off the console can be faster
                // than a socket handshake.
                attempt.run();
            });
        }, "pair-peek").start();

        dialog.show();
        codeInput.requestCodeFocus();
    }

    /** The small caps label above a block, in the one style the screen uses for it. */
    private TextView sectionHeading(String label) {
        TextView heading = text(label, Buttons.MUTED, 13);
        heading.setTypeface(Typeface.MONOSPACE);
        heading.setLetterSpacing(0.12f);
        heading.setPadding(0, buttons.inset(), 0, buttons.gap());
        return heading;
    }

    /** Fills a freshly discovered entry in from what pairing and sessions learned. */
    private void remember(Server server, List<Server> saved) {
        for (Server known : saved) {
            if (!known.host.equals(server.host) || known.port != server.port) continue;
            server.user = known.user;
            server.os = known.os;
            server.cwd = known.cwd;
            server.token = known.token;
            server.fingerprint = known.fingerprint;
            return;
        }
    }

    private void connect(Server server) {
        Server.remember(this, server);
        Intent intent = new Intent(this, TermActivity.class);
        intent.putExtra(TermActivity.EXTRA_HOST, server.host);
        intent.putExtra(TermActivity.EXTRA_PORT, server.port);
        intent.putExtra(TermActivity.EXTRA_NAME, server.name);
        // Keyed by the machine, so this is what decides whether a window is reused
        // or a new one opens: the same server comes back to the window it already
        // has, a different one gets its own. Without the data URI every terminal
        // would look like the same document and they would all share one window.
        intent.setData(android.net.Uri.parse("linux-terminal://" + server.host + ":" + server.port));

        // NEW_DOCUMENT without MULTIPLE_TASK: a window beside this one, but only
        // ever one per machine. MULTIPLE_TASK is what used to make every press of
        // connect open another, which on a three-window system is a way to run out
        // of screen by accident.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        startActivity(intent);
    }

    /**
     * What this app is, asked of the system rather than of a generated constant.
     *
     * <p>`BuildConfig` would need the build feature turned on, and the package
     * manager already knows — it is reading the same `versionName` that decided
     * whether this install was allowed to replace the last one.
     */
    private String appVersion() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception unavailable) {
            return "";
        }
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
