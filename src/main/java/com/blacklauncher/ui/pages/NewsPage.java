package com.blacklauncher.ui.pages;

import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.news.NewsService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * News SplitPane: list + plain text detail (@5).
 */
public class NewsPage extends BorderPane {

    private final NewsService newsService;
    private final ListView<Database.NewsRecord> listView = new ListView<>();
    private final TextArea detail = new TextArea();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "news-worker");
        t.setDaemon(true);
        return t;
    });

    public NewsPage(ConfigManager configManager, Database database) {
        this.newsService = new NewsService(configManager.getConfig(), database);

        getStyleClass().add("page");
        setPadding(new Insets(0));

        Label title = new Label("新闻");
        title.getStyleClass().add("page-title");

        Button refresh = new Button("刷新新闻");
        refresh.setOnAction(e -> load(true));
        Button offline = new Button("离线缓存");
        offline.setOnAction(e -> load(false));

        listView.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Database.NewsRecord item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.title() + "\n" + item.category()
                            + (item.summary() == null || item.summary().isBlank()
                            ? "" : " · " + item.summary()));
                }
            }
        });
        listView.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                detail.setText(b.title() + "\n\n" + (b.body() == null ? "" : b.body()));
            }
        });

        detail.setEditable(false);
        detail.setWrapText(true);
        detail.getStyleClass().add("code-area");

        SplitPane split = new SplitPane(listView, detail);
        split.setDividerPositions(0.38);
        VBox.setVgrow(split, Priority.ALWAYS);

        HBox tools = new HBox(8, refresh, offline);
        VBox root = new VBox(10, title, tools, split);
        setCenter(root);
        load(false);
    }

    private void load(boolean force) {
        worker.submit(() -> {
            try {
                List<Database.NewsRecord> news = newsService.load(force);
                Platform.runLater(() -> {
                    listView.setItems(FXCollections.observableArrayList(news));
                    if (!news.isEmpty()) {
                        listView.getSelectionModel().selectFirst();
                    }
                });
            } catch (Exception e) {
                ExceptionHandler.handle("加载新闻失败", e);
            }
        });
    }
}
