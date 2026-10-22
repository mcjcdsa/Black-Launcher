package com.blacklauncher.modpack;

import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.ModLoader;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * Parses CurseForge {@code manifest.json}.
 */
public final class CurseForgeManifestParser {

    private CurseForgeManifestParser() {
    }

    public static ModpackDescriptor parse(String json, boolean embeddedMods) throws LauncherException {
        try {
            JsonObject root = JsonUtil.parse(json).getAsJsonObject();
            ModpackDescriptor d = new ModpackDescriptor();
            d.setFormat(embeddedMods ? ModpackFormat.CURSEFORGE_EMBEDDED : ModpackFormat.CURSEFORGE_ONLINE);
            d.setHasEmbeddedMods(embeddedMods);

            if (root.has("name")) {
                d.setName(root.get("name").getAsString());
            }
            if (root.has("version")) {
                d.setVersion(root.get("version").getAsString());
            }
            if (root.has("overrides")) {
                d.setOverridesDir(root.get("overrides").getAsString());
            } else {
                d.setOverridesDir("overrides");
            }

            if (root.has("minecraft") && root.get("minecraft").isJsonObject()) {
                JsonObject mc = root.getAsJsonObject("minecraft");
                if (mc.has("version")) {
                    d.setMcVersion(mc.get("version").getAsString());
                }
                if (mc.has("modLoaders") && mc.get("modLoaders").isJsonArray()) {
                    parseLoaders(d, mc.getAsJsonArray("modLoaders"));
                }
            }

            if (!embeddedMods && root.has("files") && root.get("files").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("files")) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject f = el.getAsJsonObject();
                    ModpackFileEntry entry = new ModpackFileEntry();
                    if (f.has("projectID")) {
                        entry.setCurseProjectId(f.get("projectID").getAsInt());
                    } else if (f.has("projectId")) {
                        entry.setCurseProjectId(f.get("projectId").getAsInt());
                    }
                    if (f.has("fileID")) {
                        entry.setCurseFileId(f.get("fileID").getAsInt());
                    } else if (f.has("fileId")) {
                        entry.setCurseFileId(f.get("fileId").getAsInt());
                    }
                    if (f.has("required")) {
                        entry.setRequired(f.get("required").getAsBoolean());
                    }
                    entry.setPath("mods/curse-" + entry.getCurseProjectId() + "-" + entry.getCurseFileId() + ".jar");
                    if (entry.getCurseProjectId() > 0 && entry.getCurseFileId() > 0) {
                        d.getFiles().add(entry);
                    }
                }
            }

            if (!Validation.isNonBlank(d.getMcVersion())) {
                throw new LauncherException("manifest.json 缺少 minecraft.version");
            }
            if (!Validation.isNonBlank(d.getName())) {
                d.setName("curseforge-pack");
            }
            return d;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("解析 CurseForge 整合包失败: " + e.getMessage(), e);
        }
    }

    private static void parseLoaders(ModpackDescriptor d, JsonArray loaders) {
        JsonObject primary = null;
        for (JsonElement el : loaders) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (o.has("primary") && o.get("primary").getAsBoolean()) {
                primary = o;
                break;
            }
            if (primary == null) {
                primary = o;
            }
        }
        if (primary == null || !primary.has("id")) {
            return;
        }
        String id = primary.get("id").getAsString();
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("forge-")) {
            d.setLoader(ModLoader.FORGE);
            d.setLoaderVersion(id.substring("forge-".length()));
        } else if (lower.startsWith("neoforge-")) {
            d.setLoader(ModLoader.NEOFORGE);
            d.setLoaderVersion(id.substring("neoforge-".length()));
        } else if (lower.startsWith("fabric-")) {
            d.setLoader(ModLoader.FABRIC);
            d.setLoaderVersion(id.substring("fabric-".length()));
        } else if (lower.startsWith("quilt-")) {
            d.setLoader(ModLoader.QUILT);
            d.setLoaderVersion(id.substring("quilt-".length()));
        }
    }

    public static String buildManifest(ModpackDescriptor d) {
        JsonObject root = new JsonObject();
        JsonObject mc = new JsonObject();
        mc.addProperty("version", d.getMcVersion() == null ? "" : d.getMcVersion());
        JsonArray loaders = new JsonArray();
        if (d.getLoader() != null && d.getLoader() != ModLoader.VANILLA
                && Validation.isNonBlank(d.getLoaderVersion())) {
            JsonObject loader = new JsonObject();
            String prefix = switch (d.getLoader()) {
                case FABRIC -> "fabric-";
                case QUILT -> "quilt-";
                case NEOFORGE -> "neoforge-";
                default -> "forge-";
            };
            loader.addProperty("id", prefix + d.getLoaderVersion());
            loader.addProperty("primary", true);
            loaders.add(loader);
        }
        mc.add("modLoaders", loaders);
        root.add("minecraft", mc);
        root.addProperty("manifestType", "minecraftModpack");
        root.addProperty("manifestVersion", 1);
        root.addProperty("name", d.getName() == null ? "modpack" : d.getName());
        root.addProperty("version", d.getVersion() == null ? "1.0" : d.getVersion());
        root.addProperty("author", "");
        root.add("files", new JsonArray());
        root.addProperty("overrides", "overrides");
        return JsonUtil.toJsonPretty(root);
    }
}
