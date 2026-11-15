package br.com.nexu;

import br.com.nexu.ui.NexuApplication;
import javafx.application.Application;

/**
 * Ponto de entrada. Fica separado da classe {@link javafx.application.Application} para que o
 * jar executável funcione no classpath (sem module-path), como exige o JavaFX fora de módulos.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Application.launch(NexuApplication.class, args);
    }
}
