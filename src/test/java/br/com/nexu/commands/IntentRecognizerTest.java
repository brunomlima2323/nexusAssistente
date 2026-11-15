package br.com.nexu.commands;

import br.com.nexu.commands.IntentRule.RecognitionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRecognizerTest {

    private final IntentRecognizer recognizer = new IntentRecognizer();
    private final RecognitionContext timerActive = new RecognitionContext(true);

    private Intent intent(String text) {
        return recognizer.recognize(text).intent();
    }

    @Test
    void weather() {
        assertEquals(Intent.WEATHER, intent("qual a previsão do tempo"));
        assertEquals(Intent.WEATHER, intent("como está o tempo"));
        assertEquals(Intent.WEATHER, intent("vai chover hoje"));
        assertEquals(Intent.WEATHER, intent("qual a temperatura agora"));
        // "tempo" tem prioridade mesmo com outras palavras na frase
        assertEquals(Intent.WEATHER, intent("que horas tempo hoje"));
        assertEquals(Intent.WEATHER, intent("tempo"));
    }

    @Test
    void weatherCityAndDay() {
        IntentMatch m = recognizer.recognize("Como está o tempo em Recife?");
        assertEquals(Intent.WEATHER, m.intent());
        assertEquals("Recife", m.slot(IntentMatch.SLOT_CITY).orElseThrow());
        assertEquals("0", m.slot(IntentMatch.SLOT_DAY_OFFSET).orElseThrow());

        m = recognizer.recognize("previsão do tempo no rio de janeiro amanhã");
        assertEquals("Rio de Janeiro", m.slot(IntentMatch.SLOT_CITY).orElseThrow());
        assertEquals("1", m.slot(IntentMatch.SLOT_DAY_OFFSET).orElseThrow());

        m = recognizer.recognize("qual a previsão do tempo para são paulo");
        assertEquals("São Paulo", m.slot(IntentMatch.SLOT_CITY).orElseThrow());

        m = recognizer.recognize("qual a previsão do tempo para hoje");
        assertTrue(m.slot(IntentMatch.SLOT_CITY).isEmpty());

        m = recognizer.recognize("vai chover depois de amanhã em belo horizonte");
        assertEquals("Belo Horizonte", m.slot(IntentMatch.SLOT_CITY).orElseThrow());
        assertEquals("2", m.slot(IntentMatch.SLOT_DAY_OFFSET).orElseThrow());
    }

    @Test
    void timerRemaining() {
        assertEquals(Intent.TIMER_REMAINING, intent("quanto falta"));
        assertEquals(Intent.TIMER_REMAINING, intent("quanto tempo falta"));
        assertEquals(Intent.TIMER_REMAINING, intent("quanto falta no cronômetro"));
        assertEquals(Intent.TIMER_REMAINING, recognizer.recognize("falta muito", timerActive).intent());
    }

    @Test
    void timerCancel() {
        assertEquals(Intent.TIMER_CANCEL, intent("cancelar cronômetro"));
        assertEquals(Intent.TIMER_CANCEL, intent("pare o cronômetro"));
        assertEquals(Intent.TIMER_CANCEL, intent("encerrar o cronômetro"));
        assertEquals(Intent.TIMER_CANCEL, recognizer.recognize("cancela", timerActive).intent());
        assertEquals(Intent.UNKNOWN, intent("cancela"));
    }

    @Test
    void timerCreate() {
        assertTimer("cronometre 10 minutos", 600);
        assertTimer("conte 1 minuto e 30 segundos", 90);
        assertTimer("cronômetro de uma hora e meia", 5400);
        assertTimer("conte trinta segundos", 30);
        assertTimer("marque 2 horas", 7200);
        assertTimer("inicie um cronômetro de 5 minutos", 300);
        assertTimer("cronometra duas horas e quinze minutos", 8100);
        assertTimer("dez minutos", 600);
        assertTimer("conte um tempo de 5 minutos", 300);
        IntentMatch noDuration = recognizer.recognize("inicie um cronômetro");
        assertEquals(Intent.TIMER_CREATE, noDuration.intent());
        assertTrue(noDuration.slot(IntentMatch.SLOT_DURATION_SECONDS).isEmpty());
    }

    private void assertTimer(String text, long seconds) {
        IntentMatch m = recognizer.recognize(text);
        assertEquals(Intent.TIMER_CREATE, m.intent(), text);
        assertEquals(Long.toString(seconds), m.slot(IntentMatch.SLOT_DURATION_SECONDS).orElseThrow(), text);
    }

    @Test
    void timeAndDate() {
        assertEquals(Intent.TIME, intent("que horas são"));
        assertEquals(Intent.TIME, intent("me informe o horário"));
        assertEquals(Intent.DATE, intent("que dia é hoje"));
        assertEquals(Intent.DATE, intent("qual a data de hoje"));
    }

    @Test
    void shutdown() {
        assertEquals(Intent.SHUTDOWN, intent("tchau"));
        assertEquals(Intent.SHUTDOWN, intent("encerrar"));
        assertEquals(Intent.SHUTDOWN, intent("desligar assistente"));
        assertEquals(Intent.SHUTDOWN, intent("até mais"));
        assertEquals(Intent.SHUTDOWN, intent("pode desligar"));
        assertFalse(intent("desligue a luz da sala") == Intent.SHUTDOWN);
    }

    @Test
    void unknown() {
        assertEquals(Intent.UNKNOWN, intent("bom dia"));
        assertEquals(Intent.UNKNOWN, intent(""));
        assertEquals(Intent.UNKNOWN, intent("abra o navegador"));
    }
}
