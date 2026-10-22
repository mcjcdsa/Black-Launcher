package com.blacklauncher.minecraft;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.Validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans and manages local mod jars for a single version (§7).
 */
public final class LocalModService {

    private final AppConfig config;

    public LocalModService(AppConfig config) {
        this.config = config;
    }

    public Path modsDir(String versionId) {
        return MinecraftLayout.forVersion(config, versionId).resolve(DirectoryKind.MODS);
    }

    public List<LocalModEntry> list(String versionId) throws LauncherException {
        if (!Validation.isSafeVersionId(versionId)) {
            throw new LauncherException("非法版本 ID");
        }
        Path dir = modsDir(versionId);
        List<LocalModEntry> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
            for (Path file : files) {
                out.add(toEntry(file));
            }
        } catch (Exception e) {
            throw new LauncherException("扫描 Mod 目录失败: " + e.getMessage(), e);
        }
        out.sort(Comparator.comparing(LocalModEntry::fileName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public void delete(Path file) throws LauncherException {
        try {
            GameProcessGuard.get().requireWritable(DirectoryKind.MODS);
            Files.deleteIfExists(file);
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("删除 Mod 失败: " + e.getMessage(), e);
        }
    }

    public void deleteAll(List<Path> files) throws LauncherException {
        if (files == null || files.isEmpty()) {
            return;
        }
        GameProcessGuard.get().requireWritable(DirectoryKind.MODS);
        List<String> errors = new ArrayList<>();
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
            } catch (Exception e) {
                errors.add(f.getFileName() + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new LauncherException("部分删除失败：\n" + String.join("\n", errors));
        }
    }

    public Path rename(Path file, String newName) throws LauncherException {
        if (!Validation.isNonBlank(newName)) {
            throw new LauncherException("新文件名不能为空");
        }
        String safe = newName.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!safe.toLowerCase(Locale.ROOT).endsWith(".jar")
                && !safe.toLowerCase(Locale.ROOT).endsWith(".zip")
                && !safe.toLowerCase(Locale.ROOT).endsWith(".disabled")) {
            // keep original extension if user omitted it
            String old = file.getFileName().toString();
            int dot = old.lastIndexOf('.');
            if (dot > 0) {
                safe = safe + old.substring(dot);
            }
        }
        Path dest = file.getParent().resolve(safe);
        if (Files.exists(dest) && !dest.equals(file)) {
            throw new LauncherException("目标文件名已存在: " + safe);
        }
        try {
            GameProcessGuard.get().requireWritable(DirectoryKind.MODS);
            Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("重命名失败: " + e.getMessage(), e);
        }
    }

    private static LocalModEntry toEntry(Path file) {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        String format;
        if (lower.endsWith(".jar.disabled") || lower.endsWith(".disabled")) {
            format = "disabled";
        } else if (lower.endsWith(".jar")) {
            format = "jar";
        } else if (lower.endsWith(".zip")) {
            format = "zip";
        } else {
            format = extensionOf(name);
        }

        long size = 0;
        Instant modified = Instant.EPOCH;
        try {
            size = Files.size(file);
            modified = Files.getLastModifiedTime(file).toInstant();
        } catch (Exception ignored) {
        }

        LocalModEntry.Status status;
        String detail = "";
        if (format.equals("disabled") || lower.endsWith(".disabled")) {
            status = LocalModEntry.Status.DISABLED;
            detail = "已禁用（扩展名 .disabled）";
        } else if (format.equals("jar")) {
            var result = ContentPackValidator.validateModJar(file);
            if (result.valid()) {
                status = LocalModEntry.Status.OK;
            } else if (result.issues().stream().anyMatch(i -> i.contains("无法读取") || i.contains("不存在"))) {
                status = LocalModEntry.Status.CORRUPT;
                detail = String.join("; ", result.issues());
            } else {
                status = LocalModEntry.Status.INCOMPATIBLE;
                detail = String.join("; ", result.issues());
            }
        } else if (format.equals("zip")) {
            status = LocalModEntry.Status.OK;
            detail = "压缩包格式（未深度校验）";
        } else {
            status = LocalModEntry.Status.INCOMPATIBLE;
            detail = "非 jar/zip 模组文件";
        }

        return new LocalModEntry(file, name, format, size, modified, status, detail);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }
}
