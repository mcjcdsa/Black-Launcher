package com.blacklauncher.ui.pages;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Online multiplayer placeholder (@5) — no business logic yet.
 */
public class OnlinePage extends BorderPane {

    private final RotateTransition spin;

    public OnlinePage(Runnable backHome) {
        getStyleClass().add("page");
        setPadding(new Insets(4));

        Label title = new Label("联机大厅");
        title.getStyleClass().add("page-title");
        title.setStyle("-fx-font-size: 22px;");

        Label icon = new Label("🌐");
        icon.setStyle("-fx-font-size: 42px;");

        Label detail = new Label("局域网联机 / 穿透联机 / 托管房间 正在开发，敬请期待");
        detail.getStyleClass().add("muted");
        detail.setWrapText(true);
        detail.setStyle("-fx-font-size: 14px;");

        ProgressIndicator ring = new ProgressIndicator();
        ring.setPrefSize(36, 36);
        ring.setProgress(-1);
        // Slow uniform spin feel via RotateTransition on a wrapper label when animations on.
        Label spinnerProxy = new Label("◐");
        spinnerProxy.setStyle("-fx-font-size: 28px; -fx-text-fill: #8892A0;");
        spin = new RotateTransition(Duration.millis(2500), spinnerProxy);
        spin.setByAngle(360);
        spin.setCycleCount(Animation.INDEFINITE);
        spin.setInterpolator(Interpolator.LINEAR);
        if (com.blacklauncher.ui.UiAnimation.isEnabled()) {
            spin.play();
        }

        Button back = new Button("返回主页");
        back.setOnAction(e -> {
            if (backHome != null) {
                backHome.run();
            }
        });

        VBox center = new VBox(16, icon, title, detail, spinnerProxy, ring, back);
        center.setAlignment(Pos.CENTER);
        center.setPadding(new Insets(48, 24, 24, 24));
        setCenter(center);

        sceneProperty().addListener((o, a, b) -> {
            if (b == null) {
                spin.stop();
            } else if (com.blacklauncher.ui.UiAnimation.isEnabled()) {
                spin.play();
            }
        });
    }
}
