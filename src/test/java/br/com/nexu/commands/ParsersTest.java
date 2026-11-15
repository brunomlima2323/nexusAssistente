package br.com.nexu.commands;

import br.com.nexu.utils.TextNormalizer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsersTest {

    private static long seconds(String text) {
        return DurationParser.parse(text).orElseThrow(() -> new AssertionError(text)).duration().toSeconds();
    }

    @Test
    void numbersInWords() {
        assertEquals(10, NumberParser.parse("dez").orElseThrow());
        assertEquals(25, NumberParser.parse("vinte e cinco").orElseThrow());
        assertEquals(120, NumberParser.parse("cento e vinte").orElseThrow());
        assertEquals(1, NumberParser.parse("uma").orElseThrow());
        assertEquals(2, NumberParser.parse("duas").orElseThrow());
        assertEquals(3, NumberParser.parse("três").orElseThrow());
        assertEquals(2030, NumberParser.parse("dois mil e trinta").orElseThrow());
        assertEquals(15, NumberParser.parse("15").orElseThrow());
        assertEquals(List.of("1", "hora", "e", "15", "minutos"),
                NumberParser.replaceNumberWords(TextNormalizer.tokens("uma hora e quinze minutos")));
    }

    @Test
    void durations() {
        assertEquals(600, seconds("dez minutos"));
        assertEquals(3600, seconds("uma hora"));
        assertEquals(5400, seconds("uma hora e meia"));
        assertEquals(30, seconds("trinta segundos"));
        assertEquals(8100, seconds("duas horas e quinze minutos"));
        assertEquals(90, seconds("1 minuto e 30 segundos"));
        assertEquals(1800, seconds("meia hora"));
        assertEquals(90, seconds("um minuto e meio"));
        assertEquals(5400, seconds("1 hora e 30"));
        assertEquals(5400, seconds("1h30"));
        assertEquals(600, seconds("10min"));
        assertEquals(1500, seconds("vinte e cinco minutos"));
    }

    @Test
    void onlyDurationFlag() {
        assertTrue(DurationParser.parse("dez minutos").orElseThrow().onlyDuration());
        assertFalse(DurationParser.parse("cronometre dez minutos").orElseThrow().onlyDuration());
        assertTrue(DurationParser.parse("que horas são").isEmpty());
        assertEquals(Duration.ofMinutes(5), DurationParser.parse("5 minutos por favor").orElseThrow().duration());
    }
}
