package br.com.nexu.audio;

import br.com.nexu.config.AppConfig;
import br.com.nexu.utils.FuzzyMatcher;
import br.com.nexu.utils.TextNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Saída de áudio do Nexu (voz e efeitos) e controle de "half-duplex" do microfone.
 * <p>
 * Toda saída passa por uma fila única, então falas e bipes nunca se sobrepõem. Enquanto o
 * Nexu fala ou toca um som — e por um curto período depois ({@code audio.echo.guard.millis}) —
 * o microfone é considerado fechado, e o reconhecedor descarta o áudio captado. Além disso,
 * transcrições parecidas com uma fala recente do próprio Nexu são reconhecidas como eco.
 */
public final class AudioManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AudioManager.class);

    /** Eventos de saída de áudio. */
    public interface Listener {
        default void onSpeechStarted(String text) {
        }

        default void onSpeechFinished(String text) {
        }

        default void onAudioError(String message) {
        }
    }

    private static final class SpokenText {
        final String normalized;
        final Set<String> tokens;
        volatile long finishedNanos;
        volatile boolean finished;

        SpokenText(String text) {
            this.normalized = TextNormalizer.normalize(text);
            this.tokens = new HashSet<>(TextNormalizer.tokens(text));
        }
    }

    private record Sound(AudioFormat format, byte[] data) {
    }

    private final TextToSpeech tts;
    private final ExecutorService output;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger busy = new AtomicInteger();
    private final ConcurrentLinkedDeque<SpokenText> recent = new ConcurrentLinkedDeque<>();
    private final long echoGuardNanos;
    private final long echoWindowNanos;
    private final Sound beep;
    private volatile long reopenAtNanos;
    private volatile boolean closed;

    public AudioManager(AppConfig config, TextToSpeech tts) {
        this.tts = tts;
        this.echoGuardNanos = TimeUnit.MILLISECONDS.toNanos(config.getLong("audio.echo.guard.millis", 700));
        this.echoWindowNanos = TimeUnit.MILLISECONDS.toNanos(config.getLong("audio.echo.window.millis", 4000));
        this.beep = loadBeep(config.getString("audio.beep.file"));
        this.reopenAtNanos = System.nanoTime();
        this.output = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nexu-audio-out");
            t.setDaemon(true);
            return t;
        });
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public String engineName() {
        return tts.name();
    }

    /** Enfileira uma fala. O futuro completa quando a fala termina (com ou sem erro). */
    public CompletableFuture<Void> speak(String text) {
        if (closed || text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            SpokenText spoken = new SpokenText(text);
            recent.addLast(spoken);
            beginOutput();
            listeners.forEach(l -> l.onSpeechStarted(text));
            try {
                tts.speak(text);
            } catch (TextToSpeech.TtsException e) {
                log.error("Falha na síntese de voz: {}", e.getMessage(), e.getCause());
                listeners.forEach(l -> l.onAudioError("Falha na síntese de voz: " + e.getMessage()));
            } finally {
                spoken.finishedNanos = System.nanoTime();
                spoken.finished = true;
                endOutput();
                listeners.forEach(l -> l.onSpeechFinished(text));
            }
        }, output).exceptionally(error -> {
            log.error("Erro na fila de áudio", error);
            return null;
        });
    }

    /** Toca o bipe de alerta {@code times} vezes. */
    public CompletableFuture<Void> beep(int times) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            beginOutput();
            try {
                play(beep, Math.max(1, times));
            } catch (LineUnavailableException | RuntimeException e) {
                log.error("Não foi possível tocar o bipe: {}", e.getMessage());
                listeners.forEach(l -> l.onAudioError("Não foi possível tocar o bipe"));
            } finally {
                endOutput();
            }
        }, output).exceptionally(error -> null);
    }

    /** {@code true} quando o microfone pode ser processado (o Nexu não está emitindo som). */
    public boolean isMicrophoneOpen() {
        return busy.get() == 0 && System.nanoTime() - reopenAtNanos >= 0;
    }

    public boolean isSpeaking() {
        return busy.get() > 0;
    }

    /** Detecta transcrições que provavelmente são a voz do próprio Nexu captada pelo microfone. */
    public boolean isLikelyEcho(String transcript) {
        String norm = TextNormalizer.normalize(transcript);
        if (norm.isEmpty()) {
            return false;
        }
        Set<String> tokens = new HashSet<>(TextNormalizer.tokens(transcript));
        long now = System.nanoTime();
        Iterator<SpokenText> it = recent.iterator();
        while (it.hasNext()) {
            SpokenText s = it.next();
            if (s.finished && now - s.finishedNanos > echoWindowNanos) {
                it.remove();
                continue;
            }
            if (norm.length() >= 4 && s.normalized.contains(norm)) {
                return true;
            }
            if (FuzzyMatcher.jaroWinkler(norm, s.normalized) >= 0.9) {
                return true;
            }
            if (tokens.size() >= 3) {
                long common = tokens.stream().filter(s.tokens::contains).count();
                if ((double) common / tokens.size() >= 0.8) {
                    return true;
                }
            }
        }
        return false;
    }

    private void beginOutput() {
        busy.incrementAndGet();
    }

    private void endOutput() {
        reopenAtNanos = System.nanoTime() + echoGuardNanos;
        busy.decrementAndGet();
    }

    // ------------------------------------------------------------------ sons

    private static void play(Sound sound, int times) throws LineUnavailableException {
        try (SourceDataLine line = AudioSystem.getSourceDataLine(sound.format())) {
            line.open(sound.format());
            line.start();
            int frameSize = Math.max(1, sound.format().getFrameSize());
            int gapBytes = (int) (sound.format().getFrameRate() * 0.12) * frameSize;
            byte[] gap = new byte[gapBytes];
            for (int i = 0; i < times; i++) {
                line.write(sound.data(), 0, sound.data().length);
                if (i < times - 1) {
                    line.write(gap, 0, gap.length);
                }
            }
            line.drain();
            line.stop();
        }
    }

    private static Sound loadBeep(String customFile) {
        if (customFile != null && !customFile.isBlank()) {
            Path path = Path.of(customFile);
            if (Files.isRegularFile(path)) {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
                    return readWav(in);
                } catch (IOException | UnsupportedAudioFileException e) {
                    log.warn("Não foi possível ler {}: {}. Usando bipe interno.", customFile, e.getMessage());
                }
            } else {
                log.warn("Arquivo de bipe {} não encontrado. Usando bipe interno.", customFile);
            }
        }
        try (InputStream raw = AudioManager.class.getResourceAsStream("/sounds/beep.wav")) {
            if (raw != null) {
                return readWav(new BufferedInputStream(raw));
            }
        } catch (IOException | UnsupportedAudioFileException e) {
            log.warn("Bipe interno inválido: {}. Gerando tom.", e.getMessage());
        }
        return synthesizeBeep();
    }

    private static Sound readWav(InputStream in) throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(in)) {
            return new Sound(stream.getFormat(), stream.readAllBytes());
        }
    }

    /** Tom de 880 Hz com 220 ms, envelope suave para evitar estalos. */
    static Sound synthesizeBeep() {
        float sampleRate = 44_100f;
        int samples = (int) (sampleRate * 0.22);
        int fade = (int) (sampleRate * 0.01);
        byte[] data = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double envelope = Math.min(1.0, Math.min(i, samples - 1 - i) / (double) fade);
            double value = Math.sin(2 * Math.PI * 880 * i / sampleRate) * 0.6 * envelope;
            short s = (short) (value * Short.MAX_VALUE);
            data[2 * i] = (byte) (s & 0xff);
            data[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
        return new Sound(new AudioFormat(sampleRate, 16, 1, true, false), data);
    }

    @Override
    public void close() {
        closed = true;
        tts.stop();
        output.shutdownNow();
        try {
            output.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        tts.close();
    }
}
