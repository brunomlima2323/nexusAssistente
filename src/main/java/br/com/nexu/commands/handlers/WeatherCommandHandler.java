package br.com.nexu.commands.handlers;

import br.com.nexu.commands.IntentMatch;
import br.com.nexu.features.WeatherException;
import br.com.nexu.features.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Consulta a previsão do tempo e transforma o resultado em fala. Nunca inventa dados. */
public final class WeatherCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(WeatherCommandHandler.class);

    private final WeatherService weatherService;

    public WeatherCommandHandler(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public CompletableFuture<CommandResult> handle(IntentMatch match) {
        String city = match.slot(IntentMatch.SLOT_CITY).orElse(null);
        int dayOffset = match.slot(IntentMatch.SLOT_DAY_OFFSET).map(Integer::parseInt).orElse(0);
        return weatherService.fetch(city, dayOffset)
                .thenApply(report -> CommandResult.say(report.toSpeech()))
                .exceptionally(error -> {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    if (cause instanceof WeatherException weather) {
                        log.warn("Previsão do tempo indisponível: {}", cause.getMessage(), cause.getCause());
                        return CommandResult.say(weather.getUserMessage());
                    }
                    log.error("Erro inesperado na previsão do tempo", cause);
                    return CommandResult.say("Senhor, não consegui obter a previsão do tempo neste momento.");
                });
    }
}
