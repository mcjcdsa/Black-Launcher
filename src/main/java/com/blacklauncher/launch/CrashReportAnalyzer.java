package com.blacklauncher.launch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight HMCL-style crash log heuristics → Chinese advice.
 */
public final class CrashReportAnalyzer {

    public enum Kind {
        JVM_INIT_ERROR,
        OUT_OF_MEMORY,
        WRONG_JAVA,
        MISSING_NATIVES,
        MOD_RESOLUTION,
        MIXIN_FAILURE,
        GRAPHICS_DRIVER,
        OPTIFINE_CONFLICT,
        DUPLICATED_MOD,
        FABRIC_WARNINGS,
        FORGE_INCOMPLETE,
        AUTH_FAILURE,
        DISK_FULL,
        GENERIC_CRASH
    }

    public record Finding(Kind kind, String title, String advice) {
    }

    private record Rule(Kind kind, Pattern pattern, String title, String advice) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Kind.OUT_OF_MEMORY,
                    Pattern.compile("java\\.lang\\.OutOfMemoryError|Too small maximum heap|insufficient memory for the Java Runtime|There is insufficient memory for the Java Runtime Environment",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
                    "内存不足",
                    "请在设置中提高最大内存（-Xmx），并关闭其它占内存程序。"),
            new Rule(Kind.WRONG_JAVA,
                    Pattern.compile("UnsupportedClassVersionError|class file version|compatibility level JAVA_\\d+|Java version is too high|module java\\.base does not export|Unable to make protected final java\\.lang\\.Class java\\.lang\\.ClassLoader\\.defineClass|ClassNotFoundException: jdk\\.nashorn",
                            Pattern.CASE_INSENSITIVE),
                    "Java 版本不匹配",
                    "请按版本要求更换 JDK/JRE（常见：1.17+ 需 Java 17，1.20.5+ 需 Java 21；过老版本勿用过高 Java）。"),
            new Rule(Kind.JVM_INIT_ERROR,
                    Pattern.compile("Error occurred during initialization of VM|Could not create the Java Virtual Machine|Invalid maximum heap size|Could not reserve enough space for .* object heap",
                            Pattern.CASE_INSENSITIVE),
                    "JVM 未能启动",
                    "检查 Java 路径与内存参数是否合法（32 位 Java 无法使用过大的 -Xmx）。"),
            new Rule(Kind.MISSING_NATIVES,
                    Pattern.compile("UnsatisfiedLinkError|Failed to locate library|Unable to load library|lwjgl.*not found",
                            Pattern.CASE_INSENSITIVE),
                    "缺少本地库（natives）",
                    "请重新安装该版本或执行「检测修复」，确保 natives 已下载并解压。"),
            new Rule(Kind.OPTIFINE_CONFLICT,
                    Pattern.compile("OptiFine|optifine|InvalidLauncherSetupException: Invalid Services found OptiFine|Shaders Mod detected\\. Please remove it, OptiFine has built-in support",
                            Pattern.CASE_INSENSITIVE),
                    "OptiFine / 光影冲突",
                    "OptiFine 常与 Forge/NeoForge/Sodium 冲突；可改用 Iris/Oculus，或移除 OptiFine / Shaders Mod。"),
            new Rule(Kind.DUPLICATED_MOD,
                    Pattern.compile("Found a duplicate mod|Found duplicate mods|ResolutionException: Module optifine reads another module named optifine",
                            Pattern.CASE_INSENSITIVE),
                    "模组重复安装",
                    "mods 目录中存在同名/同 id 模组（或 OptiFine 装了两份），请只保留一份。"),
            new Rule(Kind.MOD_RESOLUTION,
                    Pattern.compile("ModResolutionException|Missing or unsupported mandatory dependencies|Found conflicting mods|Could not find required mod",
                            Pattern.CASE_INSENSITIVE),
                    "模组依赖冲突/缺失",
                    "检查 Mod 版本与加载器是否匹配，移除冲突或补齐依赖。"),
            new Rule(Kind.FABRIC_WARNINGS,
                    Pattern.compile("Incompatible mod set!|Incompatible mods found!|Warnings were found!",
                            Pattern.CASE_INSENSITIVE),
                    "Fabric 兼容性警告",
                    "存在互不兼容的 Fabric Mod，请按日志中的冲突列表调整。"),
            new Rule(Kind.MIXIN_FAILURE,
                    Pattern.compile("MixinApplyError|Mixin apply failed|mixin\\.injection\\.throwables|Mixin prepare failed",
                            Pattern.CASE_INSENSITIVE),
                    "Mixin 注入失败",
                    "多为模组互不兼容；可逐个禁用最近安装的 Mod 定位冲突。"),
            new Rule(Kind.FORGE_INCOMPLETE,
                    Pattern.compile("Incomplete Forge|Could not find net/minecraft/client/Minecraft\\.class|Cannot find launch target fmlclient|Invalid paths argument, contained no existing paths",
                            Pattern.CASE_INSENSITIVE),
                    "Forge 安装不完整",
                    "请删除该版本后重新安装 Forge/NeoForge，或使用「检测修复」。"),
            new Rule(Kind.GRAPHICS_DRIVER,
                    Pattern.compile("Pixel format not accelerated|GLXBadFBConfig|does not appear to support OpenGL|EXCEPTION_ACCESS_VIOLATION[\\s\\S]*?(ig|atio|nvoglv)|Failed to find service port for display",
                            Pattern.CASE_INSENSITIVE),
                    "显卡/OpenGL 问题",
                    "更新显卡驱动；笔记本可尝试强制使用独显；避免软件渲染。"),
            new Rule(Kind.AUTH_FAILURE,
                    Pattern.compile("Invalid session|Failed to login|401 Unauthorized|ForbiddenOperationException",
                            Pattern.CASE_INSENSITIVE),
                    "登录/会话失败",
                    "正版令牌可能过期或无效；请在设置中重新微软登录，或检查离线昵称是否合规。"),
            new Rule(Kind.DISK_FULL,
                    Pattern.compile("No space left on device|disk full|There is not enough space on the disk",
                            Pattern.CASE_INSENSITIVE),
                    "磁盘空间不足",
                    "清理游戏目录或系统盘空间后重试；可将游戏目录迁移到更大的磁盘。"),
            new Rule(Kind.GENERIC_CRASH,
                    Pattern.compile("---- Minecraft Crash Report ----|#@!@# Game crashed|Game crashed!",
                            Pattern.CASE_INSENSITIVE),
                    "游戏崩溃",
                    "请查看 crash-reports 与启动日志末尾的异常栈。")
    );

    private CrashReportAnalyzer() {
    }

    public static List<Finding> analyze(String log) {
        if (log == null || log.isBlank()) {
            return List.of();
        }
        Set<Kind> seen = new LinkedHashSet<>();
        List<Finding> out = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern.matcher(log);
            if (m.find() && seen.add(rule.kind)) {
                out.add(new Finding(rule.kind, rule.title, rule.advice));
            }
        }
        return out;
    }

    public static String summarize(String log) {
        List<Finding> findings = analyze(log);
        if (findings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(4, findings.size());
        for (int i = 0; i < limit; i++) {
            Finding f = findings.get(i);
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("· ").append(f.title()).append("：").append(f.advice());
        }
        return sb.toString();
    }

    public static Kind classifyExit(int exitCode, String log, boolean crashHint) {
        List<Finding> findings = analyze(log);
        for (Finding f : findings) {
            if (f.kind() == Kind.JVM_INIT_ERROR || f.kind() == Kind.OUT_OF_MEMORY) {
                return f.kind();
            }
        }
        if (!findings.isEmpty()) {
            return findings.get(0).kind();
        }
        if (exitCode == 137) {
            return Kind.OUT_OF_MEMORY;
        }
        if (crashHint || exitCode != 0) {
            return Kind.GENERIC_CRASH;
        }
        return null;
    }

    public static boolean looksLikeJvmInitFailure(String log) {
        if (log == null) {
            return false;
        }
        String lower = log.toLowerCase(Locale.ROOT);
        return lower.contains("error occurred during initialization of vm")
                || lower.contains("could not create the java virtual machine")
                || lower.contains("too small maximum heap");
    }
}
