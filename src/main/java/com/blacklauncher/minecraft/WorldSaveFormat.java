package com.blacklauncher.minecraft;

/**
 * Single-player world on-disk layout (doc {@code 3} §3).
 */
public enum WorldSaveFormat {
    /** 1.21 and earlier: DIM-1 / DIM1 at world root; playerdata / advancements / stats at root. */
    LEGACY_DIM,
    /** 26wxx+ style: dimensions/minecraft/{overworld,the_nether,the_end}; players/* ; data/*. */
    DIMENSIONS_TREE,
    /** Could not determine (missing level.dat or unrecognized). */
    UNKNOWN
}
