package br.com.nexu.ui;

import br.com.nexu.config.AppConfig;
import br.com.nexu.core.AssistantListener;
import br.com.nexu.core.AssistantState;
import br.com.nexu.core.ConversationEntry;
import br.com.nexu.core.NexuAssistant;
import br.com.nexu.utils.SpeechFormatter;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Janela principal: núcleo animado, estado, histórico de conversa, último comando reconhecido,
 * cronômetro ativo e botão para pausar/retomar a escuta.
 */
public final class NexuWindow implements AssistantListener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Stage stage;
    private final int historyMax;
    private final boolean bringToFrontOnTimer;

    private final ReactorView reactor = new ReactorView(260);
    private final Label stateLabel = new Label(AssistantState.INITIALIZING.label());
    private final Circle stateDot = new Circle(5);
    private final Label modeLabel = new Label("Inicializando sistemas...");
    private final Label partialLabel = new Label();
    private final Label timerLabel = new Label();
    private final Label lastCommandLabel = new Label("—");
    private final Label lastInterpretationLabel = new Label();
    private final Circle micDot = new Circle(6);
    private final Label micLabel = new Label("Microfone inativo");
    private final Button toggleButton = new Button("Pausar");
    private final ObservableList<ConversationEntry> history = FXCollections.observableArrayList();
    private final ListView<ConversationEntry> historyView = new ListView<>(history);

    private NexuAssistant assistant;
    private AssistantState currentState = AssistantState.INITIALIZING;
    private boolean awaitingCommand;

    public NexuWindow(Stage stage, AppConfig config) {
        this.stage = stage;
        this.historyMax = Math.max(20, config.getInt("ui.history.max", 200));
        this.bringToFrontOnTimer = config.getBoolean("ui.bring.to.front.on.timer", true);
        build();
    }

    private void build() {
        // ---- cabeçalho
        Label title = new Label("N E X U");
        title.getStyleClass().add("title");
        Label subtitle = new Label("Assistente Virtual Inteligente");
        subtitle.getStyleClass().add("subtitle");
        VBox titles = new VBox(2, title, subtitle);

        stateDot.getStyleClass().add("state-dot");
        stateLabel.getStyleClass().add("state-label");
        HBox stateChip = new HBox(8, stateDot, stateLabel);
        stateChip.setAlignment(Pos.CENTER);
        stateChip.getStyleClass().add("state-chip");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(16, titles, spacer, stateChip);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");

        // ---- painel esquerdo
        modeLabel.getStyleClass().add("mode-label");
        modeLabel.setWrapText(true);
        partialLabel.getStyleClass().add("partial-label");
        partialLabel.setWrapText(true);
        partialLabel.setMinHeight(40);
        timerLabel.getStyleClass().add("timer-label");
        timerLabel.setVisible(false);
        timerLabel.setManaged(false);

        VBox left = new VBox(14, reactor, modeLabel, partialLabel, timerLabel);
        left.setAlignment(Pos.TOP_CENTER);
        left.setPrefWidth(320);
        left.setMinWidth(300);
        left.getStyleClass().add("panel");

        // ---- histórico
        Label historyTitle = new Label("HISTÓRICO");
        historyTitle.getStyleClass().add("section-title");
        historyView.getStyleClass().add("history");
        historyView.setCellFactory(list -> new EntryCell());
        historyView.setFocusTraversable(false);
        VBox.setVgrow(historyView, Priority.ALWAYS);
        VBox right = new VBox(8, historyTitle, historyView);
        right.getStyleClass().add("panel");
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox center = new HBox(14, left, right);
        center.setPadding(new Insets(0, 16, 0, 16));

        // ---- rodapé
        micDot.getStyleClass().addAll("mic-dot", "mic-off");
        micLabel.getStyleClass().add("mic-label");
        HBox mic = new HBox(8, micDot, micLabel);
        mic.setAlignment(Pos.CENTER_LEFT);

        Label lastTitle = new Label("ÚLTIMO COMANDO");
        lastTitle.getStyleClass().add("section-title");
        lastCommandLabel.getStyleClass().add("last-command");
        lastInterpretationLabel.getStyleClass().add("last-interpretation");
        VBox last = new VBox(2, lastTitle, lastCommandLabel, lastInterpretationLabel);
        HBox.setHgrow(last, Priority.ALWAYS);

        toggleButton.getStyleClass().add("toggle-button");
        toggleButton.setDisable(true);
        toggleButton.setOnAction(e -> {
            if (assistant != null) {
                assistant.setPaused(!assistant.isPaused());
            }
        });

        HBox footer = new HBox(24, mic, last, toggleButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("footer");

        BorderPane root = new BorderPane(center, header, null, footer, null);
        root.getStyleClass().add("root-pane");

        Scene scene = new Scene(root, 1000, 640);
        scene.getStylesheets().add(Objects.requireNonNull(
                NexuWindow.class.getResource("/ui/nexu.css"), "nexu.css ausente").toExternalForm());
        stage.setTitle("Nexu");
        stage.setMinWidth(820);
        stage.setMinHeight(520);
        stage.setScene(scene);

        stage.iconifiedProperty().addListener((obs, was, iconified) -> {
            if (iconified) {
                reactor.stopAnimation();
            } else {
                reactor.startAnimation();
            }
        });
        reactor.startAnimation();
        applyState(AssistantState.INITIALIZING);
    }

    /** Conecta a janela ao assistente. Deve ser chamado na thread do JavaFX. */
    public void attach(NexuAssistant nexu) {
        this.assistant = nexu;
        nexu.addListener(this);
    }

    public void showFatalError(String message) {
        fx(() -> {
            applyState(AssistantState.ERROR);
            addEntry(ConversationEntry.system(message));
            modeLabel.setText(message);
        });
    }

    // ------------------------------------------------------------------ AssistantListener

    @Override
    public void onStateChanged(AssistantState state) {
        fx(() -> applyState(state));
    }

    @Override
    public void onAwaitingCommandChanged(boolean awaiting) {
        fx(() -> {
            awaitingCommand = awaiting;
            reactor.setAwaitingCommand(awaiting);
            updateModeLabel();
        });
    }

    @Override
    public void onConversation(ConversationEntry entry) {
        fx(() -> addEntry(entry));
    }

    @Override
    public void onPartialTranscript(String text) {
        fx(() -> partialLabel.setText(text == null || text.isBlank() ? "" : "“" + text + "…”"));
    }

    @Override
    public void onRecognized(String transcript, String interpretation) {
        fx(() -> {
            lastCommandLabel.setText("“" + transcript + "”");
            lastInterpretationLabel.setText("→ " + interpretation);
        });
    }

    @Override
    public void onAudioLevel(double level) {
        reactor.setLevel(level); // thread-safe, sem runLater para não sobrecarregar a fila do JavaFX
    }

    @Override
    public void onTimerUpdate(Duration remaining, Duration total) {
        fx(() -> {
            boolean visible = remaining != null;
            timerLabel.setVisible(visible);
            timerLabel.setManaged(visible);
            if (visible) {
                timerLabel.setText("⏱  " + SpeechFormatter.clock(remaining));
            }
        });
    }

    @Override
    public void onTimerFinished() {
        if (bringToFrontOnTimer) {
            fx(() -> {
                if (stage.isIconified()) {
                    stage.setIconified(false);
                }
                stage.toFront();
            });
        }
    }

    @Override
    public void onPausedChanged(boolean paused) {
        fx(() -> toggleButton.setText(paused ? "Iniciar" : "Pausar"));
    }

    @Override
    public void onError(String message) {
        fx(() -> lastInterpretationLabel.setText("⚠ " + message));
    }

    @Override
    public void onShutdownRequested() {
        fx(Platform::exit);
    }

    // ------------------------------------------------------------------ auxiliares

    private void applyState(AssistantState state) {
        currentState = state;
        reactor.setState(state);
        stateLabel.setText(state.label().toUpperCase());
        stateDot.getStyleClass().setAll("state-dot", "state-" + state.name().toLowerCase());
        boolean micActive = state == AssistantState.LISTENING;
        micDot.getStyleClass().setAll("mic-dot", micActive ? "mic-on" : "mic-off");
        micLabel.setText(switch (state) {
            case LISTENING -> "Microfone ativo";
            case SPEAKING -> "Microfone silenciado (falando)";
            case PROCESSING -> "Microfone ativo · processando";
            case PAUSED -> "Microfone pausado";
            case ERROR -> "Microfone indisponível";
            default -> "Microfone inativo";
        });
        toggleButton.setDisable(state == AssistantState.INITIALIZING || state == AssistantState.ERROR
                || state == AssistantState.SHUTTING_DOWN);
        updateModeLabel();
    }

    private void updateModeLabel() {
        modeLabel.setText(switch (currentState) {
            case INITIALIZING -> "Inicializando sistemas...";
            case PAUSED -> "Escuta pausada. Clique em Iniciar para retomar.";
            case ERROR -> modeLabel.getText();
            case SHUTTING_DOWN -> "Encerrando...";
            case SPEAKING -> "Respondendo...";
            case PROCESSING -> "Processando comando...";
            case LISTENING -> awaitingCommand ? "Aguardando seu comando, senhor." : "Diga “Nexu” para me chamar.";
        });
    }

    private void addEntry(ConversationEntry entry) {
        history.add(entry);
        if (history.size() > historyMax) {
            history.remove(0, history.size() - historyMax);
        }
        historyView.scrollTo(history.size() - 1);
    }

    private static void fx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /** Célula do histórico em forma de balão. */
    private static final class EntryCell extends ListCell<ConversationEntry> {
        private final Label who = new Label();
        private final Label time = new Label();
        private final Label text = new Label();
        private final VBox bubble;
        private final HBox row;

        EntryCell() {
            who.getStyleClass().add("bubble-who");
            time.getStyleClass().add("bubble-time");
            text.getStyleClass().add("bubble-text");
            text.setWrapText(true);
            HBox meta = new HBox(8, who, time);
            meta.setAlignment(Pos.CENTER_LEFT);
            bubble = new VBox(3, meta, text);
            row = new HBox(bubble);
            setPrefWidth(0);
            getStyleClass().add("entry-cell");
        }

        @Override
        protected void updateItem(ConversationEntry item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            who.setText(switch (item.speaker()) {
                case USER -> "VOCÊ";
                case NEXU -> "NEXU";
                case SYSTEM -> "SISTEMA";
            });
            time.setText(TIME.format(item.time()));
            text.setText(item.text());
            double width = getListView() != null ? getListView().getWidth() : 600;
            text.setMaxWidth(Math.max(220, width * 0.72));
            bubble.getStyleClass().setAll("bubble", "bubble-" + item.speaker().name().toLowerCase());
            row.setAlignment(switch (item.speaker()) {
                case USER -> Pos.CENTER_RIGHT;
                case NEXU -> Pos.CENTER_LEFT;
                case SYSTEM -> Pos.CENTER;
            });
            setGraphic(row);
        }
    }
}
