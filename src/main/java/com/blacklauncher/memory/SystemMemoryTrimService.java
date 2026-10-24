package com.blacklauncher.memory;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Best-effort system memory trim: EmptyWorkingSet on launcher only + optional Standby List purge.
 * Never enumerates or trims other user processes.
 */
public final class SystemMemoryTrimService {

    private static final Logger LOG = LoggerFactory.getLogger(SystemMemoryTrimService.class);

    /** SystemMemoryListInformation / MemoryPurgeStandbyList style (undocumented). */
    private static final int SystemMemoryListInformation = 80;
    private static final int MemoryPurgeStandbyList = 4;

    private SystemMemoryTrimService() {
    }

    public static boolean isTrimSupported() {
        return isWindows();
    }

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    public static boolean isElevated() {
        if (!isWindows()) {
            return false;
        }
        try {
            return Advapi32Util.isCurrentProcessElevated();
        } catch (Throwable t) {
            LOG.debug("isCurrentProcessElevated failed: {}", t.getMessage());
            return false;
        }
    }

    /**
     * Trim launcher working set; if elevated, attempt Standby List purge.
     * Never throws fatally.
     */
    public static void trim(Consumer<String> log) {
        Consumer<String> out = log != null ? log : s -> {
        };
        if (!isWindows()) {
            out.accept("Linux 不支持系统备用缓存清理");
            return;
        }

        long beforeFree = SystemMemoryProbe.probe().freeBytes();
        out.accept("【开始系统内存整理】整理前空闲内存：" + SystemMemoryProbe.formatMb(beforeFree));

        try {
            emptyOwnWorkingSet(out);
        } catch (Throwable t) {
            LOG.warn("EmptyWorkingSet failed", t);
            out.accept("修剪启动器工作集失败（已跳过）: " + t.getMessage());
        }

        if (isElevated()) {
            try {
                purgeStandbyList(out);
            } catch (Throwable t) {
                LOG.warn("Standby list purge failed", t);
                out.accept("权限不足或 API 不可用，跳过系统备用缓存清理: " + t.getMessage());
            }
        } else {
            out.accept("权限不足，跳过系统内存整理（备用缓存清理需要管理员权限）");
        }

        long afterFree = SystemMemoryProbe.probe().freeBytes();
        out.accept("【系统内存整理完成】整理前空闲：" + SystemMemoryProbe.formatMb(beforeFree)
                + " → 整理后空闲：" + SystemMemoryProbe.formatMb(afterFree));
    }

    private static void emptyOwnWorkingSet(Consumer<String> log) {
        WinNT.HANDLE process = Kernel32.INSTANCE.GetCurrentProcess();
        boolean ok = PsapiExt.INSTANCE.EmptyWorkingSet(process);
        if (ok) {
            log.accept("已修剪启动器自身工作集（EmptyWorkingSet）");
        } else {
            int err = Kernel32.INSTANCE.GetLastError();
            log.accept("EmptyWorkingSet 返回失败，错误码=" + err + "（已跳过）");
        }
    }

    private static void purgeStandbyList(Consumer<String> log) {
        Memory cmd = new Memory(4);
        cmd.setInt(0, MemoryPurgeStandbyList);
        int status = NtDll.INSTANCE.NtSetSystemInformation(
                SystemMemoryListInformation, cmd, 4);
        if (status == 0) {
            log.accept("已请求清理系统备用缓存（Standby List）");
        } else {
            log.accept("Standby List 清理失败 NTSTATUS=0x" + Integer.toHexString(status) + "（已跳过）");
        }
    }

    /** Subset of Psapi not mapped in JNA 5.14. */
    interface PsapiExt extends StdCallLibrary {
        PsapiExt INSTANCE = Native.load("psapi", PsapiExt.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean EmptyWorkingSet(WinNT.HANDLE hProcess);
    }

    interface NtDll extends StdCallLibrary {
        NtDll INSTANCE = Native.load("ntdll", NtDll.class, W32APIOptions.DEFAULT_OPTIONS);

        int NtSetSystemInformation(int SystemInformationClass, Pointer SystemInformation, int SystemInformationLength);
    }
}
