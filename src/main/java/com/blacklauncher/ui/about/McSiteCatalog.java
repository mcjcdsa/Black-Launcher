package com.blacklauncher.ui.about;

import java.util.List;

/**
 * Hard-coded Minecraft-related site navigation (§9). Order is fixed.
 */
public final class McSiteCatalog {

    public record Site(String name, String url) {
    }

    public record Group(String title, List<Site> sites) {
    }

    private McSiteCatalog() {
    }

    public static List<Group> groups() {
        return List.of(
                new Group("🏠 官方网站", List.of(
                        new Site("Minecraft 官网", "https://www.minecraft.net/zh-hans"),
                        new Site("网易我的世界", "https://mc.163.com"),
                        new Site("Mojang Bug反馈", "https://bugs.mojang.com"),
                        new Site("Minecraft Classic（网页版）", "https://classic.minecraft.net")
                )),
                new Group("🧩 模组 / 资源平台", List.of(
                        new Site("CurseForge", "https://www.curseforge.com/minecraft"),
                        new Site("Modrinth", "https://modrinth.com"),
                        new Site("MC百科（中文Mod数据库）", "https://www.mcmod.cn"),
                        new Site("Planet Minecraft", "https://www.planetminecraft.com"),
                        new Site("BBSMC", "https://bbsmc.net"),
                        new Site("MinecraftYard（中文下载）", "https://minecraftyard.net")
                )),
                new Group("💬 中文社区 / 论坛", List.of(
                        new Site("MineBBS", "https://www.minebbs.com"),
                        new Site("苦力怕论坛", "https://klpbbs.com"),
                        new Site("Minecraft 中文论坛（MCBBS）", "https://www.mcbbs.net"),
                        new Site("HiMCBBS", "https://www.himcbbs.com"),
                        new Site("草方块社区", "https://mccfk.com")
                )),
                new Group("📖 Wiki / 百科", List.of(
                        new Site("中文 Minecraft Wiki", "https://zh.minecraft.wiki"),
                        new Site("B站玩家攻略WIKI", "https://wiki.biligame.com/mc/Minecraft_Wiki"),
                        new Site("DigMinecraft（英文教程）", "https://digminecraft.com"),
                        new Site("Bedrock Wiki（基岩版开发）", "https://wiki.bedrock.dev"),
                        new Site("插件百科", "https://mineplugin.org")
                )),
                new Group("🖼 光影 / 材质包 / 皮肤", List.of(
                        new Site("MGC 中文图形站", "https://minegraph.cn"),
                        new Site("The Skindex（皮肤）", "https://www.minecraftskins.com"),
                        new Site("NameMC（皮肤查询）", "https://namemc.com"),
                        new Site("ResourcePack（材质包）", "https://resourcepack.net"),
                        new Site("Shaders Mods（光影）", "https://shadersmods.com")
                )),
                new Group("🗺 地图", List.of(
                        new Site("MinecraftMaps", "https://www.minecraftmaps.com"),
                        new Site("像素茶艺地图站", "https://www.pixieteamap.com")
                )),
                new Group("🖥 服务器列表", List.of(
                        new Site("FindMCServer（官方推荐）", "https://findmcserver.com"),
                        new Site("Minecraft-Server.net", "https://minecraft-server.net"),
                        new Site("MinecraftServers.org", "https://minecraftservers.org"),
                        new Site("MCLists.cn（国内）", "https://mclists.cn")
                )),
                new Group("🛠 实用工具", List.of(
                        new Site("CHUNKBASE（种子/地图查询）", "https://www.chunkbase.com"),
                        new Site("Minecraft Tools（合成/附魔/命令）", "https://minecraft.tools"),
                        new Site("mclo.gs（日志分析排错）", "https://mclo.gs"),
                        new Site("Blockbench（模型编辑）", "https://www.blockbench.net"),
                        new Site("命令生成器", "https://ca.projectxero.top"),
                        new Site("MC皮肤编辑器", "https://minecraftskineditor.org")
                )),
                new Group("📦 基岩版专属", List.of(
                        new Site("MCPEDL", "https://mcpedl.com"),
                        new Site("MCAPPX 版本库", "https://www.mcappx.com"),
                        new Site("MineAPK 版本库", "https://www.mineapk.cn")
                )),
                new Group("🌐 导航站（一键跳转）", List.of(
                        new Site("MCNav 导航网", "https://www.mcnav.net"),
                        new Site("所见皆是MC", "https://mcisee.top")
                ))
        );
    }
}
