package com.blacklauncher.memory;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.launch.JavaFinder;
import com.blacklauncher.launch.JavaVersionProbe;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Central hook for {@link com.blacklauncher.launch.GameLauncher}.
 */
public class MemoryOptimizeFacade {

    public static final String LOW_MEMORY_CONFIRM_PREFIX = "LOW_MEMORY_CONFIRM:";
    private static final long LOW_FREE_BYTES = 512L * 1024L * 1024L;

    public record LaunchPrep(
            MemoryProfile profile,
            List<String> jvmExtraArgs,
            String logBanner,
            boolean needsConfirmLowMemory
    ) {
    }

    public LaunchPrep prepareLaunch(AppConfig config, String versionId, Consumer<String> log)
            throws LauncherException {
        Consumer<String> out = log != null ? log : s -> {
        };

        MemoryProfile profile = MemoryProfileStore.load(config, versionId);
        Optional<String> err = JvmArgAssembler.validate(profile);
        if (err.isPresent()) {
            throw new LauncherException(err.get());
        }

        SystemMemoryProbe.Snapshot snap = SystemMemoryProbe.probe();
        if (snap.freeBytes() < 1200L * 1024L * 1024L && snap.freeBytes() >= LOW_FREE_BYTES) {
            out.accept("【提示】系统空闲内存较低（"
                    + SystemMemoryProbe.formatMb(snap.freeBytes()) + "），游戏过程容易卡顿");
        }

        if (profile.isTrimBeforeLaunch()) {
            if (!SystemMemoryTrimService.isTrimSupported()) {
                out.accept("Linux 不支持系统备用缓存清理");
            } else {
                SystemMemoryTrimService.trim(out);
            }
        }

        String javaExe = JavaFinder.resolveJavaExecutable(config != null ? config.getJavaPath() : null);
        int javaMajor = JavaVersionProbe.probeMajor(javaExe);
        List<String> jvmArgs = JvmArgAssembler.assemble(profile, javaMajor);

        SystemMemoryProbe.Snapshot after = SystemMemoryProbe.probe();
        String banner = buildBanner(profile, after, jvmArgs, false, -1);
        out.accept(banner);

        // Re-check after optional trim so a successful cleanup can clear the gate.
        boolean needsConfirm = after.freeBytes() < LOW_FREE_BYTES;
        if (needsConfirm) {
            out.accept("【警告】系统空闲内存不足 512MB（当前 "
                    + SystemMemoryProbe.formatMb(after.freeBytes()) + "）");
            throw new LauncherException(LOW_MEMORY_CONFIRM_PREFIX
                    + "系统空闲内存不足512MB，确认仍要启动？");
        }

        return new LaunchPrep(profile, jvmArgs, banner, false);
    }

    /**
     * Same as prepareLaunch but does not throw on low memory (after user confirmed).
     */
    public LaunchPrep prepareLaunchAfterConfirm(AppConfig config, String versionId, Consumer<String> log)
            throws LauncherException {
        Consumer<String> out = log != null ? log : s -> {
        };
        MemoryProfile profile = MemoryProfileStore.load(config, versionId);
        Optional<String> err = JvmArgAssembler.validate(profile);
        if (err.isPresent()) {
            throw new LauncherException(err.get());
        }
        if (profile.isTrimBeforeLaunch() && SystemMemoryTrimService.isTrimSupported()) {
            SystemMemoryTrimService.trim(out);
        }
        String javaExe = JavaFinder.resolveJavaExecutable(config != null ? config.getJavaPath() : null);
        int javaMajor = JavaVersionProbe.probeMajor(javaExe);
        List<String> jvmArgs = JvmArgAssembler.assemble(profile, javaMajor);
        SystemMemoryProbe.Snapshot snap = SystemMemoryProbe.probe();
        String banner = buildBanner(profile, snap, jvmArgs, false, -1);
        out.accept(banner);
        return new LaunchPrep(profile, jvmArgs, banner, false);
    }

    public void afterProcessStarted(long pid, MemoryProfile profile, Consumer<String> log) {
        if (profile == null || !profile.isBoostProcessPriority()) {
            return;
        }
        ProcessPriorityService.boostMinecraft(pid, log);
    }

    public void afterProcessExited(MemoryProfile profile, Consumer<String> log) {
        if (profile == null || !profile.isTrimAfterExit()) {
            return;
        }
        if (!SystemMemoryTrimService.isTrimSupported()) {
            if (log != null) {
                log.accept("Linux 不支持系统备用缓存清理");
            }
            return;
        }
        SystemMemoryTrimService.trim(log);
    }

    public static String buildBanner(MemoryProfile profile,
                                     SystemMemoryProbe.Snapshot snap,
                                     List<String> jvmArgs,
                                     boolean priorityApplied,
                                     long pid) {
        JvmGcPreset preset = JvmGcPreset.fromId(profile != null ? profile.getSchemeId() : null);
        StringBuilder sb = new StringBuilder();
        sb.append("===== 内存优化模块日志 =====\n");
        sb.append("本机总内存：").append(SystemMemoryProbe.formatMb(snap.totalBytes()))
                .append("，当前空闲：").append(SystemMemoryProbe.formatMb(snap.freeBytes())).append('\n');
        sb.append("JVM方案：").append(preset.displayNameZh()).append('\n');
        if (profile != null) {
            sb.append("Xms:").append(profile.getXmsMb()).append("M Xmx:")
                    .append(profile.getXmxMb()).append("M\n");
            sb.append("系统优化开关：启动前内存整理【")
                    .append(profile.isTrimBeforeLaunch() ? "开启" : "关闭")
                    .append("】、进程优先级【")
                    .append(profile.isBoostProcessPriority() ? "开启" : "关闭")
                    .append("】、退出后整理【")
                    .append(profile.isTrimAfterExit() ? "开启" : "关闭")
                    .append("】\n");
        }
        if (pid > 0) {
            sb.append("MC进程PID=").append(pid);
            if (priorityApplied) {
                sb.append("，进程优先级已设置 AboveNormal");
            }
            sb.append('\n');
        }
        if (jvmArgs != null && !jvmArgs.isEmpty()) {
            sb.append("最终JVM启动参数：").append(String.join(" ", jvmArgs));
        }
        return sb.toString();
    }

    public static boolean isLowMemoryConfirm(Throwable t) {
        if (t == null || t.getMessage() == null) {
            return false;
        }
        return t.getMessage().startsWith(LOW_MEMORY_CONFIRM_PREFIX);
    }

    public static String lowMemoryConfirmMessage(Throwable t) {
        if (!isLowMemoryConfirm(t)) {
            return t != null ? t.getMessage() : "";
        }
        String m = t.getMessage();
        return m.substring(LOW_MEMORY_CONFIRM_PREFIX.length()).trim();
    }

    public static void rejectIf32BitJava(AppConfig config) throws LauncherException {
        String javaExe = JavaFinder.resolveJavaExecutable(config != null ? config.getJavaPath() : null);
        if (!JavaVersionProbe.is64Bit(javaExe)) {
            throw new LauncherException("不支持 32 位 Java，请在设置中更换为 64 位 JDK/JRE。");
        }
    }

    public static void applyRecommendToProfile(MemoryProfile profile, long totalBytes) {
        if (profile == null) {
            return;
        }
        MemoryRecommend.RecommendResult r = MemoryRecommend.recommend(totalBytes, profile.isModpackHint());
        r = MemoryRecommend.applySchemeXms(r, profile.getSchemeId());
        profile.setXmsMb(r.xmsMb());
        profile.setXmxMb(r.xmxMb());
        if (Validation.isNonBlank(r.warningZh())) {
            // caller may show warning; stored only via return path
        }
    }
}
