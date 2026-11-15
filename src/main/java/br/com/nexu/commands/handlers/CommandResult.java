package br.com.nexu.commands.handlers;

/**
 * Resposta de um comando.
 *
 * @param speech   texto que o Nexu deve falar
 * @param shutdown {@code true} se o assistente deve ser encerrado após a fala
 */
public record CommandResult(String speech, boolean shutdown) {

    public static CommandResult say(String speech) {
        return new CommandResult(speech, false);
    }

    public static CommandResult sayAndShutdown(String speech) {
        return new CommandResult(speech, true);
    }
}
