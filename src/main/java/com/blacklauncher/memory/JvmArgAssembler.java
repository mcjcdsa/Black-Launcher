package com.blacklauncher.memory;

import com.blacklauncher.core.util.Validation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Assembles -Xms/-Xmx + GC preset + custom JVM args with conflict stripping.
 */
public final class JvmArgAssembler {

    private static final Logger LOG = LoggerFactory.getLogger(JvmArgAssembler.class);

    private static final Pattern STRIP_HEAP = Pattern.compile(
            "(?i)^-X(ms|mx).*$");
    private static final Pattern STRIP_GC = Pattern.compile(
            "(?i)^-XX:([+-])?(UseG1GC|UseZGC|UseParallelGC|UseConcMarkSweepGC|UseSerialGC|"
                    + "ParallelRefProcEnabled|MaxGCPauseMillis|G1HeapRegionSize|"
                    + "UnlockExperimentalVMOptions).*$");

    private JvmArgAssembler() {
    }

    public static Optional<String> validate(MemoryProfile profile) {
        if (profile == null) {
            return Optional.of("内存配置无效");
        }
        if (profile.getXmsMb() < 512) {
            return Optional.of("初始内存 Xms 不能小于 512MB");
        }
        if (profile.getXmxMb() < 512) {
            return Optional.of("最大内存 Xmx 不能小于 512MB");
        }
        if (profile.getXmsMb() > profile.getXmxMb()) {
            return Optional.of("初始内存 Xms 不能大于最大内存 Xmx");
        }
        return Optional.empty();
    }

    public static List<String> assemble(MemoryProfile profile, int javaMajor) {
        List<String> out = new ArrayList<>();
        if (profile == null) {
            return out;
        }

        JvmGcPreset preset = JvmGcPreset.fromId(profile.getSchemeId());
        if (preset == JvmGcPreset.ZGC && !preset.isAvailable(javaMajor)) {
            LOG.warn("ZGC unavailable for Java {}, falling back to balanced G1", javaMajor);
            preset = JvmGcPreset.BALANCED_G1;
        }

        int xms = profile.getXmsMb();
        int xmx = profile.getXmxMb();
        if (preset == JvmGcPreset.LOW_MEM) {
            xms = 512;
        }

        out.add("-Xms" + xms + "M");
        out.add("-Xmx" + xmx + "M");

        if (preset != JvmGcPreset.CUSTOM) {
            out.addAll(preset.jvmFlags(javaMajor));
        }

        List<String> custom = tokenize(profile.getCustomJvmArgs());
        boolean stripHeap = true;
        boolean stripGc = preset != JvmGcPreset.CUSTOM;
        for (String token : custom) {
            if (stripHeap && STRIP_HEAP.matcher(token).matches()) {
                LOG.warn("自定义 JVM 参数中存在重复堆设置，已忽略: {}", token);
                continue;
            }
            if (stripGc && isGcRelated(token)) {
                LOG.warn("自定义 JVM 参数中存在与预设冲突的 GC 参数，已忽略: {}", token);
                continue;
            }
            out.add(token);
        }
        return out;
    }

    private static boolean isGcRelated(String token) {
        if (token == null) {
            return false;
        }
        if (STRIP_GC.matcher(token).matches()) {
            return true;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.startsWith("-xx:+useg1gc")
                || lower.startsWith("-xx:-useg1gc")
                || lower.startsWith("-xx:+usezgc")
                || lower.startsWith("-xx:-usezgc")
                || lower.startsWith("-xx:maxgcpausemillis=")
                || lower.startsWith("-xx:g1heapregionsize=");
    }

    static List<String> tokenize(String raw) {
        List<String> tokens = new ArrayList<>();
        if (!Validation.isNonBlank(raw)) {
            return tokens;
        }
        for (String part : raw.trim().split("\\s+")) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        return tokens;
    }
}
