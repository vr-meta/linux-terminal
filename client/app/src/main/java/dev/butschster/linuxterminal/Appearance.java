package dev.butschster.linuxterminal;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which panel the bar is drawn as, and remembering the answer.
 *
 * <p>The five skins were built to be argued with — a decision is only a decision
 * once it has been compared against something — and that comparison used to live
 * in a debug gallery that shipped in no release. The gallery is gone, and taking
 * it away removed the only way to see the alternatives at all. This is the same
 * choice offered where it belongs: inside the product, to the person wearing it,
 * and not to a build variant.
 *
 * <p>The choice is stored rather than held, because a panel that forgets is worse
 * than a panel with one look. The bar is recreated whenever the shell restarts it
 * — resized, reopened, or after the headset sleeps — and a look that lasted until
 * then would read as the app losing its settings.
 *
 * <p>Whether the alternatives should exist at all is a separate question from
 * whether they should be reachable. CONSOLE remains the default and the shipped
 * arrangement; see {@link Buttons#skin()}.
 */
final class Appearance {

    private static final String PREFS = "appearance";
    private static final String KEY = "skin";

    /** One entry in the menu: what it is called and how to build it. */
    static final class Choice {
        final String id;
        final String name;
        final String note;

        Choice(String id, String name, String note) {
            this.id = id;
            this.name = name;
            this.note = note;
        }

        Skin skin() {
            switch (id) {
                case "capped":
                    return Skin.capped();
                case "outline":
                    return Skin.outline();
                case "machined":
                    return Skin.machined();
                case "tactile":
                    return Skin.tactile();
                default:
                    return Skin.console();
            }
        }
    }

    /**
     * In the order they were built, which is also the order they get further from
     * a flat interface and closer to a panel.
     */
    static final Choice[] CHOICES = {
            new Choice("console", "Console", "read left, press middle, rail right"),
            new Choice("tactile", "Tactile", "moulded caps on a dark deck"),
            new Choice("machined", "Machined", "milled plate, deep screens"),
            new Choice("outline", "Outline", "no material at all"),
            new Choice("capped", "Capped", "the first shallow cap"),
    };

    private Appearance() {
    }

    static String chosen(Context context) {
        return prefs(context).getString(KEY, CHOICES[0].id);
    }

    static Choice current(Context context) {
        String id = chosen(context);
        for (Choice choice : CHOICES) {
            if (choice.id.equals(id)) return choice;
        }
        return CHOICES[0];
    }

    /**
     * Put the stored choice into effect. Called before anything is drawn, from
     * every entry point that builds views, because {@link Buttons} reads the skin
     * at build time and there is no going back over a view that already exists.
     */
    static void restore(Context context) {
        Buttons.applySkin(current(context).skin());
    }

    /** Remember a choice and apply it. The caller redraws. */
    static void choose(Context context, Choice choice) {
        prefs(context).edit().putString(KEY, choice.id).apply();
        Buttons.applySkin(choice.skin());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
