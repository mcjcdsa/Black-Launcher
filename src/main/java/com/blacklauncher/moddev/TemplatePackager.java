package com.blacklauncher.moddev;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips a generated Fabric mod project directory.
 */
public final class TemplatePackager {

    private TemplatePackager() {
    }

    public static void zip(Path projectDir, Path zipFile) throws IOException {
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            throw new IllegalArgumentException("工程目录不存在: " + projectDir);
        }
        if (zipFile == null) {
            throw new IllegalArgumentException("ZIP 路径为空");
        }
        Files.createDirectories(zipFile.getParent() != null ? zipFile.getParent() : Path.of("."));
        Path root = projectDir.toAbsolutePath().normalize();
        String rootName = root.getFileName() != null ? root.getFileName().toString() : "mod";
        try (OutputStream fos = Files.newOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path rel = root.relativize(file);
                    String entryName = rootName + "/" + rel.toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path rel = root.relativize(dir);
                    String entryName = rootName + "/" + rel.toString().replace('\\', '/') + "/";
                    zos.putNextEntry(new ZipEntry(entryName));
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
