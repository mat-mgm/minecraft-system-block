package com.systemblock.terminal;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Procedural renderer for Box Drawing (U+2500 - U+257F) and Block Elements (U+2580 - U+259F).
 * Draws continuous, pixel-perfect vector lines and filled rectangles directly spanning
 * cell boundaries, guaranteeing zero gaps horizontally and vertically.
 */
public class BoxDrawingHelper {

    public static boolean isBoxOrBlock(char c) {
        return (c >= 0x2500 && c <= 0x259F);
    }

    public static boolean render(GuiGraphicsExtractor graphics, char c, int cellX, int drawY, int cellW, int cellH, int color) {
        if (!isBoxOrBlock(c)) {
            return false;
        }

        int x0 = cellX;
        int x1 = cellX + cellW;
        int y0 = drawY;
        int y1 = drawY + cellH;
        int xm = cellX + cellW / 2;
        int ym = drawY + cellH / 2;

        // Block Elements (U+2580 - U+259F)
        if (c >= 0x2580) {
            return renderBlock(graphics, c, x0, y0, x1, y1, xm, ym, color);
        }

        // Box Drawing Characters (U+2500 - U+257F)
        switch (c) {
            // Light Horizontal & Vertical
            case '─', '┄', '┈' -> graphics.fill(x0, ym, x1, ym + 1, color);
            case '│', '┆', '┊' -> graphics.fill(xm, y0, xm + 1, y1, color);

            // Light Corners
            case '┌', '╭' -> { // Down & Right
                graphics.fill(xm, ym, xm + 1, y1, color);
                graphics.fill(xm, ym, x1, ym + 1, color);
            }
            case '┐', '╮' -> { // Down & Left
                graphics.fill(xm, ym, xm + 1, y1, color);
                graphics.fill(x0, ym, xm + 1, ym + 1, color);
            }
            case '└', '╰' -> { // Up & Right
                graphics.fill(xm, y0, xm + 1, ym + 1, color);
                graphics.fill(xm, ym, x1, ym + 1, color);
            }
            case '┘', '╯' -> { // Up & Left
                graphics.fill(xm, y0, xm + 1, ym + 1, color);
                graphics.fill(x0, ym, xm + 1, ym + 1, color);
            }

            // Light T-junctions
            case '├' -> { // Up, Down, Right
                graphics.fill(xm, y0, xm + 1, y1, color);
                graphics.fill(xm, ym, x1, ym + 1, color);
            }
            case '┤' -> { // Up, Down, Left
                graphics.fill(xm, y0, xm + 1, y1, color);
                graphics.fill(x0, ym, xm + 1, ym + 1, color);
            }
            case '┬' -> { // Left, Right, Down
                graphics.fill(x0, ym, x1, ym + 1, color);
                graphics.fill(xm, ym, xm + 1, y1, color);
            }
            case '┴' -> { // Left, Right, Up
                graphics.fill(x0, ym, x1, ym + 1, color);
                graphics.fill(xm, y0, xm + 1, ym + 1, color);
            }

            // Light Cross
            case '┼' -> {
                graphics.fill(x0, ym, x1, ym + 1, color);
                graphics.fill(xm, y0, xm + 1, y1, color);
            }

            // Heavy Horizontal & Vertical
            case '━', '┅', '┉' -> graphics.fill(x0, ym - 1, x1, ym + 1, color);
            case '┃', '┇', '┋' -> graphics.fill(xm - 1, y0, xm + 1, y1, color);

            // Heavy Corners
            case '┏' -> {
                graphics.fill(xm - 1, ym - 1, xm + 1, y1, color);
                graphics.fill(xm - 1, ym - 1, x1, ym + 1, color);
            }
            case '┓' -> {
                graphics.fill(xm - 1, ym - 1, xm + 1, y1, color);
                graphics.fill(x0, ym - 1, xm + 1, ym + 1, color);
            }
            case '┗' -> {
                graphics.fill(xm - 1, y0, xm + 1, ym + 1, color);
                graphics.fill(xm - 1, ym - 1, x1, ym + 1, color);
            }
            case '┛' -> {
                graphics.fill(xm - 1, y0, xm + 1, ym + 1, color);
                graphics.fill(x0, ym - 1, xm + 1, ym + 1, color);
            }

            // Heavy T-junctions
            case '┣' -> {
                graphics.fill(xm - 1, y0, xm + 1, y1, color);
                graphics.fill(xm - 1, ym - 1, x1, ym + 1, color);
            }
            case '┫' -> {
                graphics.fill(xm - 1, y0, xm + 1, y1, color);
                graphics.fill(x0, ym - 1, xm + 1, ym + 1, color);
            }
            case '┳' -> {
                graphics.fill(x0, ym - 1, x1, ym + 1, color);
                graphics.fill(xm - 1, ym - 1, xm + 1, y1, color);
            }
            case '┻' -> {
                graphics.fill(x0, ym - 1, x1, ym + 1, color);
                graphics.fill(xm - 1, y0, xm + 1, ym + 1, color);
            }
            case '╋' -> {
                graphics.fill(x0, ym - 1, x1, ym + 1, color);
                graphics.fill(xm - 1, y0, xm + 1, y1, color);
            }

            // Double Horizontal & Vertical
            case '═' -> {
                graphics.fill(x0, ym - 1, x1, ym, color);
                graphics.fill(x0, ym + 1, x1, ym + 2, color);
            }
            case '║' -> {
                graphics.fill(xm - 1, y0, xm, y1, color);
                graphics.fill(xm + 1, y0, xm + 2, y1, color);
            }

            // Double Corners
            case '╔' -> {
                graphics.fill(xm - 1, ym - 1, xm, y1, color);
                graphics.fill(xm + 1, ym + 1, xm + 2, y1, color);
                graphics.fill(xm - 1, ym - 1, x1, ym, color);
                graphics.fill(xm + 1, ym + 1, x1, ym + 2, color);
            }
            case '╗' -> {
                graphics.fill(xm + 1, ym - 1, xm + 2, y1, color);
                graphics.fill(xm - 1, ym + 1, xm, y1, color);
                graphics.fill(x0, ym - 1, xm + 2, ym, color);
                graphics.fill(x0, ym + 1, xm - 1, ym + 2, color);
            }
            case '╚' -> {
                graphics.fill(xm - 1, y0, xm, ym + 2, color);
                graphics.fill(xm + 1, y0, xm + 2, ym - 1, color);
                graphics.fill(xm - 1, ym + 1, x1, ym + 2, color);
                graphics.fill(xm + 1, ym - 1, x1, ym, color);
            }
            case '╝' -> {
                graphics.fill(xm + 1, y0, xm + 2, ym + 2, color);
                graphics.fill(xm - 1, y0, xm, ym - 1, color);
                graphics.fill(x0, ym + 1, xm + 2, ym + 2, color);
                graphics.fill(x0, ym - 1, xm - 1, ym, color);
            }

            // Double T-junctions & Crosses
            case '╠' -> {
                graphics.fill(xm - 1, y0, xm, y1, color);
                graphics.fill(xm + 1, y0, xm + 2, y1, color);
                graphics.fill(xm + 1, ym - 1, x1, ym, color);
                graphics.fill(xm + 1, ym + 1, x1, ym + 2, color);
            }
            case '╣' -> {
                graphics.fill(xm - 1, y0, xm, y1, color);
                graphics.fill(xm + 1, y0, xm + 2, y1, color);
                graphics.fill(x0, ym - 1, xm - 1, ym, color);
                graphics.fill(x0, ym + 1, xm - 1, ym + 2, color);
            }
            case '╦' -> {
                graphics.fill(x0, ym - 1, x1, ym, color);
                graphics.fill(x0, ym + 1, x1, ym + 2, color);
                graphics.fill(xm - 1, ym + 1, xm, y1, color);
                graphics.fill(xm + 1, ym + 1, xm + 2, y1, color);
            }
            case '╩' -> {
                graphics.fill(x0, ym - 1, x1, ym, color);
                graphics.fill(x0, ym + 1, x1, ym + 2, color);
                graphics.fill(xm - 1, y0, xm, ym - 1, color);
                graphics.fill(xm + 1, y0, xm + 2, ym - 1, color);
            }
            case '╬' -> {
                graphics.fill(x0, ym - 1, x1, ym, color);
                graphics.fill(x0, ym + 1, x1, ym + 2, color);
                graphics.fill(xm - 1, y0, xm, y1, color);
                graphics.fill(xm + 1, y0, xm + 2, y1, color);
            }

            // Light Endpoints
            case '╴' -> graphics.fill(x0, ym, xm + 1, ym + 1, color);
            case '╵' -> graphics.fill(xm, y0, xm + 1, ym + 1, color);
            case '╶' -> graphics.fill(xm, ym, x1, ym + 1, color);
            case '╷' -> graphics.fill(xm, ym, xm + 1, y1, color);

            // Heavy Endpoints
            case '╸' -> graphics.fill(x0, ym - 1, xm + 1, ym + 1, color);
            case '╹' -> graphics.fill(xm - 1, y0, xm + 1, ym + 1, color);
            case '╺' -> graphics.fill(xm - 1, ym - 1, x1, ym + 1, color);
            case '╻' -> graphics.fill(xm - 1, ym - 1, xm + 1, y1, color);

            default -> {
                // Generic fallback for any other box drawing char: connect all 4 edges lightly
                graphics.fill(x0, ym, x1, ym + 1, color);
                graphics.fill(xm, y0, xm + 1, y1, color);
            }
        }
        return true;
    }

    private static boolean renderBlock(GuiGraphicsExtractor graphics, char c, int x0, int y0, int x1, int y1, int xm, int ym, int color) {
        switch (c) {
            case '█' -> graphics.fill(x0, y0, x1, y1, color);
            case '▀' -> graphics.fill(x0, y0, x1, ym, color);
            case '▄' -> graphics.fill(x0, ym, x1, y1, color);
            case '▌' -> graphics.fill(x0, y0, xm, y1, color);
            case '▐' -> graphics.fill(xm, y0, x1, y1, color);

            // Shades
            case '░' -> graphics.fill(x0, y0, x1, y1, (color & 0x00FFFFFF) | 0x40000000);
            case '▒' -> graphics.fill(x0, y0, x1, y1, (color & 0x00FFFFFF) | 0x80000000);
            case '▓' -> graphics.fill(x0, y0, x1, y1, (color & 0x00FFFFFF) | 0xC0000000);

            // Fractional horizontal blocks
            case ' ' -> graphics.fill(x0, y1 - 1, x1, y1, color);
            case '▂' -> graphics.fill(x0, y1 - 2, x1, y1, color);
            case '▃' -> graphics.fill(x0, y1 - 3, x1, y1, color);
            case '▅' -> graphics.fill(x0, y0 + 3, x1, y1, color);
            case '▆' -> graphics.fill(x0, y0 + 2, x1, y1, color);
            case '▇' -> graphics.fill(x0, y0 + 1, x1, y1, color);

            // Quadrant blocks
            case '▖' -> graphics.fill(x0, ym, xm, y1, color);
            case '▗' -> graphics.fill(xm, ym, x1, y1, color);
            case '▘' -> graphics.fill(x0, y0, xm, ym, color);
            case '▙' -> {
                graphics.fill(x0, y0, xm, y1, color);
                graphics.fill(xm, ym, x1, y1, color);
            }
            case '▚' -> {
                graphics.fill(x0, y0, xm, ym, color);
                graphics.fill(xm, ym, x1, y1, color);
            }
            case '▛' -> {
                graphics.fill(x0, y0, x1, ym, color);
                graphics.fill(x0, ym, xm, y1, color);
            }
            case '▜' -> {
                graphics.fill(x0, y0, x1, ym, color);
                graphics.fill(xm, ym, x1, y1, color);
            }
            case '▝' -> graphics.fill(xm, y0, x1, ym, color);
            case '▞' -> {
                graphics.fill(xm, y0, x1, ym, color);
                graphics.fill(x0, ym, xm, y1, color);
            }
            case '▟' -> {
                graphics.fill(x0, ym, x1, y1, color);
                graphics.fill(xm, y0, x1, ym, color);
            }

            default -> graphics.fill(x0, y0, x1, y1, color);
        }
        return true;
    }
}
