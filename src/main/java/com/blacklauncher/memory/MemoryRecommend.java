package com.blacklauncher.memory;

/**
 * Smart Xms/Xmx recommendation from total physical RAM and modpack hint.
 */
public final class MemoryRecommend {

    public record RecommendResult(
            int xmsMb,
            int xmxMb,
            int maxCapMb,
            String warningZh,
            boolean lowRamWarn
    ) {
    }

    private MemoryRecommend() {
    }

    public static RecommendResult recommend(long totalBytes, boolean modpack) {
        long totalMb = SystemMemoryProbe.bytesToMb(totalBytes);
        int xmx;
        int maxCap;
        String warning = "";
        boolean lowRam = false;

        if (totalMb <= 4096) {
            lowRam = true;
            warning = "本机内存≤4G，仅推荐1.5~2G，不建议大型模组包";
            xmx = modpack ? 2048 : 1536;
            maxCap = 2048;
        } else if (totalMb <= 8192) {
            xmx = modpack ? 3072 : 2048;
            maxCap = 3072;
        } else if (totalMb <= 16384) {
            xmx = modpack ? 5120 : 3072; // mid of 4–6G for modpack
            maxCap = (int) (totalMb / 2); // ≤50% of total
        } else {
            xmx = modpack ? 7168 : 4096; // mid of 6–8G for modpack
            maxCap = (int) (totalMb / 2);
        }

        xmx = Math.max(512, Math.min(xmx, maxCap));
        int xms = suggestXms(xmx, false);
        return new RecommendResult(xms, xmx, maxCap, warning, lowRam);
    }

    /**
     * For low_mem scheme Xms is always 512; otherwise min(xmx, max(512, xmx/2)).
     */
    public static int suggestXms(int xmxMb, boolean lowMemScheme) {
        if (lowMemScheme) {
            return 512;
        }
        return Math.min(xmxMb, Math.max(512, xmxMb / 2));
    }

    /** True when Xmx exceeds 70% of physical RAM (confirm dialog). */
    public static boolean exceeds70Percent(int xmxMb, long totalBytes) {
        long totalMb = SystemMemoryProbe.bytesToMb(totalBytes);
        if (totalMb <= 0) {
            return false;
        }
        return xmxMb > totalMb * 0.70;
    }

    public static RecommendResult applySchemeXms(RecommendResult base, String schemeId) {
        if (base == null) {
            return null;
        }
        boolean low = "low_mem".equalsIgnoreCase(schemeId);
        return new RecommendResult(
                suggestXms(base.xmxMb(), low),
                base.xmxMb(),
                base.maxCapMb(),
                base.warningZh(),
                base.lowRamWarn());
    }
}
