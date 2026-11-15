package br.com.nexu.features;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturesTest {

    private static DateTimeService at(String isoInstant) {
        return new DateTimeService(Clock.fixed(Instant.parse(isoInstant), ZoneId.of("America/Sao_Paulo")));
    }

    @Test
    void timeSpeech() {
        assertEquals("Senhor, agora são 23 horas e 15 minutos.", at("2026-11-15T02:15:00Z").timeSpeech());
        assertEquals("Senhor, agora é uma hora e um minuto.", at("2026-11-14T04:01:00Z").timeSpeech());
        assertEquals("Senhor, agora são vinte e duas horas em ponto.", at("2026-11-15T01:00:00Z").timeSpeech());
        assertEquals("Senhor, agora é meia-noite e 10 minutos.", at("2026-11-14T03:10:00Z").timeSpeech());
    }

    @Test
    void dateSpeech() {
        assertEquals("Hoje é sábado, 14 de novembro de 2026.", at("2026-11-14T15:00:00Z").dateSpeech());
        assertEquals("Hoje é terça-feira, 1º de dezembro de 2026.", at("2026-12-01T15:00:00Z").dateSpeech());
    }

    @Test
    void timerRemainingUsesMonotonicClock() {
        AtomicLong fakeNanos = new AtomicLong(1_000_000_000L);
        try (TimerService timers = new TimerService(250, fakeNanos::get)) {
            timers.start(Duration.ofMinutes(5));
            fakeNanos.addAndGet(Duration.ofSeconds(28).toNanos());
            assertEquals(272, timers.remaining().orElseThrow().toSeconds());
            assertTrue(timers.cancel().isPresent());
            assertFalse(timers.isActive());
            assertTrue(timers.cancel().isEmpty());
        }
    }

    @Test
    void timerFinishes() throws InterruptedException {
        CountDownLatch finished = new CountDownLatch(1);
        try (TimerService timers = new TimerService(50)) {
            timers.addListener(new TimerService.Listener() {
                @Override
                public void onTimerFinished(TimerService.TimerInfo timer) {
                    finished.countDown();
                }
            });
            long start = System.nanoTime();
            timers.start(Duration.ofMillis(400));
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs >= 395, "terminou cedo demais: " + elapsedMs);
            assertFalse(timers.isActive());
        }
    }

    @Test
    void timerReplacement() {
        try (TimerService timers = new TimerService(250)) {
            assertFalse(timers.start(Duration.ofMinutes(1)).replacedPrevious());
            assertTrue(timers.start(Duration.ofMinutes(2)).replacedPrevious());
        }
    }

    @Test
    void weatherReportFromOpenMeteoJson() throws Exception {
        String json = """
                {"current":{"temperature_2m":27.6,"apparent_temperature":31.2,"relative_humidity_2m":75,"weather_code":2},
                 "daily":{"temperature_2m_max":[30.1,29.0,28.0],"temperature_2m_min":[23.8,22.0,21.0],
                          "precipitation_probability_max":[60,10,0],"weather_code":[61,1,0]}}
                """;
        var node = new ObjectMapper().readTree(json);
        WeatherReport today = WeatherService.toReport(new WeatherService.GeoLocation("Recife", -8, -34, true), node, 0);
        assertEquals("Senhor, neste momento temos 28 graus, com sensação térmica de 31 graus. "
                + "A umidade está em 75%. A máxima prevista é de 30 graus e a mínima de 24. "
                + "A probabilidade de chuva é de 60%. As condições são de céu parcialmente nublado.", today.toSpeech());

        WeatherReport city = WeatherService.toReport(new WeatherService.GeoLocation("Recife", -8, -34, false), node, 0);
        assertTrue(city.toSpeech().startsWith("Senhor, em Recife temos atualmente 28 graus"));

        WeatherReport tomorrow = WeatherService.toReport(new WeatherService.GeoLocation("Recife", -8, -34, false), node, 1);
        assertEquals("Senhor, esta é a previsão para amanhã em Recife. A máxima prevista é de 29 graus e a mínima de 22. "
                + "A probabilidade de chuva é de 10%. As condições previstas são de céu predominantemente limpo.",
                tomorrow.toSpeech());
    }

    @Test
    void weatherWithoutDataFails() throws Exception {
        var node = new ObjectMapper().readTree("{}");
        try {
            WeatherService.toReport(new WeatherService.GeoLocation("X", 0, 0, true), node, 0);
            throw new AssertionError("deveria falhar");
        } catch (WeatherException expected) {
            assertTrue(expected.getUserMessage().startsWith("Senhor"));
        }
    }
}
