package br.com.nexu.audio.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** macOS: comando nativo {@code say} com voz pt-BR (padrão: Luciana). */
public final class MacSayTts extends ProcessTts {

    private static final Logger log = LoggerFactory.getLogger(MacSayTts.class);

    private volatile String voice;
    private final int rate;

    public MacSayTts(String voice, int rate) {
        this.voice = voice == null || voice.isBlank() ? null : voice;
        this.rate = rate;
    }

    @Override
    public String name() {
        return "macOS say" + (voice != null ? " (" + voice + ")" : "");
    }

    @Override
    protected List<String> command(String text) {
        List<String> cmd = new ArrayList<>(List.of("say"));
        if (voice != null) {
            cmd.add("-v");
            cmd.add(voice);
        }
        if (rate > 0) {
            cmd.add("-r");
            cmd.add(Integer.toString(rate));
        }
        cmd.add("--");
        cmd.add(text);
        return cmd;
    }

    @Override
    public void speak(String text) throws TtsException {
        try {
            super.speak(text);
        } catch (TtsException e) {
            if (voice == null) {
                throw e;
            }
            // Voz não instalada: tenta novamente com a voz padrão do sistema.
            log.warn("Voz '{}' indisponível no macOS ({}). Usando a voz padrão do sistema.", voice, e.getMessage());
            voice = null;
            super.speak(text);
        }
    }
}
