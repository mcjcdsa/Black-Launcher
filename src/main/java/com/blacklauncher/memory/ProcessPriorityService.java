package com.blacklauncher.memory;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Raises Minecraft process CPU (and best-effort IO) priority. Never uses REALTIME.
 */
public final class ProcessPriorityService {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessPriorityService.class);

    private static final int PROCESS_SET_INFORMATION = 0x0200;
    private static final int PROCESS_QUERY_INFORMATION = 0x0400;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    /** ProcessIoPriority = 33 */
    private static final int ProcessIoPriority = 33;
    /** IoPriorityHigh = 3 (avoid Critical=4) */
    private static final int IoPriorityHigh = 3;

    private ProcessPriorityService() {
    }

    public static void boostMinecraft(long pid, Consumer<String> log) {
        Consumer<String> out = log != null ? log : s -> {
        };
        if (pid <= 0) {
            out.accept("无效 PID，跳过进程优先级调整");
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            boostWindows(pid, out);
        } else {
            boostLinux(pid, out);
        }
    }

    private static void boostWindows(long pid, Consumer<String> log) {
        WinNT.HANDLE handle = null;
        try {
            int access = PROCESS_SET_INFORMATION | PROCESS_QUERY_INFORMATION | PROCESS_QUERY_LIMITED_INFORMATION;
            handle = Kernel32.INSTANCE.OpenProcess(access, false, (int) pid);
            if (handle == null || WinNT.INVALID_HANDLE_VALUE.equals(handle)
                    || Pointer.nativeValue(handle.getPointer()) == 0) {
                log.accept("无法打开 MC 进程(PID:" + pid + ")，跳过优先级设置");
                return;
            }
            boolean ok = Kernel32.INSTANCE.SetPriorityClass(handle, Kernel32.ABOVE_NORMAL_PRIORITY_CLASS);
            if (ok) {
                log.accept("已将MC进程(PID:" + pid + ")优先级设置为 AboveNormal");
            } else {
                log.accept("设置 CPU 优先级失败，错误码=" + Kernel32.INSTANCE.GetLastError());
            }

            try {
                Memory ioPrio = new Memory(4);
                ioPrio.setInt(0, IoPriorityHigh);
                int status = NtDll.INSTANCE.NtSetInformationProcess(
                        handle, ProcessIoPriority, ioPrio, 4);
                if (status == 0) {
                    log.accept("已提升 MC 进程 IO 优先级");
                } else {
                    log.accept("IO 优先级调整跳过（NTSTATUS=0x" + Integer.toHexString(status) + "）");
                }
            } catch (Throwable t) {
                LOG.debug("IO priority set failed", t);
                log.accept("IO 优先级调整不可用，已跳过: " + t.getMessage());
            }
        } catch (Throwable t) {
            LOG.warn("boostWindows failed", t);
            log.accept("提升进程优先级失败: " + t.getMessage());
        } finally {
            if (handle != null && !WinNT.INVALID_HANDLE_VALUE.equals(handle)) {
                try {
                    Kernel32.INSTANCE.CloseHandle(handle);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void boostLinux(long pid, Consumer<String> log) {
        try {
            Process p = new ProcessBuilder("renice", "-n", "-5", "-p", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.accept("renice 超时，已跳过");
                return;
            }
            if (p.exitValue() == 0) {
                log.accept("已将MC进程(PID:" + pid + ") nice 调整为 -5");
            } else {
                log.accept("renice 失败（可能需要权限），已跳过");
            }
        } catch (Throwable t) {
            LOG.debug("renice failed", t);
            log.accept("Linux 优先级调整失败: " + t.getMessage());
        }
    }

    interface NtDll extends StdCallLibrary {
        NtDll INSTANCE = Native.load("ntdll", NtDll.class, W32APIOptions.DEFAULT_OPTIONS);

        int NtSetInformationProcess(WinNT.HANDLE ProcessHandle, int ProcessInformationClass,
                                    Pointer ProcessInformation, int ProcessInformationLength);
    }
}
