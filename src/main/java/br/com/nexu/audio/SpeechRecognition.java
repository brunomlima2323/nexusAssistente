package br.com.nexu.audio;

/**
 * Reconhecimento de voz contínuo. A implementação captura o microfone em thread própria
 * e entrega transcrições ao {@link Listener}; ela não sabe nada sobre comandos — a
 * interpretação fica separada, no pacote {@code commands}.
 */
public interface SpeechRecognition extends AutoCloseable {

    /** Eventos do reconhecimento. Chamados na thread de captura: não bloqueie. */
    interface Listener {
        /** Modelo carregado e microfone aberto. */
        default void onReady() {
        }

        /** Transcrição parcial, ainda sujeita a mudanças. */
        default void onPartialResult(String text) {
        }

        /** Frase concluída. */
        void onFinalResult(String text);

        /** Nível do sinal do microfone (0..1), para animações. */
        default void onAudioLevel(double level) {
        }

        /** Erro irrecuperável (ex.: modelo ausente, microfone indisponível). */
        default void onError(String message, Throwable cause) {
        }
    }

    /** Inicia a captura de forma assíncrona. */
    void start();

    /** Suspende a captura (libera o microfone para outros programas). */
    void pause();

    /** Retoma a captura. */
    void resume();

    boolean isPaused();

    @Override
    void close();
}
