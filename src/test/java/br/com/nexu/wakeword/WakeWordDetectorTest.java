package br.com.nexu.wakeword;

import br.com.nexu.commands.IntentRecognizer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WakeWordDetectorTest {

    private final WakeWordDetector detector = create();

    private static WakeWordDetector create() {
        WakeWordDetector d = new WakeWordDetector("nexu",
                Set.of("nexus", "nessu", "nesu", "neksu", "necsu", "nexum", "nekso"),
                Set.of("anexo", "anexos", "anexu", "nexo"),
                Set.of("nesse", "nessa", "nesses", "nessas", "nisso", "disso", "desse", "dessa", "sexo", "mexo",
                        "mexe", "deixo", "fixo", "lixo", "nexa"),
                0.82, 0.90);
        IntentRecognizer intents = new IntentRecognizer();
        d.setCommandValidator(text -> intents.recognize(text).isKnown());
        return d;
    }

    @Test
    void detectsExactWakeWord() {
        WakeWordResult r = detector.detect("Nexu");
        assertTrue(r.detected());
        assertEquals("", r.remainder());
    }

    @Test
    void detectsCommonMistranscriptions() {
        for (String variant : new String[]{"nexo", "nexus", "nessu", "neksu", "Néxu", "NEXU!", "ne xu"}) {
            assertTrue(detector.detect(variant).detected(), variant);
        }
    }

    @Test
    void keepsCommandAfterWakeWord() {
        WakeWordResult r = detector.detect("Nexus, qual a previsão do tempo em São Paulo?");
        assertTrue(r.detected());
        assertEquals("qual a previsão do tempo em são paulo", r.remainder());
    }

    @Test
    void isolatedAnexoActivates() {
        assertTrue(detector.detect("anexo").detected());
        assertTrue(detector.detect("ei anexo").detected());
    }

    @Test
    void anexoAtStartOfCommandActivates() {
        WakeWordResult r = detector.detect("anexo que horas são");
        assertTrue(r.detected());
        assertTrue(r.contextual());
        assertEquals("que horas são", r.remainder());
        assertTrue(detector.detect("até mais anexo").detected());
    }

    @Test
    void anexoAsRegularWordDoesNotActivate() {
        assertFalse(detector.detect("abra o anexo").detected());
        assertFalse(detector.detect("segue em anexo o relatório").detected());
        assertFalse(detector.detect("o anexo do e-mail está pronto").detected());
        assertFalse(detector.detect("anexo do contrato").detected());
        assertFalse(detector.detect("mandei o arquivo anexo ontem").detected());
        assertFalse(detector.detect("anexo errado").detected());
    }

    @Test
    void similarCommonWordsDoNotActivate() {
        for (String text : new String[]{"nesse momento", "sexo", "mexo", "nexa", "isso não tem nexo", "deixo",
                "next", "texto", "nação", "nosso"}) {
            assertFalse(detector.detect(text).detected(), text);
        }
    }

    @Test
    void wakeWordAtEndIsDetected() {
        WakeWordResult r = detector.detect("até mais Nexu");
        assertTrue(r.detected());
        assertEquals("até mais", r.remainder());
    }
}
