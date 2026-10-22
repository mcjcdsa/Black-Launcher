package com.blacklauncher.minecraft;

import com.blacklauncher.core.exception.LauncherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Blocks mutating user content (saves / options / mods) while Minecraft is running.
 * <p>
 * {@link com.blacklauncher.launch.GameLauncher} registers its running state here.
 */
public final class GameProcessGuard {

    private static final Logger LOG = LoggerFactory.getLogger(GameProcessGuard.class);
    private static final GameProcessGuard INSTANCE = new GameProcessGuard();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> runningVersion = new AtomicReference<>("");
    private final AtomicReference<BooleanSupplier> externalProbe = new AtomicReference<>();

    private GameProcessGuard() {
    }

    public static GameProcessGuard get() {
        return INSTANCE;
    }

    public void bindExternalProbe(BooleanSupplier probe) {
        externalProbe.set(probe);
    }

    public void markStarted(String versionId) {
        running.set(true);
        runningVersion.set(versionId == null ? "" : versionId);
        LOG.info("Game process guard: started ({})", versionId);
    }

    public void markStopped() {
        running.set(false);
        runningVersion.set("");
        LOG.info("Game process guard: stopped");
    }

    public boolean isGameRunning() {
        BooleanSupplier probe = externalProbe.get();
        if (probe != null) {
            try {
                if (probe.getAsBoolean()) {
                    return true;
                }
            } catch (Exception e) {
                LOG.debug("External game probe failed", e);
            }
        }
        return running.get();
    }

    public String runningVersionId() {
        return runningVersion.get();
    }

    /**
     * Throws if a write to user content is unsafe while the game is running.
     */
    public void requireWritable(DirectoryKind kind) throws LauncherException {
        if (kind != null && kind.systemManaged()) {
            throw new LauncherException("系统目录只读，禁止修改：" + kind.description());
        }
        if (isGameRunning() && kind != null && kind.userWritable()
                && (kind == DirectoryKind.SAVES
                || kind == DirectoryKind.OPTIONS_TXT
                || kind == DirectoryKind.MODS
                || kind == DirectoryKind.CONFIG
                || kind == DirectoryKind.SERVERS_DAT)) {
            throw new LauncherException("游戏正在运行（"
                    + (runningVersionId().isBlank() ? "未知版本" : runningVersionId())
                    + "），禁止写入 " + kind.description()
                    + "。请先关闭游戏再操作。");
        }
    }

    public void requireNotRunning(String action) throws LauncherException {
        if (isGameRunning()) {
            throw new LauncherException("游戏正在运行，无法" + action + "。请先关闭游戏。");
        }
    }
}
