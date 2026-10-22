package com.blacklauncher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Persistent bottom status bar (desktop-tool chrome).
 */
public class StatusBar extends HBox {

    private final Label left = new Label("就绪");
    private final Label center = new Label("");
    private final Label right = new Label("");

    public StatusBar() {
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(16);
        setPadding(new Insets(6, 14, 6, 14));
        left.getStyleClass().add("status-bar-text");
        center.getStyleClass().add("status-bar-text");
        center.getStyleClass().add("muted");
        right.getStyleClass().add("status-bar-text");
        right.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(left, center, spacer, right);
    }

    public void setPrimary(String text) {
        left.setText(text == null ? "" : text);
    }

    public void setSecondary(String text) {
        center.setText(text == null ? "" : text);
    }

    public void setTrailing(String text) {
        right.setText(text == null ? "" : text);
    }
}
