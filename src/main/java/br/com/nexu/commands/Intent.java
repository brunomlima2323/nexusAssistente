package br.com.nexu.commands;

/**
 * Intenções que o Nexu sabe reconhecer, na ordem de prioridade de avaliação.
 * Para adicionar uma nova funcionalidade: crie a intenção aqui, uma regra em
 * {@link IntentRecognizer} e um {@link br.com.nexu.commands.handlers.CommandHandler}.
 */
public enum Intent {
    SHUTDOWN("Encerrar assistente"),
    WEATHER("Previsão do tempo"),
    TIMER_REMAINING("Tempo restante do cronômetro"),
    TIMER_CANCEL("Cancelar cronômetro"),
    TIMER_CREATE("Iniciar cronômetro"),
    TIME("Consultar horário"),
    DATE("Consultar data"),
    UNKNOWN("Não reconhecido");

    private final String label;

    Intent(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
