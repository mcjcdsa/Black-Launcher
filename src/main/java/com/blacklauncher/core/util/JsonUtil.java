package com.blacklauncher.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Shared Gson helpers.
 */
public final class JsonUtil {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private JsonUtil() {
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T fromJson(JsonElement element, Class<T> type) {
        return GSON.fromJson(element, type);
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static String toJsonPretty(Object value) {
        return PRETTY.toJson(value);
    }

    public static JsonElement parse(String json) {
        return JsonParser.parseString(json);
    }
}
