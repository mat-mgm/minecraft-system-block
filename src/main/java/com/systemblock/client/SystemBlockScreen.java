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

    private String currentMode; // "COMMAND" or "TERMINAL" (Live TUI)
    private boolean pulseOnSuccess;

    private final StringBuilder liveLogs = new StringBuilder();
    private final TerminalGrid terminalGrid = new TerminalGrid();

    // Widgets
    private MultiLineEditBox consoleView; // Used in Single Command mode
    private EditBox commandInput;
    private Button startRestartButton;
    private Button sigintButton;
    private Button clearButton;
    private Button pulseToggleButton;
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
        this.pulseOnSuccess = blockEntity.isPulseOnSuccess();
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
        this.inputWidth = Math.min(420, this.width - 24);
        this.startX = centerX - inputWidth / 2;

        this.terminalY = 24;
        int totalBottomReserved = 68; // 24 (input) + 4 + 20 (row 1 btns) + 4 + 20 (row 2 btns)
        this.terminalHeight = Math.max(70, this.height - terminalY - totalBottomReserved - 10);

        // Row 1: Console View (for Command mode)
        this.consoleView = MultiLineEditBox.builder()
                .setX(startX)
                .setY(terminalY)
                .setTextColor(0xFF00FF88) // Matrix / Emerald green
                .setShowDecorations(false) // Clean frame without character counter
                .build(this.font, inputWidth, terminalHeight, Component.literal("Console"));
        this.consoleView.setValue(this.liveLogs.toString());
        this.consoleView.visible = "COMMAND".equals(currentMode);
        this.addRenderableWidget(this.consoleView);

        // Row 2: Command / Stdin Input box
        int inputY = terminalY + terminalHeight + 6;
        int runBtnWidth = 46;
        int cmdInputWidth = inputWidth - runBtnWidth - 4;

        this.commandInput = new EditBox(this.font, startX, inputY, cmdInputWidth, 20, Component.literal("Command"));
        this.commandInput.setMaxLength(4096);
        this.commandInput.setValue(this.blockEntity.getCommand());
        this.addRenderableWidget(this.commandInput);
        this.setInitialFocus(this.commandInput);

        this.startRestartButton = this.addRenderableWidget(Button.builder(
                Component.literal("▶ Run"),
                button -> {
                    saveCommand();
                    ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "START"));
                }
        ).bounds(startX + cmdInputWidth + 4, inputY, runBtnWidth, 20).build());

        // Row 3: Utility & Setting toggles
        int row1Y = inputY + 24;
        int halfWidth = (inputWidth - 4) / 2;

        this.pulseToggleButton = this.addRenderableWidget(Button.builder(
                Component.literal("Redstone Pulse on Exit 0: " + (pulseOnSuccess ? "ON" : "OFF")),
                button -> {
                    this.pulseOnSuccess = !this.pulseOnSuccess;
                    button.setMessage(Component.literal("Redstone Pulse on Exit 0: " + (pulseOnSuccess ? "ON" : "OFF")));
                    ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "TOGGLE_PULSE"));
                }
        ).bounds(startX, row1Y, halfWidth, 20).build());

        // Mode: Live TUI Agent vs Single Command
        this.modeToggleButton = this.addRenderableWidget(Button.builder(
                Component.literal("Mode: " + ("TERMINAL".equals(currentMode) ? "Live TUI Agent" : "Single Command")),
                button -> {
                    this.currentMode = "TERMINAL".equals(this.currentMode) ? "COMMAND" : "TERMINAL";
                    button.setMessage(Component.literal("Mode: " + ("TERMINAL".equals(currentMode) ? "Live TUI Agent" : "Single Command")));
                    if (this.consoleView != null) {
                        this.consoleView.visible = "COMMAND".equals(this.currentMode);
                    }
                    ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "TOGGLE_MODE"));
                }
        ).bounds(startX + halfWidth + 4, row1Y, halfWidth, 20).build());

        // Row 4: Process controls & Close (Stop, Clear, Done)
        int row2Y = row1Y + 24;
        int btnW = (inputWidth - 8) / 3;

        this.sigintButton = this.addRenderableWidget(Button.builder(
                Component.literal("⏹ Stop"),
                button -> ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "INTERRUPT"))
        ).bounds(startX, row2Y, btnW, 20).build());

        this.clearButton = this.addRenderableWidget(Button.builder(
                Component.literal("Clear Logs"),
                button -> {
                    this.liveLogs.setLength(0);
                    this.terminalGrid.clear();
                    if (this.consoleView != null) this.consoleView.setValue("");
                    ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "CLEAR"));
                }
        ).bounds(startX + btnW + 4, row2Y, btnW, 20).build());

        this.doneButton = this.addRenderableWidget(Button.builder(
                CommonComponents.GUI_DONE,
                button -> {
                    saveCommand();
                    onClose();
                }
        ).bounds(startX + (btnW + 4) * 2, row2Y, inputWidth - (btnW + 4) * 2, 20).build());
    }

    private void saveCommand() {
        String cmd = this.commandInput.getValue();
        this.blockEntity.setCommand(cmd);
        ClientPlayNetworking.send(new TerminalInputPayload(blockPos, cmd, false));
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent keyEvent) {
        // In Live TUI mode: allow Arrow Keys, Tab, and Enter to navigate agent TUIs
        if ("TERMINAL".equals(currentMode) && this.blockEntity.isProcessRunning()) {
            if (keyEvent.key() == 265) { // Up Arrow
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[A", true));
                return true;
            } else if (keyEvent.key() == 264) { // Down Arrow
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[B", true));
                return true;
            } else if (keyEvent.key() == 262) { // Right Arrow
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[C", true));
                return true;
            } else if (keyEvent.key() == 263) { // Left Arrow
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\033[D", true));
                return true;
            } else if (keyEvent.key() == 258) { // Tab
                ClientPlayNetworking.send(new TerminalInputPayload(blockPos, "\t", true));
                return true;
            }
        }

        if (keyEvent.key() == 257 || keyEvent.key() == 335) { // GLFW Enter / KP Enter
            if (this.commandInput.isFocused()) {
                String cmd = this.commandInput.getValue();
                if (!cmd.isEmpty()) {
                    saveCommand();
                    if ("TERMINAL".equals(currentMode) && this.blockEntity.isProcessRunning()) {
                        ClientPlayNetworking.send(new TerminalInputPayload(blockPos, cmd, true));
                        this.commandInput.setValue("");
                    } else {
                        ClientPlayNetworking.send(new TerminalControlPayload(blockPos, "START"));
                    }
                    return true;
                }
            }
        }
        return super.keyPressed(keyEvent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        int centerX = this.width / 2;
        graphics.centeredText(this.font, TITLE, centerX, 10, 0xFF55FFAA);

        // If in Live TUI Agent mode: render the authentic 80x24 ANSI grid
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

        // Render rows inside the terminal screen
        // Visible lines budget based on terminalHeight (9px per line in MC font)
        int lineHeight = this.font.lineHeight; // 9
        int maxVisibleRows = Math.min(TerminalGrid.ROWS, Math.max(1, bgH / lineHeight));

        // Display the bottom-most rows up to maxVisibleRows
        int startRow = Math.max(0, TerminalGrid.ROWS - maxVisibleRows);

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

                // If background color is specified, fill cell
                if ((bg & 0xFF000000) != 0) {
                    int charW = this.font.width(String.valueOf(ch));
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
            if (terminalGrid.isCursorVisible() && r == terminalGrid.getCursorY() && (System.currentTimeMillis() % 1000 < 500)) {
                int cursorScreenX = bgX + 4 + (terminalGrid.getCursorX() * 5);
                graphics.fill(cursorScreenX, drawY, cursorScreenX + 5, drawY + lineHeight, 0xFF00FF88);
            }
        }
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }
}
