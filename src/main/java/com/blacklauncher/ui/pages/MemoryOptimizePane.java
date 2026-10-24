package com.blacklauncher.ui.pages;

import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.InstalledVersionService;
import com.blacklauncher.launch.JavaFinder;
import com.blacklauncher.launch.JavaVersionProbe;
import com.blacklauncher.memory.JvmArgAssembler;
import com.blacklauncher.memory.JvmGcPreset;
import com.blacklauncher.memory.MemoryMonitor;
import com.blacklauncher.memory.MemoryOptimizeFacade;
import com.blacklauncher.memory.MemoryProfile;
import com.blacklauncher.memory.MemoryProfileStore;
import com.blacklauncher.memory.MemoryRecommend;
import com.blacklauncher.memory.SystemMemoryProbe;
import com.blacklauncher.memory.SystemMemoryTrimService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings tab content: JVM heap + system memory optimize (PRD §12).
 */
public final class MemoryOptimizePane extends VBox {

    private final ConfigManager configManager;
    private final InstalledVersionService versions;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-opt-ui");
        t.setDaemon(true);
        return t;
    });

    private final ComboBox<String> versionBox = new ComboBox<>();
    private final Label probeLabel = new Label("正在探测系统内存…");
    private final Label statusBadge = new Label("正常");
    private final StackPane memBar = new StackPane();
    private final Rectangle barTrack = new Rectangle();
    private final Rectangle barUsed = new Rectangle();
    private final Rectangle barMc = new Rectangle();

    private final ToggleGroup schemeGroup = new ToggleGroup();
    private final RadioButton schemeG1 = new RadioButton(JvmGcPreset.BALANCED_G1.displayNameZh());
    private final RadioButton schemeZgc = new RadioButton(JvmGcPreset.ZGC.displayNameZh());
    private final RadioButton schemeLow = new RadioButton(JvmGcPreset.LOW_MEM.displayNameZh());
    private final RadioButton schemeCustom = new RadioButton(JvmGcPreset.CUSTOM.displayNameZh());

    private final Spinner<Integer> xms = new Spinner<>();
    private final Spinner<Integer> xmx = new Spinner<>();
    private final TextArea customJvm = new TextArea();
    private final CheckBox modpackHint = new CheckBox("当前版本按整合包/大型模组推荐");

    private final CheckBox trimBefore = new CheckBox("启动游戏前自动整理系统备用缓存内存");
    private final CheckBox boostPriority = new CheckBox("提升 MC 进程 CPU 与 IO 优先级（推荐）");
    private final CheckBox trimAfter = new CheckBox("游戏关闭后自动执行内存整理");
    private final Label trimAdminHint = new Label();
    private final Label monitorLabel = new Label("监控：—");

    private final MemoryMonitor monitor = new MemoryMonitor();
    private volatile long lastTotalBytes;
    private volatile MemoryProfile editing = new MemoryProfile();

    public MemoryOptimizePane(ConfigManager configManager, InstalledVersionService versions) {
        this.configManager = configManager;
        this.versions = versions;
        setSpacing(12);
        setPadding(new Insets(8, 4, 8, 4));

        Label title = new Label("内存与系统优化");
        title.getStyleClass().add("section-title");

        HBox verRow = new HBox(10, new Label("适用版本"), versionBox);
        verRow.setAlignment(Pos.CENTER_LEFT);
        versionBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(versionBox, Priority.ALWAYS);
        versionBox.setOnAction(e -> loadSelectedProfile());

        buildMemBar();
        HBox probeRow = new HBox(12, probeLabel, statusBadge);
        probeRow.setAlignment(Pos.CENTER_LEFT);
        statusBadge.getStyleClass().add("status-pill");

        Separator sep1 = new Separator();
        Label jvmTitle = new Label("JVM 堆内存设置（游戏本身）");
        jvmTitle.getStyleClass().add("section-title");

        schemeG1.setToggleGroup(schemeGroup);
        schemeZgc.setToggleGroup(schemeGroup);
        schemeLow.setToggleGroup(schemeGroup);
        schemeCustom.setToggleGroup(schemeGroup);
        schemeG1.setSelected(true);
        schemeG1.setUserData(JvmGcPreset.BALANCED_G1);
        schemeZgc.setUserData(JvmGcPreset.ZGC);
        schemeLow.setUserData(JvmGcPreset.LOW_MEM);
        schemeCustom.setUserData(JvmGcPreset.CUSTOM);
        VBox schemes = new VBox(6, schemeG1, schemeZgc, schemeLow, schemeCustom);

        xms.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(512, 65536, 512, 256));
        xmx.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(512, 65536, 2048, 256));
        xms.setEditable(true);
        xmx.setEditable(true);
        xms.setPrefWidth(120);
        xmx.setPrefWidth(120);
        HBox heapRow = new HBox(12,
                new Label("Xms (MB)"), xms,
                new Label("Xmx (MB)"), xmx,
                modpackHint);
        heapRow.setAlignment(Pos.CENTER_LEFT);

        customJvm.setPromptText("自定义 JVM 参数（空格分隔）；自定义模式下不自动追加 GC 参数");
        customJvm.setPrefRowCount(3);
        customJvm.setWrapText(true);

        Separator sep2 = new Separator();
        Label sysTitle = new Label("电脑系统内存优化（辅助，无法凭空增加物理内存）");
        sysTitle.getStyleClass().add("section-title");

        trimBefore.setTooltip(new Tooltip("仅清理系统文件缓存与启动器闲置工作集，不会关闭其他软件"));
        boostPriority.setTooltip(new Tooltip("优先给 MC 分配 CPU/IO；过高优先级可能导致系统界面卡顿"));
        trimAfter.setTooltip(new Tooltip("游戏退出后轻量整理，适合频繁切换整合包"));
        trimAdminHint.getStyleClass().add("muted");
        trimAdminHint.setWrapText(true);

        monitorLabel.getStyleClass().add("muted");
        monitorLabel.setWrapText(true);

        Button applyRec = new Button("一键应用推荐配置");
        applyRec.getStyleClass().addAll("button", "primary");
        applyRec.setOnAction(e -> applyRecommend());
        Button reset = new Button("重置全部");
        reset.setOnAction(e -> resetProfile());
        Button trimNow = new Button("手动立即整理内存");
        trimNow.setOnAction(e -> trimNow());
        Button save = new Button("保存到当前版本");
        save.getStyleClass().addAll("button", "primary");
        save.setOnAction(e -> saveProfile());
        HBox actions = new HBox(10, applyRec, reset, trimNow, save);
        actions.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(
                title,
                verRow,
                probeRow,
                memBar,
                sep1,
                jvmTitle,
                schemes,
                heapRow,
                new Label("自定义 JVM 参数"),
                customJvm,
                sep2,
                sysTitle,
                trimBefore,
                boostPriority,
                trimAfter,
                trimAdminHint,
                monitorLabel,
                actions
        );

        schemeGroup.selectedToggleProperty().addListener((o, a, b) -> onSchemeChanged());
        xmx.valueProperty().addListener((o, a, b) -> refreshBarAndBadge());
        xms.valueProperty().addListener((o, a, b) -> refreshBarAndBadge());

        refreshTrimAvailability();
        reloadVersions();
        refreshProbeAsync();
        monitor.addListener(sample -> Platform.runLater(() -> applyMonitorSample(sample)));
        monitor.start();
        sceneProperty().addListener((o, a, b) -> {
            if (b == null) {
                monitor.stop();
            } else {
                monitor.start();
            }
        });
    }

    public void onActivated() {
        reloadVersions();
        refreshProbeAsync();
        refreshTrimAvailability();
        monitor.start();
    }

    private void buildMemBar() {
        memBar.setPrefHeight(22);
        memBar.setMaxWidth(Double.MAX_VALUE);
        barTrack.setHeight(18);
        barUsed.setHeight(18);
        barMc.setHeight(18);
        barTrack.setArcWidth(4);
        barTrack.setArcHeight(4);
        barUsed.setArcWidth(4);
        barUsed.setArcHeight(4);
        barMc.setArcWidth(4);
        barMc.setArcHeight(4);
        barTrack.setFill(Color.web("#2A303A"));
        barUsed.setFill(Color.web("#5D6B82"));
        barMc.setFill(Color.web("#3478D9"));
        barTrack.widthProperty().bind(memBar.widthProperty());
        StackPane.setAlignment(barUsed, Pos.CENTER_LEFT);
        StackPane.setAlignment(barMc, Pos.CENTER_LEFT);
        memBar.getChildren().setAll(barTrack, barUsed, barMc);
        memBar.getStyleClass().add("memory-bar");
        Tooltip.install(memBar, new Tooltip("总内存｜系统已占用｜分配给 MC 的 Xmx｜剩余空闲"));
    }

    private void reloadVersions() {
        String keep = versionBox.getSelectionModel().getSelectedItem();
        try {
            var ids = versions.list().stream().map(InstalledVersionService.InstalledVersion::id).toList();
            versionBox.setItems(FXCollections.observableArrayList(ids));
            String selected = configManager.getConfig().getSelectedVersion();
            if (Validation.isNonBlank(keep) && ids.contains(keep)) {
                versionBox.getSelectionModel().select(keep);
            } else if (Validation.isNonBlank(selected) && ids.contains(selected)) {
                versionBox.getSelectionModel().select(selected);
            } else if (!ids.isEmpty()) {
                versionBox.getSelectionModel().selectFirst();
            }
            loadSelectedProfile();
        } catch (Exception e) {
            ExceptionHandler.handle("读取版本列表失败", e);
        }
    }

    private void loadSelectedProfile() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (!Validation.isNonBlank(id)) {
            editing = new MemoryProfile();
            applyProfileToUi(editing);
            return;
        }
        editing = MemoryProfileStore.load(configManager.getConfig(), id);
        applyProfileToUi(editing);
        refreshBarAndBadge();
    }

    private void applyProfileToUi(MemoryProfile p) {
        JvmGcPreset preset = JvmGcPreset.fromId(p.getSchemeId());
        switch (preset) {
            case ZGC -> schemeZgc.setSelected(true);
            case LOW_MEM -> schemeLow.setSelected(true);
            case CUSTOM -> schemeCustom.setSelected(true);
            default -> schemeG1.setSelected(true);
        }
        xms.getValueFactory().setValue(Math.max(512, p.getXmsMb()));
        xmx.getValueFactory().setValue(Math.max(512, p.getXmxMb()));
        customJvm.setText(p.getCustomJvmArgs() == null ? "" : p.getCustomJvmArgs());
        modpackHint.setSelected(p.isModpackHint());
        trimBefore.setSelected(p.isTrimBeforeLaunch());
        boostPriority.setSelected(p.isBoostProcessPriority());
        trimAfter.setSelected(p.isTrimAfterExit());
        onSchemeChanged();
    }

    private MemoryProfile readUiToProfile() {
        MemoryProfile p = editing == null ? new MemoryProfile() : editing;
        Object data = schemeGroup.getSelectedToggle() == null
                ? JvmGcPreset.BALANCED_G1
                : schemeGroup.getSelectedToggle().getUserData();
        JvmGcPreset preset = data instanceof JvmGcPreset g ? g : JvmGcPreset.BALANCED_G1;
        p.setSchemeId(preset.id());
        p.setXmsMb(xms.getValue() == null ? 512 : xms.getValue());
        p.setXmxMb(xmx.getValue() == null ? 2048 : xmx.getValue());
        p.setCustomJvmArgs(customJvm.getText() == null ? "" : customJvm.getText().trim());
        p.setModpackHint(modpackHint.isSelected());
        p.setTrimBeforeLaunch(trimBefore.isSelected());
        p.setBoostProcessPriority(boostPriority.isSelected());
        p.setTrimAfterExit(trimAfter.isSelected());
        return p;
    }

    private void onSchemeChanged() {
        String java = JavaFinder.resolveJavaExecutable(configManager.getConfig().getJavaPath());
        int major = JavaVersionProbe.probeMajor(java);
        boolean zgcOk = JvmGcPreset.ZGC.isAvailable(major);
        schemeZgc.setDisable(!zgcOk);
        if (!zgcOk && schemeZgc.isSelected()) {
            schemeG1.setSelected(true);
        }
        if (schemeLow.isSelected() && xms.getValue() != null && xms.getValue() != 512) {
            xms.getValueFactory().setValue(512);
        }
        refreshBarAndBadge();
    }

    private void refreshProbeAsync() {
        worker.submit(() -> {
            SystemMemoryProbe.Snapshot snap = SystemMemoryProbe.probe();
            Platform.runLater(() -> {
                lastTotalBytes = snap.totalBytes();
                probeLabel.setText("本机总内存 "
                        + SystemMemoryProbe.formatMb(snap.totalBytes())
                        + "｜已用 " + SystemMemoryProbe.formatMb(snap.usedBytes())
                        + "｜空闲 " + SystemMemoryProbe.formatMb(snap.freeBytes()));
                refreshBarAndBadge();
            });
        });
    }

    private void refreshBarAndBadge() {
        long total = lastTotalBytes;
        if (total <= 0) {
            return;
        }
        double w = Math.max(40, memBar.getWidth() > 1 ? memBar.getWidth() : 400);
        SystemMemoryProbe.Snapshot snap = SystemMemoryProbe.probe();
        double usedRatio = snap.usedBytes() / (double) total;
        int xmxMb = xmx.getValue() == null ? 0 : xmx.getValue();
        double mcRatio = (xmxMb * 1024L * 1024L) / (double) total;
        barUsed.setWidth(Math.max(0, Math.min(w, w * usedRatio)));
        barMc.setWidth(Math.max(0, Math.min(w, w * mcRatio)));

        long free = snap.freeBytes();
        MemoryProfile p = readUiToProfile();
        Optional<String> err = JvmArgAssembler.validate(p);
        if (err.isPresent()) {
            statusBadge.setText("配置非法");
            statusBadge.setStyle("-fx-text-fill: #E85D5D;");
        } else if (free < 512L * 1024 * 1024) {
            statusBadge.setText("内存严重不足");
            statusBadge.setStyle("-fx-text-fill: #E85D5D;");
        } else if (free < 1200L * 1024 * 1024) {
            statusBadge.setText("内存紧张");
            statusBadge.setStyle("-fx-text-fill: #E0A800;");
        } else {
            statusBadge.setText("正常");
            statusBadge.setStyle("-fx-text-fill: #3CB371;");
        }
    }

    private void applyMonitorSample(MemoryMonitor.Sample sample) {
        lastTotalBytes = sample.totalBytes();
        String mc = sample.mcPid() > 0
                ? ("MC PID " + sample.mcPid() + " 占用 "
                + SystemMemoryProbe.formatMb(sample.mcWorkingSetBytes()))
                : "MC 未运行";
        monitorLabel.setText("实时监控｜空闲 " + SystemMemoryProbe.formatMb(sample.freeBytes())
                + "｜启动器 " + SystemMemoryProbe.formatMb(sample.launcherUsedBytes())
                + "｜" + mc);
        probeLabel.setText("本机总内存 "
                + SystemMemoryProbe.formatMb(sample.totalBytes())
                + "｜已用 " + SystemMemoryProbe.formatMb(sample.usedBytes())
                + "｜空闲 " + SystemMemoryProbe.formatMb(sample.freeBytes()));
        refreshBarAndBadge();
    }

    private void refreshTrimAvailability() {
        boolean win = SystemMemoryTrimService.isTrimSupported();
        if (!win) {
            trimBefore.setVisible(false);
            trimBefore.setManaged(false);
            trimAfter.setVisible(false);
            trimAfter.setManaged(false);
            trimAdminHint.setText("当前系统不支持 Standby List 清理；仍可使用进程优先级与 JVM 调优。");
            return;
        }
        trimBefore.setVisible(true);
        trimBefore.setManaged(true);
        trimAfter.setVisible(true);
        trimAfter.setManaged(true);
        boolean elev = SystemMemoryTrimService.isElevated();
        if (!elev) {
            trimBefore.setDisable(true);
            trimAfter.setDisable(true);
            trimBefore.setSelected(false);
            trimAfter.setSelected(false);
            trimAdminHint.setText("权限不足：系统内存整理需管理员权限，已禁用相关开关；JVM 参数优化不受影响。");
        } else {
            trimBefore.setDisable(false);
            trimAfter.setDisable(false);
            trimAdminHint.setText("已取得管理员权限，可执行系统备用缓存整理（仅清理缓存，不会关闭其他软件）。");
        }
    }

    private void applyRecommend() {
        MemoryProfile p = readUiToProfile();
        SystemMemoryProbe.Snapshot snap = SystemMemoryProbe.probe();
        MemoryOptimizeFacade.applyRecommendToProfile(p, snap.totalBytes());
        if (MemoryRecommend.exceeds70Percent(p.getXmxMb(), snap.totalBytes())) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION);
            a.setTitle("内存分配过高");
            a.setHeaderText("Xmx 超过整机内存的 70%");
            a.setContentText("极易导致系统卡顿甚至蓝屏，是否仍使用推荐值 "
                    + p.getXmxMb() + " MB？");
            if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }
        MemoryRecommend.RecommendResult r = MemoryRecommend.recommend(snap.totalBytes(), p.isModpackHint());
        if (Validation.isNonBlank(r.warningZh())) {
            ExceptionHandler.showInfo("推荐说明", r.warningZh());
        }
        applyProfileToUi(p);
        ExceptionHandler.showInfo("已应用", "已写入推荐 Xms/Xmx，请再点「保存到当前版本」");
    }

    private void resetProfile() {
        editing = new MemoryProfile();
        editing.setSchemeId(JvmGcPreset.BALANCED_G1.id());
        editing.setXmsMb(512);
        editing.setXmxMb(2048);
        editing.setCustomJvmArgs("");
        editing.setTrimBeforeLaunch(false);
        editing.setBoostProcessPriority(true);
        editing.setTrimAfterExit(false);
        editing.setModpackHint(false);
        applyProfileToUi(editing);
    }

    private void trimNow() {
        if (!SystemMemoryTrimService.isTrimSupported()) {
            ExceptionHandler.showInfo("提示", "当前系统不支持系统内存整理");
            return;
        }
        if (!SystemMemoryTrimService.isElevated()) {
            ExceptionHandler.showInfo("权限不足", "请以管理员身份运行启动器后再试");
            return;
        }
        worker.submit(() -> {
            StringBuilder log = new StringBuilder();
            SystemMemoryTrimService.trim(line -> log.append(line).append('\n'));
            Platform.runLater(() -> {
                refreshProbeAsync();
                ExceptionHandler.showInfo("内存整理", log.toString().isBlank() ? "已执行" : log.toString());
            });
        });
    }

    private void saveProfile() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (!Validation.isNonBlank(id)) {
            ExceptionHandler.showInfo("提示", "请先选择游戏版本");
            return;
        }
        MemoryProfile p = readUiToProfile();
        Optional<String> err = JvmArgAssembler.validate(p);
        if (err.isPresent()) {
            ExceptionHandler.showInfo("配置非法", err.get());
            return;
        }
        SystemMemoryProbe.Snapshot snap = SystemMemoryProbe.probe();
        if (MemoryRecommend.exceeds70Percent(p.getXmxMb(), snap.totalBytes())) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION);
            a.setTitle("内存分配过高");
            a.setHeaderText("Xmx 超过整机内存的 70%");
            a.setContentText("极易导致系统卡顿甚至蓝屏，是否仍保存？");
            if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }
        try {
            MemoryProfileStore.save(configManager.getConfig(), id, p);
            // Keep global defaults in sync for Home slider / seed.
            configManager.getConfig().setMinMemoryMb(p.getXmsMb());
            configManager.getConfig().setMaxMemoryMb(p.getXmxMb());
            if (Validation.isNonBlank(p.getCustomJvmArgs())) {
                configManager.getConfig().setJvmArgs(p.getCustomJvmArgs());
            }
            configManager.getConfig().setSelectedVersion(id);
            configManager.save();
            editing = p;
            ExceptionHandler.showInfo("已保存", "已写入版本 " + id + " 的 memory.json");
        } catch (Exception e) {
            ExceptionHandler.handle("保存内存配置失败", e);
        }
    }
}
