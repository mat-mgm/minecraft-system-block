package com.systemblock.client;

import com.systemblock.SystemBlockEntity;
import com.systemblock.TerminalControlPayload;
import com.systemblock.TerminalInputPayload;
import com.systemblock.terminal.TerminalGrid;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class SystemBlockScreen extends Screen {
    private static final Component TITLE = Component.literal("System Block - Terminal & Automation");

    private final SystemBlockEntity blockEntity;
    private final BlockPos blockPos;

    private String currentMode; // "COMMAND" or "TERMINAL"
    private final StringBuilder liveLogs = new StringBuilder();
    private final TerminalGrid terminalGrid = new TerminalGrid();

    // Widgets
    private MultiLineEditBox consoleView; // Used in Single Command mode
    private EditBox commandInput;
    private Button startRestartButton;
    private Button sigintButton;
    private Button clearButton;
    private Button modeToggleButton;
    private Button doneButton;

    // Dimensions
    private int startX;
    private int inputWidth;
    private int terminalY;
    private int terminalHeight;

    public SystemBlockScreen(SystemBlockEntity blockEntity) {
        super(TITLE);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.currentMode = blockEntity.getMode();
        String initial = blockEntity.getAllLogs();
        this.liveLogs.append(initial);
        this.terminalGrid.write(initial);
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public void appendTerminalLog(String text, boolean append) {
        if (!append) {
            this.liveLogs.setLength(0);
            this.terminalGrid.clear();
        }
        this.liveLogs.append(text);
        this.terminalGrid.write(text);
        if (this.consoleView != null && "COMMAND".equals(currentMode)) {
            this.consoleView.setValue(this.liveLogs.toString());
        }
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.inputWidth = Math.max(320, Math.min(580, this.width - 24));
        this.startX = centerX - inputWidth / 2;

        this.terminalY = 24;
        int totalBottomReserved = 32; // Single row of bottom buttons (20px) + margins
        this.terminalHeight = Math.max(70, this.height - terminalY - totalBottomReserved - 10);

        // Bottom Line: [ Mode ] [ ⏹ Stop ] [ Clear Logs ] [ Done ]
        int bottomY = this.height - 28;
        int gap = 4;
        int btnWidth = (inputWidth - (gap * 3)) / 4;

        // Command Mode: Command input bar + Run button sitting directly above bottom buttons
        int inputY = bottomY - 26;
        int runBtnWidth = 46;
        int cmdInputWidth = inputWidth - runBtnWidth - 4;

        // In Command mode, the console view occupies space between terminalY and inputY (non-overlapping)
        int consoleHeight = Math.max(50, inputY - terminalY - 4);

        // Row 1: Console View (for Single Command mode)
        this.consoleView = MultiLineEditBox.builder()
                .setX(startX)
                .setY(terminalY)
                .setTextColor(0xFF00FF88) // Matrix / Emerald green
                .setShowDecorations(false)
                .build(this.font, inputWidth, consoleHeight, Component.literal("Console"));
        this.consoleView.setValue(this.liveLogs.toString());
        this.consoleView.visible = "COMMAND".equals(currentMode);
        this.addRenderableWidget(this.consoleView);

        // Command bar widgets
        this.commandInput = new EditBox(this.font, startX, inputY, cmdInputWidth, 20, Component.literal("Command"));
        this.commandInput.setMaxLength(4096);
        this.commandInput.setValue(this.blockEntity.getCommand());
        this.commandInput.visible = "COMMAND".equals(currentMode);
        this.addRenderableWidget(this.commandInput);

        this.startRestartButton = this.addRenderableWidget(Button.builder(
                Component.literal("▶ Run"),
                button -> {
                    saveCommand();
                    ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "START"));
                }
        ).bounds(startX + cmdInputWidth + 4, inputY, runBtnWidth, 20).build());
        this.startRestartButton.visible = "COMMAND".equals(currentMode);

        // Mode button
        this.modeToggleButton = this.addRenderableWidget(Button.builder(
                Component.literal("Mode: " + ("TERMINAL".equals(currentMode) ? "Terminal" : "Command")),
                button -> {
                    this.currentMode = "TERMINAL".equals(this.currentMode) ? "COMMAND" : "TERMINAL";
                    button.setMessage(Component.literal("Mode: " + ("TERMINAL".equals(currentMode) ? "Terminal" : "Command")));
                    updateModeVisibility();
                    ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "TOGGLE_MODE"));
                    if ("TERMINAL".equals(this.currentMode)) {
                        ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "START"));
                    }
                }
        ).bounds(startX, bottomY, btnWidth, 20).build());

        // Stop button
        this.sigintButton = this.addRenderableWidget(Button.builder(
                Component.literal("⏹ Stop"),
                button -> ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "INTERRUPT"))
        ).bounds(startX + (btnWidth + gap), bottomY, btnWidth, 20).build());

        // Clear button
        this.clearButton = this.addRenderableWidget(Button.builder(
                Component.literal("Clear"),
                button -> {
                    this.liveLogs.setLength(0);
                    this.terminalGrid.clear();
                    if (this.consoleView != null) this.consoleView.setValue("");
                    ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "CLEAR"));
                }
        ).bounds(startX + (btnWidth + gap) * 2, bottomY, btnWidth, 20).build());

        // Done button
        this.doneButton = this.addRenderableWidget(Button.builder(
                CommonComponents.GUI_DONE,
                button -> {
                    saveCommand();
                    onClose();
                }
        ).bounds(startX + (btnWidth + gap) * 3, bottomY, inputWidth - (btnWidth + gap) * 3, 20).build());

        updateModeVisibility();

        // If in Terminal mode, ensure server starts/connects to the interactive host shell
        if ("TERMINAL".equals(currentMode)) {
            ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "START"));
        }

        // Ensure keyboard focus defaults to command input bar when entering Command mode
        if ("COMMAND".equals(currentMode)) {
            this.setInitialFocus(this.commandInput);
        }
    }

    private void updateModeVisibility() {
        boolean isCmd = "COMMAND".equals(currentMode);
        if (this.consoleView != null) this.consoleView.visible = isCmd;
        if (this.commandInput != null) {
            this.commandInput.visible = isCmd;
            if (isCmd) {
                this.setFocused(this.commandInput);
            }
        }
        if (this.startRestartButton != null) this.startRestartButton.visible = isCmd;
        if (!isCmd) {
            this.setFocused(null);
        }
    }

    private void saveCommand() {
        String cmd = this.commandInput.getValue();
        this.blockEntity.setCommand(cmd);
        ClientPlayNetworking.send(new TerminalInputPayload(blockPos, cmd, false));
    }

    private long lastKeyTime = 0;
    private String lastKeyChar = null;

    private static String getCharForKeyEvent(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        boolean shift = event.hasShiftDown();

        // Letters A-Z
        if (key >= 65 && key <= 90) {
            char c = (char) key;
            return shift ? String.valueOf(c) : String.valueOf(Character.toLowerCase(c));
        }

        // Numbers 0-9
        if (key >= 48 && key <= 57) {
            if (!shift) {
                return String.valueOf((char) key);
            } else {
                return switch (key) {
                    case 49 -> "!";
                    case 50 -> "@";
                    case 51 -> "#";
                    case 52 -> "$";
                    case 53 -> "%";
                    case 54 -> "^";
                    case 55 -> "&";
                    case 56 -> "*";
                    case 57 -> "(";
                    case 48 -> ")";
                    default -> "";
                };
            }
        }

        // Numpad 0-9
        if (key >= 320 && key <= 329) {
            return String.valueOf((char) ('0' + (key - 320)));
        }

        // Space
        if (key == 32) return " ";

        // Standard Symbols
        return switch (key) {
            case 45 -> shift ? "_" : "-"; // Minus
            case 61 -> shift ? "+" : "="; // Equals
            case 91 -> shift ? "{" : "["; // Left bracket
            case 93 -> shift ? "}" : "]"; // Right bracket
            case 59 -> shift ? ":" : ";"; // Semicolon
            case 39 -> shift ? "\"" : "'"; // Apostrophe
            case 44 -> shift ? "<" : ","; // Comma
            case 46 -> shift ? ">" : "."; // Period
            case 47 -> shift ? "?" : "/"; // Slash
            case 92 -> shift ? "|" : "\\"; // Backslash
            case 96 -> shift ? "~" : "`"; // Grave accent
            case 330 -> "."; // Numpad decimal
            case 331 -> "/"; // Numpad divide
            case 332 -> "*"; // Numpad multiply
            case 333 -> "-"; // Numpad subtract
            case 334 -> "+"; // Numpad add
            default -> null;
        };
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if ("TERMINAL".equals(currentMode)) {
            String str = event.codepointAsString();
            if (str != null && !str.isEmpty()) {
                if (!(str.equals(lastKeyChar) && (System.currentTimeMillis() - lastKeyTime < 80))) {
                    ClientPlayNetworking.send(new TerminalInputPayload(blockPos, str, true));
                }
                return true;
            }
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent keyEvent) {
        int key = keyEvent.key();

        // In Terminal mode: handle direct terminal input and keys
        if ("TERMINAL".equals(currentMode)) {
            if (keyEvent.isEscape() || key == 256) { // Escape: exit GUI
                this.onClose();
                return true;
            }

            // Clipboard paste (Ctrl+V)
            if (keyEvent.isPaste() || (keyEvent.hasControlDown() && key == 86)) {
                if (this.minecraft != null) {
                    String clip = this.minecraft.keyboardHandler.getClipboard();
                    if (clip != null && !clip.isEmpty()) {
                        ClientPlayNetworking.send(new TerminalInputPayload(blockPos, clip, true));
                    }
                }
                return true;
            }

            // Ctrl+[A-Z] combinations
            if (keyEvent.hasControlDown() && key >= 65 && key <= 90) {
                char ctrlChar = (char) (key - 64);
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, String.valueOf(ctrlChar), true));
                return true;
            }

            if (key == 265) { // Up Arrow
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[A", true));
                return true;
            } else if (key == 264) { // Down Arrow
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[B", true));
                return true;
            } else if (key == 262) { // Right Arrow
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[C", true));
                return true;
            } else if (key == 263) { // Left Arrow
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[D", true));
                return true;
            } else if (key == 258) { // Tab
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\t", true));
                return true;
            } else if (key == 259) { // Backspace (ASCII 127 DEL)
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\u007F", true));
                return true;
            } else if (key == 261) { // Delete
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[3~", true));
                return true;
            } else if (key == 268) { // Home
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[H", true));
                return true;
            } else if (key == 269) { // End
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[F", true));
                return true;
            } else if (key == 266) { // Page Up
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[5~", true));
                return true;
            } else if (key == 267) { // Page Down
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[6~", true));
                return true;
            } else if (key == 257 || key == 335) { // Enter / Keypad Enter
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\r", true));
                return true;
            }

            // Printable letters, digits, symbols, space
            String ch = getCharForKeyEvent(keyEvent);
            if (ch != null) {
                lastKeyTime = System.currentTimeMillis();
                lastKeyChar = ch;
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, ch, true));
                return true;
            }

            // Block inventory key from closing the terminal
            if (this.minecraft != null && this.minecraft.options.keyInventory.matches(keyEvent)) {
                return true;
            }

            return true;
        }

        // Single Command mode input handling
        if ("COMMAND".equals(currentMode)) {
            if (key == 257 || key == 335) {
                if (this.commandInput != null && this.commandInput.isFocused()) {
                    String cmd = this.commandInput.getValue();
                    if (!cmd.isEmpty()) {
                        saveCommand();
                        ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "START"));
                        return true;
                    }
                }
            }
        }
        return super.keyPressed(keyEvent);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
        if ("TERMINAL".equals(currentMode)) {
            double mx = event.x();
            double my = event.y();
            if (mx >= startX && mx <= startX + inputWidth && my >= terminalY && my <= terminalY + terminalHeight) {
                // Click inside terminal screen: focus the terminal
                this.setFocused(null);
                return true;
            }
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        int centerX = this.width / 2;
        graphics.centeredText(this.font, TITLE, centerX, 10, 0xFF55FFAA);

        // If in Terminal mode: render the authentic 80x24 ANSI grid
        if ("TERMINAL".equals(currentMode)) {
            renderTuiGrid(graphics);
        }
    }

    private void renderTuiGrid(GuiGraphicsExtractor graphics) {
        // Draw terminal background box (CRT dark screen)
        int bgX = startX;
        int bgY = terminalY;
        int bgW = inputWidth;
        int bgH = terminalHeight;

        // Outer dark bezel & inner terminal screen
        graphics.fill(bgX - 2, bgY - 2, bgX + bgW + 2, bgY + bgH + 2, 0xFF22252A);
        graphics.fill(bgX, bgY, bgX + bgW, bgY + bgH, 0xFF0D1117);

        // Enable hardware scissoring to strictly prevent any text overflowing out of the terminal box
        graphics.enableScissor(bgX, bgY, bgX + bgW, bgY + bgH);

        // Render rows inside the terminal screen
        // Visible lines budget based on terminalHeight (9px per line in MC font)
        int lineHeight = this.font.lineHeight; // 9
        int maxVisibleRows = Math.min(TerminalGrid.ROWS, Math.max(1, bgH / lineHeight));

        // Ensure the cursor row is always visible within the rendered window
        int curY = terminalGrid.getCursorY();
        int startRow = 0;
        if (curY >= maxVisibleRows) {
            startRow = Math.min(TerminalGrid.ROWS - maxVisibleRows, curY - maxVisibleRows + 1);
        }

        for (int r = startRow; r < TerminalGrid.ROWS; r++) {
            int drawY = bgY + (r - startRow) * lineHeight;
            if (drawY + lineHeight > bgY + bgH) break;

            // Build line text and render with ANSI colors
            StringBuilder lineSb = new StringBuilder();
            int currentChunkFg = -1;
            int chunkStartX = bgX + 4;

            for (int c = 0; c < TerminalGrid.COLS; c++) {
                char ch = terminalGrid.getChar(r, c);
                int fg = terminalGrid.getFg(r, c);
                int bg = terminalGrid.getBg(r, c);

                int charW = this.font.width(String.valueOf(ch));
                // Stop rendering if reaching right boundary
                if (chunkStartX + this.font.width(lineSb.toString()) + charW > bgX + bgW - 4) {
                    break;
                }

                // If background color is specified, fill cell
                if ((bg & 0xFF000000) != 0) {
                    graphics.fill(chunkStartX + this.font.width(lineSb.toString()), drawY,
                            chunkStartX + this.font.width(lineSb.toString()) + Math.max(5, charW), drawY + lineHeight, bg);
                }

                if (currentChunkFg == -1) currentChunkFg = fg;

                if (fg != currentChunkFg && lineSb.length() > 0) {
                    graphics.text(this.font, lineSb.toString(), chunkStartX, drawY, currentChunkFg, false);
                    chunkStartX += this.font.width(lineSb.toString());
                    lineSb.setLength(0);
                    currentChunkFg = fg;
                }
                lineSb.append(ch);
            }

            if (lineSb.length() > 0) {
                graphics.text(this.font, lineSb.toString(), chunkStartX, drawY, currentChunkFg != -1 ? currentChunkFg : 0xFF00FF88, false);
            }

            // Blinking cursor
            if (terminalGrid.isCursorVisible() && r == curY && (System.currentTimeMillis() % 1000 < 500)) {
                int cX = terminalGrid.getCursorX();
                int cursorScreenX = bgX + 4;
                for (int c = 0; c < cX && c < TerminalGrid.COLS; c++) {
                    cursorScreenX += this.font.width(String.valueOf(terminalGrid.getChar(r, c)));
                }
                char atCursor = (cX >= 0 && cX < TerminalGrid.COLS) ? terminalGrid.getChar(r, cX) : ' ';
                int cursorW = Math.max(5, this.font.width(String.valueOf(atCursor)));
                if (cursorScreenX + cursorW <= bgX + bgW - 2) {
                    graphics.fill(cursorScreenX, drawY, cursorScreenX + cursorW, drawY + lineHeight, 0xFF00FF88);
                }
            }
        }

        graphics.disableScissor();
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }
}
