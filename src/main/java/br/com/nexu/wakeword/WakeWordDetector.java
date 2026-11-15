package br.com.nexu.wakeword;

import br.com.nexu.config.AppConfig;
import br.com.nexu.utils.FuzzyMatcher;
import br.com.nexu.utils.PhoneticEncoder;
import br.com.nexu.utils.TextNormalizer;
import br.com.nexu.utils.TextNormalizer.Token;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Identifica a palavra de ativação "Nexu" em transcrições imperfeitas.
 * <p>
 * Estratégia:
 * <ul>
 *     <li>normalização (minúsculas, sem acentos e pontuação);</li>
 *     <li>lista de variações conhecidas ("nexus", "nessu"...);</li>
 *     <li>comparação fonética ({@link PhoneticEncoder}) + Jaro-Winkler;</li>
 *     <li>junção de palavras quebradas pelo reconhecedor ("ne xu");</li>
 *     <li>análise de contexto para palavras reais do português, principalmente "anexo":
 *         "anexo" isolado, ou no início/fim de uma frase de comando, ativa o Nexu;
 *         "o anexo", "em anexo", "anexo do e-mail" ou "anexo" no meio da frase não ativam.</li>
 * </ul>
 */
public final class WakeWordDetector {

    /** Interjeições que podem preceder o nome ("ei Nexu", "ok Nexu"). */
    private static final Set<String> FILLERS = Set.of("ei", "oi", "ola", "ok", "okay", "hey", "ai", "fala", "alo",
            "e", "entao", "por", "favor", "o", "senhor");

    /** Palavras que, antes de "anexo", indicam o substantivo/adjetivo e não o nome. */
    private static final Set<String> CONTEXT_PREV_BLOCK = Set.of("o", "os", "no", "nos", "do", "dos", "ao", "aos",
            "um", "uns", "em", "seu", "sua", "seus", "meu", "minha", "teu", "este", "esse", "esta", "essa", "aquele",
            "segue", "seguem", "novo", "primeiro", "segundo", "ultimo", "pelo", "num", "como", "com", "sem", "tem",
            "nenhum", "faz", "de", "outro", "veja", "vide", "abrir", "abra", "abre", "enviei", "mandei", "mando",
            "envio", "baixar", "baixe", "arquivo", "documento", "nesse", "neste", "qual", "quais", "foto", "imagem");

    /** Palavras que, depois de "anexo", indicam o substantivo/adjetivo e não o nome. */
    private static final Set<String> CONTEXT_NEXT_BLOCK = Set.of("do", "da", "dos", "das", "de", "no", "na", "nos",
            "nas", "ao", "com", "foi", "esta", "estao", "sera", "enviado", "enviada", "segue", "contem", "contendo",
            "em", "pdf", "arquivo", "documento", "abaixo", "acima", "anterior", "aberto", "anexado", "ficou", "tem");

    private final String wakeWord;
    private final String wakePhonetic;
    private final Set<String> aliases;
    private final Set<String> contextualWords;
    private final Set<String> blocklist;
    private final double threshold;
    private final double contextualThreshold;
    private volatile Predicate<String> commandValidator = text -> false;

    private record Candidate(int start, int end, String word, double score, boolean contextual) {
    }

    public WakeWordDetector(String wakeWord, Set<String> aliases, Set<String> contextualWords,
                            Set<String> blocklist, double threshold, double contextualThreshold) {
        this.wakeWord = TextNormalizer.normalize(wakeWord);
        this.wakePhonetic = PhoneticEncoder.encode(this.wakeWord);
        this.aliases = normalizeAll(aliases);
        this.aliases.add(this.wakeWord);
        this.contextualWords = normalizeAll(contextualWords);
        this.blocklist = normalizeAll(blocklist);
        this.threshold = threshold;
        this.contextualThreshold = contextualThreshold;
    }

    public static WakeWordDetector fromConfig(AppConfig config) {
        return new WakeWordDetector(
                config.getString("wakeword.word", "nexu"),
                new HashSet<>(config.getList("wakeword.aliases")),
                new HashSet<>(config.getList("wakeword.contextual.words")),
                new HashSet<>(config.getList("wakeword.blocklist")),
                config.getDouble("wakeword.threshold", 0.82),
                config.getDouble("wakeword.contextual.threshold", 0.90));
    }

    /**
     * Define como verificar se o restante da frase é um comando válido. Usado para aceitar
     * "anexo, que horas são?" como ativação, mas não "anexo errado".
     */
    public void setCommandValidator(Predicate<String> validator) {
        this.commandValidator = validator == null ? text -> false : validator;
    }

    public WakeWordResult detect(String text) {
        List<Token> tokens = TextNormalizer.tokenize(text);
        if (tokens.isEmpty()) {
            return WakeWordResult.none();
        }
        Candidate best = null;
        for (int i = 0; i < tokens.size(); i++) {
            best = better(best, evaluate(tokens, i, i, tokens.get(i).norm()));
            if (i + 1 < tokens.size()) {
                String a = tokens.get(i).norm();
                String b = tokens.get(i + 1).norm();
                if (a.length() <= 4 && b.length() <= 4) {
                    best = better(best, evaluate(tokens, i, i + 1, a + b));
                }
            }
        }
        if (best == null) {
            return WakeWordResult.none();
        }
        List<Token> rest = remainder(tokens, best.start(), best.end());
        return new WakeWordResult(true, best.score(), best.word(), best.contextual(), TextNormalizer.joinRaw(rest));
    }

    /** Similaridade de uma palavra isolada com a palavra de ativação (0..1). */
    public double score(String word) {
        String w = TextNormalizer.normalize(word).replace(" ", "");
        if (w.isEmpty() || blocklist.contains(w)) {
            return 0.0;
        }
        if (aliases.contains(w)) {
            return 1.0;
        }
        String phonetic = PhoneticEncoder.encode(w);
        if (phonetic.length() < 3 || phonetic.length() > wakePhonetic.length() + 2
                || phonetic.charAt(0) != wakePhonetic.charAt(0)) {
            return 0.0;
        }
        return 0.7 * FuzzyMatcher.similarity(phonetic, wakePhonetic) + 0.3 * FuzzyMatcher.jaroWinkler(w, wakeWord);
    }

    private Candidate evaluate(List<Token> tokens, int start, int end, String word) {
        if (blocklist.contains(word)) {
            return null;
        }
        double direct = score(word);
        double viaPrefix = 0.0;
        if (word.length() >= 5 && word.charAt(0) == 'a' && !aliases.contains(word)) {
            viaPrefix = score(word.substring(1)); // "anexo" -> "nexo"
        }
        boolean contextual;
        double value;
        if (viaPrefix > direct) {
            value = viaPrefix;
            contextual = true;
        } else {
            value = direct;
            contextual = contextualWords.contains(word);
        }
        if (value < (contextual ? contextualThreshold : threshold)) {
            return null;
        }
        if (contextual && !contextAllows(tokens, start, end)) {
            return null;
        }
        return new Candidate(start, end, word, value, contextual);
    }

    private boolean contextAllows(List<Token> tokens, int start, int end) {
        String prev = start > 0 ? tokens.get(start - 1).norm() : null;
        String next = end + 1 < tokens.size() ? tokens.get(end + 1).norm() : null;
        if (prev != null && CONTEXT_PREV_BLOCK.contains(prev)) {
            return false;
        }
        if (next != null && CONTEXT_NEXT_BLOCK.contains(next)) {
            return false;
        }
        List<Token> rest = remainder(tokens, start, end);
        if (rest.isEmpty()) {
            return true; // "anexo" pronunciado isoladamente (talvez com "ei", "ok"...)
        }
        boolean atStart = true;
        for (int i = 0; i < start; i++) {
            if (!FILLERS.contains(tokens.get(i).norm())) {
                atStart = false;
                break;
            }
        }
        boolean atEnd = end == tokens.size() - 1;
        if (!atStart && !atEnd) {
            return false;
        }
        return commandValidator.test(TextNormalizer.joinRaw(rest));
    }

    private static List<Token> remainder(List<Token> tokens, int start, int end) {
        List<Token> rest = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            if (i < start || i > end) {
                rest.add(tokens.get(i));
            }
        }
        while (!rest.isEmpty() && FILLERS.contains(rest.get(0).norm())) {
            rest.remove(0);
        }
        return rest;
    }

    private static Candidate better(Candidate current, Candidate other) {
        if (other == null) {
            return current;
        }
        if (current == null || other.score() > current.score() + 1e-9) {
            return other;
        }
        return current;
    }

    private static Set<String> normalizeAll(Set<String> words) {
        Set<String> out = new HashSet<>();
        for (String w : words) {
            String n = TextNormalizer.normalize(w).replace(" ", "");
            if (!n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }
}
