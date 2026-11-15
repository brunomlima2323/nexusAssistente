package br.com.nexu.core;

import br.com.nexu.audio.AudioManager;
import br.com.nexu.audio.SpeechRecognition;
import br.com.nexu.audio.TextToSpeech;
import br.com.nexu.audio.VoskSpeechRecognition;
import br.com.nexu.audio.tts.TtsFactory;
import br.com.nexu.commands.CommandProcessor;
import br.com.nexu.commands.Intent;
import br.com.nexu.commands.IntentMatch;
import br.com.nexu.commands.IntentRecognizer;
import br.com.nexu.commands.IntentRule.RecognitionContext;
import br.com.nexu.commands.handlers.CommandResult;
import br.com.nexu.commands.handlers.DateTimeCommandHandler;
import br.com.nexu.commands.handlers.TimerCommandHandler;
import br.com.nexu.commands.handlers.WeatherCommandHandler;
import br.com.nexu.config.AppConfig;
import br.com.nexu.features.DateTimeService;
import br.com.nexu.features.TimerService;
import br.com.nexu.features.WeatherService;
import br.com.nexu.utils.TextNormalizer;
import br.com.nexu.wakeword.WakeWordDetector;
import br.com.nexu.wakeword.WakeWordResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Orquestrador do Nexu: liga reconhecimento de voz, palavra de ativação, interpretação de
 * comandos, execução e resposta falada.
 * <p>
 * Modelo de threads:
 * <ul>
 *     <li>{@code nexu-stt}: captura do microfone e transcrição (Vosk);</li>
 *     <li>{@code nexu-core}: decisões do assistente, uma frase por vez, em ordem;</li>
 *     <li>{@code nexu-http}: consultas de rede (previsão do tempo);</li>
 *     <li>{@code nexu-audio-out}: fila de fala e bipes;</li>
 *     <li>{@code nexu-timer}: cronômetro, independente de todo o resto.</li>
 * </ul>
 * Nenhuma dessas threads é a da interface gráfica.
 */
public final class NexuAssistant implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NexuAssistant.class);

    private final AppConfig config;
    private final AudioManager audio;
    private final SpeechRecognition recognition;
    private final WakeWordDetector wakeWord;
    private final IntentRecognizer intents;
    private final CommandProcessor processor;
    private final TimerService timers;
    private final WeatherService weather;
    private final ScheduledExecutorService core;
    private final List<AssistantListener> listeners = new CopyOnWriteArrayList<>();

    private final String greeting;
    private final String wakeResponse;
    private final String farewell;
    private final String notUnderstood;
    private final long conversationTimeoutMillis;
    private final int beepCount;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger processing = new AtomicInteger();
    private volatile boolean shuttingDown;
    private volatile boolean recognitionReady;
    private volatile boolean paused;
    private volatile String fatalError;
    private volatile AssistantState state = AssistantState.INITIALIZING;
    private volatile boolean awaitingCommand;
    private ScheduledFuture<?> conversationExpiry; // acessado apenas na thread nexu-core

    /** Cria o assistente com os componentes padrão (Vosk, motor de voz do sistema, Open-Meteo). */
    public static NexuAssistant create(AppConfig config) {
        TextToSpeech tts = TtsFactory.create(config);
        AudioManager audio = new AudioManager(config, tts);
        return new NexuAssistant(config, audio,
                listener -> new VoskSpeechRecognition(config, audio::isMicrophoneOpen, listener),
                new WeatherService(config),
                new TimerService(config.getLong("timer.tick.millis", 250)),
                new DateTimeService());
    }

    public NexuAssistant(AppConfig config, AudioManager audio,
                         Function<SpeechRecognition.Listener, SpeechRecognition> recognitionFactory,
                         WeatherService weather, TimerService timers, DateTimeService dateTime) {
        this.config = config;
        this.audio = audio;
        this.weather = weather;
        this.timers = timers;
        this.greeting = config.getString("assistant.greeting", "Olá senhor, estou à sua disposição.");
        this.wakeResponse = config.getString("assistant.wake.response", "Olá senhor, estou aqui. O que deseja?");
        this.farewell = config.getString("assistant.farewell", "Até logo, senhor. Foi um prazer ajudá-lo.");
        this.notUnderstood = config.getString("assistant.not.understood", "Desculpe, senhor, não compreendi. Poderia repetir?");
        this.conversationTimeoutMillis = config.getLong("assistant.conversation.timeout.seconds", 12) * 1000L;
        this.beepCount = config.getInt("audio.beep.count", 3);

        this.core = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexu-core");
            t.setDaemon(true);
            return t;
        });

        this.intents = new IntentRecognizer();
        this.wakeWord = WakeWordDetector.fromConfig(config);
        this.wakeWord.setCommandValidator(text -> intents.recognize(text, context()).isKnown());

        this.processor = new CommandProcessor(config.getLong("assistant.dedup.window.millis", 3000));
        processor.register(match -> CompletableFuture.completedFuture(CommandResult.sayAndShutdown(farewell)), Intent.SHUTDOWN);
        processor.register(new WeatherCommandHandler(weather), Intent.WEATHER);
        processor.register(new TimerCommandHandler(timers, Duration.ofHours(config.getLong("timer.max.hours", 24))),
                TimerCommandHandler.intents());
        processor.register(new DateTimeCommandHandler(dateTime), Intent.TIME, Intent.DATE);

        timers.addListener(new TimerEvents());
        audio.addListener(new AudioEvents());
        this.recognition = recognitionFactory.apply(new RecognitionEvents());
    }

    // ------------------------------------------------------------------ API pública

    public void addListener(AssistantListener listener) {
        listeners.add(listener);
    }

    public void removeListener(AssistantListener listener) {
        listeners.remove(listener);
    }

    /** Fala a saudação e começa a escutar. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.info("Iniciando Nexu (voz: {})", audio.engineName());
        emit(l -> l.onConversation(ConversationEntry.system("Motor de voz: " + audio.engineName())));
        refreshState();
        respond(greeting);
        recognition.start();
    }

    public boolean isPaused() {
        return paused;
    }

    /** Pausa ou retoma a escuta. Cronômetros continuam funcionando. */
    public void setPaused(boolean pause) {
        if (closed.get() || paused == pause) {
            return;
        }
        paused = pause;
        if (pause) {
            recognition.pause();
            core.execute(this::closeConversationWindow);
        } else {
            recognition.resume();
        }
        emit(l -> l.onPausedChanged(pause));
        emit(l -> l.onConversation(ConversationEntry.system(pause ? "Escuta pausada." : "Escuta retomada.")));
        refreshState();
    }

    public AssistantState state() {
        return state;
    }

    /**
     * Processa um texto como se tivesse sido falado (útil para testes e para futuras entradas
     * por teclado). O texto passa pelas mesmas regras de ativação.
     */
    public void submitText(String text) {
        if (!closed.get()) {
            core.execute(() -> handleTranscript(text));
        }
    }

    // ------------------------------------------------------------------ fluxo principal

    private void handleTranscript(String text) {
        if (shuttingDown || text == null || text.isBlank()) {
            return;
        }
        if (audio.isLikelyEcho(text)) {
            log.debug("Descartado como eco da própria voz: {}", text);
            emit(l -> l.onRecognized(text, "ignorado (eco da voz do Nexu)"));
            return;
        }
        String normalized = TextNormalizer.normalize(text);
        if (normalized.isEmpty()) {
            return;
        }

        WakeWordResult wake = wakeWord.detect(text);
        String commandText;
        if (wake.detected()) {
            commandText = wake.remainder();
            log.info("Palavra de ativação detectada em \"{}\" (\"{}\", similaridade {})",
                    text, wake.matchedWord(), String.format("%.2f", wake.score()));
        } else if (awaitingCommand) {
            commandText = text;
        } else {
            emit(l -> l.onRecognized(text, "ignorado (aguardando \"Nexu\")"));
            return;
        }

        emit(l -> l.onConversation(ConversationEntry.user(text)));
        openConversationWindow(); // mantém a escuta ativa enquanto processa

        if (commandText.isBlank()) {
            emit(l -> l.onRecognized(text, "palavra de ativação"));
            respond(wakeResponse).thenRun(() -> core.execute(this::openConversationWindow));
            return;
        }

        IntentMatch match = intents.recognize(commandText, context());
        emit(l -> l.onRecognized(text, match.describe()));

        if (!match.isKnown()) {
            int words = TextNormalizer.tokens(commandText).size();
            if (wake.detected() && words <= 1) {
                respond(wakeResponse).thenRun(() -> core.execute(this::openConversationWindow));
            } else if (wake.detected() || words >= 2) {
                respond(notUnderstood).thenRun(() -> core.execute(this::openConversationWindow));
            }
            // Ruído curto durante a conversa: ignorado em silêncio.
            return;
        }

        if (processor.checkAndRegister(match)) {
            log.info("Comando duplicado ignorado: {}", match.describe());
            emit(l -> l.onRecognized(text, match.describe() + " (duplicado, ignorado)"));
            return;
        }
        execute(match);
    }

    private void execute(IntentMatch match) {
        processing.incrementAndGet();
        refreshState();
        log.info("Executando {}", match.describe());
        processor.execute(match).whenComplete((result, error) -> {
            processing.decrementAndGet();
            CommandResult response = result != null ? result
                    : CommandResult.say("Senhor, ocorreu um erro inesperado.");
            if (error != null) {
                log.error("Falha no comando {}", match.intent(), error);
            }
            CompletableFuture<Void> spoken = respond(response.speech());
            refreshState();
            if (response.shutdown()) {
                shuttingDown = true;
                refreshState();
                spoken.whenComplete((v, e) -> requestShutdown());
            } else {
                spoken.thenRun(() -> core.execute(this::openConversationWindow));
            }
        });
    }

    private CompletableFuture<Void> respond(String text) {
        emit(l -> l.onConversation(ConversationEntry.nexu(text)));
        return audio.speak(text);
    }

    private RecognitionContext context() {
        return new RecognitionContext(timers.isActive());
    }

    // ------------------------------------------------------------------ janela de conversa

    private void openConversationWindow() {
        if (paused || shuttingDown || closed.get()) {
            return;
        }
        if (conversationExpiry != null) {
            conversationExpiry.cancel(false);
        }
        conversationExpiry = core.schedule(this::closeConversationWindow, conversationTimeoutMillis, TimeUnit.MILLISECONDS);
        setAwaiting(true);
    }

    private void closeConversationWindow() {
        if (conversationExpiry != null) {
            conversationExpiry.cancel(false);
            conversationExpiry = null;
        }
        setAwaiting(false);
    }

    private void setAwaiting(boolean value) {
        if (awaitingCommand != value) {
            awaitingCommand = value;
            emit(l -> l.onAwaitingCommandChanged(value));
        }
    }

    // ------------------------------------------------------------------ estado

    private synchronized void refreshState() {
        AssistantState next;
        if (shuttingDown || closed.get()) {
            next = AssistantState.SHUTTING_DOWN;
        } else if (audio.isSpeaking()) {
            next = AssistantState.SPEAKING;
        } else if (processing.get() > 0) {
            next = AssistantState.PROCESSING;
        } else if (fatalError != null) {
            next = AssistantState.ERROR;
        } else if (!recognitionReady) {
            next = AssistantState.INITIALIZING;
        } else if (paused) {
            next = AssistantState.PAUSED;
        } else {
            next = AssistantState.LISTENING;
        }
        if (next != state) {
            state = next;
            emit(l -> l.onStateChanged(next));
        }
    }

    private void requestShutdown() {
        log.info("Encerramento solicitado pelo usuário");
        if (listeners.isEmpty()) {
            close();
            return;
        }
        emit(AssistantListener::onShutdownRequested);
    }

    private void emit(Consumer<AssistantListener> action) {
        for (AssistantListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException e) {
                log.warn("Listener falhou", e);
            }
        }
    }

    // ------------------------------------------------------------------ eventos internos

    private final class RecognitionEvents implements SpeechRecognition.Listener {
        @Override
        public void onReady() {
            recognitionReady = true;
            refreshState();
            emit(l -> l.onConversation(ConversationEntry.system("Reconhecimento de voz pronto. Diga \"Nexu\" para me chamar.")));
        }

        @Override
        public void onPartialResult(String text) {
            emit(l -> l.onPartialTranscript(text));
        }

        @Override
        public void onFinalResult(String text) {
            emit(l -> l.onPartialTranscript(""));
            if (!closed.get()) {
                core.execute(() -> handleTranscript(text));
            }
        }

        @Override
        public void onAudioLevel(double level) {
            emit(l -> l.onAudioLevel(level));
        }

        @Override
        public void onError(String message, Throwable cause) {
            log.error("Reconhecimento de voz: {}", message, cause);
            fatalError = message;
            refreshState();
            emit(l -> l.onError(message));
            emit(l -> l.onConversation(ConversationEntry.system(message)));
            respond("Senhor, houve um problema com o reconhecimento de voz. Os detalhes estão na tela.");
        }
    }

    private final class AudioEvents implements AudioManager.Listener {
        @Override
        public void onSpeechStarted(String text) {
            refreshState();
        }

        @Override
        public void onSpeechFinished(String text) {
            refreshState();
        }

        @Override
        public void onAudioError(String message) {
            emit(l -> l.onError(message));
        }
    }

    private final class TimerEvents implements TimerService.Listener {
        @Override
        public void onTimerStarted(TimerService.TimerInfo timer) {
            emit(l -> l.onTimerUpdate(timer.total(), timer.total()));
        }

        @Override
        public void onTimerTick(TimerService.TimerInfo timer, Duration remaining) {
            emit(l -> l.onTimerUpdate(remaining, timer.total()));
        }

        @Override
        public void onTimerFinished(TimerService.TimerInfo timer) {
            emit(l -> l.onTimerUpdate(null, timer.total()));
            emit(AssistantListener::onTimerFinished);
            emit(l -> l.onConversation(ConversationEntry.system("Cronômetro de "
                    + br.com.nexu.utils.SpeechFormatter.duration(timer.total()) + " finalizado.")));
            audio.beep(beepCount).thenCompose(v -> respond("Senhor, o tempo acabou."));
        }

        @Override
        public void onTimerCancelled(TimerService.TimerInfo timer) {
            emit(l -> l.onTimerUpdate(null, timer.total()));
        }
    }

    // ------------------------------------------------------------------ encerramento

    /** Libera microfone, áudio, rede e threads. Pode ser chamado mais de uma vez. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("Encerrando Nexu e liberando recursos");
        shuttingDown = true;
        refreshState();
        safely(recognition::close, "reconhecimento de voz");
        safely(timers::close, "cronômetro");
        safely(weather::close, "serviço meteorológico");
        safely(core::shutdownNow, "núcleo");
        safely(audio::close, "áudio");
        listeners.clear();
    }

    private static void safely(Runnable action, String what) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Falha ao encerrar {}: {}", what, e.getMessage());
        }
    }

    AppConfig config() {
        return config;
    }
}
