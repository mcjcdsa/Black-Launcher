package com.blacklauncher.minecraft.nbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal big-endian NBT reader/writer for gzip compounds (e.g. {@code servers.dat}).
 * Supports all standard tag types for correct skipping; writing covers what we need for servers.
 */
public final class NbtIo {

    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    private NbtIo() {
    }

    /** Reads a gzip-compressed unnamed (or named) root compound. */
    public static NbtCompound readGzip(Path path) throws IOException {
        try (InputStream raw = Files.newInputStream(path);
             GZIPInputStream gzip = new GZIPInputStream(raw);
             DataInputStream in = new DataInputStream(gzip)) {
            return readRootCompound(in);
        }
    }

    /** Writes an unnamed root compound as gzip (Minecraft {@code servers.dat} style). */
    public static void writeGzip(Path path, NbtCompound root) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(TAG_COMPOUND);
            out.writeUTF(""); // unnamed root
            writeCompoundPayload(out, root);
        }
        try (OutputStream raw = Files.newOutputStream(path);
             GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
            gzip.write(bos.toByteArray());
        }
    }

    static NbtCompound readRootCompound(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        if (type != TAG_COMPOUND) {
            throw new IOException("Expected root TAG_Compound, got type " + type);
        }
        in.readUTF(); // root name (usually empty)
        return readCompoundPayload(in);
    }

    static NbtCompound readCompoundPayload(DataInputStream in) throws IOException {
        NbtCompound compound = new NbtCompound();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return compound;
            }
            String name = in.readUTF();
            Object value = readPayload(in, type);
            compound.putRaw(name, type, value);
        }
    }

    static Object readPayload(DataInputStream in, int type) throws IOException {
        return switch (type) {
            case TAG_BYTE -> in.readByte();
            case TAG_SHORT -> in.readShort();
            case TAG_INT -> in.readInt();
            case TAG_LONG -> in.readLong();
            case TAG_FLOAT -> in.readFloat();
            case TAG_DOUBLE -> in.readDouble();
            case TAG_BYTE_ARRAY -> {
                int len = in.readInt();
                ensureNonNegative(len, "byte array");
                byte[] data = new byte[len];
                in.readFully(data);
                yield data;
            }
            case TAG_STRING -> in.readUTF();
            case TAG_LIST -> readList(in);
            case TAG_COMPOUND -> readCompoundPayload(in);
            case TAG_INT_ARRAY -> {
                int len = in.readInt();
                ensureNonNegative(len, "int array");
                int[] data = new int[len];
                for (int i = 0; i < len; i++) {
                    data[i] = in.readInt();
                }
                yield data;
            }
            case TAG_LONG_ARRAY -> {
                int len = in.readInt();
                ensureNonNegative(len, "long array");
                long[] data = new long[len];
                for (int i = 0; i < len; i++) {
                    data[i] = in.readLong();
                }
                yield data;
            }
            default -> throw new IOException("Unknown NBT tag type: " + type);
        };
    }

    static NbtList readList(DataInputStream in) throws IOException {
        int elemType = in.readUnsignedByte();
        int len = in.readInt();
        ensureNonNegative(len, "list");
        List<Object> items = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            if (elemType == TAG_END) {
                break;
            }
            items.add(readPayload(in, elemType));
        }
        return new NbtList(elemType, items);
    }

    static void writeCompoundPayload(DataOutputStream out, NbtCompound compound) throws IOException {
        for (Map.Entry<String, NbtCompound.Entry> e : compound.entries().entrySet()) {
            out.writeByte(e.getValue().type());
            out.writeUTF(e.getKey());
            writePayload(out, e.getValue().type(), e.getValue().value());
        }
        out.writeByte(TAG_END);
    }

    static void writePayload(DataOutputStream out, int type, Object value) throws IOException {
        switch (type) {
            case TAG_BYTE -> out.writeByte((Byte) value);
            case TAG_SHORT -> out.writeShort((Short) value);
            case TAG_INT -> out.writeInt((Integer) value);
            case TAG_LONG -> out.writeLong((Long) value);
            case TAG_FLOAT -> out.writeFloat((Float) value);
            case TAG_DOUBLE -> out.writeDouble((Double) value);
            case TAG_BYTE_ARRAY -> {
                byte[] data = (byte[]) value;
                out.writeInt(data.length);
                out.write(data);
            }
            case TAG_STRING -> out.writeUTF((String) value);
            case TAG_LIST -> writeList(out, (NbtList) value);
            case TAG_COMPOUND -> writeCompoundPayload(out, (NbtCompound) value);
            case TAG_INT_ARRAY -> {
                int[] data = (int[]) value;
                out.writeInt(data.length);
                for (int v : data) {
                    out.writeInt(v);
                }
            }
            case TAG_LONG_ARRAY -> {
                long[] data = (long[]) value;
                out.writeInt(data.length);
                for (long v : data) {
                    out.writeLong(v);
                }
            }
            default -> throw new IOException("Cannot write NBT tag type: " + type);
        }
    }

    static void writeList(DataOutputStream out, NbtList list) throws IOException {
        out.writeByte(list.elementType());
        out.writeInt(list.size());
        for (Object item : list.items()) {
            writePayload(out, list.elementType(), item);
        }
    }

    /** Round-trip helper used by tests: parse gzip bytes already in memory. */
    public static NbtCompound readGzipBytes(byte[] gzipped) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gzipped)))) {
            return readRootCompound(in);
        }
    }

    private static void ensureNonNegative(int len, String what) throws IOException {
        if (len < 0) {
            throw new IOException("Negative " + what + " length: " + len);
        }
        if (len > 16_777_216) {
            throw new IOException(what + " length too large: " + len);
        }
    }

    /** Mutable named-tag compound. */
    public static final class NbtCompound {
        private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>();

        public record Entry(int type, Object value) {
        }

        public Map<String, Entry> entries() {
            return map;
        }

        void putRaw(String name, int type, Object value) {
            map.put(name, new Entry(type, value));
        }

        public void putString(String name, String value) {
            map.put(name, new Entry(TAG_STRING, value == null ? "" : value));
        }

        public void putByte(String name, byte value) {
            map.put(name, new Entry(TAG_BYTE, value));
        }

        public void putList(String name, NbtList list) {
            map.put(name, new Entry(TAG_LIST, list));
        }

        public String getString(String name) {
            Entry e = map.get(name);
            if (e == null || e.type() != TAG_STRING) {
                return "";
            }
            return e.value() == null ? "" : e.value().toString();
        }

        public NbtList getList(String name) {
            Entry e = map.get(name);
            if (e == null || e.type() != TAG_LIST) {
                return null;
            }
            return (NbtList) e.value();
        }

        public boolean contains(String name) {
            return map.containsKey(name);
        }
    }

    /** Homogeneous NBT list. */
    public static final class NbtList {
        private final int elementType;
        private final List<Object> items;

        public NbtList(int elementType, List<Object> items) {
            this.elementType = elementType;
            this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
        }

        public static NbtList ofCompounds(List<NbtCompound> compounds) {
            List<Object> items = new ArrayList<>(compounds == null ? List.of() : compounds);
            return new NbtList(TAG_COMPOUND, items);
        }

        public int elementType() {
            return elementType;
        }

        public List<Object> items() {
            return items;
        }

        public int size() {
            return items.size();
        }

        public NbtCompound compoundAt(int index) {
            Object o = items.get(index);
            return o instanceof NbtCompound c ? c : null;
        }
    }
}
