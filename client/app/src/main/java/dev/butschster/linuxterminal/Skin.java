package dev.butschster.linuxterminal;

import android.graphics.Color;

/**
 * What the bar is made of: colour, corner, and whether a key is a moulded cap or
 * a lit outline.
 *
 * <p>This exists so the two answers can be held side by side and compared while
 * wearing the headset, which is the only place the question can be settled. Every
 * number the bar draws with used to be a constant in {@link Buttons}; they are
 * here instead, and {@link Buttons#applySkin} copies the chosen set into place.
 *
 * <p><b>CAPPED</b> is what the bar has always been: a solid moulded key with a
 * darker lip along its bottom edge and its legend engraved into the face. The
 * volume is physical — you read which way it would move.
 *
 * <p><b>OUTLINE</b> is the other tradition: a near-black deck, keys drawn as thin
 * lit rectangles, and one accent colour carrying every "this one does something".
 * It reads as an instrument rather than as a keyboard.
 *
 * <p>Neither is obviously right for this display. Through pancake lenses the
 * contrast that survives is luminance contrast, and the two skins spend it
 * differently: CAPPED puts it in the fill and keeps the edges quiet, OUTLINE puts
 * nearly all of it in a 1dp edge and leaves the fill almost as dark as the deck.
 * A 1dp edge at arm's length is roughly 0.02° across — well under the 0.31° where
 * `docs/readability.md` measured this project's own text becoming unreadable. That
 * argument says the outline should fail; it is an argument, and the person wearing
 * it outranks one, so both are built and looked at.
 */
public final class Skin {

    public enum Kind { CAPPED, OUTLINE, MACHINED, TACTILE, CONSOLE }

    public final Kind kind;

    /** The deck the dynamic half sits on, and the slightly different one for the fixed half. */
    public final int bg;
    public final int bgPanel;

    /** Every line in the bar: the rules between groups and the dividers between panels. */
    public final int rule;

    public final int text;
    public final int muted;

    /** Group headings. On OUTLINE they carry the accent; on CAPPED they stay muted. */
    public final int heading;

    /** The one colour that says "lit" — borders, indicators, the active state. */
    public final int accent;

    /** Fill per role, indexed by the style constants in {@link Buttons}. */
    public final int[] fill;

    /** Edge per role. Ignored when {@link #border} is zero. */
    public final int[] edge;

    public final int cornerDp;

    /** Edge width in dp. Zero means the key has no drawn edge at all. */
    public final int border;

    /** A darker copy showing below the cap. The moulded look; false on OUTLINE. */
    public final boolean lip;

    /** Legend cut into the face rather than printed on it. */
    public final boolean engrave;

    /**
     * Rings of falling alpha just inside the edge, so a lit key looks lit rather
     * than merely outlined.
     *
     * <p>Drawn as three concentric strokes rather than a real blur: a blur needs
     * {@link android.graphics.BlurMaskFilter}, which forces the view onto a
     * software layer, and this bar redraws its left half whenever the foreground
     * process changes. Three strokes cost nothing and, at this size, read the same.
     */
    public final boolean glow;

    /** Grain and scratches from {@link Wear}, on the plate and on every cap. */
    public final boolean textured;

    /** How far a cap stands above the plate, in dp. Zero is a flat key. */
    public final int depthDp;

    /**
     * Whether the reading half is drawn as recessed displays rather than as groups
     * of keys on the plate.
     *
     * <p>It is a claim about what those things are. Where you are, and the places
     * you could go, are read before they are pressed; behind glass they stop
     * competing with the keys for the same kind of attention. Only the skins with
     * a material can carry it — a display sunk into a flat colour is just a darker
     * rectangle.
     */
    public final boolean displays;

    /**
     * How a screen is set into this particular panel.
     *
     * <p>The idea is the same in all three — a thing you read, behind glass, told
     * apart from the things you press — but a panel states it in its own material.
     * A deep machined well in a flat skin would be a rendering of a well sitting on
     * a surface that has no depth anywhere else, which reads as a mistake rather
     * than as a screen.
     */
    public enum Screen {
        /** A shallow inset: shadow along the top wall, nothing else. */
        INSET,
        /** No depth at all — the glass is outlined in the accent, like every key. */
        FRAMED,
        /** A true recess with a lit lower wall. Only honest where the plate has material. */
        RECESSED,
    }

    public final Screen screen;

    /** The unlit screen, and the phosphor lit behind it. */
    public final int glass;
    public final int phosphor;

    /**
     * Semantic accents. Named by what they mean, never by what they look like —
     * a palette whose entries are "blue" and "green" gets used decoratively within
     * a week, and then nothing on the panel means anything.
     */
    public final int accentNav;
    public final int accentGo;
    public final int accentStop;
    public final int accentWarn;

    /** How far a control travels when pressed, in dp. Zero means it does not move. */
    public final int travelDp;

    /**
     * Whether places are drawn as a terminal listing with a cursor row rather than
     * as a row of separate targets.
     *
     * <p>A listing is one control with N rows; a row of buttons is N controls. The
     * first is what a small embedded display does and it reads as one device; the
     * second is a toolbar that happens to be dark.
     */
    public final boolean listing;

    /** Scanlines, vignette and a little bloom on lit text. */
    public final boolean crt;

    /** Legend colour per role. The role is told by the letter as well as the edge. */
    public final int[] legend;

    /** The dimmer phosphor, for anything on a screen that is not the subject. */
    public final int phosphorDim;

    /**
     * Whether groups of keys sit in recessed sections rather than directly on the
     * panel.
     *
     * <p>Four surfaces, in order: panel, recessed section, control housing,
     * interactive face. Without the section the panel has two, and everything on
     * it reads as one plane of buttons.
     */
    public final boolean sections;

    private Skin(Builder b) {
        this.kind = b.kind;
        this.bg = b.bg;
        this.bgPanel = b.bgPanel;
        this.rule = b.rule;
        this.text = b.text;
        this.muted = b.muted;
        this.heading = b.heading;
        this.accent = b.accent;
        this.fill = b.fill;
        this.edge = b.edge;
        this.cornerDp = b.cornerDp;
        this.border = b.border;
        this.lip = b.lip;
        this.engrave = b.engrave;
        this.glow = b.glow;
        this.textured = b.textured;
        this.depthDp = b.depthDp;
        this.displays = b.displays;
        this.screen = b.screen;
        this.glass = b.glass;
        this.phosphor = b.phosphor;
        this.accentNav = b.accentNav != 0 ? b.accentNav : b.accent;
        this.accentGo = b.accentGo != 0 ? b.accentGo : b.fill[4];
        this.accentStop = b.accentStop != 0 ? b.accentStop : b.fill[3];
        this.accentWarn = b.accentWarn != 0 ? b.accentWarn : b.fill[3];
        this.travelDp = b.travelDp;
        this.listing = b.listing;
        this.crt = b.crt;
        // Every skin that did not name its legends writes them in its ordinary
        // text colour, which is what all three did before this existed.
        if (b.legend != null) {
            this.legend = b.legend;
        } else {
            int[] plain = new int[b.fill.length];
            java.util.Arrays.fill(plain, b.text);
            this.legend = plain;
        }
        this.phosphorDim = b.phosphorDim != 0 ? b.phosphorDim : b.muted;
        this.sections = b.sections;
    }

    // ------------------------------------------------------------------ the two

    /**
     * The bar as it has always been.
     *
     * <p>Four roles, not eight decorative shades: an earlier palette had two greys
     * sixteen units apart, which through pancake lenses is one grey.
     */
    public static Skin capped() {
        Builder b = new Builder(Kind.CAPPED);
        b.bg = Color.rgb(24, 25, 31);
        b.bgPanel = Color.rgb(31, 33, 40);
        b.rule = Color.rgb(52, 54, 64);
        b.text = Color.rgb(224, 226, 232);
        b.muted = Color.rgb(150, 155, 168);
        b.heading = b.muted;
        b.accent = Color.rgb(40, 82, 128);
        b.fill = new int[]{
                Color.rgb(58, 60, 70),      // KEY — a literal keystroke
                Color.rgb(52, 58, 74),      // DESTINATION — somewhere to go
                Color.rgb(40, 82, 128),     // COMMAND — a line that runs
                Color.rgb(122, 58, 30),     // WARN — destructive
                Color.rgb(38, 86, 62),      // ENTER
                Color.rgb(52, 118, 84),     // VOICE
                Color.rgb(74, 80, 104),     // ARROW — the cluster found without looking
        };
        b.edge = b.fill;                    // unused: border is zero
        // Halved across every skin at once. A softer corner reads as consumer
        // hardware; this is a tool, and the tighter break makes the caps read as
        // parts rather than as pills. One number per skin, so they stay in step.
        b.cornerDp = 4;
        b.border = 0;
        b.lip = true;
        b.engrave = true;
        b.glow = false;
        b.displays = true;
        b.screen = Screen.INSET;
        // Darker than the plate by more than a shade — a screen in a flat skin has
        // no wall to be read by, so the step in luminance is doing the whole job.
        b.glass = Color.rgb(14, 15, 18);
        // Amber, the colour of a gas-discharge panel. Not the skin's own blue: the
        // blue is what a key that runs is coloured, and a screen that shared it
        // would look like an enormous button.
        b.phosphor = Color.rgb(255, 178, 84);
        return new Skin(b);
    }

    /**
     * The instrument reading: a near-black deck and keys drawn as lit outlines.
     *
     * <p>The fills are deliberately close to the deck — the edge is what is meant
     * to be seen. Roles are told apart by edge hue, and only the two that act on
     * their own (COMMAND, ENTER) plus the two that are dangerous (WARN, and VOICE
     * while it is live) are lit; a plain keystroke stays unlit, so a bar at rest
     * has a handful of lit keys rather than forty.
     */
    public static Skin outline() {
        Builder b = new Builder(Kind.OUTLINE);
        b.bg = Color.rgb(9, 13, 20);
        b.bgPanel = Color.rgb(12, 17, 25);
        b.rule = Color.rgb(28, 40, 55);
        b.text = Color.rgb(205, 217, 232);
        b.muted = Color.rgb(120, 138, 160);
        b.accent = Color.rgb(56, 200, 235);
        b.heading = b.accent;
        int face = Color.rgb(17, 24, 34);
        b.fill = new int[]{
                face,                       // KEY
                Color.rgb(18, 27, 40),      // DESTINATION
                Color.rgb(14, 34, 48),      // COMMAND
                Color.rgb(38, 20, 18),      // WARN
                Color.rgb(15, 33, 28),      // ENTER
                Color.rgb(15, 35, 30),      // VOICE
                Color.rgb(19, 29, 42),      // ARROW
        };
        b.edge = new int[]{
                Color.rgb(44, 58, 76),      // KEY — unlit; it only types
                Color.rgb(60, 96, 130),     // DESTINATION
                b.accent,                   // COMMAND — lit: it runs on press
                Color.rgb(226, 106, 74),    // WARN
                Color.rgb(70, 200, 140),    // ENTER — lit: it runs on press
                Color.rgb(70, 200, 140),    // VOICE
                Color.rgb(70, 104, 140),    // ARROW
        };
        b.cornerDp = 5;
        b.border = 1;
        b.lip = false;
        b.engrave = false;
        b.glow = true;
        b.displays = true;
        b.screen = Screen.FRAMED;
        // A shade under the deck, no more. This skin has no depth to spend, so the
        // screen is told by its outline and by what is written on it.
        b.glass = Color.rgb(6, 9, 14);
        b.phosphor = b.accent;
        return new Skin(b);
    }

    /**
     * The instrument panel as an object: anodised plate, moulded caps with a
     * bevel, and the grain and scratches of something that has been used.
     *
     * <p>The colours are warmer and lighter than either of the others, and both
     * moves are needed for the material to read. A near-black cap has nowhere to
     * put a highlight — a bevel needs a face bright enough that lightening its top
     * edge is visible — and a perfectly neutral grey reads as a rendering rather
     * than as metal, because anodising and moulded ABS both carry a little warmth.
     *
     * <p>The roles keep their hues but arrive at them through the material: a
     * destructive key is a red-brown cap of the same plastic, not a red rectangle.
     */
    public static Skin machined() {
        Builder b = new Builder(Kind.MACHINED);
        b.bg = Color.rgb(38, 38, 42);
        b.bgPanel = Color.rgb(44, 44, 49);
        b.rule = Color.rgb(62, 62, 68);
        b.text = Color.rgb(232, 230, 226);
        b.muted = Color.rgb(158, 156, 152);
        b.heading = b.muted;
        b.accent = Color.rgb(96, 148, 196);
        b.fill = new int[]{
                Color.rgb(74, 74, 80),      // KEY
                Color.rgb(70, 78, 92),      // DESTINATION
                Color.rgb(56, 96, 140),     // COMMAND
                Color.rgb(134, 68, 44),     // WARN
                Color.rgb(54, 100, 76),     // ENTER
                Color.rgb(64, 128, 96),     // VOICE
                Color.rgb(88, 92, 112),     // ARROW
        };
        b.edge = b.fill;
        b.cornerDp = 3;
        b.border = 0;
        b.lip = false;                      // the depth comes from KeyFace instead
        b.engrave = true;
        b.glow = false;
        b.textured = true;
        // Three, not one. The lip in CAPPED is a single device pixel and is meant
        // to be felt rather than seen; here the shadow has to be wide enough to
        // hold a gradient, or the cap has no height to catch light on.
        b.depthDp = 3;
        b.displays = true;
        b.screen = Screen.RECESSED;
        // Near black with a trace of green in it, because an unlit phosphor screen
        // is never neutral — the coating is visible even with nothing driving it.
        b.glass = Color.rgb(9, 13, 10);
        // The green of a VFD rather than of an LED: slightly yellow, which is what
        // a phosphor actually emits, and it survives being thin far better than a
        // pure primary green does.
        b.phosphor = Color.rgb(126, 240, 138);
        return new Skin(b);
    }

    /**
     * A spatial control surface: graphite plate, shallow mechanical keys, and the
     * reading half as a real embedded terminal rather than as buttons.
     *
     * <p>Depth is deliberately small — five to fifteen millimetres of apparent
     * relief, not a modelled machine. What the depth is spent on is <b>state</b>:
     * a key at rest stands proud, a pressed one travels into its housing, a latched
     * one stays down. That is the whole argument for the style — the panel explains
     * its own mechanics before anything is read.
     *
     * <p>Colour is semantic and sparing. Navigation is cool blue, confirmation is
     * green, interruption is red, a suspended or special action is amber, and
     * everything else is graphite. Glow marks focus and state, never importance in
     * general: a panel where forty keys glow has told you nothing.
     */
    public static Skin tactile() {
        Builder b = new Builder(Kind.TACTILE);
        // Values taken literally from the panel's own token sheet rather than
        // re-derived, so the two can be compared without an argument about whether
        // the port was faithful.
        b.bg = 0xFF0B1017;                  // --bg
        b.bgPanel = 0xFF111824;             // --panel
        b.rule = 0xFF1B2432;
        b.text = 0xFFE7EEF8;                // --text
        b.muted = 0xFF8391A5;               // --text-muted

        b.accentNav = 0xFF2DA8FF;           // --blue
        b.accentGo = 0xFF36E37B;            // --green
        b.accentStop = 0xFFFF5252;          // --red
        b.accentWarn = 0xFFFFAD42;          // --amber
        b.accent = b.accentNav;
        b.heading = 0xFF536072;             // --text-dim

        // One material for every key. The variant changes the edge, the legend and
        // a tint of the face — never the whole cap. A key repainted end to end
        // stops being the same object in a different role and becomes a coloured
        // rectangle, which is the flat look this style exists to avoid.
        b.fill = new int[]{
                0xFF202A38,                 // KEY — --surface-raised, mid stop
                0xFF202A38,                 // DESTINATION
                0xFF202A38,                 // COMMAND
                0xFF2B1D22,                 // WARN — danger keeps a red-brown body
                0xFF1B2C24,                 // ENTER — success, faintly green
                0xFF1B2C24,                 // VOICE
                0xFF202A38,                 // ARROW
        };
        // One border colour for the whole panel, warmed a quarter of the way
        // towards the role's accent and no further.
        //
        // The first version used the accent at full strength on danger and success,
        // and it read as two loud keys among quiet ones — the eye went to the
        // outline rather than to the legend, which is the thing that actually says
        // what the key does. A cabinet of real keys does not change the colour of
        // its shells per function; it changes the legend, and sometimes the tint of
        // the plastic. That is what these are now.
        b.edge = new int[]{
                0xFF334052,                 // KEY — the neutral border
                0xFF334052,
                0xFF334052,
                0xFF4C3B44,                 // WARN — neutral, a quarter towards red
                0xFF35473F,                 // ENTER — neutral, a quarter towards green
                0xFF35473F,
                0xFF3B4A5E,
        };
        // The legend carries the role too: pale red on danger, pale green on
        // success. Both are lighter than the accent itself — an accent-strength
        // letterform on a dark cap is a thin bright shape, and thin bright shapes
        // are the first thing the lenses smear.
        b.legend = new int[]{
                b.text, b.text, b.text,
                0xFFFFB0B0,                 // --key--danger colour
                0xFFBAFFCF,                 // --key--success colour
                0xFFBAFFCF,
                b.text,
        };

        // Half of what the token sheet asked for. At 9dp the caps read as soft and
        // consumer; at 4 they read as machined parts with a break on the edge,
        // which is what the rest of the panel is claiming to be. The screens keep
        // their own radius from --radius-lg, so glass and key are still told apart
        // by shape as well as by depth.
        b.cornerDp = 4;
        b.border = 1;
        b.lip = false;
        b.engrave = false;
        b.glow = false;
        b.textured = false;
        // 0 4px 0 — the solid extrusion that gives the cap its height, plus an
        // ambient blur under it.
        b.depthDp = 4;
        b.travelDp = 3;                     // translateY(3px) on press
        b.displays = true;
        b.screen = Screen.RECESSED;
        b.listing = true;
        b.crt = true;
        b.glass = 0xFF031009;               // --crt-bg
        b.phosphor = 0xFF52FF7D;            // --crt-green
        b.phosphorDim = 0xFF1B9E47;         // --crt-green-dim
        b.sections = true;
        return new Skin(b);
    }

    /**
     * The whole device, not only its keys: three layers side by side — the screens
     * that are read, the controls that are pressed, and a narrow rail of utilities
     * — with the panel drawn as one housing around them.
     *
     * <p>This is the first skin whose difference is <b>structural</b> rather than
     * material. Every earlier one changed colour, depth and texture while keeping
     * the same arrangement, which is why two of them ended up looking alike. Here
     * the arrangement itself is the design: a directory listing with a head, mini
     * displays reporting state, chips for commands, keys grouped into labelled
     * sections, and mechanical controls for the things that latch.
     *
     * <p>Its keys stand higher and are rounder than {@code tactile} — 7dp of lift
     * against 4, 12dp of radius against 4. That is deliberate contrast, not drift:
     * a taller, softer cap belongs to a console you rest your hands on, where a
     * tight low one belongs to an instrument face.
     */
    public static Skin console() {
        Builder b = new Builder(Kind.CONSOLE);
        b.bg = 0xFF0B1017;
        b.bgPanel = 0xFF111824;
        b.rule = 0xFF1B2432;
        b.text = 0xFFE7EEF8;
        b.muted = 0xFF8391A5;
        b.heading = 0xFF536072;

        b.accentNav = 0xFF2DA8FF;
        b.accentGo = 0xFF36E37B;
        b.accentStop = 0xFFFF5252;
        b.accentWarn = 0xFFFFAD42;
        b.accent = b.accentNav;

        // --key-face for this skin: 180deg #2E3A4C → #222C3B → #151D28
        b.fill = new int[]{
                0xFF222C3B,                 // KEY
                0xFF222C3B,                 // DESTINATION
                0xFF222C3B,                 // COMMAND
                0xFF2B1D22,                 // WARN
                0xFF15291E,                 // ENTER — the primary face
                0xFF16281E,                 // VOICE
                // The arrow cluster is a shade bluer than the keys around it. It
                // is the one group on the pad the hand finds without reading, and
                // the eye needs to find it the same way — but it is still a key,
                // so the difference is a tint of the same material rather than a
                // colour of its own.
                0xFF23334A,                 // ARROW
        };
        b.edge = new int[]{
                0xFF2B3747,
                0x612DA8FF,                 // nav keys carry a blue edge at 38%
                0x612DA8FF,
                0x73FF5252,                 // danger at 45%
                0x9936E37B,                 // primary at 60%
                0x8036E37B,
                0x662DA8FF,                 // ARROW — blue at 40%
        };
        b.legend = new int[]{
                b.text,
                0xFFCFE6FF,                 // --key--nav
                0xFFCFE6FF,
                0xFFFFB0B0,                 // --key--danger
                0xFFC9FFDC,                 // --key--primary
                0xFFBAFFCF,
                0xFFCFE6FF,                 // ARROW — the nav legend
        };

        b.cornerDp = 12;                    // --key-radius for this skin
        b.border = 1;
        b.lip = false;
        b.engrave = false;
        b.glow = false;
        b.textured = false;
        b.depthDp = 7;                      // --key-lift
        b.travelDp = 6;                     // --key-travel
        b.displays = true;
        b.screen = Screen.RECESSED;
        b.listing = true;
        b.crt = true;
        b.sections = true;
        b.glass = 0xFF031009;
        b.phosphor = 0xFF52FF7D;
        b.phosphorDim = 0xFF1B9E47;
        return new Skin(b);
    }

    private static final class Builder {
        final Kind kind;
        int bg, bgPanel, rule, text, muted, heading, accent, cornerDp, border, depthDp;
        int glass, phosphor;
        int accentNav, accentGo, accentStop, accentWarn, travelDp, phosphorDim;
        int[] legend;
        boolean listing, crt, sections;
        Screen screen = Screen.INSET;
        int[] fill, edge;
        boolean lip, engrave, glow, textured, displays;

        Builder(Kind kind) {
            this.kind = kind;
        }
    }
}
