package br.com.nexu.audio.tts;

import br.com.nexu.audio.TextToSpeech;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Base para motores de voz executados como processo externo (um processo por fala).
 * Os argumentos são passados diretamente ao sistema operacional, sem shell, portanto o texto
 * falado não pode injetar comandos.
 */
public abstract class ProcessTts implements TextToSpeech {

    private static final long BASE_TIMEOUT_SECONDS = 20;

    private volatile Process current;

    /** Comando a executar para falar o texto. */
    protected abstract List<String> command(String text);

    /** Se {@code true}, o texto é enviado pela entrada padrão do processo. */
    protected boolean textViaStdin() {
        return false;
    }

    @Override
    public void speak(String text) throws TtsException {
        if (text == null || text.isBlank()) {
            return;
        }
        List<String> cmd = command(text);
        Process process;
        try {
            process = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new TtsException("Não foi possível iniciar o motor de voz '" + cmd.get(0) + "'", e);
        }
        current = process;
        try {
            if (textViaStdin()) {
                try (OutputStream out = process.getOutputStream()) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            long timeout = BASE_TIMEOUT_SECONDS + text.length() / 8;
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new TtsException("O motor de voz excedeu o tempo limite");
            }
            int exit = process.exitValue();
            if (exit != 0 && current == process) {
                throw new TtsException("O motor de voz terminou com código " + exit);
            }
        } catch (IOException e) {
            throw new TtsException("Falha de comunicação com o motor de voz", e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new TtsException("Fala interrompida", e);
        } finally {
            current = null;
        }
    }

    @Override
    public void stop() {
        Process p = current;
        current = null;
        if (p != null) {
            p.destroy();
        }
    }

    @Override
    public void close() {
        stop();
    }
}
