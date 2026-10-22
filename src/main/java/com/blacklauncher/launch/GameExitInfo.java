package com.blacklauncher.launch;

import java.nio.file.Path;

/**
 * Result of a monitored Minecraft process exit.
 */
public record GameExitInfo(
        String versionId,
        int exitCode,
        Path logFile,
        Path gameDir,
        boolean crashed,
        String summary,
        String diagnosis
) {
    public GameExitInfo(String versionId, int exitCode, Path logFile, Path gameDir,
                        boolean crashed, String summary) {
        this(versionId, exitCode, logFile, gameDir, crashed, summary, "");
    }
}
