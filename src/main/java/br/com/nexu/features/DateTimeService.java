package br.com.nexu.features;

import br.com.nexu.utils.SpeechFormatter;
import br.com.nexu.utils.TextNormalizer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Data e hora reais do sistema operacional, no fuso horário configurado no sistema. */
public final class DateTimeService {

    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", TextNormalizer.PT_BR);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", TextNormalizer.PT_BR);

    private final Clock clock;

    public DateTimeService() {
        this(Clock.systemDefaultZone());
    }

    public DateTimeService(Clock clock) {
        this.clock = clock;
    }

    /** "Senhor, agora são 23 horas e 15 minutos." */
    public String timeSpeech() {
        LocalTime now = LocalTime.now(clock);
        int h = now.getHour();
        int m = now.getMinute();
        String hourPart;
        boolean singular;
        if (h == 0) {
            hourPart = "meia-noite";
            singular = true;
        } else if (h == 12) {
            hourPart = "meio-dia";
            singular = true;
        } else {
            hourPart = SpeechFormatter.hours(h);
            singular = h == 1;
        }
        String minutePart;
        if (m == 0) {
            minutePart = (h == 0 || h == 12) ? "" : " em ponto";
        } else {
            minutePart = " e " + SpeechFormatter.minutes(m);
        }
        return "Senhor, agora " + (singular ? "é " : "são ") + hourPart + minutePart + ".";
    }

    /** "Hoje é sábado, 14 de novembro de 2026." */
    public String dateSpeech() {
        LocalDate today = LocalDate.now(clock);
        String day = today.getDayOfMonth() == 1 ? "1º" : Integer.toString(today.getDayOfMonth());
        return "Hoje é " + WEEKDAY.format(today) + ", " + day + " de " + MONTH.format(today)
                + " de " + today.getYear() + ".";
    }
}
