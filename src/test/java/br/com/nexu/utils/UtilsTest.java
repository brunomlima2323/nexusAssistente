package br.com.nexu.utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilsTest {

    @Test
    void normalization() {
        assertEquals("previsao do tempo em sao paulo", TextNormalizer.normalize("Previsão do TEMPO em São Paulo?!"));
        assertEquals(List.of("10", "min"), TextNormalizer.tokens("10min"));
        assertEquals("Rio de Janeiro", TextNormalizer.titleCase("rio de janeiro"));
    }

    @Test
    void phonetic() {
        String key = PhoneticEncoder.encode("nexu");
        for (String w : new String[]{"nexo", "nexus", "nessu", "neksu", "Néxu", "necsu"}) {
            assertEquals(key, PhoneticEncoder.encode(w), w);
        }
        assertEquals("a" + key, PhoneticEncoder.encode("anexo"));
    }

    @Test
    void fuzzy() {
        assertEquals(1, FuzzyMatcher.levenshtein("tempo", "tempu"));
        assertTrue(FuzzyMatcher.tokenMatches("cronometre", "cronometro"));
        assertTrue(FuzzyMatcher.tokenMatches("cancele", "cancelar"));
        // palavras-chave curtas (até 4 letras) exigem igualdade exata
        assertTrue(!FuzzyMatcher.tokenMatches("pare", "para"));
        assertTrue(!FuzzyMatcher.tokenMatches("dias", "data"));
        assertTrue(FuzzyMatcher.jaroWinkler("martha", "marhta") > 0.95);
    }

    @Test
    void speechFormatting() {
        assertEquals("10 minutos", SpeechFormatter.duration(Duration.ofMinutes(10)));
        assertEquals("4 minutos e 32 segundos", SpeechFormatter.duration(Duration.ofSeconds(272)));
        assertEquals("uma hora e 30 minutos", SpeechFormatter.duration(Duration.ofMinutes(90)));
        assertEquals("duas horas, um minuto e um segundo", SpeechFormatter.duration(Duration.ofSeconds(7261)));
        assertEquals("04:32", SpeechFormatter.clock(Duration.ofSeconds(272)));
        assertEquals("1:00:00", SpeechFormatter.clock(Duration.ofHours(1)));
        assertTrue(SpeechFormatter.isSingular(Duration.ofMinutes(1)));
    }
}
