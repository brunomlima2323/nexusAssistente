package br.com.nexu.audio.tts;

import br.com.nexu.audio.TextToSpeech;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Usado quando nenhum motor de voz está disponível (ou {@code tts.engine=none}).
 * As respostas continuam aparecendo no histórico da interface, e o motivo é informado no log.
 */
public final class NoVoiceTts implements TextToSpeech {

    private static final Logger log = LoggerFactory.getLogger(NoVoiceTts.class);

    private final String reason;

    public NoVoiceTts(String reason) {
        this.reason = reason;
        log.warn("Síntese de voz desativada: {}", reason);
    }

    public String reason() {
        return reason;
    }

    @Override
    public String name() {
        return "Sem voz (" + reason + ")";
    }

    @Override
    public void speak(String text) {
        log.info("[sem voz] {}", text);
    }

    @Override
    public void stop() {
        // nada a interromper
    }

    @Override
    public void close() {
        // nada a liberar
    }
}
