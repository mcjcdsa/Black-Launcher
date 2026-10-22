package com.blacklauncher.download;

import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.model.VersionJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
// List.of used for argument side names

/**
 * Merges a loader profile JSON with its vanilla parent (inheritsFrom).
 */
public final class VersionJsonMerger {

    private VersionJsonMerger() {
    }

    public static VersionJson merge(VersionJson parent, VersionJson child) {
        JsonObject parentObj = JsonUtil.parse(JsonUtil.toJson(parent)).getAsJsonObject();
        JsonObject childObj = JsonUtil.parse(JsonUtil.toJson(child)).getAsJsonObject();
        JsonObject merged = deepMerge(parentObj, childObj);

        // Libraries: child first (HMCL), then parent fills missing names.
        JsonArray parentLibs = parentObj.has("libraries") ? parentObj.getAsJsonArray("libraries") : new JsonArray();
        JsonArray childLibs = childObj.has("libraries") ? childObj.getAsJsonArray("libraries") : new JsonArray();
        Map<String, JsonElement> libMap = new LinkedHashMap<>();
        for (JsonElement el : childLibs) {
            libMap.put(libraryKey(el), el);
        }
        for (JsonElement el : parentLibs) {
            libMap.putIfAbsent(libraryKey(el), el);
        }
        JsonArray libs = new JsonArray();
        for (JsonElement el : libMap.values()) {
            libs.add(el);
        }
        merged.add("libraries", libs);
        // Concatenate modern arguments arrays (parent then child) instead of replacing.
        merged.add("arguments", mergeArguments(
                parentObj.has("arguments") ? parentObj.get("arguments") : null,
                childObj.has("arguments") ? childObj.get("arguments") : null));
        // Keep walking the parent chain during launch-time resolution.
        if (parentObj.has("inheritsFrom") && !parentObj.get("inheritsFrom").isJsonNull()) {
            merged.add("inheritsFrom", parentObj.get("inheritsFrom"));
        } else {
            merged.remove("inheritsFrom");
        }

        VersionJson result = JsonUtil.fromJson(merged, VersionJson.class);
        if (result.getLibraries() == null) {
            result.setLibraries(new ArrayList<>());
        }
        if (!Validation.isNonBlank(result.getMainClass()) && Validation.isNonBlank(parent.getMainClass())) {
            result.setMainClass(parent.getMainClass());
        }
        if (result.getDownloads() == null) {
            result.setDownloads(parent.getDownloads());
        }
        if (result.getAssetIndex() == null) {
            result.setAssetIndex(parent.getAssetIndex());
        }
        if (!Validation.isNonBlank(result.getAssets())) {
            result.setAssets(parent.getAssets());
        }
        return result;
    }

    private static JsonObject deepMerge(JsonObject parent, JsonObject child) {
        JsonObject out = parent.deepCopy();
        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {
            String key = entry.getKey();
            if ("libraries".equals(key) || "inheritsFrom".equals(key) || "arguments".equals(key)) {
                continue;
            }
            out.add(key, entry.getValue());
        }
        return out;
    }

    private static JsonElement mergeArguments(JsonElement parentArgs, JsonElement childArgs) {
        if (parentArgs == null || parentArgs.isJsonNull()) {
            return childArgs == null ? new JsonObject() : childArgs.deepCopy();
        }
        if (childArgs == null || childArgs.isJsonNull()) {
            return parentArgs.deepCopy();
        }
        if (!parentArgs.isJsonObject() || !childArgs.isJsonObject()) {
            return childArgs.deepCopy();
        }
        JsonObject parent = parentArgs.getAsJsonObject();
        JsonObject child = childArgs.getAsJsonObject();
        JsonObject out = new JsonObject();
        for (String side : List.of("jvm", "game")) {
            JsonArray merged = new JsonArray();
            if (parent.has(side) && parent.get(side).isJsonArray()) {
                for (JsonElement el : parent.getAsJsonArray(side)) {
                    merged.add(el.deepCopy());
                }
            }
            if (child.has(side) && child.get(side).isJsonArray()) {
                for (JsonElement el : child.getAsJsonArray(side)) {
                    merged.add(el.deepCopy());
                }
            }
            if (merged.size() > 0) {
                out.add(side, merged);
            }
        }
        return out;
    }

    private static String libraryKey(JsonElement el) {
        if (el != null && el.isJsonObject() && el.getAsJsonObject().has("name")) {
            return el.getAsJsonObject().get("name").getAsString();
        }
        return String.valueOf(el);
    }

    public static List<VersionJson.Library> asList(VersionJson json) {
        return json.getLibraries() == null ? List.of() : json.getLibraries();
    }
}
