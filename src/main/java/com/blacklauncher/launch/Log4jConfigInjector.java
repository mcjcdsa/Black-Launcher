package com.blacklauncher.launch;

import com.blacklauncher.core.util.Validation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Extracts a hardened log4j2.xml into the instance and sets {@code -Dlog4j.configurationFile}
 * (HMCL DefaultLauncher approach).
 */
public final class Log4jConfigInjector {

    private static final Logger LOG = LoggerFactory.getLogger(Log4jConfigInjector.class);
    private static final String RESOURCE = "/game/log4j2-safe.xml";

    private Log4jConfigInjector() {
    }

    public static Path ensureConfig(Path gameDir) {
        if (gameDir == null) {
            return null;
        }
        Path target = gameDir.resolve("log4j2.xml");
        try {
            Files.createDirectories(gameDir);
            try (InputStream in = Log4jConfigInjector.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    LOG.warn("Missing classpath resource {}", RESOURCE);
                    return null;
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            }
        } catch (Exception e) {
            LOG.warn("Failed to write log4j2.xml to {}", target, e);
            return null;
        }
    }

    public static void applyJvmArg(List<String> command, Path log4jFile) {
        if (command == null || log4jFile == null || !Files.isRegularFile(log4jFile)) {
            return;
        }
        boolean present = command.stream().anyMatch(a ->
                Validation.isNonBlank(a) && a.startsWith("-Dlog4j.configurationFile="));
        if (!present) {
            command.add("-Dlog4j.configurationFile=" + log4jFile.toAbsolutePath());
        }
    }
}
