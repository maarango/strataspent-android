// Standalone Java tool that renders the two Play Store image assets:
//
//   play-store/high-res-icon.png   — 512x512 store icon
//   play-store/feature-graphic.png — 1024x500 banner
//
// Run from the repo root with the JDK that ships with Android Studio:
//
//   $jdk = 'C:\Program Files\Android\Android Studio\jbr\bin'
//   & "$jdk\javac.exe" tools/RenderPlayAssets.java
//   & "$jdk\java.exe"  -cp tools RenderPlayAssets
//
// The output is rough-and-ready — a starting point so you have something
// to submit to Play Console immediately. Replace later with a custom
// design from Figma / Photopea / your designer.

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

public class RenderPlayAssets {

    static final Color SLATE_950 = new Color(0x02, 0x06, 0x17);
    static final Color SLATE_900 = new Color(0x0F, 0x17, 0x2A);
    static final Color INDIGO_500 = new Color(0x63, 0x66, 0xF1);
    static final Color INDIGO_400 = new Color(0x81, 0x8C, 0xF8);
    static final Color INDIGO_200 = new Color(0xC7, 0xD2, 0xFE);
    static final Color WHITE = new Color(0xFF, 0xFF, 0xFF);
    static final Color OFFWHITE = new Color(0xE0, 0xE7, 0xFF);

    public static void main(String[] args) throws Exception {
        new File("play-store").mkdirs();
        renderHighResIcon(new File("play-store/high-res-icon.png"));
        renderFeatureGraphic(new File("play-store/feature-graphic.png"));
        System.out.println("Wrote play-store/high-res-icon.png (512x512)");
        System.out.println("Wrote play-store/feature-graphic.png (1024x500)");
    }

    static void renderHighResIcon(File out) throws Exception {
        int size = 512;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Radial gradient background (matches launcher icon)
            RadialGradientPaint bg = new RadialGradientPaint(
                size / 2f, size / 2f, size * 0.7f,
                new float[]{0f, 1f},
                new Color[]{INDIGO_500, SLATE_900}
            );
            g.setPaint(bg);
            g.fillRect(0, 0, size, size);

            drawWallet(g, size / 2f, size / 2f, size * 0.55f);
        } finally {
            g.dispose();
        }
        ImageIO.write(img, "png", out);
    }

    static void renderFeatureGraphic(File out) throws Exception {
        int w = 1024;
        int h = 500;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Diagonal slate gradient
            GradientPaint bg = new GradientPaint(
                0, 0, SLATE_950,
                w, h, new Color(0x1F, 0x29, 0x44)
            );
            g.setPaint(bg);
            g.fillRect(0, 0, w, h);

            // Subtle indigo glow on the left where the icon lives
            g.setPaint(new RadialGradientPaint(
                w * 0.22f, h / 2f, w * 0.35f,
                new float[]{0f, 1f},
                new Color[]{
                    new Color(INDIGO_500.getRed(), INDIGO_500.getGreen(), INDIGO_500.getBlue(), 120),
                    new Color(SLATE_950.getRed(), SLATE_950.getGreen(), SLATE_950.getBlue(), 0),
                }
            ));
            g.fillRect(0, 0, w, h);

            // Wallet icon on the left
            float walletCx = w * 0.22f;
            float walletCy = h / 2f;
            drawWallet(g, walletCx, walletCy, h * 0.62f);

            // Wordmark
            g.setColor(WHITE);
            Font title = new Font(Font.SANS_SERIF, Font.BOLD, 88);
            g.setFont(title);
            g.drawString("StrataSpent", w * 0.42f, h / 2f - 8);

            Font tagline = new Font(Font.SANS_SERIF, Font.PLAIN, 32);
            g.setFont(tagline);
            g.setColor(INDIGO_200);
            g.drawString("Family expenses, together.", w * 0.42f, h / 2f + 56);
        } finally {
            g.dispose();
        }
        ImageIO.write(img, "png", out);
    }

    /** Draws a stylised white wallet centred on (cx, cy), fitting within
     *  a `size` x `size` box. Approximates the launcher-icon wallet. */
    static void drawWallet(Graphics2D g, float cx, float cy, float size) {
        float w = size;
        float h = size * 0.7f;
        float x = cx - w / 2f;
        float y = cy - h / 2f;

        // Soft drop shadow
        g.setColor(new Color(0, 0, 0, 60));
        g.fill(new RoundRectangle2D.Float(x + 6, y + 10, w, h, h * 0.25f, h * 0.25f));

        // Flap (top strip)
        float flapH = h * 0.18f;
        g.setColor(WHITE);
        g.fill(new RoundRectangle2D.Float(
            x + w * 0.08f, y, w * 0.84f, flapH * 1.6f, flapH, flapH
        ));

        // Body
        g.setColor(WHITE);
        g.fill(new RoundRectangle2D.Float(x, y + flapH * 0.7f, w, h * 0.95f - flapH * 0.7f, h * 0.20f, h * 0.20f));

        // Stitch line
        g.setColor(OFFWHITE);
        g.fill(new java.awt.geom.Rectangle2D.Float(
            x + w * 0.06f, y + h * 0.30f, w * 0.88f, h * 0.018f
        ));

        // Coin slot
        float slotW = w * 0.36f;
        float slotH = h * 0.32f;
        float slotX = x + w * 0.60f;
        float slotY = y + h * 0.45f;
        g.setColor(SLATE_900);
        g.fill(new RoundRectangle2D.Float(slotX, slotY, slotW, slotH, slotH * 0.45f, slotH * 0.45f));

        // Coin
        float coinD = slotH * 0.55f;
        float coinX = slotX + slotW * 0.18f;
        float coinY = slotY + (slotH - coinD) / 2f;
        g.setColor(INDIGO_400);
        g.fillOval((int) coinX, (int) coinY, (int) coinD, (int) coinD);
    }
}
