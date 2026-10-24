package com.blacklauncher.memory;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.Arrays;
import java.util.List;

/**
 * Minimal Psapi bindings for process working-set queries (not fully covered by jna-platform).
 */
final class WinProcessMemory {

    private WinProcessMemory() {
    }

    @Structure.FieldOrder({
            "cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize",
            "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
            "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage",
            "PagefileUsage", "PeakPagefileUsage"
    })
    public static class PROCESS_MEMORY_COUNTERS extends Structure {
        public WinDef.DWORD cb;
        public WinDef.DWORD PageFaultCount;
        public BaseTSD.SIZE_T PeakWorkingSetSize;
        public BaseTSD.SIZE_T WorkingSetSize;
        public BaseTSD.SIZE_T QuotaPeakPagedPoolUsage;
        public BaseTSD.SIZE_T QuotaPagedPoolUsage;
        public BaseTSD.SIZE_T QuotaPeakNonPagedPoolUsage;
        public BaseTSD.SIZE_T QuotaNonPagedPoolUsage;
        public BaseTSD.SIZE_T PagefileUsage;
        public BaseTSD.SIZE_T PeakPagefileUsage;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize",
                    "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
                    "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage",
                    "PagefileUsage", "PeakPagefileUsage");
        }
    }

    interface PsapiLib extends StdCallLibrary {
        PsapiLib INSTANCE = Native.load("psapi", PsapiLib.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean GetProcessMemoryInfo(WinNT.HANDLE Process, PROCESS_MEMORY_COUNTERS ppsmemCounters, int cb);
    }

    static long workingSetBytes(long pid) {
        if (pid <= 0) {
            return 0L;
        }
        WinNT.HANDLE handle = null;
        try {
            int access = 0x1000 | 0x0400;
            handle = com.sun.jna.platform.win32.Kernel32.INSTANCE.OpenProcess(access, false, (int) pid);
            if (handle == null || Pointer.nativeValue(handle.getPointer()) == 0) {
                return 0L;
            }
            PROCESS_MEMORY_COUNTERS counters = new PROCESS_MEMORY_COUNTERS();
            counters.cb = new WinDef.DWORD(counters.size());
            boolean ok = PsapiLib.INSTANCE.GetProcessMemoryInfo(handle, counters, counters.size());
            if (!ok || counters.WorkingSetSize == null) {
                return 0L;
            }
            return counters.WorkingSetSize.longValue();
        } catch (Throwable t) {
            return 0L;
        } finally {
            if (handle != null) {
                try {
                    com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(handle);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
