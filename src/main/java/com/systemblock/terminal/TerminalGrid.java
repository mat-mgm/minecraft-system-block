package com.systemblock.terminal;

import java.util.Arrays;

/**
 * High-performance 80x24 VT100 / ANSI terminal grid buffer for rendering
 * interactive terminal UIs (TUIs) like Claude Code, OpenCode, htop, and vim inside Minecraft.
 */
public class TerminalGrid {
    public static final int COLS = 80;
    public static final int ROWS = 24;

    private final char[][] chars = new char[ROWS][COLS];
    private final int[][] fgColors = new int[ROWS][COLS]; // Minecraft ARGB color ints
    private final int[][] bgColors = new int[ROWS][COLS]; // Minecraft ARGB color ints

    // Default matrix green on dark background
    public static final int DEFAULT_FG = 0xFF55FF55; // Bright green
    public static final int DEFAULT_BG = 0x00000000; // Transparent / dark

    // Standard 16 ANSI Color Palette
    public static final int[] ANSI_PALETTE = new int[] {
            0xFF000000, // 0: Black
            0xFFAA0000, // 1: Red
            0xFF00AA00, // 2: Green
            0xFFAAAA00, // 3: Yellow/Brown
            0xFF0000AA, // 4: Blue
            0xFFAA00AA, // 5: Magenta
            0xFF00AAAA, // 6: Cyan
            0xFFAAAAAA, // 7: Light Gray
            0xFF555555, // 8: Dark Gray
            0xFFFF5555, // 9: Bright Red
            0xFF55FF55, // 10: Bright Green
            0xFFFFFF55, // 11: Bright Yellow
            0xFF5555FF, // 12: Bright Blue
            0xFFFF55FF, // 13: Bright Magenta
            0xFF55FFFF, // 14: Bright Cyan
            0xFFFFFFFF  // 15: White
    };

    private int cursorX = 0;
    private int cursorY = 0;
    private boolean cursorVisible = true;

    private int currentFg = DEFAULT_FG;
    private int currentBg = DEFAULT_BG;

    // ANSI escape parsing state
    private boolean inEscape = false;
    private boolean inCsi = false;
    private final StringBuilder csiParams = new StringBuilder();

    public TerminalGrid() {
        clear();
    }

    public synchronized void clear() {
        for (int r = 0; r < ROWS; r++) {
            Arrays.fill(chars[r], ' ');
            Arrays.fill(fgColors[r], DEFAULT_FG);
            Arrays.fill(bgColors[r], DEFAULT_BG);
        }
        cursorX = 0;
        cursorY = 0;
        currentFg = DEFAULT_FG;
        currentBg = DEFAULT_BG;
    }

    public synchronized int getCursorX() {
        return cursorX;
    }

    public synchronized int getCursorY() {
        return cursorY;
    }

    public synchronized boolean isCursorVisible() {
        return cursorVisible;
    }

    public synchronized char getChar(int r, int c) {
        if (r < 0 || r >= ROWS || c < 0 || c >= COLS) return ' ';
        return chars[r][c];
    }

    public synchronized int getFg(int r, int c) {
        if (r < 0 || r >= ROWS || c < 0 || c >= COLS) return DEFAULT_FG;
        return fgColors[r][c];
    }

    public synchronized int getBg(int r, int c) {
        if (r < 0 || r >= ROWS || c < 0 || c >= COLS) return DEFAULT_BG;
        return bgColors[r][c];
    }

    public synchronized void write(String text) {
        if (text == null || text.isEmpty()) return;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char ch = text.charAt(i);

            if (inEscape) {
                if (ch == '[') {
                    inCsi = true;
                    csiParams.setLength(0);
                } else if (inCsi) {
                    if ((ch >= '0' && ch <= '9') || ch == ';' || ch == '?' || ch == '=') {
                        csiParams.append(ch);
                    } else {
                        // Execution character
                        handleCsi(ch, csiParams.toString());
                        inEscape = false;
                        inCsi = false;
                    }
                } else {
                    // Escape single char commands
                    if (ch == 'c') { // RIS: Reset
                        clear();
                    }
                    inEscape = false;
                }
                continue;
            }

            if (ch == '\033') { // ESC
                inEscape = true;
                inCsi = false;
                continue;
            }

            if (ch == '\r') {
                cursorX = 0;
            } else if (ch == '\n') {
                newLine();
            } else if (ch == '\b') {
                cursorX = Math.max(0, cursorX - 1);
            } else if (ch == '\t') {
                cursorX = Math.min(COLS - 1, (cursorX + 8) & ~7);
            } else if (ch >= 32) {
                if (cursorX >= COLS) {
                    cursorX = 0;
                    newLine();
                }
                chars[cursorY][cursorX] = ch;
                fgColors[cursorY][cursorX] = currentFg;
                bgColors[cursorY][cursorX] = currentBg;
                cursorX++;
            }
        }
    }

    private void newLine() {
        if (cursorY < ROWS - 1) {
            cursorY++;
        } else {
            // Scroll up
            for (int r = 0; r < ROWS - 1; r++) {
                System.arraycopy(chars[r + 1], 0, chars[r], 0, COLS);
                System.arraycopy(fgColors[r + 1], 0, fgColors[r], 0, COLS);
                System.arraycopy(bgColors[r + 1], 0, bgColors[r], 0, COLS);
            }
            Arrays.fill(chars[ROWS - 1], ' ');
            Arrays.fill(fgColors[ROWS - 1], DEFAULT_FG);
            Arrays.fill(bgColors[ROWS - 1], DEFAULT_BG);
        }
    }

    private void handleCsi(char cmd, String params) {
        String[] parts = params.split(";");
        int p1 = (parts.length > 0 && !parts[0].isEmpty()) ? parseSafe(parts[0], 1) : 1;
        int p2 = (parts.length > 1 && !parts[1].isEmpty()) ? parseSafe(parts[1], 1) : 1;

        switch (cmd) {
            case 'H': // Cursor position (row, col)
            case 'f':
                cursorY = Math.max(0, Math.min(ROWS - 1, p1 - 1));
                cursorX = Math.max(0, Math.min(COLS - 1, p2 - 1));
                break;
            case 'A': // Cursor Up
                cursorY = Math.max(0, cursorY - p1);
                break;
            case 'B': // Cursor Down
                cursorY = Math.min(ROWS - 1, cursorY + p1);
                break;
            case 'C': // Cursor Forward
                cursorX = Math.min(COLS - 1, cursorX + p1);
                break;
            case 'D': // Cursor Back
                cursorX = Math.max(0, cursorX - p1);
                break;
            case 'G': // Cursor to column
                cursorX = Math.max(0, Math.min(COLS - 1, p1 - 1));
                break;
            case 'd': // Cursor to row
                cursorY = Math.max(0, Math.min(ROWS - 1, p1 - 1));
                break;
            case 'J': // Erase in display
                if (p1 == 2 || p1 == 3) {
                    clear();
                } else if (p1 == 0) {
                    // Erase from cursor to end
                    for (int c = cursorX; c < COLS; c++) eraseCell(cursorY, c);
                    for (int r = cursorY + 1; r < ROWS; r++) {
                        Arrays.fill(chars[r], ' ');
                        Arrays.fill(fgColors[r], DEFAULT_FG);
                        Arrays.fill(bgColors[r], DEFAULT_BG);
                    }
                }
                break;
            case 'K': // Erase in line
                if (p1 == 0) {
                    for (int c = cursorX; c < COLS; c++) eraseCell(cursorY, c);
                } else if (p1 == 1) {
                    for (int c = 0; c <= cursorX; c++) eraseCell(cursorY, c);
                } else if (p1 == 2) {
                    for (int c = 0; c < COLS; c++) eraseCell(cursorY, c);
                }
                break;
            case 'm': // SGR Color / Formatting
                handleSgr(parts);
                break;
            case 'h': // Private modes (show cursor)
                if (params.contains("?25")) cursorVisible = true;
                break;
            case 'l': // Private modes (hide cursor)
                if (params.contains("?25")) cursorVisible = false;
                break;
        }
    }

    private void eraseCell(int r, int c) {
        if (r >= 0 && r < ROWS && c >= 0 && c < COLS) {
            chars[r][c] = ' ';
            fgColors[r][c] = currentFg;
            bgColors[r][c] = currentBg;
        }
    }

    private void handleSgr(String[] parts) {
        if (parts.length == 0 || (parts.length == 1 && parts[0].isEmpty())) {
            currentFg = DEFAULT_FG;
            currentBg = DEFAULT_BG;
            return;
        }
        for (int i = 0; i < parts.length; i++) {
            int code = parseSafe(parts[i], 0);
            if (code == 0) {
                currentFg = DEFAULT_FG;
                currentBg = DEFAULT_BG;
            } else if (code >= 30 && code <= 37) {
                currentFg = ANSI_PALETTE[code - 30];
            } else if (code == 39) {
                currentFg = DEFAULT_FG;
            } else if (code >= 40 && code <= 47) {
                currentBg = ANSI_PALETTE[code - 40];
            } else if (code == 49) {
                currentBg = DEFAULT_BG;
            } else if (code >= 90 && code <= 97) {
                currentFg = ANSI_PALETTE[code - 90 + 8];
            } else if (code >= 100 && code <= 107) {
                currentBg = ANSI_PALETTE[code - 100 + 8];
            } else if (code == 38 && i + 2 < parts.length && "5".equals(parts[i + 1])) {
                // 256 colors: 38;5;<color>
                int c256 = parseSafe(parts[i + 2], 7);
                if (c256 < 16) currentFg = ANSI_PALETTE[c256];
                i += 2;
            } else if (code == 48 && i + 2 < parts.length && "5".equals(parts[i + 1])) {
                int c256 = parseSafe(parts[i + 2], 0);
                if (c256 < 16) currentBg = ANSI_PALETTE[c256];
                i += 2;
            }
        }
    }

    private int parseSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
