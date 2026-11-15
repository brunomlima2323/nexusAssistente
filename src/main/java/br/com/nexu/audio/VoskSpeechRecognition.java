package br.com.nexu.audio;

import br.com.nexu.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Reconhecimento contínuo e offline em português com o Vosk (Kaldi).
 * <p>
 * Captura o microfone a 16 kHz, mono, 16 bits, e envia os blocos ao reconhecedor.
 * Quando o {@code microphoneGate} indica que o Nexu está falando, o áudio é descartado e o
 * reconhecedor é reiniciado ao reabrir — assim a própria voz do assistente não vira comando.
 */
public final class VoskSpeechRecognition implements SpeechRecognition {

    private static final Logger log = LoggerFactory.getLogger(VoskSpeechRecognition.class);
    private static final int BUFFER_BYTES = 4096; // ~128 ms a 16 kHz/16 bits

    private final Path modelPath;
    private final float sampleRate;
    private final String deviceName;
    private final BooleanSupplier microphoneGate;
    private final Listener listener;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object pauseLock = new Object();

    private volatile boolean running;
    private volatile boolean paused;
    private Thread worker;

    public VoskSpeechRecognition(AppConfig config, BooleanSupplier microphoneGate, Listener listener) {
        this.modelPath = Path.of(config.getString("vosk.model.path", "models/vosk-model-small-pt-0.3"));
        this.sampleRate = config.getInt("audio.sample.rate", 16000);
        this.deviceName = config.getString("audio.input.device");
        this.microphoneGate = microphoneGate;
        this.listener = listener;
    }

    @Override
    public synchronized void start() {
        if (worker != null) {
            return;
        }
        running = true;
        worker = new Thread(this::run, "nexu-stt");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    private void run() {
        if (!Files.isDirectory(modelPath)) {
            listener.onError("Modelo de reconhecimento de voz não encontrado em " + modelPath.toAbsolutePath()
                    + ". Baixe o modelo em português em https://alphacephei.com/vosk/models e configure vosk.model.path.", null);
            return;
        }
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
        } catch (UnsatisfiedLinkError e) {
            listener.onError("Biblioteca nativa do Vosk indisponível para este sistema: " + e.getMessage(), e);
            return;
        }
        log.info("Carregando modelo Vosk de {}", modelPath.toAbsolutePath());
        try (Model model = new Model(modelPath.toString());
             Recognizer recognizer = new Recognizer(model, sampleRate)) {
            capture(recognizer);
        } catch (IOException e) {
            listener.onError("Falha ao carregar o modelo de voz em " + modelPath.toAbsolutePath()
                    + ". Verifique se a pasta contém um modelo Vosk válido.", e);
        } catch (LineUnavailableException e) {
            listener.onError("Microfone indisponível: " + e.getMessage(), e);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            listener.onError("Falha no reconhecimento de voz: " + e.getMessage(), e);
        } finally {
            log.info("Reconhecimento de voz encerrado");
        }
    }

    private void capture(Recognizer recognizer) throws LineUnavailableException {
        TargetDataLine line = null;
        boolean readyNotified = false;
        boolean gateWasClosed = false;
        String lastPartial = "";
        byte[] buffer = new byte[BUFFER_BYTES];
        try {
            while (running) {
                if (paused) {
                    if (line != null) {
                        closeLine(line);
                        line = null;
                        recognizer.reset();
                        lastPartial = "";
                        listener.onAudioLevel(0);
                    }
                    waitWhilePaused();
                    continue;
                }
                if (line == null) {
                    line = openLine();
                    if (!readyNotified) {
                        readyNotified = true;
                        log.info("Microfone aberto: {}", line.getFormat());
                        listener.onReady();
                    }
                }
                int read = line.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                listener.onAudioLevel(level(buffer, read));

                if (!microphoneGate.getAsBoolean()) {
                    gateWasClosed = true;
                    continue;
                }
                if (gateWasClosed) {
                    recognizer.reset();
                    gateWasClosed = false;
                    lastPartial = "";
                }
                if (recognizer.acceptWaveForm(buffer, read)) {
                    String text = field(recognizer.getResult(), "text");
                    lastPartial = "";
                    if (!text.isBlank()) {
                        log.info("Reconhecido: \"{}\"", text);
                        listener.onFinalResult(text);
                    }
                } else {
                    String partial = field(recognizer.getPartialResult(), "partial");
                    if (!partial.equals(lastPartial)) {
                        lastPartial = partial;
                        listener.onPartialResult(partial);
                    }
                }
            }
        } finally {
            if (line != null) {
                closeLine(line);
            }
        }
    }

    private void waitWhilePaused() {
        synchronized (pauseLock) {
            while (paused && running) {
                try {
                    pauseLock.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }

    private TargetDataLine openLine() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        TargetDataLine line = null;
        if (!deviceName.isBlank()) {
            String wanted = deviceName.toLowerCase(Locale.ROOT);
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (mixerInfo.getName().toLowerCase(Locale.ROOT).contains(wanted)) {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    if (mixer.isLineSupported(info)) {
                        line = (TargetDataLine) mixer.getLine(info);
                        log.info("Usando microfone '{}'", mixerInfo.getName());
                        break;
                    }
                }
            }
            if (line == null) {
                log.warn("Microfone '{}' não encontrado. Usando o padrão do sistema.", deviceName);
            }
        }
        if (line == null) {
            if (!AudioSystem.isLineSupported(info)) {
                throw new LineUnavailableException("nenhum microfone compatível com 16 kHz mono 16 bits");
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
        }
        line.open(format, BUFFER_BYTES * 4);
        line.start();
        return line;
    }

    private static void closeLine(TargetDataLine line) {
        try {
            line.stop();
            line.flush();
        } finally {
            line.close();
        }
    }

    /** Nível RMS normalizado (0..1) de áudio PCM 16 bits little-endian. */
    static double level(byte[] data, int length) {
        int samples = length / 2;
        if (samples == 0) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
            sum += (double) sample * sample;
        }
        double rms = Math.sqrt(sum / samples) / Short.MAX_VALUE;
        return Math.min(1.0, rms * 6.0);
    }

    private String field(String json, String name) {
        try {
            return mapper.readTree(json).path(name).asText("").trim();
        } catch (IOException e) {
            log.debug("JSON inválido do Vosk: {}", json);
            return "";
        }
    }

    @Override
    public void close() {
        Thread t;
        synchronized (this) {
            running = false;
            t = worker;
        }
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
