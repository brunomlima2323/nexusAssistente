package br.com.nexu.commands;

import br.com.nexu.utils.TextNormalizer;

import java.util.List;
import java.util.Optional;

/**
 * Regra de reconhecimento de uma intenção. As regras são avaliadas em ordem de
 * prioridade pelo {@link IntentRecognizer}; a primeira que reconhecer a frase vence.
 */
@FunctionalInterface
public interface IntentRule {

    Optional<IntentMatch> match(Utterance utterance, RecognitionContext context);

    /**
     * Frase já pré-processada.
     *
     * @param text       texto original (com acentos)
     * @param rawTokens  palavras originais e normalizadas, lado a lado
     * @param tokens     palavras normalizadas com números por extenso convertidos em algarismos
     * @param normalized {@code tokens} unidos por espaço
     * @param duration   duração encontrada na frase, se houver
     */
    record Utterance(String text, List<TextNormalizer.Token> rawTokens, List<String> tokens,
                     String normalized, Optional<DurationParser.Result> duration) {
    }

    /** Estado do assistente relevante para a interpretação. */
    record RecognitionContext(boolean timerActive) {
        public static final RecognitionContext EMPTY = new RecognitionContext(false);
    }
}
