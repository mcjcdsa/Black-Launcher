package com.blacklauncher.modpack;

import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.core.util.ZipSafe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Auto-detects modpack format (§6.3) from a zip/mrpack file or folder.
 */
public final class ModpackDetector {

    private ModpackDetector() {
    }

    public static ModpackDescriptor detect(Path path) throws LauncherException {
        if (path == null || !Files.exists(path)) {
            throw new LauncherException("整合包路径不存在");
        }
        try {
            if (Files.isDirectory(path)) {
                return detectFolder(path);
            }
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".zip") || name.endsWith(".mrpack"))) {
                throw new LauncherException("仅支持 .zip / .mrpack 整合包文件");
            }
            return detectArchive(path);
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("识别整合包失败: " + e.getMessage(), e);
        }
    }

    private static ModpackDescriptor detectArchive(Path zip) throws Exception {
        if (ZipSafe.containsEntry(zip, "modrinth.index.json")) {
            String json = ZipSafe.readEntryUtf8(zip, "modrinth.index.json");
            ModpackDescriptor d = ModrinthIndexParser.parse(json);
            d.setSourceArchive(zip);
            if (!Validation.isNonBlank(d.getName())) {
                d.setName(stripExt(zip.getFileName().toString()));
            }
            return d;
        }

        if (ZipSafe.containsEntry(zip, "manifest.json")) {
            boolean embedded = ZipSafe.hasJarUnder(zip, "mods");
            String json = ZipSafe.readEntryUtf8(zip, "manifest.json");
            ModpackDescriptor d = CurseForgeManifestParser.parse(json, embedded);
            d.setSourceArchive(zip);
            if (!Validation.isNonBlank(d.getName())) {
                d.setName(stripExt(zip.getFileName().toString()));
            }
            return d;
        }

        // Nested .minecraft / versions layout inside zip — treat as folder-style after extract.
        if (ZipSafe.hasEntryPrefix(zip, "versions")
                || ZipSafe.containsEntry(zip, "options.txt")
                || ZipSafe.hasEntryPrefix(zip, ".minecraft")) {
            ModpackDescriptor d = new ModpackDescriptor();
            d.setFormat(ModpackFormat.FOLDER);
            d.setSourceArchive(zip);
            d.setName(stripExt(zip.getFileName().toString()));
            return d;
        }

        if (ZipSafe.hasEntryPrefix(zip, "mods") || ZipSafe.hasEntryPrefix(zip, "config")) {
            ModpackDescriptor d = new ModpackDescriptor();
            d.setFormat(ModpackFormat.SIMPLE);
            d.setSourceArchive(zip);
            d.setName(stripExt(zip.getFileName().toString()));
            d.setHasEmbeddedMods(ZipSafe.hasJarUnder(zip, "mods"));
            return d;
        }

        throw new LauncherException("无法识别的整合包格式");
    }

    private static ModpackDescriptor detectFolder(Path dir) throws Exception {
        Path mr = dir.resolve("modrinth.index.json");
        if (Files.isRegularFile(mr)) {
            ModpackDescriptor d = ModrinthIndexParser.parse(Files.readString(mr));
            d.setSourceFolder(dir);
            return d;
        }
        Path manifest = dir.resolve("manifest.json");
        if (Files.isRegularFile(manifest)) {
            boolean embedded = Files.isDirectory(dir.resolve("mods"))
                    && Files.list(dir.resolve("mods")).anyMatch(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".jar"));
            ModpackDescriptor d = CurseForgeManifestParser.parse(Files.readString(manifest), embedded);
            d.setSourceFolder(dir);
            return d;
        }
        if (Files.isDirectory(dir.resolve("versions"))
                || Files.isRegularFile(dir.resolve("options.txt"))
                || Files.isDirectory(dir.resolve(".minecraft"))) {
            ModpackDescriptor d = new ModpackDescriptor();
            d.setFormat(ModpackFormat.FOLDER);
            d.setSourceFolder(dir);
            d.setName(dir.getFileName().toString());
            return d;
        }
        if (Files.isDirectory(dir.resolve("mods")) || Files.isDirectory(dir.resolve("config"))) {
            ModpackDescriptor d = new ModpackDescriptor();
            d.setFormat(ModpackFormat.SIMPLE);
            d.setSourceFolder(dir);
            d.setName(dir.getFileName().toString());
            d.setHasEmbeddedMods(Files.isDirectory(dir.resolve("mods")));
            return d;
        }
        throw new LauncherException("无法识别的整合包格式");
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
