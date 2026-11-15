package br.com.nexu.utils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Formata números e durações para serem falados corretamente em português.
 * Motores de voz costumam ler "2 horas" como "dois horas"; por isso, horas terminadas
 * em 1 ou 2 são escritas por extenso no feminino ("uma hora", "vinte e duas horas").
 */
public final class SpeechFormatter {

    private static final String[] UNITS_M = {"zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove",
            "dez", "onze", "doze", "treze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove"};
    private static final String[] TENS = {"", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa"};

    private SpeechFormatter() {
    }

    /** Escreve por extenso números de 0 a 99. */
    public static String spell(int n, boolean feminine) {
        if (n < 0 || n > 99) {
            return Integer.toString(n);
        }
        if (n < 20) {
            return unit(n, feminine);
        }
        int tens = n / 10;
        int units = n % 10;
        return units == 0 ? TENS[tens] : TENS[tens] + " e " + unit(units, feminine);
    }

    private static String unit(int n, boolean feminine) {
        if (feminine && n == 1) {
            return "uma";
        }
        if (feminine && n == 2) {
            return "duas";
        }
        return UNITS_M[n];
    }

    /** "1 hora" -> "uma hora"; "22 horas" -> "vinte e duas horas"; "5 horas" permanece. */
    public static String hours(long h) {
        String number = needsFeminine(h) ? spell((int) h, true) : Long.toString(h);
        return number + (h == 1 ? " hora" : " horas");
    }

    public static String minutes(long m) {
        return (m == 1 ? "um" : Long.toString(m)) + (m == 1 ? " minuto" : " minutos");
    }

    public static String seconds(long s) {
        return (s == 1 ? "um" : Long.toString(s)) + (s == 1 ? " segundo" : " segundos");
    }

    private static boolean needsFeminine(long n) {
        if (n < 1 || n > 99) {
            return false;
        }
        long last = n % 10;
        return (last == 1 || last == 2) && n != 11 && n != 12;
    }

    /** "1 hora, 5 minutos e 30 segundos". Segundos fracionários são arredondados para cima. */
    public static String duration(Duration duration) {
        long totalSeconds = Math.max(0, (duration.toMillis() + 999) / 1000);
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        List<String> parts = new ArrayList<>(3);
        if (h > 0) {
            parts.add(hours(h));
        }
        if (m > 0) {
            parts.add(minutes(m));
        }
        if (s > 0 || parts.isEmpty()) {
            parts.add(seconds(s));
        }
        return joinPortuguese(parts);
    }

    /** Quantidade de componentes (horas/minutos/segundos) não nulos. */
    public static boolean isSingular(Duration duration) {
        long totalSeconds = Math.max(0, (duration.toMillis() + 999) / 1000);
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        int components = (h > 0 ? 1 : 0) + (m > 0 ? 1 : 0) + (s > 0 ? 1 : 0);
        return components == 1 && (h == 1 || (h == 0 && m == 1) || (h == 0 && m == 0 && s == 1));
    }

    public static String joinPortuguese(List<String> parts) {
        if (parts.isEmpty()) {
            return "";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " e " + parts.get(parts.size() - 1);
    }

    /** Relógio digital "hh:mm:ss" ou "mm:ss". */
    public static String clock(Duration duration) {
        long totalSeconds = Math.max(0, (duration.toMillis() + 999) / 1000);
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    /** Arredonda graus: "1 grau", "28 graus". */
    public static String degrees(double value) {
        long rounded = Math.round(value);
        return rounded + (Math.abs(rounded) == 1 ? " grau" : " graus");
    }
}
