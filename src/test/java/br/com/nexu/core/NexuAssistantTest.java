package br.com.nexu.core;

import br.com.nexu.audio.AudioManager;
import br.com.nexu.audio.SpeechRecognition;
import br.com.nexu.audio.tts.NoVoiceTts;
import br.com.nexu.config.AppConfig;
import br.com.nexu.features.DateTimeService;
import br.com.nexu.features.TimerService;
import br.com.nexu.features.WeatherService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fluxo completo do assistente sem microfone (texto injetado) e sem voz. */
class NexuAssistantTest {

    private static AppConfig config() throws IOException {
        Properties p = new Properties();
        try (InputStream in = NexuAssistantTest.class.getResourceAsStream("/application.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        p.setProperty("audio.echo.guard.millis", "0");
        return AppConfig.of(p);
    }

    private static final class FakeRecognition implements SpeechRecognition {
        final Listener listener;

        FakeRecognition(Listener listener) {
            this.listener = listener;
        }

        @Override public void start() { listener.onReady(); }
        @Override public void pause() { }
        @Override public void resume() { }
        @Override public boolean isPaused() { return false; }
        @Override public void close() { }
    }

    @Test
    void conversationFlow() throws Exception {
        AppConfig config = config();
        AudioManager audio = new AudioManager(config, new NoVoiceTts("teste"));
        FakeRecognition[] holder = new FakeRecognition[1];
        try (NexuAssistant nexu = new NexuAssistant(config, audio, l -> holder[0] = new FakeRecognition(l),
                new WeatherService(config), new TimerService(100), new DateTimeService())) {
            List<String> spoken = new CopyOnWriteArrayList<>();
            nexu.addListener(new AssistantListener() {
                @Override
                public void onConversation(ConversationEntry entry) {
                    if (entry.speaker() == ConversationEntry.Speaker.NEXU) {
                        spoken.add(entry.text());
                    }
                }
            });
            nexu.start();
            waitFor(() -> spoken.size() == 1);
            assertEquals("Olá senhor, estou à sua disposição.", spoken.get(0));

            holder[0].listener.onFinalResult("que horas são"); // sem palavra de ativação: ignorado
            holder[0].listener.onFinalResult("anexo");
            waitFor(() -> spoken.size() == 2);
            assertEquals("Olá senhor, estou aqui. O que deseja?", spoken.get(1));

            holder[0].listener.onFinalResult("cronometre dez minutos"); // dentro da janela de conversa
            waitFor(() -> spoken.size() == 3);
            assertEquals("Perfeitamente, senhor. Cronômetro iniciado para 10 minutos.", spoken.get(2));

            holder[0].listener.onFinalResult("cronometre dez minutos"); // duplicado
            holder[0].listener.onFinalResult("quanto falta");
            waitFor(() -> spoken.size() == 4);
            assertTrue(spoken.get(3).startsWith("Senhor, faltam 9 minutos e 5") || spoken.get(3).startsWith("Senhor, faltam 10 minutos"),
                    spoken.get(3));

            holder[0].listener.onFinalResult("nexu cancelar cronômetro");
            waitFor(() -> spoken.size() == 5);
            assertEquals("Conforme solicitado, senhor. Cronômetro cancelado.", spoken.get(4));

            holder[0].listener.onFinalResult("o anexo do e-mail chegou"); // não ativa; na janela de conversa, não é comando
            waitFor(() -> spoken.size() == 6);
            assertEquals("Desculpe, senhor, não compreendi. Poderia repetir?", spoken.get(5));

            holder[0].listener.onFinalResult("nexu que horas são");
            waitFor(() -> spoken.size() == 7);
            assertTrue(spoken.get(6).startsWith("Senhor, agora"), spoken.get(6));
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condição não atingida a tempo");
            }
            Thread.sleep(20);
        }
    }
}
