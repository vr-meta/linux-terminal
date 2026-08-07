package dev.butschster.linuxterminal;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;

import org.json.JSONObject;

import java.util.Locale;

/**
 * The bar, in a window of its own.
 *
 * <p>Docked under the terminal it was whatever height the terminal left it. In its own
 * window the shell places and resizes it like anything else, and since the buttons wrap,
 * making it wider or taller simply shows more of them — which is the point.
 *
 * <p>It drives whichever terminal window was in front last; see {@link Terminals}.
 */
public class BarActivity extends Activity implements ContextBar.Host, Terminals.Watcher {

    private ContextBar bar;
    private Dictation dictation;
    private String dictationHost;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        bar = new ContextBar(this, this);
        setContentView(bar);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Terminals.watch(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Terminals.unwatch(this);
    }

    // ------------------------------------------------------------ Terminals.Watcher

    private String lastStatus;

    @Override
    public void onTerminalChanged(Terminals.Target target) {
        if (target == null) {
            bar.clearContext();
            lastStatus = null;
            return;
        }
        JSONObject context = target.context();
        if (context != null) bar.setContext(context);

        // A status message — a new grid size, a lost connection — replaces the path
        // until the next context arrives and puts it back. Only when it is new,
        // otherwise every context update would wipe the path it just set.
        String status = target.status();
        if (context == null || (status != null && !status.equals(lastStatus))) {
            bar.setStatus(status);
        }
        lastStatus = status;
    }

    // ------------------------------------------------------------ ContextBar.Host

    private Terminals.Target target() {
        return Terminals.active();
    }

    @Override
    public void onAction(String send, boolean enter) {
        Terminals.Target target = target();
        if (target != null) target.send(send, enter);
    }

    @Override
    public void onKeyboard() {
        Terminals.Target target = target();
        if (target != null) target.showKeyboard();
    }

    @Override
    public void onFontStep(int delta) {
        Terminals.Target target = target();
        if (target != null) target.fontStep(delta);
    }

    @Override
    public void onScroll(int rows) {
        Terminals.Target target = target();
        if (target != null) target.scroll(rows);
    }

    /**
     * Dictation talks to the same machine the active terminal does. Reading a fixed
     * address from a file was fine when there was one host; with a connection manager
     * the answer changes with the window in front.
     */
    private Dictation dictationFor(Terminals.Target target) {
        String host = target.host();
        if (host == null) return null;
        if (dictation == null || !host.equals(dictationHost)) {
            dictation = new Dictation(host, new DictationListener());
            dictationHost = host;
        }
        return dictation;
    }

    @Override
    public void onDictate() {
        Terminals.Target target = target();
        if (target == null) return;
        dictation = dictationFor(target);
        if (dictation == null || dictation.isRecording()) return;
        bar.showRecording();
        dictation.start();
    }

    @Override
    public void onStopDictating() {
        if (dictation != null) dictation.stop();
    }

    private class DictationListener implements Dictation.Listener {
        @Override
        public void onLevel(float level) {
            bar.pushLevel(level);
        }

        @Override
        public void onElapsed(long millis) {
            bar.setTimer(String.format(Locale.US, "%d:%02d",
                    millis / 60000, (millis / 1000) % 60));
        }

        @Override
        public void onRecognising() {
            bar.showRecognising();
        }

        @Override
        public void onResult(String text, String problem) {
            bar.hideRecording();
            Terminals.Target target = target();
            if (text != null && target != null) {
                // Straight into the pty, not submitted: a misheard word is fixed on
                // the line it landed on, and pressing Enter stays a decision.
                target.send(text, false);
            } else {
                bar.setStatus(problem == null ? "nothing recognised" : problem);
            }
        }
    }
}
