package br.com.nexu.audio.tts;

import br.com.nexu.audio.TextToSpeech;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Windows: voz nativa SAPI (System.Speech) através de um processo PowerShell persistente.
 * <p>
 * Manter o processo aberto evita o atraso de inicialização a cada fala. Cada frase é enviada
 * em Base64 (UTF-8), o que elimina problemas de acentuação e qualquer risco de injeção.
 * Por padrão é escolhida a primeira voz pt-BR instalada (ex.: "Microsoft Maria Desktop").
 */
public final class WindowsSapiTts implements TextToSpeech {

    private static final Logger log = LoggerFactory.getLogger(WindowsSapiTts.class);

    private static final String READY = "__READY__";
    private static final String DONE = "__DONE__";
    private static final String ERROR = "__ERROR__:";
    private static final String SELECTED = "__SELECTED__:";

    private static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            Add-Type -AssemblyName System.Speech
            $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            $s.SetOutputToDefaultAudioDevice()
            $wanted = '%VOICE%'
            $culture = '%CULTURE%'
            $voices = @($s.GetInstalledVoices() | Where-Object { $_.Enabled })
            $v = $null
            if ($wanted.Length -gt 0) { $v = $voices | Where-Object { $_.VoiceInfo.Name -like "*$wanted*" } | Select-Object -First 1 }
            if (-not $v) { $v = $voices | Where-Object { $_.VoiceInfo.Culture.Name -eq $culture } | Select-Object -First 1 }
            if ($v) { $s.SelectVoice($v.VoiceInfo.Name); [Console]::Out.WriteLine('__SELECTED__:' + $v.VoiceInfo.Name) }
            else { [Console]::Out.WriteLine('__SELECTED__:' + $s.Voice.Name + ' (nenhuma voz ' + $culture + ' instalada)') }
            $s.Rate = %RATE%
            $s.Volume = %VOLUME%
            [Console]::Out.WriteLine('__READY__')
            [Console]::Out.Flush()
            while ($true) {
              $line = [Console]::In.ReadLine()
              if ($line -eq $null -or $line -eq '__EXIT__') { break }
              if ($line.Trim().Length -gt 0) {
                try {
                  $text = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($line))
                  $s.Speak($text)
                } catch { [Console]::Out.WriteLine('__ERROR__:' + $_.Exception.Message) }
              }
              [Console]::Out.WriteLine('__DONE__')
              [Console]::Out.Flush()
            }
            $s.Dispose()
            """;

    private final String voice;
    private final String culture;
    private final int rate;
    private final int volume;

    private final Object lock = new Object();
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private Process process;
    private BufferedWriter writer;
    private volatile String selectedVoice = "SAPI";

    public WindowsSapiTts(String voice, String culture, int rate, int volume) {
        this.voice = sanitize(voice);
        this.culture = sanitize(culture == null || culture.isBlank() ? "pt-BR" : culture);
        this.rate = Math.max(-10, Math.min(10, rate));
        this.volume = Math.max(0, Math.min(100, volume));
    }

    @Override
    public String name() {
        return "Windows SAPI (" + selectedVoice + ")";
    }

    @Override
    public void speak(String text) throws TtsException {
        if (text == null || text.isBlank()) {
            return;
        }
        synchronized (lock) {
            ensureStarted();
            lines.clear();
            String payload = Base64.getEncoder().encodeToString(text.replace('\n', ' ').getBytes(StandardCharsets.UTF_8));
            try {
                writer.write(payload);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                destroy();
                throw new TtsException("Falha ao enviar texto ao PowerShell", e);
            }
            long timeoutMillis = 20_000 + text.length() * 150L;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            Process speaking = process;
            try {
                while (true) {
                    String line = lines.poll(250, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        if (!speaking.isAlive() || System.nanoTime() - deadline > 0) {
                            destroy();
                            throw new TtsException("A síntese de voz do Windows não respondeu");
                        }
                        continue;
                    }
                    if (line.equals(DONE)) {
                        return;
                    }
                    if (line.startsWith(ERROR)) {
                        log.warn("SAPI: {}", line.substring(ERROR.length()));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TtsException("Fala interrompida", e);
            }
        }
    }

    private void ensureStarted() throws TtsException {
        if (process != null && process.isAlive()) {
            return;
        }
        String script = SCRIPT
                .replace("%VOICE%", voice)
                .replace("%CULTURE%", culture)
                .replace("%RATE%", Integer.toString(rate))
                .replace("%VOLUME%", Integer.toString(volume));
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        try {
            process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new TtsException("Não foi possível iniciar o PowerShell para a síntese de voz", e);
        }
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII));
        lines.clear();
        Process started = process;
        Thread reader = new Thread(() -> readOutput(started), "nexu-tts-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (true) {
                String line = lines.poll(250, TimeUnit.MILLISECONDS);
                if (line == null) {
                    if (!started.isAlive() || System.nanoTime() - deadline > 0) {
                        destroy();
                        throw new TtsException("A síntese de voz do Windows não inicializou "
                                + "(verifique se o PowerShell está disponível)");
                    }
                    continue;
                }
                if (line.startsWith(SELECTED)) {
                    selectedVoice = line.substring(SELECTED.length());
                    log.info("Voz do Windows selecionada: {}", selectedVoice);
                } else if (line.equals(READY)) {
                    return;
                } else {
                    log.debug("PowerShell: {}", line);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroy();
            throw new TtsException("Inicialização da voz interrompida", e);
        }
    }

    private void readOutput(Process p) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.startsWith("__")) {
                    lines.offer(trimmed);
                } else if (!trimmed.isEmpty()) {
                    log.warn("PowerShell: {}", trimmed);
                }
            }
        } catch (IOException e) {
            log.debug("Leitura do PowerShell encerrada: {}", e.getMessage());
        }
    }

    private void destroy() {
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
    }

    @Override
    public void stop() {
        // A fala do SAPI é síncrona dentro do PowerShell: interromper exige encerrar o processo.
        Process p = process;
        if (p != null) {
            p.destroyForcibly();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (process != null && process.isAlive()) {
                try {
                    writer.write("__EXIT__");
                    writer.newLine();
                    writer.flush();
                    process.waitFor(1500, TimeUnit.MILLISECONDS);
                } catch (IOException e) {
                    log.debug("PowerShell já encerrado");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            destroy();
        }
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[^\\p{L}\\p{N} ._()-]", "");
    }
}
