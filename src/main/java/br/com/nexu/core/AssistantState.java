package br.com.nexu.core;

/** Estado geral do assistente, exibido na interface. */
public enum AssistantState {
    INITIALIZING("Inicializando"),
    LISTENING("Escutando"),
    PROCESSING("Processando"),
    SPEAKING("Falando"),
    PAUSED("Pausado"),
    ERROR("Erro"),
    SHUTTING_DOWN("Encerrando");

    private final String label;

    AssistantState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
