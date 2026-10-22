package com.blacklauncher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Global UI transitions controlled by {@link #enabled}. Durations 150–220ms, EASE_OUT only.
 */
public final class UiAnimation {

    private static volatile boolean enabled = true;

    private UiAnimation() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void fadeIn(Node node, Runnable after) {
        if (node == null) {
            if (after != null) {
                after.run();
            }
            return;
        }
        if (!enabled) {
            node.setOpacity(1);
            if (after != null) {
                after.run();
            }
            return;
        }
        node.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(180), node);
        ft.setToValue(1);
        ft.setInterpolator(Interpolator.EASE_OUT);
        if (after != null) {
            ft.setOnFinished(e -> after.run());
        }
        ft.play();
    }

    public static void fadeSwap(Node outgoing, Node incoming, Runnable afterShow) {
        if (!enabled) {
            if (outgoing != null) {
                outgoing.setOpacity(1);
            }
            if (incoming != null) {
                incoming.setOpacity(1);
            }
            if (afterShow != null) {
                afterShow.run();
            }
            return;
        }
        if (outgoing == null || outgoing == incoming) {
            fadeIn(incoming, afterShow);
            return;
        }
        FadeTransition out = new FadeTransition(Duration.millis(150), outgoing);
        out.setToValue(0);
        out.setInterpolator(Interpolator.EASE_OUT);
        out.setOnFinished(e -> {
            outgoing.setOpacity(1);
            fadeIn(incoming, afterShow);
        });
        out.play();
    }

    public static void animatePrefWidth(Node node, double toWidth) {
        if (node == null) {
            return;
        }
        if (!enabled) {
            if (node instanceof javafx.scene.layout.Region r) {
                r.setPrefWidth(toWidth);
            }
            return;
        }
        if (!(node instanceof javafx.scene.layout.Region region)) {
            return;
        }
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(region.prefWidthProperty(), region.getPrefWidth())),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(region.prefWidthProperty(), toWidth, Interpolator.EASE_OUT))
        );
        tl.play();
    }

    public static void animateOpacity(Node node, double to) {
        if (node == null) {
            return;
        }
        if (!enabled) {
            node.setOpacity(to);
            return;
        }
        FadeTransition ft = new FadeTransition(Duration.millis(180), node);
        ft.setToValue(to);
        ft.setInterpolator(Interpolator.EASE_OUT);
        ft.play();
    }
}
