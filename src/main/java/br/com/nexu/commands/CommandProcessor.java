package br.com.nexu.commands;

import br.com.nexu.commands.handlers.CommandHandler;
import br.com.nexu.commands.handlers.CommandResult;
import br.com.nexu.utils.FuzzyMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Encaminha intenções reconhecidas para seus handlers, evita execuções duplicadas
 * (o reconhecedor às vezes entrega a mesma frase duas vezes, com pequenas variações)
 * e converte falhas em respostas educadas.
 */
public final class CommandProcessor {

    private static final Logger log = LoggerFactory.getLogger(CommandProcessor.class);

    private final Map<Intent, CommandHandler> handlers = new EnumMap<>(Intent.class);
    private final long dedupWindowNanos;
    private final LongSupplier clock;

    private IntentMatch lastMatch;
    private long lastMatchNanos;

    public CommandProcessor(long dedupWindowMillis) {
        this(dedupWindowMillis, System::nanoTime);
    }

    CommandProcessor(long dedupWindowMillis, LongSupplier clock) {
        this.dedupWindowNanos = dedupWindowMillis * 1_000_000L;
        this.clock = clock;
    }

    public synchronized void register(CommandHandler handler, Intent... intents) {
        for (Intent intent : intents) {
            handlers.put(intent, handler);
        }
    }

    /**
     * Retorna {@code true} se o comando é repetição de um recém-executado (mesma intenção,
     * mesmos parâmetros e texto muito parecido dentro da janela configurada). Registra o comando
     * como último executado quando não for duplicado.
     */
    public synchronized boolean checkAndRegister(IntentMatch match) {
        long now = clock.getAsLong();
        boolean duplicate = lastMatch != null
                && now - lastMatchNanos < dedupWindowNanos
                && lastMatch.intent() == match.intent()
                && lastMatch.slots().equals(match.slots())
                && FuzzyMatcher.jaroWinkler(lastMatch.normalizedText(), match.normalizedText()) >= 0.85;
        if (!duplicate) {
            lastMatch = match;
            lastMatchNanos = now;
        }
        return duplicate;
    }

    public CompletableFuture<CommandResult> execute(IntentMatch match) {
        CommandHandler handler;
        synchronized (this) {
            handler = handlers.get(match.intent());
        }
        if (handler == null) {
            return CompletableFuture.completedFuture(
                    CommandResult.say("Desculpe, senhor, ainda não sei executar esse comando."));
        }
        try {
            return handler.handle(match).exceptionally(error -> {
                log.error("Falha ao executar {}", match.intent(), error);
                return CommandResult.say("Senhor, ocorreu um erro ao executar o comando.");
            });
        } catch (RuntimeException e) {
            log.error("Falha ao executar {}", match.intent(), e);
            return CompletableFuture.completedFuture(CommandResult.say("Senhor, ocorreu um erro ao executar o comando."));
        }
    }
}
