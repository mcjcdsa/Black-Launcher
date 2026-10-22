package com.blacklauncher.core.net;

import com.blacklauncher.core.exception.LauncherException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Shared control plane for download tasks: cancel, user-pause, waiting-for-network.
 * <p>
 * Auto-resume applies only to network outages — never to {@link #pauseByUser()}.
 */
public class DownloadControl {

    public enum State {
        RUNNING,
        WAITING_NETWORK,
        PAUSED_BY_USER,
        CANCELLED
    }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean userPaused = new AtomicBoolean(false);
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
    private final AtomicReference<Consumer<State>> stateListener = new AtomicReference<>();

    public void setStateListener(Consumer<State> listener) {
        stateListener.set(listener);
    }

    public State state() {
        return state.get();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean isUserPaused() {
        return userPaused.get();
    }

    public void cancel() {
        cancelled.set(true);
        userPaused.set(false);
        setState(State.CANCELLED);
        synchronized (this) {
            notifyAll();
        }
    }

    /** Manual pause — will NOT auto-resume when network recovers. */
    public void pauseByUser() {
        if (cancelled.get()) {
            return;
        }
        userPaused.set(true);
        setState(State.PAUSED_BY_USER);
        synchronized (this) {
            notifyAll();
        }
    }

    /** Resume after a manual pause. */
    public void resumeByUser() {
        if (cancelled.get()) {
            return;
        }
        userPaused.set(false);
        setState(State.RUNNING);
        synchronized (this) {
            notifyAll();
        }
    }

    void markWaitingNetwork() {
        if (cancelled.get() || userPaused.get()) {
            return;
        }
        setState(State.WAITING_NETWORK);
    }

    void markRunning() {
        if (cancelled.get() || userPaused.get()) {
            return;
        }
        setState(State.RUNNING);
    }

    /**
     * Blocks while user-paused or offline (network wait). Returns when RUNNING again.
     */
    public void awaitRunnable(NetworkMonitor monitor) throws LauncherException {
        while (true) {
            if (cancelled.get()) {
                throw new DownloadCancelledException();
            }
            if (userPaused.get()) {
                setState(State.PAUSED_BY_USER);
                waitQuiet(500);
                if (userPaused.get()) {
                    // Stay paused until user resumes — do not auto-clear.
                    synchronized (this) {
                        while (userPaused.get() && !cancelled.get()) {
                            try {
                                wait(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new DownloadCancelledException();
                            }
                        }
                    }
                    if (cancelled.get()) {
                        throw new DownloadCancelledException();
                    }
                    continue;
                }
            }
            if (monitor != null && !monitor.isOnline()) {
                markWaitingNetwork();
                monitor.awaitOnline(this);
                if (cancelled.get()) {
                    throw new DownloadCancelledException();
                }
                if (userPaused.get()) {
                    continue;
                }
                markRunning();
                continue;
            }
            markRunning();
            return;
        }
    }

    public void checkNotStopped() throws LauncherException {
        if (cancelled.get()) {
            throw new DownloadCancelledException();
        }
        if (userPaused.get()) {
            throw new UserPausedException();
        }
    }

    private void setState(State next) {
        State prev = state.getAndSet(next);
        if (prev != next) {
            Consumer<State> listener = stateListener.get();
            if (listener != null) {
                try {
                    listener.accept(next);
                } catch (Exception ignored) {
                    // UI listeners must not break downloads
                }
            }
        }
    }

    private void waitQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
