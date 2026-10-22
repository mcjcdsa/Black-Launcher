package com.blacklauncher.launch;

import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.model.VersionJson;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ensures the selected JRE meets {@code javaVersion.majorVersion} from the version JSON (HMCL-style gate).
 */
public final class JavaVersionProbe {

    private static final Pattern VERSION_TOKEN = Pattern.compile(
            "(?:version\\s+\")?(\\d+)(?:\\.(\\d+))?", Pattern.CASE_INSENSITIVE);

    private JavaVersionProbe() {
    }

    public static void ensureCompatible(String configuredJavaPath, VersionJson version) throws LauncherException {
        int required = requiredMajor(version);
        if (required <= 0) {
            return;
        }
        String exe = JavaFinder.resolveJavaExecutable(configuredJavaPath);
        int actual = probeMajor(exe);
        if (actual <= 0) {
            throw new LauncherException("无法检测 Java 版本（" + exe + "）。请在设置中指定可用的 JDK/JRE。");
        }
        if (actual < required) {
            throw new LauncherException("当前 Java " + actual + " 过低，该版本需要 Java "
                    + required + "+。请在设置中更换 Java 路径。");
        }
    }

    public static int requiredMajor(VersionJson version) {
        if (version == null || version.getJavaVersion() == null) {
            return 0;
        }
        return Math.max(0, version.getJavaVersion().getMajorVersion());
    }

    public static int probeMajor(String javaExecutable) {
        String line = JavaFinder.probeVersion(javaExecutable);
        if (!Validation.isNonBlank(line)) {
            return 0;
        }
        // Examples: openjdk version "17.0.9" / java version "1.8.0_392"
        Matcher m = VERSION_TOKEN.matcher(line);
        if (!m.find()) {
            return 0;
        }
        int major = Integer.parseInt(m.group(1));
        if (major == 1 && m.group(2) != null) {
            return Integer.parseInt(m.group(2));
        }
        return major;
    }
}
