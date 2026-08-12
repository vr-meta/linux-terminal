package dev.butschster.linuxterminal;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;

import java.util.Random;

/**
 * The texture of a real object: brushed metal, grain, and the scratches a panel
 * picks up from being used.
 *
 * <p>Generated rather than shipped. A photographed texture would be an asset to
 * license, several hundred kilobytes in the APK, and wrong at any density it was
 * not authored for; a tile drawn here is a few kilobytes of pixels made at the
 * density it will be drawn at, and it costs one allocation for the life of the
 * process.
 *
 * <p><b>Seeded, never random at runtime.</b> {@link Random} with a fixed seed
 * gives the same panel every launch. Genuinely random wear would mean a key that
 * has a scratch across its legend today and not tomorrow, which is the one way a
 * texture can actually cost readability.
 *
 * <p>All of it is faint on purpose — grain at roughly 4% and scratches at 6%.
 * `docs/readability.md` measured this project's own text going from readable to
 * unreadable across a third of a degree of angular size, and every percent of
 * contrast spent on texture is a percent not spent on the legend standing on it.
 * Texture that is obvious on a monitor is noise through the lenses.
 */
public final class Wear {

    /** One tile, reused everywhere by a repeating shader. */
    private static final int TILE = 192;

    private static Bitmap grain;
    private static Bitmap scratches;

    private Wear() {}

    /**
     * Fine grain: the sandblasted or moulded surface itself, before any wear.
     *
     * <p>Monochrome noise rather than coloured: a coloured grain shifts the hue of
     * whatever it lies on, and the palette here is deliberate. Alpha carries it, so
     * the same tile works over dark metal and over a lit key alike.
     */
    public static synchronized Shader grain() {
        if (grain == null) {
            grain = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
            Random random = new Random(0x5EEDL);
            int[] pixels = new int[TILE * TILE];
            for (int i = 0; i < pixels.length; i++) {
                // Two draws averaged, which is cheap and lands the distribution
                // nearer the middle than a single uniform one — pure uniform noise
                // reads as static, this reads as a surface.
                int value = (random.nextInt(255) + random.nextInt(255)) / 2;
                int alpha = 26 + random.nextInt(20);
                pixels[i] = Color.argb(alpha, value, value, value);
            }
            grain.setPixels(pixels, 0, TILE, 0, 0, TILE, TILE);
        }
        return new BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    }

    /**
     * Use: brushed lines in one direction, plus the scratches a working panel has.
     *
     * <p>Horizontal, because that is what brushing a flat face leaves and because a
     * vertical streak on a key would be read as a division of the key. The
     * scratches are drawn over the brushing rather than under it, since a scratch
     * is later than the finish underneath it.
     */
    public static synchronized Shader use() {
        if (scratches == null) {
            scratches = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(scratches);
            canvas.drawColor(Color.TRANSPARENT);
            Random random = new Random(0xB4005AL);

            // The brushed finish: many faint full-width lines, alternately lighter
            // and darker, so the surface has direction without having a pattern.
            Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
            brush.setStrokeWidth(1f);
            for (int i = 0; i < TILE * 2; i++) {
                float y = random.nextFloat() * TILE;
                boolean light = random.nextBoolean();
                int alpha = 6 + random.nextInt(10);
                brush.setColor(light ? Color.argb(alpha, 255, 255, 255)
                        : Color.argb(alpha, 0, 0, 0));
                canvas.drawLine(0, y, TILE, y, brush);
            }

            // Scratches: short, near-horizontal, and light, because a scratch in a
            // dark anodised surface exposes the brighter metal under it.
            Paint scratch = new Paint(Paint.ANTI_ALIAS_FLAG);
            scratch.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 26; i++) {
                float x = random.nextFloat() * TILE;
                float y = random.nextFloat() * TILE;
                float length = 8 + random.nextFloat() * 60;
                float drop = (random.nextFloat() - 0.5f) * 7;
                scratch.setStrokeWidth(0.6f + random.nextFloat() * 0.8f);
                scratch.setColor(Color.argb(18 + random.nextInt(26), 255, 255, 255));
                canvas.drawLine(x, y, x + length, y + drop, scratch);
            }
        }
        return new BitmapShader(scratches, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    }
}
