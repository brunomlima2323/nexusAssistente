package br.com.nexu.audio;

/**
 * Motor de síntese de voz. As implementações são chamadas sempre a partir de uma única
 * thread (a fila de áudio do {@link AudioManager}), por isso podem ser bloqueantes.
 */
public interface TextToSpeech extends AutoCloseable {

    /** Nome legível do motor, exibido na interface. */
    String name();

    /** Fala o texto e só retorna quando a fala terminar. */
    void speak(String text) throws TtsException;

    /** Interrompe a fala atual, se houver. */
    void stop();

    @Override
    void close();

    /** Erro de síntese de voz. */
    final class TtsException extends Exception {
        public TtsException(String message) {
            super(message);
        }

        public TtsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
