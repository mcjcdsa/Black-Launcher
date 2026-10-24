package com.blacklauncher.moddev;

/**
 * Parameters for generating a Fabric mod project.
 * <p>
 * Optional version fields ({@code yarnVersion}, {@code loaderVersion}, {@code fabricApiVersion})
 * skip network resolution when non-blank (useful for tests).
 */
public record TemplateRequest(
        String modName,
        String modId,
        String packageName,
        String minecraftVersion,
        boolean kotlin,
        boolean dataGen,
        boolean splitSources,
        boolean kotlinDsl,
        String yarnVersion,
        String loaderVersion,
        String fabricApiVersion
) {
    public TemplateRequest(
            String modName,
            String modId,
            String packageName,
            String minecraftVersion,
            boolean kotlin,
            boolean dataGen,
            boolean splitSources,
            boolean kotlinDsl) {
        this(modName, modId, packageName, minecraftVersion, kotlin, dataGen, splitSources, kotlinDsl,
                null, null, null);
    }
}
