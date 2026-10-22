package com.blacklauncher.core.util;

import com.blacklauncher.core.exception.LauncherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Copies / moves directory trees when changing launcher or game paths.
 */
public final class PathMigrationService {

    private static final Logger LOG = LoggerFactory.getLogger(PathMigrationService.class);

    public record Result(long filesCopied, long bytesCopied, Path from, Path to) {
    }

    private PathMigrationService() {
    }

    /**
     * Copy {@code from} → {@code to}. Skips the bootstrap redirect file name.
     *
     * @param progress (filesDone, message)
     * @param deleteSource after successful copy, delete source (move semantics)
     */
    public static Result migrate(Path from, Path to, boolean deleteSource,
                                 BiConsumer<Long, String> progress) throws LauncherException {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Path src = from.toAbsolutePath().normalize();
        Path dst = to.toAbsolutePath().normalize();
        if (src.equals(dst)) {
            throw new LauncherException("新路径与当前路径相同");
        }
        if (!Files.exists(src)) {
            throw new LauncherException("源目录不存在: " + src);
        }
        if (dst.startsWith(src)) {
            throw new LauncherException("目标不能位于源目录内部");
        }
        if (src.startsWith(dst) && Files.exists(dst)) {
            throw new LauncherException("源目录位于目标内部，无法安全迁移");
        }

        try {
            Files.createDirectories(dst);
            DiskIoGuard.ensureWritableDirectory(dst);
            long estimate = roughSize(src);
            DiskIoGuard.ensureSpace(dst, Math.max(estimate, 64L * 1024 * 1024));

            AtomicLong files = new AtomicLong();
            AtomicLong bytes = new AtomicLong();
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path rel = src.relativize(dir);
                    Path targetDir = dst.resolve(rel.toString());
                    Files.createDirectories(targetDir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    // Never copy the redirect pointer out of bootstrap when walking bootstrap itself.
                    if ("launcher-home.txt".equals(name) && src.equals(AppPaths.bootstrapHome().toAbsolutePath().normalize())) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path rel = src.relativize(file);
                    Path target = dst.resolve(rel.toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    long n = files.incrementAndGet();
                    bytes.addAndGet(attrs.size());
                    if (progress != null && (n % 25 == 0 || n == 1)) {
                        progress.accept(n, "已复制 " + n + " 个文件 → " + target.getFileName());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (deleteSource) {
                if (progress != null) {
                    progress.accept(files.get(), "正在清理旧目录…");
                }
                deleteTree(src, true);
            }

            LOG.info("Migrated {} → {} ({} files, {} bytes)", src, dst, files.get(), bytes.get());
            return new Result(files.get(), bytes.get(), src, dst);
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("数据迁移失败: " + e.getMessage(), e);
        }
    }

    /**
     * Delete directory contents. If {@code keepRedirectAnchor} and path is bootstrap home,
     * keep {@code launcher-home.txt}.
     */
    public static void deleteTree(Path root, boolean keepRedirectAnchor) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Path abs = root.toAbsolutePath().normalize();
        boolean bootstrap = abs.equals(AppPaths.bootstrapHome().toAbsolutePath().normalize());
        Files.walkFileTree(abs, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (keepRedirectAnchor && bootstrap && "launcher-home.txt".equals(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                if (dir.equals(abs) && keepRedirectAnchor && bootstrap) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    Files.deleteIfExists(dir);
                } catch (Exception e) {
                    LOG.debug("Could not delete {}: {}", dir, e.toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static long roughSize(Path root) {
        AtomicLong total = new AtomicLong();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total.addAndGet(attrs.size());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            return 512L * 1024 * 1024;
        }
        return total.get();
    }
}
