package com.blacklauncher.minecraft;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.nbt.NbtIo;
import com.blacklauncher.minecraft.nbt.NbtIo.NbtCompound;
import com.blacklauncher.minecraft.nbt.NbtIo.NbtList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes Minecraft {@code servers.dat} for a version instance.
 * {@code pinned} is a launcher-side flag: pinned entries are stored first in the list.
 */
public final class ServerListService {

    private static final Logger LOG = LoggerFactory.getLogger(ServerListService.class);

    private final AppConfig config;

    public ServerListService(AppConfig config) {
        this.config = config;
    }

    public record ServerEntry(String name, String ip, boolean pinned) {
        public ServerEntry {
            name = name == null ? "" : name.trim();
            ip = ip == null ? "" : ip.trim();
        }

        public ServerEntry withPinned(boolean pinned) {
            return new ServerEntry(name, ip, pinned);
        }
    }

    public Path serversDatPath(String versionId) {
        return MinecraftLayout.forVersion(config, versionId).resolve(DirectoryKind.SERVERS_DAT);
    }

    public List<ServerEntry> load(AppConfig cfg, String versionId) throws Exception {
        AppConfig use = cfg != null ? cfg : config;
        Path file = MinecraftLayout.forVersion(use, versionId).resolve(DirectoryKind.SERVERS_DAT);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        NbtCompound root = NbtIo.readGzip(file);
        NbtList list = root.getList("servers");
        List<ServerEntry> out = new ArrayList<>();
        if (list == null || list.elementType() != NbtIo.TAG_COMPOUND) {
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            NbtCompound c = list.compoundAt(i);
            if (c == null) {
                continue;
            }
            String name = c.getString("name");
            String ip = c.getString("ip");
            if (!Validation.isNonBlank(ip) && !Validation.isNonBlank(name)) {
                continue;
            }
            // First entry treated as pinned for UI; order is the source of truth in NBT.
            out.add(new ServerEntry(name, ip, i == 0));
        }
        return out;
    }

    public void save(AppConfig cfg, String versionId, List<ServerEntry> entries) throws Exception {
        AppConfig use = cfg != null ? cfg : config;
        GameProcessGuard.get().requireWritable(DirectoryKind.SERVERS_DAT);
        List<ServerEntry> ordered = orderForSave(entries);
        NbtCompound root = new NbtCompound();
        List<NbtCompound> compounds = new ArrayList<>(ordered.size());
        for (ServerEntry e : ordered) {
            if (e == null || !Validation.isNonBlank(e.ip())) {
                continue;
            }
            NbtCompound c = new NbtCompound();
            c.putString("name", e.name() == null || e.name().isBlank() ? e.ip() : e.name());
            c.putString("ip", e.ip());
            compounds.add(c);
        }
        root.putList("servers", NbtList.ofCompounds(compounds));
        Path file = MinecraftLayout.forVersion(use, versionId).resolve(DirectoryKind.SERVERS_DAT);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        NbtIo.writeGzip(file, root);
        LOG.info("Wrote {} servers to {}", compounds.size(), file);
    }

    public void addOrUpdate(AppConfig cfg, String versionId, ServerEntry entry) throws Exception {
        if (entry == null || !Validation.isNonBlank(entry.ip())) {
            throw new LauncherException("服务器地址不能为空");
        }
        List<ServerEntry> list = load(cfg, versionId);
        String key = normalizeIp(entry.ip());
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (normalizeIp(list.get(i).ip()).equals(key)) {
                boolean pinned = entry.pinned() || list.get(i).pinned();
                list.set(i, new ServerEntry(entry.name(), entry.ip(), pinned));
                found = true;
                break;
            }
        }
        if (!found) {
            if (entry.pinned()) {
                list.add(0, entry);
            } else {
                list.add(entry);
            }
        } else if (entry.pinned()) {
            pinInList(list, entry.ip());
        }
        save(cfg, versionId, list);
    }

    public void remove(AppConfig cfg, String versionId, String ip) throws Exception {
        if (!Validation.isNonBlank(ip)) {
            return;
        }
        String key = normalizeIp(ip);
        List<ServerEntry> list = load(cfg, versionId);
        list.removeIf(e -> normalizeIp(e.ip()).equals(key));
        for (int i = 0; i < list.size(); i++) {
            ServerEntry e = list.get(i);
            list.set(i, e.withPinned(i == 0));
        }
        save(cfg, versionId, list);
    }

    public void pin(AppConfig cfg, String versionId, String ip) throws Exception {
        if (!Validation.isNonBlank(ip)) {
            throw new LauncherException("服务器地址不能为空");
        }
        List<ServerEntry> list = load(cfg, versionId);
        if (!pinInList(list, ip)) {
            throw new LauncherException("未找到服务器：" + ip);
        }
        save(cfg, versionId, list);
    }

    private static boolean pinInList(List<ServerEntry> list, String ip) {
        String key = normalizeIp(ip);
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (normalizeIp(list.get(i).ip()).equals(key)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return false;
        }
        ServerEntry target = list.remove(idx).withPinned(true);
        list.add(0, target);
        for (int i = 1; i < list.size(); i++) {
            list.set(i, list.get(i).withPinned(false));
        }
        return true;
    }

    private static List<ServerEntry> orderForSave(List<ServerEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<ServerEntry> pinned = new ArrayList<>();
        List<ServerEntry> rest = new ArrayList<>();
        for (ServerEntry e : entries) {
            if (e == null) {
                continue;
            }
            if (e.pinned()) {
                pinned.add(e);
            } else {
                rest.add(e);
            }
        }
        List<ServerEntry> out = new ArrayList<>(pinned.size() + rest.size());
        out.addAll(pinned);
        out.addAll(rest);
        return out;
    }

    static String normalizeIp(String ip) {
        if (ip == null) {
            return "";
        }
        return ip.trim().toLowerCase(Locale.ROOT);
    }
}
