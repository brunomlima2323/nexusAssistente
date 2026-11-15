package br.com.nexu.commands.handlers;

import br.com.nexu.commands.IntentMatch;
import br.com.nexu.features.DateTimeService;

import java.util.concurrent.CompletableFuture;

/** Responde horário e data usando o relógio e o fuso do sistema operacional. */
public final class DateTimeCommandHandler implements CommandHandler {

    private final DateTimeService dateTimeService;

    public DateTimeCommandHandler(DateTimeService dateTimeService) {
        this.dateTimeService = dateTimeService;
    }

    @Override
    public CompletableFuture<CommandResult> handle(IntentMatch match) {
        return CompletableFuture.completedFuture(switch (match.intent()) {
            case TIME -> CommandResult.say(dateTimeService.timeSpeech());
            case DATE -> CommandResult.say(dateTimeService.dateSpeech());
            default -> throw new IllegalArgumentException("Intenção não suportada: " + match.intent());
        });
    }
}
