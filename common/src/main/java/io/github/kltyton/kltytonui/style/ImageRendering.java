package io.github.kltyton.kltytonui.style;

import java.util.Locale;

public final class ImageRendering {
    private ImageRendering() {
    }

    public static String keyword(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "auto", "smooth", "high-quality", "crisp-edges", "pixelated" -> normalized;
            default -> null;
        };
    }

    public static Mode parse(String value) {
        String keyword = keyword(value);
        if (keyword == null) return Mode.AUTO;
        return switch (keyword) {
            case "smooth" -> Mode.SMOOTH;
            case "high-quality" -> Mode.HIGH_QUALITY;
            case "crisp-edges" -> Mode.CRISP_EDGES;
            case "pixelated" -> Mode.PIXELATED;
            default -> Mode.AUTO;
        };
    }

    public static double scale(double drawnWidth, double drawnHeight,
                               double intrinsicWidth, double intrinsicHeight) {
        double x = scale(drawnWidth, intrinsicWidth);
        double y = scale(drawnHeight, intrinsicHeight);
        if (x <= 0) return y;
        if (y <= 0) return x;
        return Math.max(x, y);
    }

    private static double scale(double drawn, double intrinsic) {
        if (!(drawn > 0) || !(intrinsic > 0)) return 0;
        return drawn / intrinsic;
    }

    public static boolean linearFiltering(String value, double scale) {
        return switch (parse(value)) {
            case AUTO, SMOOTH, HIGH_QUALITY -> true;
            case CRISP_EDGES -> false;
            case PIXELATED -> scale > 0 && scale < 1.0;
        };
    }

    public enum Mode {
        AUTO,
        SMOOTH,
        HIGH_QUALITY,
        CRISP_EDGES,
        PIXELATED
    }
}
