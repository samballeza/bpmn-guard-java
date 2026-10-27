package bpmnguard.render;

import java.awt.Font;

/**
 * Rendering tunables. Pixel = (di - extentMin) * scale + padding.
 *
 * @param scale         DI units to pixels
 * @param padding       canvas margin in pixels
 * @param haloPadding   gap between a flagged shape and its halo
 * @param stroke        halo / edge highlight stroke width
 * @param badges        draw rule badges
 */
public record RenderOptions(double scale, int padding, double haloPadding, float stroke, boolean badges,
                            Font font, Font badgeFont, Font markerFont) {

    public static final RenderOptions DEFAULT = new RenderOptions(
            1.0, 40, 6.0, 2.5f, true,
            new Font(Font.SANS_SERIF, Font.PLAIN, 12),
            new Font(Font.SANS_SERIF, Font.BOLD, 10),
            new Font(Font.SANS_SERIF, Font.BOLD, 20));

    public RenderOptions withScale(double s) {
        return new RenderOptions(s, padding, haloPadding, stroke, badges, font, badgeFont, markerFont);
    }

    public RenderOptions withPadding(int p) {
        return new RenderOptions(scale, p, haloPadding, stroke, badges, font, badgeFont, markerFont);
    }
}
