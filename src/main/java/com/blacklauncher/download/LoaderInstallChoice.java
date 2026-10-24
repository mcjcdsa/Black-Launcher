package com.blacklauncher.download;

/**
 * Result of the pre-download loader dialog (§10.1).
 *
 * @param loader              install flavour
 * @param loaderVersion       pinned loader version; null/blank = auto pick
 * @param installCompanionApi whether to auto-install Fabric API / Quilted Fabric API
 */
public record LoaderInstallChoice(
        ModLoader loader,
        String loaderVersion,
        boolean installCompanionApi
) {
    public static LoaderInstallChoice vanilla() {
        return new LoaderInstallChoice(ModLoader.VANILLA, null, false);
    }

    public ModLoader loaderOrVanilla() {
        return loader == null ? ModLoader.VANILLA : loader;
    }
}
