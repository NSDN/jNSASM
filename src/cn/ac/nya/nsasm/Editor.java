package cn.ac.nya.nsasm;

import javafx.application.Application;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/**
 * Created by drzzm on 2017.4.24.
 */
public class Editor extends Application {

    private static Stage stage;
    private static GridPane root;

    private static final String title = "NSASM Editor" + " v" + NSASM.version;
    private static final int width = 640;
    private static final int height = 360;

    private int tabCnt = 0;

    @Override
    public void start(Stage primaryStage) throws Exception {
        stage = primaryStage;
        stage.setMinWidth(640);
        stage.setMinHeight(360);

        root = new GridPane();
        root.setAlignment(Pos.CENTER);
        root.setHgap(10); root.setVgap(10);
        root.setMinSize(640, 360);
        root.setPadding(new Insets(5.0, 5.0, 5.0, 5.0));

        TextArea textArea = new TextArea();
        GridPane.setHalignment(textArea, HPos.LEFT);
        GridPane.setValignment(textArea, VPos.TOP);
        GridPane.setHgrow(textArea, Priority.ALWAYS);
        GridPane.setVgrow(textArea, Priority.ALWAYS);
        GridPane.setMargin(textArea, new Insets(5.0, 5.0, 0.0, 5.0));
        textArea.setId("content");
        textArea.setOnKeyTyped((event -> {
            TextArea area = ((TextArea) root.lookup("#content"));
            Label tabCntLabel = ((Label) root.lookup("#tabCnt"));
            Label codeLenLabel = ((Label) root.lookup("#codeLen"));
            int pos = area.getCaretPosition();
            switch (event.getCharacter()) {
                case "\b":
                    if (pos == 0 && area.getText().isEmpty()) {
                        tabCnt = tabCnt > 0 ? tabCnt - 1 : 0;
                    }
                    while (pos > 0 && (
                                area.getText().charAt(pos - 1) == '\t' ||
                                area.getText().charAt(pos - 1) == '\n'
                        )) {
                        if (area.getText().charAt(pos - 1) == '\n') {
                            tabCnt = tabCnt > 0 ? tabCnt - 1 : 0;
                            break;
                        }
                        pos -= 1;
                        if (pos == 0 || area.getText().charAt(pos - 1) == '\n') {
                            tabCnt = tabCnt > 0 ? tabCnt - 1 : 0;
                            break;
                        }
                    }
                    break;
                case "\t":
                    tabCnt += 1;
                    break;
                case "\r":
                    for (int i = 0; i < tabCnt; i++)
                        area.insertText(area.getCaretPosition(), "\t");
                    break;
            }
            tabCntLabel.setText("Tab count: " + tabCnt);
            codeLenLabel.setText("Code length: " + area.getText().length());
        }));
        root.add(textArea, 0, 0);

        GridPane gridPane = new GridPane();
        gridPane.setHgap(10); gridPane.setVgap(10);
        GridPane.setHalignment(textArea, HPos.LEFT);
        GridPane.setValignment(textArea, VPos.BOTTOM);
        GridPane.setHgrow(textArea, Priority.ALWAYS);
        GridPane.setMargin(gridPane, new Insets(0.0, 5.0, 5.0, 5.0));
        gridPane.setAlignment(Pos.BASELINE_RIGHT);
        gridPane.setHgap(10); gridPane.setVgap(10);

        Button btnOK = new Button("Run");
        btnOK.setId("btnOK");
        btnOK.setOnMouseClicked(event -> {
            String code = ((TextArea) root.lookup("#content")).getText();
            ((Button) root.lookup("#btnOK")).setDisable(true);
            ((Button) root.lookup("#btnExit")).setDisable(true);
            new Thread(() -> {
                Util.execute(code);
                ((Button) root.lookup("#btnOK")).setDisable(false);
                ((Button) root.lookup("#btnExit")).setDisable(false);
            }).start();
        });
        gridPane.add(btnOK, 1, 0);
        Button btnExit = new Button("Exit");
        btnExit.setId("btnExit");
        btnExit.setCancelButton(true);
        btnExit.setOnMouseClicked(event -> {
            ((TextArea) root.lookup("#content")).clear();
            stage.close();
        });
        gridPane.add(btnExit, 2, 0);
        
        GridPane infoPane = new GridPane();
        GridPane.setHgrow(infoPane, Priority.ALWAYS);
        infoPane.setAlignment(Pos.CENTER_LEFT);

        Label tabCntLabel = new Label();
        tabCntLabel.setText("Tab count: 0");
        tabCntLabel.setId("tabCnt");
        infoPane.add(tabCntLabel, 0, 0);

        Separator separator = new Separator();
        separator.setOrientation(Orientation.VERTICAL);
        GridPane.setMargin(separator, new Insets(0.0, 5.0, 0.0, 5.0));
        infoPane.add(separator, 1, 0);

        Label codeLenLabel = new Label();
        codeLenLabel.setText("Code length: 0");
        codeLenLabel.setId("codeLen");
        infoPane.add(codeLenLabel, 2, 0);

        gridPane.add(infoPane, 0, 0);

        root.add(gridPane, 0, 1);

        stage.setTitle(title);
        stage.setScene(new Scene(root, width, height));
        stage.show();
    }

    public void show() {
        launch();
    }

}
