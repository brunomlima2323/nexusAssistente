package br.com.nexu.commands;

import br.com.nexu.commands.IntentRule.RecognitionContext;
import br.com.nexu.commands.IntentRule.Utterance;
import br.com.nexu.utils.FuzzyMatcher;
import br.com.nexu.utils.TextNormalizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reconhecimento de intenções por palavras-chave, contexto e correspondência aproximada.
 * <p>
 * Prioridades (a primeira regra que reconhecer a frase vence):
 * <ol>
 *     <li>Encerramento do assistente;</li>
 *     <li>Previsão do tempo (qualquer frase com "tempo", exceto quando o contexto é claramente de cronômetro);</li>
 *     <li>Tempo restante do cronômetro ("quanto falta?");</li>
 *     <li>Cancelamento do cronômetro;</li>
 *     <li>Criação de cronômetro;</li>
 *     <li>Horário;</li>
 *     <li>Data.</li>
 * </ol>
 * A palavra de ativação é tratada antes, pelo {@link br.com.nexu.wakeword.WakeWordDetector}.
 */
public final class IntentRecognizer {

    // --- vocabulário (normalizado, sem acentos) ---
    static final Set<String> TIMER_NOUNS = Set.of("cronometro", "timer", "temporizador", "contagem", "contador", "alarme");
    static final Set<String> TIMER_VERBS = Set.of(
            "cronometre", "cronometra", "cronometrar", "conte", "conta", "contar", "marque", "marca", "marcar",
            "inicie", "inicia", "iniciar", "comece", "comeca", "comecar", "programe", "programa", "programar",
            "defina", "define", "definir", "coloque", "coloca", "colocar", "bote", "bota", "ponha", "poe",
            "crie", "cria", "criar", "ative", "ativa", "ativar", "dispare", "dispara", "avise", "avisa", "acione",
            "lembre", "lembra", "lembrar");
    static final Set<String> REMAINING_WORDS = Set.of("falta", "faltam", "faltando", "resta", "restam", "restando", "restante");
    static final Set<String> QUANTITY_WORDS = Set.of("quanto", "quantos", "quantas", "ainda");
    static final Set<String> CANCEL_VERBS = Set.of(
            "cancelar", "cancele", "cancela", "parar", "pare", "para", "interromper", "interrompa", "interrompe",
            "desligar", "desligue", "desliga", "encerrar", "encerre", "encerra", "zerar", "zere", "zera",
            "apagar", "apague", "apaga", "remover", "remova", "remove", "esquece", "esqueca", "suspender", "suspenda");
    static final Set<String> WEATHER_PRIMARY = Set.of("tempo");
    static final Set<String> WEATHER_SECONDARY = Set.of(
            "previsao", "clima", "climatica", "temperatura", "chover", "chuva", "chovendo", "chove",
            "meteorologia", "meteorologica", "umidade", "calor", "frio", "ensolarado", "nublado", "graus");
    static final Set<String> SHUTDOWN_WORDS = Set.of("tchau", "xau", "chau", "adeus", "encerrar", "encerre", "encerra",
            "desligar", "desligue", "desliga", "finalizar", "finalize", "sair", "fechar", "feche");
    static final Set<String> SHUTDOWN_ALWAYS = Set.of("tchau", "xau", "chau", "adeus");
    static final List<String> SHUTDOWN_PHRASES = List.of("ate mais", "ate logo", "ate breve", "ate a proxima", "ate depois");
    /** Palavras que podem acompanhar "encerrar/desligar" sem mudar o sentido de encerramento. */
    static final Set<String> SHUTDOWN_COMPANIONS = Set.of("o", "a", "assistente", "nexu", "sistema", "voce", "pode",
            "se", "por", "favor", "agora", "programa", "aplicativo", "app", "nexus", "ja", "isso", "tudo");
    static final Set<String> TIME_WORDS = Set.of("horario", "horarios");
    static final Set<String> HOUR_WORDS = Set.of("hora", "horas");
    static final Set<String> DATE_WORDS = Set.of("data");
    static final Set<String> DAY_QUALIFIERS = Set.of("hoje", "que", "qual", "semana", "mes", "estamos");

    // --- extração da cidade ---
    private static final Set<String> CITY_PREPOSITIONS = Set.of("em", "no", "na", "para", "pra", "pro", "de", "do", "da", "sobre");
    private static final Set<String> CITY_CONNECTORS = Set.of("de", "do", "da", "dos", "das");
    private static final Set<String> CITY_STOP = Set.of("hoje", "agora", "amanha", "depois", "por", "favor", "nexu",
            "neste", "nesse", "nesta", "momento", "ai", "la", "aqui", "fora", "esta", "semana", "manha", "tarde",
            "noite", "cidade", "minha", "atual", "hj", "como", "e", "vai", "que", "qual", "sera", "ta", "o", "a",
            "os", "as", "um", "uma", "mim", "gente", "local", "regiao", "onde", "estou", "proximos", "dias", "dia");

    private final List<IntentRule> rules = new CopyOnWriteArrayList<>();

    public IntentRecognizer() {
        rules.add(this::shutdownRule);
        rules.add(this::weatherRule);
        rules.add(this::timerRemainingRule);
        rules.add(this::timerCancelRule);
        rules.add(this::timerCreateRule);
        rules.add(this::timeRule);
        rules.add(this::dateRule);
    }

    /** Acrescenta uma regra com prioridade menor que as existentes (ponto de extensão). */
    public void registerRule(IntentRule rule) {
        rules.add(rule);
    }

    public IntentMatch recognize(String text) {
        return recognize(text, RecognitionContext.EMPTY);
    }

    public IntentMatch recognize(String text, RecognitionContext context) {
        List<TextNormalizer.Token> raw = TextNormalizer.tokenize(text);
        List<String> norm = new ArrayList<>(raw.size());
        raw.forEach(t -> norm.add(t.norm()));
        List<String> tokens = NumberParser.replaceNumberWords(norm);
        String normalized = String.join(" ", tokens);
        if (tokens.isEmpty()) {
            return IntentMatch.unknown(text, normalized);
        }
        Utterance utterance = new Utterance(text, raw, tokens, normalized, DurationParser.parseTokens(tokens));
        for (IntentRule rule : rules) {
            Optional<IntentMatch> match = rule.match(utterance, context);
            if (match.isPresent()) {
                return match.get();
            }
        }
        return IntentMatch.unknown(text, normalized);
    }

    // ------------------------------------------------------------------ regras

    private Optional<IntentMatch> shutdownRule(Utterance u, RecognitionContext ctx) {
        List<String> t = u.tokens();
        if (FuzzyMatcher.containsAny(t, TIMER_NOUNS)) {
            return Optional.empty(); // "encerrar o cronômetro" é cancelamento, não encerramento
        }
        if (FuzzyMatcher.containsAny(t, SHUTDOWN_ALWAYS)) {
            return match(Intent.SHUTDOWN, 0.95, Map.of(), u);
        }
        for (String phrase : SHUTDOWN_PHRASES) {
            if (FuzzyMatcher.containsPhrase(t, phrase)) {
                return match(Intent.SHUTDOWN, 0.9, Map.of(), u);
            }
        }
        if (FuzzyMatcher.containsAny(t, SHUTDOWN_WORDS)) {
            // "desligar"/"encerrar" só encerram o Nexu se não houver outro objeto na frase
            for (String token : t) {
                boolean isVerb = SHUTDOWN_WORDS.stream().anyMatch(w -> FuzzyMatcher.tokenMatches(token, w));
                if (!isVerb && !SHUTDOWN_COMPANIONS.contains(token)) {
                    return Optional.empty();
                }
            }
            return match(Intent.SHUTDOWN, 0.85, Map.of(), u);
        }
        return Optional.empty();
    }

    private Optional<IntentMatch> weatherRule(Utterance u, RecognitionContext ctx) {
        List<String> t = u.tokens();
        boolean hasTempo = FuzzyMatcher.containsAny(t, WEATHER_PRIMARY);
        boolean hasOther = FuzzyMatcher.containsAny(t, WEATHER_SECONDARY);
        if (!hasTempo && !hasOther) {
            return Optional.empty();
        }
        if (isTimerContext(u, ctx, hasTempo)) {
            return Optional.empty();
        }
        Map<String, String> slots = new HashMap<>();
        extractCity(u.rawTokens()).ifPresent(city -> slots.put(IntentMatch.SLOT_CITY, city));
        slots.put(IntentMatch.SLOT_DAY_OFFSET, Integer.toString(dayOffset(t)));
        return match(Intent.WEATHER, hasTempo ? 0.95 : 0.8, slots, u);
    }

    /**
     * "tempo" tem prioridade meteorológica, mas algumas frases são claramente de cronômetro:
     * "quanto tempo falta?", "conte um tempo de 5 minutos", "cancelar o tempo do cronômetro".
     */
    private boolean isTimerContext(Utterance u, RecognitionContext ctx, boolean hasTempo) {
        List<String> t = u.tokens();
        boolean hasTimerNoun = FuzzyMatcher.containsAny(t, TIMER_NOUNS);
        boolean asksRemaining = FuzzyMatcher.containsAny(t, REMAINING_WORDS);
        boolean asksQuantity = FuzzyMatcher.containsExact(t, QUANTITY_WORDS);
        if (asksRemaining && (hasTimerNoun || ctx.timerActive() || (hasTempo && asksQuantity))) {
            return true;
        }
        if (u.duration().isPresent() && (hasTimerNoun || FuzzyMatcher.containsAny(t, TIMER_VERBS))) {
            return true;
        }
        return hasTimerNoun && FuzzyMatcher.containsAny(t, CANCEL_VERBS);
    }

    private Optional<IntentMatch> timerRemainingRule(Utterance u, RecognitionContext ctx) {
        List<String> t = u.tokens();
        if (!FuzzyMatcher.containsAny(t, REMAINING_WORDS)) {
            return Optional.empty();
        }
        boolean hasTimerNoun = FuzzyMatcher.containsAny(t, TIMER_NOUNS);
        boolean asksQuantity = FuzzyMatcher.containsExact(t, QUANTITY_WORDS);
        if (hasTimerNoun || asksQuantity || ctx.timerActive()) {
            return match(Intent.TIMER_REMAINING, 0.9, Map.of(), u);
        }
        return Optional.empty();
    }

    private Optional<IntentMatch> timerCancelRule(Utterance u, RecognitionContext ctx) {
        List<String> t = u.tokens();
        if (!FuzzyMatcher.containsAny(t, CANCEL_VERBS)) {
            return Optional.empty();
        }
        boolean hasTimerNoun = FuzzyMatcher.containsAny(t, TIMER_NOUNS);
        if (hasTimerNoun || (ctx.timerActive() && t.size() <= 4 && u.duration().isEmpty())) {
            return match(Intent.TIMER_CANCEL, hasTimerNoun ? 0.9 : 0.7, Map.of(), u);
        }
        return Optional.empty();
    }

    private Optional<IntentMatch> timerCreateRule(Utterance u, RecognitionContext ctx) {
        List<String> t = u.tokens();
        boolean hasTimerNoun = FuzzyMatcher.containsAny(t, TIMER_NOUNS);
        boolean hasTimerVerb = FuzzyMatcher.containsAny(t, TIMER_VERBS);
        if (u.duration().isPresent()) {
            DurationParser.Result result = u.duration().get();
            if (hasTimerNoun || hasTimerVerb || result.onlyDuration()) {
                return match(Intent.TIMER_CREATE, result.onlyDuration() && !hasTimerNoun ? 0.75 : 0.95,
                        Map.of(IntentMatch.SLOT_DURATION_SECONDS, Long.toString(result.duration().toSeconds())), u);
            }
            return Optional.empty();
        }
        if (hasTimerNoun && (hasTimerVerb || t.size() <= 3)) {
            return match(Intent.TIMER_CREATE, 0.7, Map.of(), u); // sem duração: o Nexu perguntará
        }
        return Optional.empty();
    }

    private Optional<IntentMatch> timeRule(Utterance u, RecognitionContext ctx) {
        List<String> t = u.tokens();
        if (FuzzyMatcher.containsAny(t, TIME_WORDS)
                || (FuzzyMatcher.containsExact(t, HOUR_WORDS) && u.duration().isEmpty())) {
            return match(Intent.TIME, 0.9, Map.of(), u);
        }
        return Optional.empty();
    }

    private Optional<IntentMatch> dateRule(Utterance u, RecognitionContext ctx) {
        List<String> t = u.tokens();
        boolean hasData = FuzzyMatcher.containsExact(t, DATE_WORDS);
        boolean hasDia = FuzzyMatcher.containsExact(t, Set.of("dia"));
        if (hasData || (hasDia && FuzzyMatcher.containsExact(t, DAY_QUALIFIERS))) {
            return match(Intent.DATE, 0.9, Map.of(), u);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ auxiliares

    private static Optional<IntentMatch> match(Intent intent, double confidence, Map<String, String> slots, Utterance u) {
        return Optional.of(new IntentMatch(intent, confidence, slots, u.text(), u.normalized()));
    }

    static int dayOffset(List<String> tokens) {
        int amanha = tokens.indexOf("amanha");
        if (amanha < 0) {
            return 0;
        }
        return amanha >= 2 && "depois".equals(tokens.get(amanha - 2)) ? 2 : 1;
    }

    /**
     * Procura "em/no/na/para + nome" fora das palavras meteorológicas:
     * "como está o tempo em Recife" → "Recife"; "previsão do tempo no Rio de Janeiro" → "Rio de Janeiro".
     */
    static Optional<String> extractCity(List<TextNormalizer.Token> tokens) {
        String best = null;
        int anchor = -1;
        for (int i = 0; i < tokens.size(); i++) {
            String n = tokens.get(i).norm();
            if (WEATHER_PRIMARY.contains(n) || WEATHER_SECONDARY.contains(n)) {
                anchor = i;
                break;
            }
        }
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (!CITY_PREPOSITIONS.contains(tokens.get(i).norm())) {
                continue;
            }
            List<TextNormalizer.Token> name = new ArrayList<>();
            for (int j = i + 1; j < tokens.size() && name.size() < 5; j++) {
                String n = tokens.get(j).norm();
                if (CITY_STOP.contains(n) || WEATHER_PRIMARY.contains(n) || WEATHER_SECONDARY.contains(n)
                        || NumberParser.isInteger(n)) {
                    break;
                }
                if (CITY_CONNECTORS.contains(n) && name.isEmpty()) {
                    break;
                }
                name.add(tokens.get(j));
            }
            while (!name.isEmpty() && CITY_CONNECTORS.contains(name.get(name.size() - 1).norm())) {
                name.remove(name.size() - 1);
            }
            if (!name.isEmpty()) {
                String city = TextNormalizer.titleCase(TextNormalizer.joinRaw(name));
                if (i > anchor) {
                    return Optional.of(city);
                }
                if (best == null) {
                    best = city;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
