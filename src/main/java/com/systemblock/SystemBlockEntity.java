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

    // Active host process & stdin streams
    private Process activeProcess = null;
    private final java.util.concurrent.BlockingQueue<byte[]> stdinQueue = new java.util.concurrent.LinkedBlockingQueue<>(2048);
    private final java.util.concurrent.atomic.AtomicLong totalBytesOutput = new java.util.concurrent.atomic.AtomicLong(0);

    // IPC manager for agent redstone signals (mc-signal helper & signal.cmd)
    private final com.systemblock.ipc.BlockIpcManager ipcManager;

    public SystemBlockEntity(BlockPos pos, BlockState state) {
        super(SystemBlockMod.SYSTEM_BLOCK_ENTITY_TYPE, pos, state);
        this.ipcManager = new com.systemblock.ipc.BlockIpcManager(pos);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        killProcess();
        if (ipcManager != null) {
            ipcManager.stop();
        }
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
            sb.append(line);
        }
        return sb.toString();
    }

    public synchronized boolean isProcessRunning() {
        return activeProcess != null && activeProcess.isAlive();
    }

    private synchronized void appendLog(String line) {
        if (line == null || line.isEmpty()) return;
        totalBytesOutput.addAndGet(line.length());

        while (logBuffer.size() >= MAX_BUFFER_LINES) {
            logBuffer.removeFirst();
        }
        logBuffer.addLast(line);

        // Broadcast delta safely on Minecraft server main thread to prevent Netty thread deadlock
        if (this.level instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(() -> {
                TerminalOutputPayload payload = new TerminalOutputPayload(this.worldPosition, line, true);
                for (ServerPlayer player : serverLevel.players()) {
                    if (player.distanceToSqr(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ()) < 1024) {
                        ServerPlayNetworking.send(player, payload);
                    }
                }
            });
        }
    }

    public synchronized void clearLogs() {
        logBuffer.clear();
        if (this.level instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(() -> {
                TerminalOutputPayload payload = new TerminalOutputPayload(this.worldPosition, "", false);
                for (ServerPlayer player : serverLevel.players()) {
                    if (player.distanceToSqr(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ()) < 1024) {
                        ServerPlayNetworking.send(player, payload);
                    }
                }
            });
        }
    }

    public void sendInputToProcess(String input) {
        sendInputToProcess(input, false);
    }

    public void sendInputToProcess(String input, boolean raw) {
        if (input == null) return;

        if (!isProcessRunning()) {
            if (raw) {
                startProcess(null);
            } else {
                appendLog("> " + input + "\n");
                startProcess(input);
                return;
            }
        }

        byte[] bytes = (raw || input.endsWith("\n"))
                ? input.getBytes(StandardCharsets.UTF_8)
                : (input + "\n").getBytes(StandardCharsets.UTF_8);

        if (!raw) {
            appendLog("> " + input + "\n");
        }

        // Asynchronous non-blocking stdin queue write — prevents Minecraft server tick freezes
        stdinQueue.offer(bytes);
    }

    public synchronized void interruptProcess() {
        if (activeProcess != null && activeProcess.isAlive()) {
            appendLog("[Sending SIGINT / terminating process...]\n");
            stdinQueue.clear();
            stdinQueue.offer(new byte[] { 3 }); // ASCII 3 = Ctrl+C
            try {
                activeProcess.toHandle().descendants().forEach(ProcessHandle::destroy);
                activeProcess.destroy();
            } catch (Exception ignored) {}
        } else {
            appendLog("[No active process to interrupt]\n");
        }
    }

    public synchronized void killProcess() {
        if (activeProcess != null) {
            appendLog("[Killing process tree (SIGKILL)...]\n");
            stdinQueue.clear();
            try {
                activeProcess.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (Exception ignored) {}
            try {
                activeProcess.destroyForcibly();
            } catch (Exception ignored) {}
            activeProcess = null;
        } else {
            appendLog("[No active process to kill]\n");
        }
    }

    public synchronized void startProcess(String cmdToRun) {
        if (isProcessRunning()) {
            if (cmdToRun != null && !cmdToRun.isEmpty()) {
                appendLog("[Notice: A process is already running. Interrupt or wait before starting a new one]\n");
            }
            return;
        }

        stdinQueue.clear();
        totalBytesOutput.set(0);

        final boolean isTerminalMode = "TERMINAL".equals(this.mode);
        String rawCmd;
        if (isTerminalMode) {
            rawCmd = (cmdToRun != null && !cmdToRun.trim().isEmpty()) ? cmdToRun.trim() : "$SHELL";
        } else {
            rawCmd = (cmdToRun != null && !cmdToRun.trim().isEmpty()) ? cmdToRun.trim() : this.command.trim();
        }

        final String targetCmd = rawCmd;
        if (targetCmd.isEmpty()) {
            appendLog("[No command configured to execute]\n");
            return;
        }

        final BlockPos pos = this.worldPosition;
        appendLog("[Starting session in ~ at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ": " + targetCmd + "]\n");

        if (this.level instanceof ServerLevel sLevel) {
            this.ipcManager.start(sLevel);
        }

        Thread runner = new Thread(() -> {
            try {
                File homeDir = new File(System.getProperty("user.home"));
                String osName = System.getProperty("os.name", "").toLowerCase();
                boolean isWindows = osName.contains("win");
                boolean isMac = osName.contains("mac") || osName.contains("darwin");
                ProcessBuilder pb;

                String ipcBinPath = ipcManager.getIpcDir().getAbsolutePath();

                if (isWindows) {
                    // Windows: support PowerShell or cmd.exe fallback
                    pb = new ProcessBuilder("cmd.exe", "/c", targetCmd);
                    String sysPath = System.getenv("PATH");
                    pb.environment().put("PATH", ipcBinPath + (sysPath != null ? ";" + sysPath : ""));
                } else {
                    // Unix (macOS & Linux)
                    pb = new ProcessBuilder(buildUnixCommand(targetCmd, isMac, isTerminalMode));
                    String currentPath = System.getenv("PATH");
                    StringBuilder enrichedPath = new StringBuilder();
                    enrichedPath.append(ipcBinPath).append(":"); // Ensure mc-signal is immediately in PATH
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
                    pb.environment().put("TERM", "xterm-256color");
                    pb.environment().put("COLUMNS", "80");
                    pb.environment().put("LINES", "24");
                    pb.environment().put("LANG", "en_US.UTF-8");
                    pb.environment().put("LC_ALL", "en_US.UTF-8");
                    pb.environment().put("PYTHONIOENCODING", "utf-8");

                    // Set default shell
                    String envShell = System.getenv("SHELL");
                    if (envShell == null || envShell.isEmpty() || !new File(envShell).exists()) {
                        String[] shellCandidates = new String[] {
                                "/run/current-system/sw/bin/zsh",
                                "/run/current-system/sw/bin/bash",
                                "/bin/zsh",
                                "/bin/bash",
                                "/usr/bin/zsh",
                                "/usr/bin/bash",
                                "/run/current-system/sw/bin/sh",
                                "/bin/sh",
                                "/usr/bin/sh"
                        };
                        for (String cand : shellCandidates) {
                            if (new File(cand).exists()) {
                                envShell = cand;
                                break;
                            }
                        }
                    }
                    if (envShell == null || envShell.isEmpty()) envShell = "/bin/sh";
                    pb.environment().put("SHELL", envShell);
                }

                pb.directory(homeDir);
                pb.environment().put("HOME", homeDir.getAbsolutePath());
                pb.environment().put("MC_POS_X", String.valueOf(pos.getX()));
                pb.environment().put("MC_POS_Y", String.valueOf(pos.getY()));
                pb.environment().put("MC_POS_Z", String.valueOf(pos.getZ()));
                pb.environment().put("MC_SIGNAL_PIPE", ipcManager.getSignalPipeFile().getAbsolutePath());
                pb.redirectErrorStream(true);

                Process process = pb.start();
                synchronized (this) {
                    this.activeProcess = process;
                }

                // Dedicated async stdin writer thread - guarantees Minecraft never freezes waiting on stdin
                final OutputStream stdinStream = process.getOutputStream();
                Thread stdinWriterThread = new Thread(() -> {
                    try {
                        while (activeProcess == process && process.isAlive()) {
                            byte[] chunk = stdinQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                            if (chunk != null && chunk.length > 0) {
                                stdinStream.write(chunk);
                                stdinStream.flush();
                            }
                        }
                    } catch (Exception ignored) {}
                }, "SystemBlock-Stdin-" + pos.getX() + "-" + pos.getY() + "-" + pos.getZ());
                stdinWriterThread.setDaemon(true);
                stdinWriterThread.start();

                // Process Freeze Watchdog thread - automatically terminates runaway processes
                Thread watchdogThread = new Thread(() -> {
                    long startTime = System.currentTimeMillis();
                    long lastBytes = 0;
                    while (activeProcess == process && process.isAlive()) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            break;
                        }
                        if (activeProcess != process || !process.isAlive()) break;

                        // Check 1: Output flood freeze protection (> 2MB in 500ms)
                        long currentBytes = totalBytesOutput.get();
                        if (currentBytes - lastBytes > 2_000_000) {
                            appendLog("\n[Freeze Watchdog: Terminating process due to excessive output flood (>4MB/s)]\n");
                            killProcess();
                            break;
                        }
                        lastBytes = currentBytes;

                        // Check 2: Single command timeout (kill if unresponsive for > 30s)
                        if (!isTerminalMode && System.currentTimeMillis() - startTime > 30_000) {
                            appendLog("\n[Freeze Watchdog: Command timed out after 30 seconds. Terminating process]\n");
                            killProcess();
                            break;
                        }
                    }
                }, "SystemBlock-Watchdog-" + pos.getX() + "-" + pos.getY() + "-" + pos.getZ());
                watchdogThread.setDaemon(true);
                watchdogThread.start();

                try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[2048];
                    int charsRead;
                    while ((charsRead = reader.read(buffer)) != -1) {
                        String chunk = new String(buffer, 0, charsRead);
                        appendLog(chunk);
                    }
                }

                int exitCode = process.waitFor();
                appendLog("\n[Session exited with code: " + exitCode + "]\n");

                synchronized (this) {
                    if (this.activeProcess == process) {
                        this.activeProcess = null;
                    }
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
                appendLog("[Error: " + e.getMessage() + "]\n");
                synchronized (this) {
                    this.activeProcess = null;
                }
            }
        }, "SystemBlock-Terminal-" + pos.getX() + "-" + pos.getY() + "-" + pos.getZ());
        runner.setDaemon(true);
        runner.start();
    }

    private static String[] buildUnixCommand(String cmd, boolean isMac, boolean isTerminalMode) {
        // In Terminal mode, ALWAYS run within a genuine VT100 / PTY interactive terminal session
        if (isTerminalMode) {
            String pyScript = "import pty, os, sys, fcntl, struct, threading\n"
                    + "m, s = pty.openpty()\n"
                    + "try: fcntl.ioctl(m, 0x5414, struct.pack('HHHH', 24, 80, 0, 0))\n"
                    + "except Exception: pass\n"
                    + "pid = os.fork()\n"
                    + "if pid == 0:\n"
                    + "  os.close(m); os.setsid()\n"
                    + "  try: fcntl.ioctl(s, 0x540E, 0)\n"
                    + "  except Exception: pass\n"
                    + "  for fd in (0, 1, 2): os.dup2(s, fd)\n"
                    + "  if s > 2: os.close(s)\n"
                    + "  shell = os.environ.get('SHELL', '/bin/sh')\n"
                    + "  target = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] not in ('', '$SHELL') else None\n"
                    + "  if target:\n"
                    + "    os.execlp(shell, shell, '-c', target)\n"
                    + "  else:\n"
                    + "    os.execlp(shell, shell, '-i')\n"
                    + "else:\n"
                    + "  os.close(s)\n"
                    + "  def fwd():\n"
                    + "    while True:\n"
                    + "      try:\n"
                    + "        b = os.read(0, 1024)\n"
                    + "        if not b: break\n"
                    + "        os.write(m, b)\n"
                    + "      except Exception: break\n"
                    + "  t = threading.Thread(target=fwd, daemon=True)\n"
                    + "  t.start()\n"
                    + "  while True:\n"
                    + "    try:\n"
                    + "      d = os.read(m, 1024)\n"
                    + "      if not d: break\n"
                    + "      os.write(1, d)\n"
                    + "    except OSError: break\n";

            String[] pythonCandidates = new String[] {
                    "/etc/profiles/per-user/rs/bin/python3",
                    "/run/current-system/sw/bin/python3",
                    "/usr/bin/python3",
                    "/usr/local/bin/python3",
                    "/opt/homebrew/bin/python3",
                    "python3"
            };
            for (String py : pythonCandidates) {
                if (py.startsWith("/")) {
                    File f = new File(py);
                    if (f.exists() && f.canExecute()) {
                        return new String[] { py, "-c", pyScript, cmd };
                    }
                } else {
                    return new String[] { py, "-c", pyScript, cmd };
                }
            }
            return new String[] { "python3", "-c", pyScript, cmd };
        }

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
