package br.com.nexu.commands.handlers;

import br.com.nexu.commands.IntentMatch;

import java.util.concurrent.CompletableFuture;

/**
 * Executa uma intenção reconhecida. Implementações devem ser não bloqueantes:
 * operações demoradas (rede, disco) precisam rodar de forma assíncrona.
 */
@FunctionalInterface
public interface CommandHandler {

    CompletableFuture<CommandResult> handle(IntentMatch match);
}
