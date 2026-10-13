package com.systemblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.logging.Logger;

public class SystemBlockEntity extends BlockEntity {
    private static final Logger LOGGER = Logger.getLogger("MinecraftSystemBlock");

    private String command = "";
    private String mode = "COMMAND"; // "COMMAND" or "TERMINAL"
    private boolean pulseOnSuccess = true;

    // Buffer of terminal output lines (up to 300 lines)
    private final LinkedList<String> logBuffer = new LinkedList<>();
    private static final int MAX_BUFFER_LINES = 300;

    // Active host process & stdin writer
    private Process activeProcess = null;
    private BufferedWriter processStdin = null;

    public SystemBlockEntity(BlockPos pos, BlockState state) {
        super(SystemBlockMod.SYSTEM_BLOCK_ENTITY_TYPE, pos, state);
    }

    public synchronized String getCommand() {
        return command;
    }

    public synchronized void setCommand(String command) {
        this.command = command != null ? command : "";
        this.setChanged();
        notifyUpdate();
    }

    public synchronized String getMode() {
        return mode;
    }

    public synchronized void setMode(String mode) {
        this.mode = mode != null ? mode : "COMMAND";
        this.setChanged();
        notifyUpdate();
    }

    public synchronized boolean isPulseOnSuccess() {
        return pulseOnSuccess;
    }

    public synchronized void setPulseOnSuccess(boolean pulse) {
        this.pulseOnSuccess = pulse;
        this.setChanged();
        notifyUpdate();
    }

    public synchronized String getAllLogs() {
        StringBuilder sb = new StringBuilder();
        for (String line : logBuffer) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    public synchronized boolean isProcessRunning() {
        return activeProcess != null && activeProcess.isAlive();
    }

    private synchronized void appendLog(String line) {
        while (logBuffer.size() >= MAX_BUFFER_LINES) {
            logBuffer.removeFirst();
        }
        logBuffer.addLast(line);

        // Broadcast delta to any nearby players or viewers
        if (this.level instanceof ServerLevel serverLevel) {
            TerminalOutputPayload payload = new TerminalOutputPayload(this.worldPosition, line + "\n", true);
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceToSqr(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ()) < 1024) {
                    ServerPlayNetworking.send(player, payload);
                }
            }
        }
    }

    public synchronized void clearLogs() {
        logBuffer.clear();
        if (this.level instanceof ServerLevel serverLevel) {
            TerminalOutputPayload payload = new TerminalOutputPayload(this.worldPosition, "", false);
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceToSqr(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ()) < 1024) {
                    ServerPlayNetworking.send(player, payload);
                }
            }
        }
    }

    public synchronized void sendInputToProcess(String input) {
        if (isProcessRunning() && processStdin != null) {
            try {
                processStdin.write(input);
                processStdin.newLine();
                processStdin.flush();
                appendLog("> " + input);
            } catch (IOException e) {
                appendLog("[Error writing to stdin: " + e.getMessage() + "]");
            }
        } else {
            // Not currently running, start process with this command if requested
            appendLog("> " + input);
            startProcess(input);
        }
    }

    public synchronized void interruptProcess() {
        if (isProcessRunning()) {
            appendLog("[Sending SIGINT / terminating process...]");
            activeProcess.destroy(); // SIGTERM / destroy
        } else {
            appendLog("[No active process to interrupt]");
        }
    }

    public synchronized void killProcess() {
        if (isProcessRunning()) {
            appendLog("[Killing process (SIGKILL)...]");
            activeProcess.destroyForcibly();
        } else {
            appendLog("[No active process to kill]");
        }
    }

    public synchronized void startProcess(String cmdToRun) {
        if (isProcessRunning()) {
            appendLog("[Notice: A process is already running. Interrupt or wait before starting a new one]");
            return;
        }

        final String targetCmd = (cmdToRun != null && !cmdToRun.trim().isEmpty()) ? cmdToRun.trim() : this.command.trim();
        if (targetCmd.isEmpty()) {
            appendLog("[No command configured to execute]");
            return;
        }

        final BlockPos pos = this.worldPosition;
        appendLog("[Starting session in ~ at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ": " + targetCmd + "]");

        Thread runner = new Thread(() -> {
            try {
                File homeDir = new File(System.getProperty("user.home"));
                String osName = System.getProperty("os.name", "").toLowerCase();
                boolean isWindows = osName.contains("win");
                boolean isMac = osName.contains("mac") || osName.contains("darwin");
                ProcessBuilder pb;

                if (isWindows) {
                    // Windows: support PowerShell or cmd.exe fallback
                    pb = new ProcessBuilder("cmd.exe", "/c", targetCmd);
                } else {
                    // Unix (macOS & Linux)
                    pb = new ProcessBuilder(buildUnixCommand(targetCmd, isMac));
                    String currentPath = System.getenv("PATH");
                    StringBuilder enrichedPath = new StringBuilder();
                    if (currentPath != null && !currentPath.isEmpty()) {
                        enrichedPath.append(currentPath).append(":");
                    }
                    if (isMac) {
                        // macOS Homebrew (Apple Silicon & Intel), MacPorts, and system paths
                        enrichedPath.append("/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
                    } else {
                        // Linux (standard, NixOS profiles, user local bins)
                        String user = System.getProperty("user.name", "");
                        enrichedPath.append("/run/current-system/sw/bin:")
                                .append("/etc/profiles/per-user/").append(user).append("/bin:")
                                .append(homeDir.getAbsolutePath()).append("/.nix-profile/bin:")
                                .append(homeDir.getAbsolutePath()).append("/.local/bin:")
                                .append("/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
                    }
                    pb.environment().put("PATH", enrichedPath.toString());
                    pb.environment().put("USER", System.getProperty("user.name", "user"));
                }

                pb.directory(homeDir);
                pb.environment().put("HOME", homeDir.getAbsolutePath());
                pb.environment().put("MC_POS_X", String.valueOf(pos.getX()));
                pb.environment().put("MC_POS_Y", String.valueOf(pos.getY()));
                pb.environment().put("MC_POS_Z", String.valueOf(pos.getZ()));
                pb.redirectErrorStream(true);

                Process process = pb.start();
                synchronized (this) {
                    this.activeProcess = process;
                    this.processStdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendLog(line);
                    }
                }

                int exitCode = process.waitFor();
                appendLog("[Session exited with code: " + exitCode + "]");

                synchronized (this) {
                    this.activeProcess = null;
                    this.processStdin = null;
                }

                // If exit code is 0 and pulseOnSuccess is enabled, emit redstone pulse
                if (exitCode == 0 && isPulseOnSuccess() && this.level instanceof ServerLevel serverLevel) {
                    serverLevel.getServer().execute(() -> {
                        BlockState currentState = serverLevel.getBlockState(pos);
                        if (currentState.getBlock() instanceof SystemBlock) {
                            SystemBlock.triggerSuccessPulse(serverLevel, pos, currentState);
                        }
                    });
                }
            } catch (Exception e) {
                LOGGER.severe("[SystemBlockEntity] Failed: " + e.getMessage());
                appendLog("[Error: " + e.getMessage() + "]");
                synchronized (this) {
                    this.activeProcess = null;
                    this.processStdin = null;
                }
            }
        }, "SystemBlock-Terminal-" + pos.getX() + "-" + pos.getY() + "-" + pos.getZ());
        runner.setDaemon(true);
        runner.start();
    }

    private static String[] buildUnixCommand(String cmd, boolean isMac) {
        if (isMac) {
            // macOS prefers /bin/zsh (default on macOS 10.15+) or /bin/bash
            File zsh = new File("/bin/zsh");
            if (zsh.exists() && zsh.canExecute()) {
                return new String[] { "/bin/zsh", "-c", cmd };
            }
            File bash = new File("/bin/bash");
            if (bash.exists() && bash.canExecute()) {
                return new String[] { "/bin/bash", "-c", cmd };
            }
        }

        File envBin = new File("/usr/bin/env");
        if (envBin.exists() && envBin.canExecute()) {
            return new String[] { "/usr/bin/env", "bash", "-c", cmd };
        }
        File binEnv = new File("/bin/env");
        if (binEnv.exists() && binEnv.canExecute()) {
            return new String[] { "/bin/env", "bash", "-c", cmd };
        }
        String[] candidates = new String[] {
                "/run/current-system/sw/bin/bash",
                "/bin/bash",
                "/usr/bin/bash",
                "/bin/zsh",
                "/usr/bin/zsh",
                "/run/current-system/sw/bin/sh",
                "/bin/sh",
                "/usr/bin/sh",
                "bash",
                "sh"
        };
        for (String candidate : candidates) {
            if (candidate.startsWith("/")) {
                File f = new File(candidate);
                if (f.exists() && f.canExecute()) {
                    return new String[] { candidate, "-c", cmd };
                }
            } else {
                return new String[] { candidate, "-c", cmd };
            }
        }
        return new String[] { "sh", "-c", cmd };
    }

    private void notifyUpdate() {
        if (this.level != null && !this.level.isClientSide()) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("Command", this.command);
        output.putString("Mode", this.mode);
        output.putBoolean("PulseOnSuccess", this.pulseOnSuccess);
        output.putString("LastLogs", getAllLogs());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.command = input.getStringOr("Command", "");
        this.mode = input.getStringOr("Mode", "COMMAND");
        this.pulseOnSuccess = input.getBooleanOr("PulseOnSuccess", true);
        String savedLogs = input.getStringOr("LastLogs", "");
        if (!savedLogs.isEmpty()) {
            logBuffer.clear();
            for (String l : savedLogs.split("\n")) {
                if (!l.isEmpty()) logBuffer.add(l);
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("Command", this.command);
        tag.putString("Mode", this.mode);
        tag.putBoolean("PulseOnSuccess", this.pulseOnSuccess);
        tag.putString("LastLogs", getAllLogs());
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
