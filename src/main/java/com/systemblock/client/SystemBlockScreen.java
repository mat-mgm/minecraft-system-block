package com.systemblock.client;

import com.systemblock.SystemBlockEntity;
import com.systemblock.TerminalControlPayload;
import com.systemblock.TerminalInputPayload;
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
    private boolean pulseOnSuccess;
    private boolean expanded = true; // Default expanded for terminal view

    private StringBuilder liveLogs = new StringBuilder();

    // Widgets
    private MultiLineEditBox consoleView;
    private EditBox commandInput;
    private Button sendButton;
    private Button startRestartButton;
    private Button sigintButton;
    private Button killButton;
    private Button clearButton;
    private Button pulseToggleButton;
    private Button modeToggleButton;
    private Button doneButton;

    public SystemBlockScreen(SystemBlockEntity blockEntity) {
        super(TITLE);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.currentMode = blockEntity.getMode();
        this.pulseOnSuccess = blockEntity.isPulseOnSuccess();
        this.liveLogs.append(blockEntity.getAllLogs());
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public void appendTerminalLog(String text, boolean append) {
        if (!append) {
            this.liveLogs.setLength(0);
        }
        this.liveLogs.append(text);
        if (this.consoleView != null) {
            this.consoleView.setValue(this.liveLogs.toString());
        }
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int inputWidth = Math.min(380, this.width - 30);
        int startX = centerX - inputWidth / 2;

        // Allocate comfortable vertical budget
        // Available height between title (20px) and bottom buttons (bottom 60px)
        // Row 1: Console View (Y: 26)
        // Height calculated so console + input + buttons never collide
        int consoleY = 26;
        int totalBottomReserved = 68; // 24 (input) + 4 + 20 (row 1 btns) + 4 + 20 (row 2 btns)
        int consoleHeight = Math.max(60, this.height - consoleY - totalBottomReserved - 10);

        this.consoleView = MultiLineEditBox.builder()
                .setX(startX)
                .setY(consoleY)
                .setTextColor(0xFF00FF88) // Matrix / Emerald green
                .setShowDecorations(false) // Prevents character limit counter from drawing over input/Run button
                .build(this.font, inputWidth, consoleHeight, Component.literal("Console"));
        this.consoleView.setValue(this.liveLogs.toString());
        this.addRenderableWidget(this.consoleView);

        // Row 2: Command / Stdin Input box
        int inputY = consoleY + consoleHeight + 6;
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

        // Row 3: Utility & Setting toggles (placed below input)
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

        // Mode: Continuous vs Single Run
        this.modeToggleButton = this.addRenderableWidget(Button.builder(
                Component.literal("Mode: " + ("TERMINAL".equals(currentMode) ? "Interactive Session" : "Single Command")),
                button -> {
                    this.currentMode = "TERMINAL".equals(this.currentMode) ? "COMMAND" : "TERMINAL";
                    button.setMessage(Component.literal("Mode: " + ("TERMINAL".equals(currentMode) ? "Interactive Session" : "Single Command")));
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
        graphics.centeredText(this.font, TITLE, centerX, 12, 0xFF55FFAA);
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }
}
