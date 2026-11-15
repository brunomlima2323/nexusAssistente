package br.com.nexu.commands;

import br.com.nexu.utils.TextNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Converte números escritos por extenso em português para algarismos.
 * <p>
 * Exemplos: "dez" → 10, "vinte e cinco" → 25, "cento e vinte" → 120, "duas mil e trinta" → 2030.
 * Trabalha sobre palavras já normalizadas (sem acentos).
 */
public final class NumberParser {

    private static final Map<String, Integer> UNITS = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("um", 1), Map.entry("uma", 1),
            Map.entry("dois", 2), Map.entry("duas", 2), Map.entry("tres", 3),
            Map.entry("quatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6),
            Map.entry("sete", 7), Map.entry("oito", 8), Map.entry("nove", 9),
            Map.entry("dez", 10), Map.entry("onze", 11), Map.entry("doze", 12),
            Map.entry("treze", 13), Map.entry("quatorze", 14), Map.entry("catorze", 14),
            Map.entry("quinze", 15), Map.entry("dezesseis", 16), Map.entry("dezasseis", 16),
            Map.entry("dezessete", 17), Map.entry("dezassete", 17), Map.entry("dezoito", 18),
            Map.entry("dezenove", 19), Map.entry("dezanove", 19));

    private static final Map<String, Integer> TENS = Map.of(
            "vinte", 20, "trinta", 30, "quarenta", 40, "cinquenta", 50, "sessenta", 60,
            "setenta", 70, "oitenta", 80, "noventa", 90);

    private static final Map<String, Integer> HUNDREDS = Map.ofEntries(
            Map.entry("cem", 100), Map.entry("cento", 100),
            Map.entry("duzentos", 200), Map.entry("duzentas", 200),
            Map.entry("trezentos", 300), Map.entry("trezentas", 300),
            Map.entry("quatrocentos", 400), Map.entry("quatrocentas", 400),
            Map.entry("quinhentos", 500), Map.entry("quinhentas", 500),
            Map.entry("seiscentos", 600), Map.entry("seiscentas", 600),
            Map.entry("setecentos", 700), Map.entry("setecentas", 700),
            Map.entry("oitocentos", 800), Map.entry("oitocentas", 800),
            Map.entry("novecentos", 900), Map.entry("novecentas", 900));

    private record Parsed(long value, int next) {
    }

    private NumberParser() {
    }

    /** Substitui sequências de números por extenso pelos algarismos correspondentes. */
    public static List<String> replaceNumberWords(List<String> tokens) {
        List<String> out = new ArrayList<>(tokens.size());
        int i = 0;
        while (i < tokens.size()) {
            Parsed parsed = parseNumber(tokens, i);
            if (parsed != null) {
                out.add(Long.toString(parsed.value()));
                i = parsed.next();
            } else {
                out.add(tokens.get(i));
                i++;
            }
        }
        return out;
    }

    /** Interpreta um texto inteiro como número ("vinte e dois" → 22, "15" → 15). */
    public static OptionalLong parse(String text) {
        List<String> tokens = replaceNumberWords(TextNormalizer.tokens(text));
        if (tokens.size() == 1 && isInteger(tokens.get(0))) {
            return OptionalLong.of(Long.parseLong(tokens.get(0)));
        }
        return OptionalLong.empty();
    }

    public static boolean isInteger(String token) {
        if (token == null || token.isEmpty() || token.length() > 9) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Parsed parseNumber(List<String> t, int start) {
        Parsed first = parseChunk(t, start);
        long value;
        int idx;
        if (first != null) {
            value = first.value();
            idx = first.next();
        } else if ("mil".equals(t.get(start))) {
            value = 1;
            idx = start;
        } else {
            return null;
        }
        if (idx < t.size() && "mil".equals(t.get(idx))) {
            value *= 1000;
            idx++;
            int k = idx;
            if (k < t.size() && "e".equals(t.get(k))) {
                k++;
            }
            Parsed rest = k < t.size() ? parseChunk(t, k) : null;
            if (rest != null && rest.value() < 1000) {
                value += rest.value();
                idx = rest.next();
            }
        } else if (first == null) {
            return null;
        }
        return new Parsed(value, idx);
    }

    private static Parsed parseChunk(List<String> t, int start) {
        int idx = start;
        long value = 0;
        boolean any = false;

        Integer hundreds = lookup(HUNDREDS, t, idx);
        if (hundreds != null) {
            value += hundreds;
            idx++;
            any = true;
            if (isConnector(t, idx) && (lookup(TENS, t, idx + 1) != null || lookup(UNITS, t, idx + 1) != null)) {
                idx++;
            } else {
                return new Parsed(value, idx);
            }
        }

        Integer tens = lookup(TENS, t, idx);
        if (tens != null) {
            value += tens;
            idx++;
            Integer unit = lookup(UNITS, t, idx + 1);
            if (isConnector(t, idx) && unit != null && unit > 0 && unit < 10) {
                value += unit;
                idx += 2;
            }
            return new Parsed(value, idx);
        }

        Integer unit = lookup(UNITS, t, idx);
        if (unit != null) {
            return new Parsed(value + unit, idx + 1);
        }
        return any ? new Parsed(value, idx) : null;
    }

    private static boolean isConnector(List<String> t, int idx) {
        return idx < t.size() && "e".equals(t.get(idx));
    }

    private static Integer lookup(Map<String, Integer> map, List<String> t, int idx) {
        return idx >= 0 && idx < t.size() ? map.get(t.get(idx)) : null;
    }
}
