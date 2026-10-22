package com.blacklauncher.minecraft;

import com.blacklauncher.core.util.Validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validates user content packs (resource packs) and world folders.
 */
public final class ContentPackValidator {

    private ContentPackValidator() {
    }

    public record ValidationResult(boolean valid, List<String> issues) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult fail(String... issues) {
            return new ValidationResult(false, List.of(issues));
        }
    }

    /** Resource pack: folder or zip must contain {@code pack.mcmeta}. */
    public static ValidationResult validateResourcePack(Path path) {
        if (path == null || !Files.exists(path)) {
            return ValidationResult.fail("路径不存在");
        }
        try {
            if (Files.isDirectory(path)) {
                Path meta = path.resolve("pack.mcmeta");
                if (!Files.isRegularFile(meta)) {
                    return ValidationResult.fail("缺少 pack.mcmeta");
                }
                String text = Files.readString(meta, StandardCharsets.UTF_8);
                if (!text.toLowerCase().contains("pack")) {
                    return ValidationResult.fail("pack.mcmeta 内容无效");
                }
                return ValidationResult.ok();
            }
            String name = path.getFileName().toString().toLowerCase();
            if (!name.endsWith(".zip")) {
                return ValidationResult.fail("资源包应为文件夹或 zip");
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                ZipEntry entry = zip.getEntry("pack.mcmeta");
                if (entry == null) {
                    // some packs nest one level
                    boolean found = zip.stream().anyMatch(e -> e.getName().endsWith("pack.mcmeta"));
                    if (!found) {
                        return ValidationResult.fail("zip 内缺少 pack.mcmeta");
                    }
                }
            }
            return ValidationResult.ok();
        } catch (Exception e) {
            return ValidationResult.fail("校验失败: " + e.getMessage());
        }
    }

    /** World folder must contain {@code level.dat}. */
    public static ValidationResult validateWorldFolder(Path worldDir) {
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return ValidationResult.fail("存档目录不存在");
        }
        Path level = worldDir.resolve("level.dat");
        if (!Files.isRegularFile(level)) {
            return ValidationResult.fail("缺少 level.dat，不是合法单人存档");
        }
        try {
            long size = Files.size(level);
            if (size < 16) {
                return ValidationResult.fail("level.dat 过小，可能已损坏");
            }
            // gzip magic
            try (InputStream in = Files.newInputStream(level)) {
                int b1 = in.read();
                int b2 = in.read();
                if (b1 != 0x1f || b2 != 0x8b) {
                    return ValidationResult.fail("level.dat 不是 gzip 压缩的 NBT");
                }
            }
        } catch (IOException e) {
            return ValidationResult.fail("读取 level.dat 失败: " + e.getMessage());
        }
        return ValidationResult.ok();
    }

    public static ValidationResult validateModJar(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return ValidationResult.fail("模组文件不存在");
        }
        String name = jar.getFileName().toString().toLowerCase();
        if (!name.endsWith(".jar")) {
            return ValidationResult.fail("模组应为 .jar 文件");
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            boolean fabric = zip.getEntry("fabric.mod.json") != null;
            boolean quilt = zip.getEntry("quilt.mod.json") != null;
            boolean forge = zip.stream().anyMatch(e -> {
                String n = e.getName().replace('\\', '/').toLowerCase();
                return n.equals("meta-inf/mods.toml") || n.equals("meta-inf/neoforge.mods.toml");
            });
            if (!fabric && !quilt && !forge) {
                return ValidationResult.fail("未找到 fabric.mod.json / mods.toml，可能不是模组 jar");
            }
            return ValidationResult.ok();
        } catch (Exception e) {
            return ValidationResult.fail("无法读取 jar: " + e.getMessage());
        }
    }

    public static List<String> summarizeIssues(ValidationResult result) {
        return result == null || result.issues() == null ? List.of() : new ArrayList<>(result.issues());
    }

    public static boolean looksLikeWorldName(String name) {
        return Validation.isNonBlank(name) && !name.contains("..") && !name.contains("/") && !name.contains("\\");
    }
}
