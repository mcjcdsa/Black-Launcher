package com.blacklauncher.launch;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.ProcessCharsets;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.memory.MemoryOptimizeFacade;
import com.blacklauncher.memory.MemoryProfile;
import com.blacklauncher.minecraft.GameProcessGuard;
import com.blacklauncher.minecraft.MinecraftLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Starts the Minecraft process, captures logs and monitors abnormal exits.
 */
public class GameLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(GameLauncher.class);
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final AppConfig config;
    private final Database database;
    private final LaunchCommandBuilder commandBuilder = new LaunchCommandBuilder();
    private final MemoryOptimizeFacade memoryFacade = new MemoryOptimizeFacade();
    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "game-log");
        t.setDaemon(true);
        return t;
    });

    private Process currentProcess;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile MemoryProfile activeMemoryProfile;

    public GameLauncher(AppConfig config, Database database) {
        this.config = config;
        this.database = database;
        GameProcessGuard.get().bindExternalProbe(this::isRunning);
    }

    public synchronized boolean isRunning() {
        return currentProcess != null && currentProcess.isAlive();
    }

    public String previewCommand(String versionId) {
        return commandBuilder.preview(config, versionId);
    }

    public synchronized void launch(String versionId,
                                    Consumer<String> logLine,
                                    Consumer<GameExitInfo> onExit) throws LauncherException {
        launch(versionId, logLine, onExit, false);
    }

    /**
     * @param acknowledgeLowMemory when true, skip the {@code LOW_MEMORY_CONFIRM:} gate
     *                             (UI already confirmed after a previous throw).
     */
    public synchronized void launch(String versionId,
                                    Consumer<String> logLine,
                                    Consumer<GameExitInfo> onExit,
                                    boolean acknowledgeLowMemory) throws LauncherException {
        if (isRunning()) {
            throw new LauncherException("游戏已在运行中");
        }
        Validation.requireSafeVersionId(versionId);
        stopRequested.set(false);

        MemoryOptimizeFacade.rejectIf32BitJava(config);

        MinecraftLayout layout = MinecraftLayout.forVersion(config, versionId);
        Path gameDir = layout.instanceRoot();
        try {
            layout.ensureUserDirectories();
        } catch (Exception e) {
            throw new LauncherException("创建游戏目录失败", e);
        }

        GameOptionsConfigurator.applyIfEnabled(config, versionId);

        MemoryOptimizeFacade.LaunchPrep prep;
        if (acknowledgeLowMemory) {
            prep = memoryFacade.prepareLaunchAfterConfirm(config, versionId, logLine);
        } else {
            prep = memoryFacade.prepareLaunch(config, versionId, logLine);
        }
        activeMemoryProfile = prep.profile();

        List<String> command = commandBuilder.build(config, versionId, gameDir);
        Path logFile = AppPaths.logsDir().resolve(
                "launch_" + versionId + "_" + LOG_TS.format(LocalDateTime.now()) + ".log");

        long logId;
        try {
            logId = database.insertLaunchLog(versionId, logFile.toAbsolutePath().toString());
            database.markLaunched(versionId);
        } catch (Exception e) {
            throw new LauncherException("写入启动日志记录失败", e);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(gameDir.toFile());
            pb.redirectErrorStream(true);
            currentProcess = pb.start();
            GameProcessGuard.get().markStarted(versionId);
            long pid = currentProcess.pid();
            LOG.info("Launched Minecraft version {} pid={}", versionId, pid);
            memoryFacade.afterProcessStarted(pid, activeMemoryProfile, logLine);

            Process process = currentProcess;
            MemoryProfile profileForExit = activeMemoryProfile;
            ioPool.submit(() -> captureLog(
                    process, versionId, gameDir, logFile, logId, logLine, onExit, profileForExit));
        } catch (Exception e) {
            currentProcess = null;
            activeMemoryProfile = null;
            GameProcessGuard.get().markStopped();
            throw new LauncherException("启动游戏失败: " + e.getMessage(), e);
        }
    }

    private void captureLog(Process process,
                            String versionId,
                            Path gameDir,
                            Path logFile,
                            long logId,
                            Consumer<String> logLine,
                            Consumer<GameExitInfo> onExit,
                            MemoryProfile memoryProfile) {
        AtomicBoolean crashHint = new AtomicBoolean(false);
        StringBuilder logBuf = new StringBuilder(64_000);
        // Decode with native charset (same as -Dfile.encoding / stdout.encoding on the game JVM);
        // persist launch_*.log as UTF-8 for the UI and CrashDialog.
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), ProcessCharsets.gameOutputCharset()));
             BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
                if (logBuf.length() < 500_000) {
                    logBuf.append(line).append('\n');
                }
                if (looksLikeCrashLine(line)) {
                    crashHint.set(true);
                }
                if (logLine != null) {
                    logLine.accept(line);
                }
            }
            int code = process.waitFor();
            try {
                database.finishLaunchLog(logId, code);
            } catch (Exception e) {
                LOG.warn("Failed to update launch log exit code", e);
            }

            String fullLog = logBuf.toString();
            boolean userStopped = stopRequested.get();
            boolean jvmFailed = CrashReportAnalyzer.looksLikeJvmInitFailure(fullLog);
            boolean crashed = !userStopped && (code != 0 || crashHint.get() || jvmFailed || hasCrashReport(gameDir));
            String diagnosis = crashed ? CrashReportAnalyzer.summarize(fullLog) : "";
            String summary;
            if (userStopped) {
                summary = "游戏已被启动器结束。退出码：" + code;
            } else if (jvmFailed) {
                summary = "版本 " + versionId + " JVM 未能正常启动（退出码 " + code + "）。";
            } else if (crashed) {
                summary = "版本 " + versionId + " 异常退出（退出码 " + code + "）。"
                        + "可查看启动日志或 crash-reports 目录排查问题。";
            } else {
                summary = "版本 " + versionId + " 已正常结束（退出码 " + code + "）。";
            }
            if (Validation.isNonBlank(diagnosis)) {
                summary = summary + "\n" + diagnosis;
            }

            GameExitInfo info = new GameExitInfo(versionId, code, logFile, gameDir, crashed, summary, diagnosis);
            if (crashed) {
                LOG.warn("Minecraft crashed: {}", summary);
            } else {
                LOG.info("Minecraft exited: {}", summary);
            }
            if (onExit != null) {
                onExit.accept(info);
            }
        } catch (Exception e) {
            LOG.error("Log capture failed", e);
            if (onExit != null) {
                onExit.accept(new GameExitInfo(
                        versionId,
                        -1,
                        logFile,
                        gameDir,
                        true,
                        "监控游戏进程时出错: " + e.getMessage(),
                        ""
                ));
            }
        } finally {
            try {
                memoryFacade.afterProcessExited(memoryProfile, logLine);
            } catch (Exception e) {
                LOG.debug("afterProcessExited failed: {}", e.getMessage());
            }
            synchronized (this) {
                if (currentProcess == process) {
                    currentProcess = null;
                }
                if (activeMemoryProfile == memoryProfile) {
                    activeMemoryProfile = null;
                }
            }
            GameProcessGuard.get().markStopped();
        }
    }

    private static boolean looksLikeCrashLine(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("---- minecraft crash report ----")
                || lower.contains("#@!@# game crashed")
                || lower.contains("game crashed!")
                || lower.contains("exception in thread \"main\"")
                || lower.contains("java.lang.outofmemoryerror")
                || lower.contains("failed to start the minecraft server")
                || (lower.contains("crash") && lower.contains("report"));
    }

    private static boolean hasCrashReport(Path gameDir) {
        if (gameDir == null) {
            return false;
        }
        Path crashDir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(crashDir)) {
            return false;
        }
        try (var stream = Files.list(crashDir)) {
            long recent = System.currentTimeMillis() - 5 * 60 * 1000L;
            return stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() >= recent;
                        } catch (Exception e) {
                            return false;
                        }
                    });
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void stop() {
        stopRequested.set(true);
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroy();
        }
    }

    public void shutdown() {
        stop();
        ioPool.shutdownNow();
    }
}
