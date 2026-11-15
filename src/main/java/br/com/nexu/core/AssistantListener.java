package br.com.nexu.core;

import java.time.Duration;

/**
 * Eventos do assistente para a interface (ou qualquer outro observador).
 * Os métodos são chamados em threads de trabalho; quem atualizar UI deve repassar para a
 * thread gráfica.
 */
public interface AssistantListener {

    default void onStateChanged(AssistantState state) {
    }

    /** {@code true} quando o Nexu aguarda um comando sem exigir a palavra de ativação. */
    default void onAwaitingCommandChanged(boolean awaitingCommand) {
    }

    default void onConversation(ConversationEntry entry) {
    }

    default void onPartialTranscript(String text) {
    }

    /**
     * Frase transcrita pelo microfone e como ela foi interpretada.
     *
     * @param transcript     texto bruto do reconhecedor
     * @param interpretation descrição da interpretação ("Previsão do tempo · Recife", "ignorado"...)
     */
    default void onRecognized(String transcript, String interpretation) {
    }

    default void onAudioLevel(double level) {
    }

    /** @param remaining tempo restante, ou {@code null} quando não há cronômetro ativo */
    default void onTimerUpdate(Duration remaining, Duration total) {
    }

    default void onTimerFinished() {
    }

    default void onPausedChanged(boolean paused) {
    }

    default void onError(String message) {
    }

    /** O usuário pediu para encerrar; a aplicação deve fechar. */
    default void onShutdownRequested() {
    }
}
