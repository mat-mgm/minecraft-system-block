package com.systemblock.terminal;

import java.util.Arrays;

/**
 * High-performance VT100 / ANSI terminal grid buffer for rendering
 * interactive terminal UIs (TUIs) like Claude Code, OpenCode, htop, lsd, and vim inside Minecraft.
 * Supports full Unicode (including 32-bit surrogate pairs and Nerd Font icons)
 * and dynamic column/row scaling.
 */
public class TerminalGrid {
    public static final int DEFAULT_COLS = 80;
    public static final int DEFAULT_ROWS = 24;

    private int cols = DEFAULT_COLS;
    private int rows = DEFAULT_ROWS;

    private String[][] cells = new String[DEFAULT_ROWS][DEFAULT_COLS];
    private int[][] fgColors = new int[DEFAULT_ROWS][DEFAULT_COLS]; // Minecraft ARGB color ints
    private int[][] bgColors = new int[DEFAULT_ROWS][DEFAULT_COLS]; // Minecraft ARGB color ints

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
    private boolean inOsc = false;
    private final StringBuilder csiParams = new StringBuilder();

    public TerminalGrid() {
        this(DEFAULT_COLS, DEFAULT_ROWS);
    }

    public TerminalGrid(int cols, int rows) {
        this.cols = Math.max(20, Math.min(300, cols));
        this.rows = Math.max(10, Math.min(120, rows));
        this.cells = new String[this.rows][this.cols];
        this.fgColors = new int[this.rows][this.cols];
        this.bgColors = new int[this.rows][this.cols];
        clear();
    }

    public synchronized void clear() {
        for (int r = 0; r < rows; r++) {
            Arrays.fill(cells[r], " ");
            Arrays.fill(fgColors[r], DEFAULT_FG);
            Arrays.fill(bgColors[r], DEFAULT_BG);
        }
        cursorX = 0;
        cursorY = 0;
        currentFg = DEFAULT_FG;
        currentBg = DEFAULT_BG;
    }

    public synchronized void resize(int newCols, int newRows) {
        newCols = Math.max(20, Math.min(300, newCols));
        newRows = Math.max(10, Math.min(120, newRows));
        if (newCols == this.cols && newRows == this.rows) return;

        String[][] newCells = new String[newRows][newCols];
        int[][] newFg = new int[newRows][newCols];
        int[][] newBg = new int[newRows][newCols];

        for (int r = 0; r < newRows; r++) {
            Arrays.fill(newCells[r], " ");
            Arrays.fill(newFg[r], DEFAULT_FG);
            Arrays.fill(newBg[r], DEFAULT_BG);
        }

        int copyRows = Math.min(this.rows, newRows);
        int copyCols = Math.min(this.cols, newCols);
        for (int r = 0; r < copyRows; r++) {
            System.arraycopy(this.cells[r], 0, newCells[r], 0, copyCols);
            System.arraycopy(this.fgColors[r], 0, newFg[r], 0, copyCols);
            System.arraycopy(this.bgColors[r], 0, newBg[r], 0, copyCols);
        }

        this.cells = newCells;
        this.fgColors = newFg;
        this.bgColors = newBg;
        this.cols = newCols;
        this.rows = newRows;
        this.cursorX = Math.min(this.cols - 1, this.cursorX);
        this.cursorY = Math.min(this.rows - 1, this.cursorY);
    }

    public synchronized int getCols() {
        return cols;
    }

    public synchronized int getRows() {
        return rows;
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

    public synchronized String[][] getRawCells() {
        return cells;
    }

    public synchronized int[][] getRawFg() {
        return fgColors;
    }

    public synchronized int[][] getRawBg() {
        return bgColors;
    }

    public synchronized String getCell(int r, int c) {
        if (r < 0 || r >= rows || c < 0 || c >= cols) return " ";
        String s = cells[r][c];
        return s != null ? s : " ";
    }

    public synchronized char getChar(int r, int c) {
        String s = getCell(r, c);
        return s.isEmpty() ? ' ' : s.charAt(0);
    }

    public synchronized int getFg(int r, int c) {
        if (r < 0 || r >= rows || c < 0 || c >= cols) return DEFAULT_FG;
        return fgColors[r][c];
    }

    public synchronized int getBg(int r, int c) {
        if (r < 0 || r >= rows || c < 0 || c >= cols) return DEFAULT_BG;
        return bgColors[r][c];
    }

    public synchronized void write(String text) {
        if (text == null || text.isEmpty()) return;
        int len = text.length();
        for (int i = 0; i < len; ) {
            int cp = text.codePointAt(i);
            int count = Character.charCount(cp);
            i += count;

            if (inOsc) {
                // OSC command (like set title \033]...\007 or \033]...\033\)
                if (cp == 7 || cp == '\007') { // BEL
                    inOsc = false;
                    inEscape = false;
                } else if (cp == '\033') {
                    inOsc = false;
                    inEscape = true;
                }
                continue;
            }

            if (inEscape) {
                if (cp == '[') {
                    inCsi = true;
                    csiParams.setLength(0);
                } else if (cp == ']') {
                    inOsc = true;
                } else if (inCsi) {
                    if ((cp >= '0' && cp <= '9') || cp == ';' || cp == '?' || cp == '=' || cp == '$' || cp == ' ') {
                        csiParams.appendCodePoint(cp);
                    } else {
                        // Execution character
                        handleCsi((char) cp, csiParams.toString());
                        inEscape = false;
                        inCsi = false;
                    }
                } else {
                    // Escape single char commands
                    if (cp == 'c') { // RIS: Reset
                        clear();
                    }
                    inEscape = false;
                }
                continue;
            }

            if (cp == '\033') { // ESC
                inEscape = true;
                inCsi = false;
                inOsc = false;
                continue;
            }

            if (cp == '\r') {
                cursorX = 0;
            } else if (cp == '\n') {
                newLine();
            } else if (cp == '\b') {
                cursorX = Math.max(0, cursorX - 1);
            } else if (cp == '\t') {
                cursorX = Math.min(cols - 1, (cursorX + 8) & ~7);
            } else if (cp >= 32) {
                if (cursorX >= cols) {
                    cursorX = 0;
                    newLine();
                }
                cells[cursorY][cursorX] = new String(Character.toChars(cp));
                fgColors[cursorY][cursorX] = currentFg;
                bgColors[cursorY][cursorX] = currentBg;
                cursorX++;
            }
        }
    }

    private void newLine() {
        if (cursorY < rows - 1) {
            cursorY++;
        } else {
            // Scroll up
            for (int r = 0; r < rows - 1; r++) {
                System.arraycopy(cells[r + 1], 0, cells[r], 0, cols);
                System.arraycopy(fgColors[r + 1], 0, fgColors[r], 0, cols);
                System.arraycopy(bgColors[r + 1], 0, bgColors[r], 0, cols);
            }
            Arrays.fill(cells[rows - 1], " ");
            Arrays.fill(fgColors[rows - 1], DEFAULT_FG);
            Arrays.fill(bgColors[rows - 1], DEFAULT_BG);
        }
    }

    private void handleCsi(char cmd, String params) {
        String[] parts = params.split(";");
        int defP = (cmd == 'K' || cmd == 'J' || cmd == 'm') ? 0 : 1;
        int p1 = (parts.length > 0 && !parts[0].isEmpty()) ? parseSafe(parts[0], defP) : defP;
        int p2 = (parts.length > 1 && !parts[1].isEmpty()) ? parseSafe(parts[1], 1) : 1;

        switch (cmd) {
            case 'H': // Cursor position (row, col)
            case 'f':
                cursorY = Math.max(0, Math.min(rows - 1, p1 - 1));
                cursorX = Math.max(0, Math.min(cols - 1, p2 - 1));
                break;
            case 'A': // Cursor Up
                cursorY = Math.max(0, cursorY - p1);
                break;
            case 'B': // Cursor Down
                cursorY = Math.min(rows - 1, cursorY + p1);
                break;
            case 'C': // Cursor Forward
                cursorX = Math.min(cols - 1, cursorX + p1);
                break;
            case 'D': // Cursor Back
                cursorX = Math.max(0, cursorX - p1);
                break;
            case 'G': // Cursor to column
                cursorX = Math.max(0, Math.min(cols - 1, p1 - 1));
                break;
            case 'd': // Cursor to row
                cursorY = Math.max(0, Math.min(rows - 1, p1 - 1));
                break;
            case 'J': // Erase in display
                if (p1 == 2 || p1 == 3) {
                    clear();
                } else if (p1 == 0) {
                    // Erase from cursor to end
                    for (int c = cursorX; c < cols; c++) eraseCell(cursorY, c);
                    for (int r = cursorY + 1; r < rows; r++) {
                        Arrays.fill(cells[r], " ");
                        Arrays.fill(fgColors[r], DEFAULT_FG);
                        Arrays.fill(bgColors[r], DEFAULT_BG);
                    }
                }
                break;
            case 'K': // Erase in line
                if (p1 == 0) {
                    for (int c = cursorX; c < cols; c++) eraseCell(cursorY, c);
                } else if (p1 == 1) {
                    for (int c = 0; c <= cursorX; c++) eraseCell(cursorY, c);
                } else if (p1 == 2) {
                    for (int c = 0; c < cols; c++) eraseCell(cursorY, c);
                }
                break;
            case 'm': // SGR Color / Formatting
                handleSgr(parts);
                break;
            case 't': // Window manipulation (e.g. 8;rows;cols: resize window)
                if (parts.length >= 3 && "8".equals(parts[0])) {
                    int r = parseSafe(parts[1], rows);
                    int c = parseSafe(parts[2], cols);
                    resize(c, r);
                }
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
        if (r >= 0 && r < rows && c >= 0 && c < cols) {
            cells[r][c] = " ";
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
                currentFg = get256Color(c256);
                i += 2;
            } else if (code == 38 && i + 4 < parts.length && "2".equals(parts[i + 1])) {
                // 24-bit RGB TrueColor: 38;2;r;g;b
                int r = Math.max(0, Math.min(255, parseSafe(parts[i + 2], 255)));
                int g = Math.max(0, Math.min(255, parseSafe(parts[i + 3], 255)));
                int b = Math.max(0, Math.min(255, parseSafe(parts[i + 4], 255)));
                currentFg = 0xFF000000 | (r << 16) | (g << 8) | b;
                i += 4;
            } else if (code == 48 && i + 2 < parts.length && "5".equals(parts[i + 1])) {
                int c256 = parseSafe(parts[i + 2], 0);
                currentBg = get256Color(c256);
                i += 2;
            } else if (code == 48 && i + 4 < parts.length && "2".equals(parts[i + 1])) {
                // 24-bit RGB TrueColor: 48;2;r;g;b
                int r = Math.max(0, Math.min(255, parseSafe(parts[i + 2], 0)));
                int g = Math.max(0, Math.min(255, parseSafe(parts[i + 3], 0)));
                int b = Math.max(0, Math.min(255, parseSafe(parts[i + 4], 0)));
                currentBg = 0xFF000000 | (r << 16) | (g << 8) | b;
                i += 4;
            }
        }
    }

    public static int get256Color(int c) {
        if (c < 0) return DEFAULT_FG;
        if (c < 16) return ANSI_PALETTE[c];
        if (c <= 231) {
            int n = c - 16;
            int r = (n / 36) * 51;
            int g = ((n % 36) / 6) * 51;
            int b = (n % 6) * 51;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        if (c <= 255) {
            int gray = 8 + (c - 232) * 10;
            return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }
        return DEFAULT_FG;
    }

    private int parseSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
