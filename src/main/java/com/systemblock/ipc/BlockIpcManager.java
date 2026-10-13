package com.systemblock.ipc;

import com.systemblock.SystemBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Manages inter-process communication (IPC) between terminal agents (Claude Code, OpenCode, Aider)
 * or user shell scripts and the Minecraft System Block.
 * Exposes a named command file / pipe and generates a helper script 'mc-signal' in the session PATH.
 */
public class BlockIpcManager {
    private static final Logger LOGGER = Logger.getLogger("MinecraftSystemBlock");

    private final BlockPos pos;
    private final File ipcDir;
    private final File signalPipeFile;
    private final File helperScriptFile;
    private volatile boolean running = false;
    private Thread listenerThread = null;

    public BlockIpcManager(BlockPos pos) {
        this.pos = pos;
        this.ipcDir = new File(System.getProperty("java.io.tmpdir"), "system_block_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ());
        this.signalPipeFile = new File(ipcDir, "signal.cmd");
        this.helperScriptFile = new File(ipcDir, "mc-signal");
    }

    public File getIpcDir() {
        return ipcDir;
    }

    public File getSignalPipeFile() {
        return signalPipeFile;
    }

    public synchronized void start(ServerLevel level) {
        if (running) return;
        running = true;

        if (!ipcDir.exists()) {
            ipcDir.mkdirs();
        }

        // Generate the executable helper script 'mc-signal'
        createHelperScript();

        // Start background polling listener for signals written to signal.cmd
        listenerThread = new Thread(() -> {
            while (running) {
                try {
                    if (signalPipeFile.exists() && signalPipeFile.length() > 0) {
                        processSignalFile(level);
                    }
                    Thread.sleep(50); // Fast 50ms check
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    LOGGER.warning("[BlockIpcManager] Listener error: " + e.getMessage());
                }
            }
        }, "SystemBlock-IPC-" + pos.getX() + "-" + pos.getY() + "-" + pos.getZ());
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }
        try {
            if (signalPipeFile.exists()) signalPipeFile.delete();
            if (helperScriptFile.exists()) helperScriptFile.delete();
            if (ipcDir.exists()) ipcDir.delete();
        } catch (Exception ignored) {}
    }

    private void createHelperScript() {
        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            if (isWindows) {
                File bat = new File(ipcDir, "mc-signal.bat");
                try (FileWriter fw = new FileWriter(bat)) {
                    fw.write("@echo off\r\n");
                    fw.write("echo %* >> \"" + signalPipeFile.getAbsolutePath() + "\"\r\n");
                }
            }

            // POSIX bash/sh script
            try (FileWriter fw = new FileWriter(helperScriptFile)) {
                fw.write("#!/usr/bin/env sh\n");
                fw.write("if [ \"$#\" -eq 0 ]; then\n");
                fw.write("  echo 'Usage: mc-signal pulse | mc-signal power <0-15>'\n");
                fw.write("  exit 1\n");
                fw.write("fi\n");
                fw.write("echo \"$*\" >> \"" + signalPipeFile.getAbsolutePath() + "\"\n");
            }
            helperScriptFile.setExecutable(true, false);
        } catch (IOException e) {
            LOGGER.warning("[BlockIpcManager] Failed to create helper script: " + e.getMessage());
        }
    }

    private void processSignalFile(ServerLevel level) {
        try {
            String content;
            synchronized (this) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(signalPipeFile), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line.trim()).append("\n");
                    }
                    content = sb.toString();
                }
                // Clear the file
                try (FileOutputStream fos = new FileOutputStream(signalPipeFile)) {
                    fos.write(new byte[0]);
                }
            }

            for (String rawCmd : content.split("\n")) {
                String cmd = rawCmd.trim().toUpperCase();
                if (cmd.isEmpty()) continue;

                level.getServer().execute(() -> {
                    BlockState state = level.getBlockState(pos);
                    if (!(state.getBlock() instanceof SystemBlock)) return;

                    if (cmd.equals("PULSE")) {
                        LOGGER.info("[SystemBlock] Received IPC command: PULSE at " + pos);
                        SystemBlock.triggerSuccessPulse(level, pos, state);
                    } else if (cmd.startsWith("POWER")) {
                        String[] parts = cmd.split("\\s+");
                        int power = 15;
                        if (parts.length > 1) {
                            try {
                                power = Math.max(0, Math.min(15, Integer.parseInt(parts[1])));
                            } catch (NumberFormatException ignored) {}
                        }
                        LOGGER.info("[SystemBlock] Received IPC command: POWER " + power + " at " + pos);
                        SystemBlock.setSignalPower(level, pos, state, power);
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.warning("[BlockIpcManager] Error processing signal: " + e.getMessage());
        }
    }
}
