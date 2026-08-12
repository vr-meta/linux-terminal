package dev.butschster.linuxterminal;

import android.content.Context;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * The one monospaced face the whole app is set in.
 *
 * <p>It used to be {@link Typeface#MONOSPACE}, which sounds neutral and is not: on
 * Horizon OS that alias resolves to <b>DroidSansMono.ttf</b> — checked on the
 * headset's own {@code /system/etc/fonts.xml}, not assumed — a face drawn for
 * Android 2 and shipped since out of compatibility. It is narrow, low in
 * x-height, and it does not look like a terminal because it was never meant to be
 * one.
 *
 * <p>That matters more here than it would on a phone. `docs/readability.md` puts
 * the comfort threshold at about 0.39° of angular size per glyph and the failure
 * point near 0.31°, and those numbers were measured <b>with DejaVu Sans Mono</b>.
 * Every conclusion in that file therefore describes this face and not the one the
 * client was actually drawing with, so bundling it is not a preference — it makes
 * the measurements apply to what is on screen.
 *
 * <p>Bundled rather than asked of the system: a system alias can resolve to
 * anything on any device, and a bar whose columns depend on which build of Horizon
 * OS the headset is running is not a bar anybody can measure.
 *
 * <p>An asset, not a dependency. The "no new dependencies" rule is about code that
 * has to keep working — a font file has no version, no transitive graph and no
 * behaviour.
 */
public final class Fonts {

    /**
     * Measured with, and therefore the default. Bitstream Vera / DejaVu licence,
     * which permits redistribution; the notice belongs in NOTICE.md.
     */
    public static final String DEJAVU = "DejaVuSansMono.ttf";

    /**
     * Drawn for reading code for hours: about 10% more x-height at the same em, and
     * 0/O and 1/l/I deliberately pulled apart. SIL Open Font Licence.
     *
     * <p>The higher x-height is the interesting part for this project — angular
     * size is what readability follows, and x-height is what angular size is
     * actually spent on in a lowercase path like {@code ~/repos/home}.
     */
    public static final String JETBRAINS = "JetBrainsMono-Regular.ttf";

    /** What the system hands back for "monospace". Kept only for comparison. */
    public static final String SYSTEM = "";

    private static final Map<String, Typeface> loaded = new HashMap<>();

    private static String chosen = DEJAVU;

    private Fonts() {}

    public static void choose(String asset) {
        chosen = asset;
    }

    public static String chosen() {
        return chosen;
    }

    /**
     * The face to draw with. Cached: {@code createFromAsset} parses the file every
     * call, and this bar rebuilds its left half whenever the foreground process
     * changes — several times a second while a program is starting.
     */
    public static synchronized Typeface mono(Context context) {
        if (chosen == null || chosen.isEmpty()) return Typeface.MONOSPACE;
        Typeface face = loaded.get(chosen);
        if (face == null) {
            face = Typeface.createFromAsset(context.getAssets(), chosen);
            loaded.put(chosen, face);
        }
        return face;
    }
}
