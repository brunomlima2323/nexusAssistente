package br.com.nexu.commands;

import java.util.Map;
import java.util.Optional;

/**
 * Resultado da interpretação de uma frase.
 *
 * @param intent         intenção identificada
 * @param confidence     confiança aproximada (0..1)
 * @param slots          parâmetros extraídos (cidade, duração, dia...)
 * @param text           texto original analisado
 * @param normalizedText texto normalizado usado na análise
 */
public record IntentMatch(Intent intent, double confidence, Map<String, String> slots,
                          String text, String normalizedText) {

    public static final String SLOT_CITY = "city";
    public static final String SLOT_DAY_OFFSET = "dayOffset";
    public static final String SLOT_DURATION_SECONDS = "durationSeconds";

    public IntentMatch {
        slots = Map.copyOf(slots);
    }

    public static IntentMatch unknown(String text, String normalized) {
        return new IntentMatch(Intent.UNKNOWN, 0.0, Map.of(), text, normalized);
    }

    public Optional<String> slot(String name) {
        return Optional.ofNullable(slots.get(name));
    }

    public boolean isKnown() {
        return intent != Intent.UNKNOWN;
    }

    /** Descrição curta para exibição na interface. */
    public String describe() {
        StringBuilder sb = new StringBuilder(intent.label());
        slot(SLOT_CITY).ifPresent(c -> sb.append(" · ").append(c));
        slot(SLOT_DAY_OFFSET).filter(d -> !"0".equals(d))
                .ifPresent(d -> sb.append(" · ").append("1".equals(d) ? "amanhã" : "depois de amanhã"));
        slot(SLOT_DURATION_SECONDS).ifPresent(s -> sb.append(" · ").append(s).append(" s"));
        return sb.toString();
    }
}
