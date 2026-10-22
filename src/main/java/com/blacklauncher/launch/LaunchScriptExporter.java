package com.blacklauncher.launch;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.MinecraftLayout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports a standalone Windows {@code .bat} that launches a version without the launcher UI (§7).
 */
public final class LaunchScriptExporter {

    private final AppConfig config;
    private final LaunchCommandBuilder commandBuilder = new LaunchCommandBuilder();

    public LaunchScriptExporter(AppConfig config) {
        this.config = config;
    }

    public Path exportDefault(String versionId) throws LauncherException {
        Validation.requireSafeVersionId(versionId);
        try {
            Files.createDirectories(AppPaths.launchScriptsDir());
        } catch (Exception e) {
            throw new LauncherException("无法创建 LaunchScript 目录: " + e.getMessage(), e);
        }
        Path out = AppPaths.launchScriptsDir().resolve(versionId + "-独立启动脚本.bat");
        return exportTo(versionId, out);
    }

    public Path exportTo(String versionId, Path outputBat) throws LauncherException {
        Validation.requireSafeVersionId(versionId);
        if (outputBat == null) {
            throw new LauncherException("未指定脚本保存路径");
        }
        try {
            Path gameDir = MinecraftLayout.forVersion(config, versionId).instanceRoot();
            Files.createDirectories(gameDir);
            List<String> cmd = commandBuilder.build(config, versionId, gameDir);
            if (cmd.isEmpty()) {
                throw new LauncherException("启动命令为空");
            }

            StringBuilder bat = new StringBuilder();
            bat.append("@echo off\r\n");
            bat.append("chcp 65001 >nul\r\n");
            bat.append("title Black Launcher - ").append(sanitizeTitle(versionId)).append("\r\n");
            bat.append("cd /d ").append(quoteBat(gameDir.toAbsolutePath().toString())).append("\r\n");
            bat.append("echo Starting ").append(versionId).append("...\r\n");
            for (int i = 0; i < cmd.size(); i++) {
                if (i > 0) {
                    bat.append(' ');
                }
                bat.append(quoteBat(cmd.get(i)));
            }
            bat.append("\r\n");
            bat.append("if errorlevel 1 (\r\n");
            bat.append("  echo.\r\n");
            bat.append("  echo Game exited with error. Press any key to close.\r\n");
            bat.append("  pause >nul\r\n");
            bat.append(")\r\n");

            Files.createDirectories(outputBat.getParent());
            // GBK for cmd.exe on Chinese Windows when chcp fails; UTF-8 with BOM + chcp 65001 as primary.
            byte[] utf8Bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] body = bat.toString().getBytes(StandardCharsets.UTF_8);
            byte[] all = new byte[utf8Bom.length + body.length];
            System.arraycopy(utf8Bom, 0, all, 0, utf8Bom.length);
            System.arraycopy(body, 0, all, utf8Bom.length, body.length);
            Files.write(outputBat, all);
            return outputBat;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("导出启动脚本失败: " + e.getMessage(), e);
        }
    }

    private static String quoteBat(String arg) {
        if (arg == null) {
            return "\"\"";
        }
        if (arg.isEmpty()) {
            return "\"\"";
        }
        boolean need = arg.indexOf(' ') >= 0 || arg.indexOf('\t') >= 0
                || arg.indexOf('&') >= 0 || arg.indexOf('^') >= 0
                || arg.indexOf('%') >= 0 || arg.indexOf('(') >= 0
                || arg.indexOf(')') >= 0 || arg.indexOf('"') >= 0;
        if (!need) {
            return arg;
        }
        return "\"" + arg.replace("\"", "\\\"") + "\"";
    }

    private static String sanitizeTitle(String versionId) {
        return versionId.replaceAll("[^A-Za-z0-9._\\-+\\u4e00-\\u9fff]", "_");
    }
}
