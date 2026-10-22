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
 * Parses {@code modrinth.index.json} from an {@code .mrpack}.
 */
public final class ModrinthIndexParser {

    private ModrinthIndexParser() {
    }

    public static ModpackDescriptor parse(String json) throws LauncherException {
        try {
            JsonObject root = JsonUtil.parse(json).getAsJsonObject();
            ModpackDescriptor d = new ModpackDescriptor();
            d.setFormat(ModpackFormat.MODRINTH);
            if (root.has("name")) {
                d.setName(root.get("name").getAsString());
            }
            if (root.has("versionId")) {
                d.setVersion(root.get("versionId").getAsString());
            } else if (root.has("version")) {
                d.setVersion(root.get("version").getAsString());
            }

            if (root.has("dependencies") && root.get("dependencies").isJsonObject()) {
                JsonObject deps = root.getAsJsonObject("dependencies");
                applyDependency(d, deps, "minecraft", true);
                if (deps.has("fabric-loader")) {
                    d.setLoader(ModLoader.FABRIC);
                    d.setLoaderVersion(deps.get("fabric-loader").getAsString());
                } else if (deps.has("quilt-loader")) {
                    d.setLoader(ModLoader.QUILT);
                    d.setLoaderVersion(deps.get("quilt-loader").getAsString());
                } else if (deps.has("forge")) {
                    d.setLoader(ModLoader.FORGE);
                    d.setLoaderVersion(deps.get("forge").getAsString());
                } else if (deps.has("neoforge")) {
                    d.setLoader(ModLoader.NEOFORGE);
                    d.setLoaderVersion(deps.get("neoforge").getAsString());
                }
            }

            if (root.has("files") && root.get("files").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("files")) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject f = el.getAsJsonObject();
                    if (f.has("env") && f.get("env").isJsonObject()) {
                        JsonObject env = f.getAsJsonObject("env");
                        String client = env.has("client") ? env.get("client").getAsString() : "required";
                        if ("unsupported".equalsIgnoreCase(client)) {
                            continue;
                        }
                    }
                    ModpackFileEntry entry = new ModpackFileEntry();
                    if (f.has("path")) {
                        entry.setPath(f.get("path").getAsString().replace('\\', '/'));
                    }
                    if (f.has("fileSize")) {
                        entry.setFileSize(f.get("fileSize").getAsLong());
                    }
                    if (f.has("hashes") && f.get("hashes").isJsonObject()) {
                        JsonObject hashes = f.getAsJsonObject("hashes");
                        if (hashes.has("sha1")) {
                            entry.setSha1(hashes.get("sha1").getAsString());
                        }
                        if (hashes.has("sha512")) {
                            entry.setSha512(hashes.get("sha512").getAsString());
                        }
                    }
                    if (f.has("downloads") && f.get("downloads").isJsonArray()) {
                        JsonArray urls = f.getAsJsonArray("downloads");
                        for (JsonElement u : urls) {
                            if (u.isJsonPrimitive()) {
                                entry.getDownloads().add(u.getAsString());
                            }
                        }
                    }
                    d.getFiles().add(entry);
                }
            }
            if (!Validation.isNonBlank(d.getMcVersion())) {
                throw new LauncherException("modrinth.index.json 缺少 minecraft 版本");
            }
            return d;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("解析 Modrinth 整合包失败: " + e.getMessage(), e);
        }
    }

    private static void applyDependency(ModpackDescriptor d, JsonObject deps, String key, boolean mc) {
        if (!deps.has(key)) {
            return;
        }
        String v = deps.get(key).getAsString();
        if (mc) {
            d.setMcVersion(v);
        }
    }

    /** Build a minimal index JSON for export (offline-friendly: empty downloads → pack embeds files). */
    public static String buildIndex(ModpackDescriptor d) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);
        root.addProperty("game", "minecraft");
        root.addProperty("versionId", d.getVersion() == null ? "1.0" : d.getVersion());
        root.addProperty("name", d.getName() == null ? "modpack" : d.getName());
        root.addProperty("summary", "");

        JsonObject deps = new JsonObject();
        deps.addProperty("minecraft", d.getMcVersion() == null ? "" : d.getMcVersion());
        if (d.getLoader() == ModLoader.FABRIC && Validation.isNonBlank(d.getLoaderVersion())) {
            deps.addProperty("fabric-loader", d.getLoaderVersion());
        } else if (d.getLoader() == ModLoader.QUILT && Validation.isNonBlank(d.getLoaderVersion())) {
            deps.addProperty("quilt-loader", d.getLoaderVersion());
        } else if (d.getLoader() == ModLoader.FORGE && Validation.isNonBlank(d.getLoaderVersion())) {
            deps.addProperty("forge", d.getLoaderVersion());
        } else if (d.getLoader() == ModLoader.NEOFORGE && Validation.isNonBlank(d.getLoaderVersion())) {
            deps.addProperty("neoforge", d.getLoaderVersion());
        }
        root.add("dependencies", deps);
        root.add("files", new JsonArray());
        return JsonUtil.toJsonPretty(root);
    }

    public static ModLoader guessLoaderFromId(String id) {
        if (!Validation.isNonBlank(id)) {
            return ModLoader.VANILLA;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("fabric")) {
            return ModLoader.FABRIC;
        }
        if (lower.contains("quilt")) {
            return ModLoader.QUILT;
        }
        if (lower.contains("neoforge")) {
            return ModLoader.NEOFORGE;
        }
        if (lower.contains("forge")) {
            return ModLoader.FORGE;
        }
        return ModLoader.VANILLA;
    }

    public static String guessLoaderVersionFromId(String versionId, ModLoader loader) {
        if (!Validation.isNonBlank(versionId) || loader == null || loader == ModLoader.VANILLA) {
            return null;
        }
        // fabric-loader-0.15.11-1.20.1 / 1.20.1-forge-47.2.0
        String id = versionId.toLowerCase(Locale.ROOT);
        try {
            if (loader == ModLoader.FABRIC && id.contains("fabric-loader-")) {
                int start = id.indexOf("fabric-loader-") + "fabric-loader-".length();
                int end = id.indexOf('-', start);
                return end > start ? versionId.substring(start, end) : null;
            }
            if (loader == ModLoader.FORGE && id.contains("-forge-")) {
                return versionId.substring(id.lastIndexOf("-forge-") + "-forge-".length());
            }
            if (loader == ModLoader.NEOFORGE && id.contains("neoforge-")) {
                int start = id.indexOf("neoforge-") + "neoforge-".length();
                return versionId.substring(start);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }
}
