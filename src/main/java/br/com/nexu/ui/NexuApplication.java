package br.com.nexu.ui;

import br.com.nexu.config.AppConfig;
import br.com.nexu.core.NexuAssistant;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Aplicação JavaFX: cria a janela, o assistente e garante a liberação de recursos ao sair. */
public final class NexuApplication extends Application {

    private static final Logger log = LoggerFactory.getLogger(NexuApplication.class);

    private NexuAssistant assistant;

    @Override
    public void start(Stage stage) {
        AppConfig config = AppConfig.load();
        NexuWindow window = new NexuWindow(stage, config);
        stage.setOnCloseRequest(event -> Platform.exit());
        stage.show();
        try {
            assistant = NexuAssistant.create(config);
        } catch (RuntimeException e) {
            log.error("Falha ao iniciar o Nexu", e);
            window.showFatalError("Não foi possível iniciar o Nexu: " + e.getMessage());
            return;
        }
        window.attach(assistant);
        assistant.start();
    }

    @Override
    public void stop() {
        if (assistant != null) {
            assistant.close();
        }
        // Garantia: se alguma biblioteca nativa mantiver threads vivas, encerra a JVM.
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "nexu-exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
