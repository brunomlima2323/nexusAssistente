package br.com.nexu.commands;

import br.com.nexu.utils.TextNormalizer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Extrai durações de frases em linguagem natural.
 * <p>
 * Entende: "10 minutos", "dez minutos", "1 minuto e 30 segundos", "uma hora e meia",
 * "meia hora", "duas horas e quinze minutos", "1 hora e 30", "1h30", "um minuto e meio".
 */
public final class DurationParser {

    /**
     * @param duration     duração encontrada
     * @param onlyDuration {@code true} quando a frase contém apenas a duração
     *                     (ex.: "dez minutos"), útil como resposta a "por quanto tempo?"
     */
    public record Result(Duration duration, boolean onlyDuration) {
    }

    private static final Map<String, Long> UNIT_SECONDS = Map.ofEntries(
            Map.entry("hora", 3600L), Map.entry("horas", 3600L), Map.entry("h", 3600L),
            Map.entry("hr", 3600L), Map.entry("hrs", 3600L),
            Map.entry("minuto", 60L), Map.entry("minutos", 60L), Map.entry("min", 60L),
            Map.entry("mins", 60L), Map.entry("minutinho", 60L), Map.entry("minutinhos", 60L),
            Map.entry("segundo", 1L), Map.entry("segundos", 1L), Map.entry("seg", 1L),
            Map.entry("segs", 1L), Map.entry("s", 1L), Map.entry("segundinhos", 1L));

    private static final Set<String> HALF = Set.of("meia", "meio");
    private static final Set<String> NEUTRAL = Set.of("de", "e", "por", "favor", "durante", "apenas", "so", "mais", "uns", "umas");

    private DurationParser() {
    }

    public static Optional<Result> parse(String text) {
        return parseTokens(NumberParser.replaceNumberWords(TextNormalizer.tokens(text)));
    }

    /** Recebe palavras normalizadas e com números já convertidos por {@link NumberParser}. */
    public static Optional<Result> parseTokens(List<String> t) {
        int n = t.size();
        boolean[] used = new boolean[n];
        long total = 0;
        int i = 0;
        while (i < n) {
            String token = t.get(i);
            Long unit = i + 1 < n ? UNIT_SECONDS.get(t.get(i + 1)) : null;

            if (NumberParser.isInteger(token) && unit != null) {
                total += Long.parseLong(token) * unit;
                used[i] = used[i + 1] = true;
                i += 2;

                // "uma hora e meia", "um minuto e meio"
                if (i + 1 < n && "e".equals(t.get(i)) && HALF.contains(t.get(i + 1))) {
                    total += unit / 2;
                    used[i] = used[i + 1] = true;
                    i += 2;
                    continue;
                }
                // "uma hora e trinta", "1h30" => unidade imediatamente menor
                int k = i;
                if (k < n && "e".equals(t.get(k))) {
                    k++;
                }
                if (unit > 1 && k < n && NumberParser.isInteger(t.get(k))
                        && (k + 1 >= n || !UNIT_SECONDS.containsKey(t.get(k + 1)))) {
                    total += Long.parseLong(t.get(k)) * (unit / 60);
                    for (int u = i; u <= k; u++) {
                        used[u] = true;
                    }
                    i = k + 1;
                }
                continue;
            }

            // "meia hora", "meio minuto"
            if (HALF.contains(token) && unit != null) {
                total += unit / 2;
                used[i] = used[i + 1] = true;
                i += 2;
                continue;
            }
            i++;
        }

        if (total <= 0) {
            return Optional.empty();
        }
        boolean onlyDuration = true;
        for (int k = 0; k < n; k++) {
            if (!used[k] && !NEUTRAL.contains(t.get(k))) {
                onlyDuration = false;
                break;
            }
        }
        return Optional.of(new Result(Duration.ofSeconds(total), onlyDuration));
    }
}
