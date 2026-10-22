package com.blacklauncher.ui.dialogs;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Non-blocking-looking progress dialog for modpack import/export (updated from worker via Platform.runLater).
 */
public final class ModpackProgressDialog {

    private final Dialog<Void> dialog;
    private final Label message = new Label("准备中…");
    private final Label detail = new Label("");
    private final ProgressBar bar = new ProgressBar(0);

    public ModpackProgressDialog(Window owner, String title) {
        dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        message.setWrapText(true);
        detail.getStyleClass().add("muted");
        detail.setWrapText(true);
        bar.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(10, message, bar, detail);
        box.setPadding(new Insets(12));
        box.setPrefWidth(420);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        dialog.setOnCloseRequest(e -> {
            // Cancel button closes; caller should also cancel DownloadControl if needed.
        });
    }

    public void show() {
        Platform.runLater(() -> dialog.show());
    }

    public void close() {
        Platform.runLater(() -> {
            if (dialog.isShowing()) {
                dialog.close();
            }
        });
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public void update(String msg, double progress01, String detailText) {
        Platform.runLater(() -> {
            if (msg != null) {
                message.setText(msg);
            }
            if (progress01 >= 0) {
                bar.setProgress(Math.max(0, Math.min(1, progress01)));
            }
            if (detailText != null) {
                detail.setText(detailText);
            }
        });
    }

    public Dialog<Void> dialog() {
        return dialog;
    }
}
