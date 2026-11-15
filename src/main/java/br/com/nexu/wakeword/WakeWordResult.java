package br.com.nexu.wakeword;

/**
 * Resultado da busca pela palavra de ativação.
 *
 * @param detected    {@code true} se "Nexu" (ou variação aceita) foi identificado
 * @param score       similaridade da melhor correspondência (0..1)
 * @param matchedWord palavra transcrita que foi interpretada como "Nexu"
 * @param contextual  {@code true} quando a ativação dependeu de contexto (ex.: "anexo")
 * @param remainder   restante da frase, sem a palavra de ativação (com acentos preservados)
 */
public record WakeWordResult(boolean detected, double score, String matchedWord, boolean contextual, String remainder) {

    private static final WakeWordResult NONE = new WakeWordResult(false, 0.0, "", false, "");

    public static WakeWordResult none() {
        return NONE;
    }

    public boolean hasCommand() {
        return detected && !remainder.isBlank();
    }
}
