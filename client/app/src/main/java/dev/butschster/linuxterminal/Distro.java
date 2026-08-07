package dev.butschster.linuxterminal;

import android.graphics.Color;

import java.util.Locale;

/**
 * Which Linux a machine is running, reduced to a colour and a letter.
 *
 * <p>The server sends {@code PRETTY_NAME} from {@code /etc/os-release}, so this
 * is a match against a string a distribution chose for itself — Ubuntu, Fedora,
 * Debian and the dozen others common enough to be recognised at a glance. Any
 * unmatched name still gets a badge; it is grey and says L, which is true of
 * every one of them.
 *
 * <p>Deliberately not the distributions' logos. Those are trademarks with usage
 * policies of their own, and this app is being prepared for a store where
 * somebody else's mark in the interface is a question at review time. A brand
 * colour and an initial carry the same recognition and belong to nobody.
 */
public final class Distro {

    public final int colour;
    public final String letter;

    private Distro(int colour, String letter) {
        this.colour = colour;
        this.letter = letter;
    }

    /** Order matters: the longer name is tested first where one contains another. */
    public static Distro of(String prettyName) {
        String name = prettyName == null ? "" : prettyName.toLowerCase(Locale.ROOT);

        if (name.contains("ubuntu")) return new Distro(Color.rgb(233, 84, 32), "U");
        if (name.contains("linux mint") || name.contains("mint")) return new Distro(Color.rgb(135, 207, 62), "M");
        if (name.contains("pop!_os") || name.contains("pop os")) return new Distro(Color.rgb(72, 185, 199), "P");
        if (name.contains("debian")) return new Distro(Color.rgb(215, 10, 83), "D");
        if (name.contains("fedora")) return new Distro(Color.rgb(41, 101, 165), "F");
        if (name.contains("manjaro")) return new Distro(Color.rgb(53, 191, 164), "M");
        if (name.contains("arch")) return new Distro(Color.rgb(23, 147, 209), "A");
        if (name.contains("opensuse") || name.contains("suse")) return new Distro(Color.rgb(115, 186, 37), "S");
        if (name.contains("alpine")) return new Distro(Color.rgb(13, 89, 122), "A");
        if (name.contains("kali")) return new Distro(Color.rgb(38, 118, 191), "K");
        if (name.contains("rocky")) return new Distro(Color.rgb(16, 185, 129), "R");
        if (name.contains("almalinux") || name.contains("alma")) return new Distro(Color.rgb(0, 102, 204), "A");
        if (name.contains("centos")) return new Distro(Color.rgb(146, 44, 136), "C");
        if (name.contains("red hat") || name.contains("rhel")) return new Distro(Color.rgb(204, 0, 0), "R");
        if (name.contains("gentoo")) return new Distro(Color.rgb(84, 72, 122), "G");
        if (name.contains("void")) return new Distro(Color.rgb(71, 143, 72), "V");
        if (name.contains("nixos") || name.contains("nix")) return new Distro(Color.rgb(82, 125, 190), "N");
        if (name.contains("raspbian") || name.contains("raspberry")) return new Distro(Color.rgb(196, 24, 60), "R");

        return new Distro(Color.rgb(96, 100, 112), "L");
    }
}
