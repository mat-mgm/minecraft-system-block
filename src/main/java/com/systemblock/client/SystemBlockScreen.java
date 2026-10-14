package com.systemblock.client;

import com.mojang.blaze3d.platform.InputConstants;
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
    private final StringBuilder commandLogs = new StringBuilder();
    private final TerminalGrid terminalGrid = new TerminalGrid();

    // Widgets
    private MultiLineEditBox consoleView; // Used in Single Command mode
    private EditBox commandInput;
    private Button startRestartButton;
    private Button sigintButton;
    private Button clearButton;
    private Button zoomButton;
    private Button modeToggleButton;
    private Button doneButton;
    private static float fontScale = 0.65f;

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
        String initialCmd = blockEntity.getCommandLogs();
        if (initialCmd != null && !initialCmd.isEmpty()) {
            this.commandLogs.append(initialCmd);
        }
        String initialTerm = blockEntity.getTerminalLogs();
        if (initialTerm != null && !initialTerm.isEmpty()) {
            this.terminalGrid.write(initialTerm);
        }
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public void appendTerminalLog(String targetMode, String text, boolean append) {
        if ("COMMAND".equals(targetMode)) {
            if (!append) {
                this.commandLogs.setLength(0);
            }
            this.commandLogs.append(text);
            int maxChars = 65536;
            if (this.commandLogs.length() > maxChars) {
                this.commandLogs.delete(0, this.commandLogs.length() - maxChars);
            }
            if (this.consoleView != null) {
                this.consoleView.setValue(this.commandLogs.toString());
            }
        } else if ("TERMINAL".equals(targetMode)) {
            if (!append) {
                this.terminalGrid.clear();
            }
            this.terminalGrid.write(text);
        }
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.inputWidth = Math.max(320, this.width - 24);
        this.startX = centerX - inputWidth / 2;

        this.terminalY = 22;
        int totalBottomReserved = 32; // Single row of bottom buttons (20px) + margins
        this.terminalHeight = Math.max(70, this.height - terminalY - totalBottomReserved - 8);

        // Bottom Line: [ Mode ] [ ⏹ Stop ] [ Zoom: 0.65x ] [ Clear ] [ Done ]
        int bottomY = this.height - 28;
        int gap = 4;
        int btnWidth = (inputWidth - (gap * 4)) / 5;

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
        this.consoleView.setValue(this.commandLogs.toString());
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
                    ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "COMMAND", "START"));
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
                    ClientPlayNetworking.send(new TerminalControlPayload(blockPos, this.currentMode, "TOGGLE_MODE"));
                    if ("TERMINAL".equals(this.currentMode)) {
                        ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "TERMINAL", "START"));
                        adaptTerminalDimensions();
                    }
                }
        ).bounds(startX, bottomY, btnWidth, 20).build());

        // Stop button
        this.sigintButton = this.addRenderableWidget(Button.builder(
                Component.literal("⏹ Stop"),
                button -> ClientPlayNetworking.send(new TerminalControlPayload(blockPos, currentMode, "INTERRUPT"))
        ).bounds(startX + (btnWidth + gap), bottomY, btnWidth, 20).build());

        // Zoom button: cycling through multiple sub-scale modes down to 0.25x for massive terminal applications
        this.zoomButton = this.addRenderableWidget(Button.builder(
                Component.literal("Zoom: " + fontScale + "x"),
                button -> {
                    if (fontScale == 0.65f) fontScale = 0.5f;
                    else if (fontScale == 0.5f) fontScale = 0.4f;
                    else if (fontScale == 0.4f) fontScale = 0.3f;
                    else if (fontScale == 0.3f) fontScale = 0.25f;
                    else if (fontScale == 0.25f) fontScale = 1.0f;
                    else if (fontScale == 1.0f) fontScale = 0.8f;
                    else fontScale = 0.65f;
                    button.setMessage(Component.literal("Zoom: " + fontScale + "x"));
                    adaptTerminalDimensions();
                }
        ).bounds(startX + (btnWidth + gap) * 2, bottomY, btnWidth, 20).build());

        // Clear button
        this.clearButton = this.addRenderableWidget(Button.builder(
                Component.literal("Clear"),
                button -> {
                    if ("COMMAND".equals(currentMode)) {
                        this.commandLogs.setLength(0);
                        if (this.consoleView != null) this.consoleView.setValue("");
                        ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "COMMAND", "CLEAR_COMMAND"));
                    } else {
                        this.terminalGrid.clear();
                        ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "TERMINAL", "CLEAR_TERMINAL"));
                    }
                }
        ).bounds(startX + (btnWidth + gap) * 3, bottomY, btnWidth, 20).build());

        // Done button
        this.doneButton = this.addRenderableWidget(Button.builder(
                CommonComponents.GUI_DONE,
                button -> {
                    saveCommand();
                    onClose();
                }
        ).bounds(startX + (btnWidth + gap) * 4, bottomY, inputWidth - (btnWidth + gap) * 4, 20).build());

        updateModeVisibility();

        // If in Terminal mode, ensure server starts/connects to the interactive host shell and adapts grid dimensions
        if ("TERMINAL".equals(currentMode)) {
            ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "TERMINAL", "START"));
            if (this.minecraft != null && this.minecraft.textInputManager() != null) {
                this.minecraft.textInputManager().startTextInput(this);
            }
            adaptTerminalDimensions();
        }

        // Ensure keyboard focus defaults to command input bar when entering Command mode
        if ("COMMAND".equals(currentMode)) {
            this.setInitialFocus(this.commandInput);
        }
    }

    private void adaptTerminalDimensions() {
        if ("TERMINAL".equals(currentMode)) {
            float virtualW = (inputWidth - 8) / fontScale;
            float virtualH = (terminalHeight - 6) / fontScale;
            int targetCols = Math.max(80, Math.min(260, (int) (virtualW / 6)));
            int targetRows = Math.max(24, Math.min(100, (int) (virtualH / 9)));
            terminalGrid.resize(targetCols, targetRows);
            sendTerminalInput("\033[8;" + targetRows + ";" + targetCols + "t");
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
        if (this.zoomButton != null) this.zoomButton.visible = !isCmd;
        if (!isCmd) {
            this.setFocused(null);
            if (this.minecraft != null && this.minecraft.textInputManager() != null) {
                this.minecraft.textInputManager().startTextInput(this);
            }
        } else {
            if (this.minecraft != null && this.minecraft.textInputManager() != null) {
                this.minecraft.textInputManager().stopTextInput(this);
            }
        }
    }

    private void sendTerminalInput(String str) {
        if (str != null && !str.isEmpty()) {
            ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "TERMINAL", str, true));
        }
    }

    private void saveCommand() {
        String cmd = this.commandInput.getValue();
        this.blockEntity.setCommand(cmd);
        ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "COMMAND", cmd, false));
    }

    @Override
    public void removed() {
        super.removed();
        if (this.minecraft != null && this.minecraft.textInputManager() != null) {
            this.minecraft.textInputManager().stopTextInput(this);
        }
    }

    @Override
    public boolean isInputCaptured() {
        return "TERMINAL".equals(currentMode) || super.isInputCaptured();
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if ("TERMINAL".equals(currentMode)) {
            String str = event.codepointAsString();
            if (str != null && !str.isEmpty()) {
                sendTerminalInput(str);
                return true;
            }
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent keyEvent) {
        int key = keyEvent.key();
        int shortcut = keyEvent.shortcutKey();

        // In Terminal mode: handle direct terminal input and keys
        if ("TERMINAL".equals(currentMode)) {
            if (keyEvent.isEscape() || key == InputConstants.KEY_ESCAPE || shortcut == 27) { // Escape: exit GUI
                this.onClose();
                return true;
            }

            // Clipboard paste (Ctrl+V)
            if (keyEvent.isPaste() || (keyEvent.hasControlDown() && (shortcut == InputConstants.KEYCODE_V || key == InputConstants.KEY_V || shortcut == 'v' || shortcut == 'V'))) {
                if (this.minecraft != null) {
                    String clip = this.minecraft.keyboardHandler.getClipboard();
                    if (clip != null && !clip.isEmpty()) {
                        sendTerminalInput(clip);
                    }
                }
                return true;
            }

            // Ctrl+[A-Z] combinations (e.g. Ctrl+C for SIGINT, Ctrl+D for EOF, Ctrl+L for clear)
            if (keyEvent.hasControlDown()) {
                char ctrlChar = 0;
                if (shortcut >= 'a' && shortcut <= 'z') {
                    ctrlChar = (char) (shortcut - 'a' + 1);
                } else if (shortcut >= 'A' && shortcut <= 'Z') {
                    ctrlChar = (char) (shortcut - 'A' + 1);
                } else if (shortcut >= 1 && shortcut <= 26) {
                    ctrlChar = (char) shortcut;
                }
                if (ctrlChar > 0) {
                    sendTerminalInput(String.valueOf(ctrlChar));
                    return true;
                }
            }

            // Enter / Return / Keypad Enter
            if (shortcut == InputConstants.KEYCODE_RETURN || shortcut == InputConstants.KEYCODE_NUMPADENTER
                    || key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER
                    || shortcut == 13 || shortcut == 10 || key == 257 || key == 335) {
                sendTerminalInput("\r");
                return true;
            }

            // Backspace
            if (shortcut == InputConstants.KEYCODE_BACKSPACE || key == InputConstants.KEY_BACKSPACE || shortcut == 8 || key == 259) {
                sendTerminalInput("\u007F"); // ASCII 127 DEL
                return true;
            }

            // Tab
            if (shortcut == InputConstants.KEYCODE_TAB || key == InputConstants.KEY_TAB || shortcut == 9 || key == 258) {
                sendTerminalInput("\t");
                return true;
            }

            // Delete
            if (shortcut == InputConstants.KEYCODE_DELETE || key == InputConstants.KEY_DELETE || shortcut == 127 || key == 261) {
                sendTerminalInput("\033[3~");
                return true;
            }

            // Arrow keys
            if (shortcut == InputConstants.KEYCODE_UP || key == InputConstants.KEY_UP || key == 265) {
                sendTerminalInput("\033[A");
                return true;
            } else if (shortcut == InputConstants.KEYCODE_DOWN || key == InputConstants.KEY_DOWN || key == 264) {
                sendTerminalInput("\033[B");
                return true;
            } else if (shortcut == InputConstants.KEYCODE_RIGHT || key == InputConstants.KEY_RIGHT || key == 262) {
                sendTerminalInput("\033[C");
                return true;
            } else if (shortcut == InputConstants.KEYCODE_LEFT || key == InputConstants.KEY_LEFT || key == 263) {
                sendTerminalInput("\033[D");
                return true;
            }

            // Home / End
            if (shortcut == InputConstants.KEYCODE_HOME || key == InputConstants.KEY_HOME || key == 268) {
                sendTerminalInput("\033[H");
                return true;
            } else if (shortcut == InputConstants.KEYCODE_END || key == InputConstants.KEY_END || key == 269) {
                sendTerminalInput("\033[F");
                return true;
            }

            // Page Up / Page Down
            if (shortcut == InputConstants.KEYCODE_PAGEUP || key == InputConstants.KEY_PAGEUP || key == 266) {
                sendTerminalInput("\033[5~");
                return true;
            } else if (shortcut == InputConstants.KEYCODE_PAGEDOWN || key == InputConstants.KEY_PAGEDOWN || key == 267) {
                sendTerminalInput("\033[6~");
                return true;
            }

            // Consume all other keys in Terminal mode to prevent unwanted game interaction
            return true;
        }

        // Single Command mode input handling
        if ("COMMAND".equals(currentMode)) {
            if (shortcut == InputConstants.KEYCODE_RETURN || shortcut == InputConstants.KEYCODE_NUMPADENTER
                    || key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER
                    || shortcut == 13 || shortcut == 10 || key == 257 || key == 335) {
                if (this.commandInput != null && this.commandInput.isFocused()) {
                    String cmd = this.commandInput.getValue();
                    if (!cmd.isEmpty()) {
                        saveCommand();
                        ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "COMMAND", "START"));
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
                if (this.minecraft != null && this.minecraft.textInputManager() != null) {
                    this.minecraft.textInputManager().startTextInput(this);
                }
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

        // Apply matrix sub-scaling for crisp, spacious terminal layout
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(bgX + 4, bgY + 3);
            graphics.pose().scale(fontScale, fontScale);

            int lineHeight = this.font.lineHeight; // 9
            float virtualH = (bgH - 6) / fontScale;
            float virtualW = (bgW - 8) / fontScale;
            int spaceW = Math.max(1, this.font.width(" "));

            synchronized (terminalGrid) {
                int gridCols = terminalGrid.getCols();
                int gridRows = terminalGrid.getRows();
                int curY = terminalGrid.getCursorY();
                int curX = terminalGrid.getCursorX();
                boolean cursorVisible = terminalGrid.isCursorVisible();
                boolean showBlinkCursor = cursorVisible && (System.currentTimeMillis() % 1000 < 500);

                int maxVisibleRows = Math.min(gridRows, Math.max(1, (int) (virtualH / lineHeight)));
                int startRow = 0;
                if (curY >= maxVisibleRows) {
                    startRow = Math.min(gridRows - maxVisibleRows, curY - maxVisibleRows + 1);
                }

                String[][] cells = terminalGrid.getRawCells();
                int[][] fgColors = terminalGrid.getRawFg();
                int[][] bgColors = terminalGrid.getRawBg();

                for (int r = startRow; r < gridRows; r++) {
                    int drawY = (r - startRow) * lineHeight;
                    if (drawY + lineHeight > virtualH) break;

                    String[] rowCells = (cells != null && r < cells.length) ? cells[r] : null;
                    int[] rowFg = (fgColors != null && r < fgColors.length) ? fgColors[r] : null;
                    int[] rowBg = (bgColors != null && r < bgColors.length) ? bgColors[r] : null;

                    if (rowCells == null || rowFg == null || rowBg == null) continue;

                    // Find the last column that has actual visual content (character or background)
                    int lastActiveCol = -1;
                    for (int c = Math.min(gridCols, rowCells.length) - 1; c >= 0; c--) {
                        int bg = rowBg[c];
                        String cell = rowCells[c];
                        if ((bg & 0xFF000000) != 0 || (cell != null && !cell.equals(" "))) {
                            lastActiveCol = c;
                            break;
                        }
                    }

                    boolean isCursorRow = (r == curY && showBlinkCursor && curX >= 0 && curX < gridCols);

                    // If row has no text/background content and no cursor, skip it entirely (0 GPU calls)
                    if (lastActiveCol == -1 && !isCursorRow) {
                        continue;
                    }

                    int renderLimitCol = Math.max(lastActiveCol, isCursorRow ? curX : -1);

                    // Pass 1: Contiguous background run batching
                    int currentX = 0;
                    int spanStartX = 0;
                    int currentSpanBg = 0;

                    for (int c = 0; c <= renderLimitCol && currentX < virtualW; c++) {
                        String cell = (c < rowCells.length) ? rowCells[c] : " ";
                        int cellW = (cell == null || cell.equals(" ")) ? spaceW : Math.max(1, this.font.width(cell));
                        int bg = (c < rowBg.length) ? rowBg[c] : 0;
                        boolean hasBg = (bg & 0xFF000000) != 0;

                        if (hasBg) {
                            if ((currentSpanBg & 0xFF000000) == 0) {
                                currentSpanBg = bg;
                                spanStartX = currentX;
                            } else if (bg != currentSpanBg) {
                                if (currentX > spanStartX) {
                                    graphics.fill(spanStartX, drawY, currentX, drawY + lineHeight, currentSpanBg);
                                }
                                currentSpanBg = bg;
                                spanStartX = currentX;
                            }
                        } else {
                            if ((currentSpanBg & 0xFF000000) != 0) {
                                if (currentX > spanStartX) {
                                    graphics.fill(spanStartX, drawY, currentX, drawY + lineHeight, currentSpanBg);
                                }
                                currentSpanBg = 0;
                            }
                        }
                        currentX += cellW;
                    }
                    if ((currentSpanBg & 0xFF000000) != 0 && currentX > spanStartX) {
                        graphics.fill(spanStartX, drawY, currentX, drawY + lineHeight, currentSpanBg);
                    }

                    // Pass 2: Foreground text batching by color
                    currentX = 0;
                    StringBuilder chunkSb = new StringBuilder();
                    int chunkStartX = 0;
                    int currentChunkFg = -1;
                    int cursorVirtualX = -1;
                    int cursorVirtualW = spaceW;

                    for (int c = 0; c <= renderLimitCol && currentX < virtualW; c++) {
                        String cell = (c < rowCells.length) ? rowCells[c] : " ";
                        int cellW = (cell == null || cell.equals(" ")) ? spaceW : Math.max(1, this.font.width(cell));
                        int fg = (c < rowFg.length) ? rowFg[c] : TerminalGrid.DEFAULT_FG;

                        if (isCursorRow && c == curX) {
                            cursorVirtualX = currentX;
                            cursorVirtualW = Math.max(5, cellW);
                        }

                        if (c <= lastActiveCol) {
                            if (currentChunkFg == -1) {
                                currentChunkFg = fg;
                                chunkStartX = currentX;
                            } else if (fg != currentChunkFg) {
                                if (chunkSb.length() > 0) {
                                    graphics.text(this.font, chunkSb.toString(), chunkStartX, drawY, currentChunkFg, false);
                                    chunkSb.setLength(0);
                                }
                                currentChunkFg = fg;
                                chunkStartX = currentX;
                            }
                            chunkSb.append(cell != null ? cell : " ");
                        } else {
                            if (chunkSb.length() > 0) {
                                graphics.text(this.font, chunkSb.toString(), chunkStartX, drawY, currentChunkFg != -1 ? currentChunkFg : TerminalGrid.DEFAULT_FG, false);
                                chunkSb.setLength(0);
                                currentChunkFg = -1;
                            }
                        }

                        currentX += cellW;
                    }

                    if (chunkSb.length() > 0) {
                        graphics.text(this.font, chunkSb.toString(), chunkStartX, drawY, currentChunkFg != -1 ? currentChunkFg : TerminalGrid.DEFAULT_FG, false);
                    }

                    // Pass 3: Draw cursor if on this row
                    if (isCursorRow && cursorVirtualX != -1 && cursorVirtualX + cursorVirtualW <= virtualW) {
                        graphics.fill(cursorVirtualX, drawY, cursorVirtualX + cursorVirtualW, drawY + lineHeight, 0xFF00FF88);
                    }
                }
            }
        } finally {
            graphics.pose().popMatrix();
            graphics.disableScissor();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isInGameUi() {
        return false;
    }
}
