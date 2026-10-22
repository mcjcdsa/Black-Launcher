package com.blacklauncher.ui.dialogs;

import com.blacklauncher.auth.MicrosoftAuthService;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.exception.ExceptionHandler;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Microsoft login: device-code or browser PKCE (authorization code).
 */
public final class MicrosoftLoginDialog {

    private MicrosoftLoginDialog() {
    }

    public static void show(Window owner, ConfigManager configManager, Runnable onSuccess) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("微软账号登录");
        dialog.setHeaderText("设备代码流 / 浏览器 PKCE（需已审批的 Azure client_id）");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Label status = new Label("选择一种登录方式。浏览器登录需在 Azure 应用中允许回调 http://127.0.0.1。");
        status.setWrapText(true);
        TextArea detail = new TextArea();
        detail.setEditable(false);
        detail.setWrapText(true);
        detail.setPrefRowCount(8);

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<MicrosoftAuthService.PkceLogin> pkceRef = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ms-auth");
            t.setDaemon(true);
            return t;
        });

        Button startDevice = new Button("设备代码登录");
        startDevice.getStyleClass().add("primary-button");
        Button startBrowser = new Button("浏览器登录 (PKCE)");
        Button cancelPoll = new Button("停止等待");

        Runnable setBusy = () -> {
            startDevice.setDisable(true);
            startBrowser.setDisable(true);
        };
        Runnable setIdle = () -> {
            startDevice.setDisable(false);
            startBrowser.setDisable(false);
        };

        startDevice.setOnAction(e -> {
            stop.set(false);
            setBusy.run();
            status.setText("正在请求设备代码…");
            worker.submit(() -> {
                try {
                    MicrosoftAuthService auth = new MicrosoftAuthService(configManager.getConfig());
                    MicrosoftAuthService.DeviceCode code = auth.requestDeviceCode();
                    Platform.runLater(() -> {
                        detail.setText("请在浏览器打开：\n" + code.verificationUri()
                                + "\n\n输入代码： " + code.userCode()
                                + "\n\n" + (code.message().isBlank()
                                ? "确认授权后，启动器会自动继续。"
                                : code.message()));
                        status.setText("等待你在浏览器中完成授权…");
                        try {
                            Desktop.getDesktop().browse(URI.create(code.verificationUri()));
                        } catch (Exception ignored) {
                            // user can open manually
                        }
                    });
                    int intervalMs = Math.max(3, code.intervalSec()) * 1000;
                    long deadline = System.currentTimeMillis()
                            + Math.min(900, Math.max(60, code.expiresInSec())) * 1000L;
                    while (!stop.get() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(intervalMs);
                        MicrosoftAuthService.PollResult poll = auth.pollAndLogin(code);
                        if (poll.slowDown()) {
                            intervalMs += 5000;
                            continue;
                        }
                        if (!poll.isDone()) {
                            continue;
                        }
                        finishSuccess(auth, poll.session(), configManager, onSuccess, status, detail, setIdle);
                        return;
                    }
                    Platform.runLater(() -> {
                        status.setText(stop.get() ? "已取消" : "授权超时，请重试");
                        setIdle.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        status.setText("登录失败");
                        setIdle.run();
                    });
                    ExceptionHandler.handle("微软登录失败", ex);
                }
            });
        });

        startBrowser.setOnAction(e -> {
            stop.set(false);
            setBusy.run();
            status.setText("正在启动本地回调并打开浏览器…");
            worker.submit(() -> {
                MicrosoftAuthService.PkceLogin pkce = null;
                try {
                    MicrosoftAuthService auth = new MicrosoftAuthService(configManager.getConfig());
                    pkce = auth.beginAuthorizationCodeLogin();
                    pkceRef.set(pkce);
                    MicrosoftAuthService.PkceLogin pkceFinal = pkce;
                    Platform.runLater(() -> {
                        detail.setText("已打开浏览器完成微软登录。\n回调地址：\n"
                                + pkceFinal.server().redirectUri()
                                + "\n\n若浏览器未自动打开，请手动访问授权页（见状态栏）。");
                        status.setText("等待浏览器授权回调…");
                        try {
                            Desktop.getDesktop().browse(URI.create(pkceFinal.authorizeUrl()));
                        } catch (Exception ignored) {
                            detail.appendText("\n\n授权 URL：\n" + pkceFinal.authorizeUrl());
                        }
                    });
                    MicrosoftAuthService.Session session = auth.finishAuthorizationCodeLogin(pkce);
                    finishSuccess(auth, session, configManager, onSuccess, status, detail, setIdle);
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        status.setText(stop.get() ? "已取消" : "登录失败");
                        setIdle.run();
                    });
                    if (!stop.get()) {
                        ExceptionHandler.handle("微软浏览器登录失败", ex);
                    }
                } finally {
                    if (pkce != null) {
                        pkce.close();
                    }
                    pkceRef.set(null);
                }
            });
        });

        cancelPoll.setOnAction(e -> {
            stop.set(true);
            MicrosoftAuthService.PkceLogin pkce = pkceRef.getAndSet(null);
            if (pkce != null) {
                pkce.server().cancel();
                pkce.close();
            }
        });

        HBox buttons = new HBox(8, startDevice, startBrowser, cancelPoll);
        VBox box = new VBox(10, status, detail, buttons);
        box.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(560);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.setOnHidden(ev -> {
            stop.set(true);
            MicrosoftAuthService.PkceLogin pkce = pkceRef.getAndSet(null);
            if (pkce != null) {
                pkce.server().cancel();
                pkce.close();
            }
            worker.shutdownNow();
        });
        dialog.showAndWait();
    }

    private static void finishSuccess(MicrosoftAuthService auth,
                                      MicrosoftAuthService.Session session,
                                      ConfigManager configManager,
                                      Runnable onSuccess,
                                      Label status,
                                      TextArea detail,
                                      Runnable setIdle) throws Exception {
        auth.applyToConfig(session);
        configManager.save();
        Platform.runLater(() -> {
            status.setText("登录成功：" + session.playerName());
            detail.appendText("\n\nUUID: " + session.playerUuid());
            setIdle.run();
            if (onSuccess != null) {
                onSuccess.run();
            }
        });
    }
}
