package com.blacklauncher.core.net;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadControlTest {

    @Test
    void userPauseDoesNotClearOnResumeUntilExplicit() {
        DownloadControl control = new DownloadControl();
        AtomicReference<DownloadControl.State> seen = new AtomicReference<>();
        control.setStateListener(seen::set);

        control.pauseByUser();
        assertEquals(DownloadControl.State.PAUSED_BY_USER, control.state());
        assertTrue(control.isUserPaused());
        assertEquals(DownloadControl.State.PAUSED_BY_USER, seen.get());

        control.resumeByUser();
        assertEquals(DownloadControl.State.RUNNING, control.state());
        assertFalse(control.isUserPaused());
        assertEquals(DownloadControl.State.RUNNING, seen.get());
    }

    @Test
    void waitingNetworkIsSeparateFromUserPause() {
        DownloadControl control = new DownloadControl();
        control.markWaitingNetwork();
        assertEquals(DownloadControl.State.WAITING_NETWORK, control.state());

        control.pauseByUser();
        assertEquals(DownloadControl.State.PAUSED_BY_USER, control.state());
        // Manual pause must win; recovering network must not imply auto-resume of user pause.
        assertTrue(control.isUserPaused());
    }

    @Test
    void cancelClearsPause() {
        DownloadControl control = new DownloadControl();
        control.pauseByUser();
        control.cancel();
        assertEquals(DownloadControl.State.CANCELLED, control.state());
        assertFalse(control.isUserPaused());
        assertTrue(control.isCancelled());
    }
}
