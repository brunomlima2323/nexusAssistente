package br.com.nexu.commands.handlers;

import br.com.nexu.commands.Intent;
import br.com.nexu.commands.IntentMatch;
import br.com.nexu.features.TimerService;
import br.com.nexu.utils.SpeechFormatter;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Criação, consulta e cancelamento do cronômetro. */
public final class TimerCommandHandler implements CommandHandler {

    private final TimerService timerService;
    private final Duration maxDuration;

    public TimerCommandHandler(TimerService timerService, Duration maxDuration) {
        this.timerService = timerService;
        this.maxDuration = maxDuration;
    }

    @Override
    public CompletableFuture<CommandResult> handle(IntentMatch match) {
        return CompletableFuture.completedFuture(switch (match.intent()) {
            case TIMER_CREATE -> create(match);
            case TIMER_REMAINING -> remaining();
            case TIMER_CANCEL -> cancel();
            default -> throw new IllegalArgumentException("Intenção não suportada: " + match.intent());
        });
    }

    private CommandResult create(IntentMatch match) {
        Optional<String> seconds = match.slot(IntentMatch.SLOT_DURATION_SECONDS);
        if (seconds.isEmpty()) {
            return CommandResult.say("Pois não, senhor. Por quanto tempo devo cronometrar?");
        }
        Duration duration = Duration.ofSeconds(Long.parseLong(seconds.get()));
        if (duration.isZero() || duration.isNegative()) {
            return CommandResult.say("Senhor, não entendi a duração do cronômetro.");
        }
        if (duration.compareTo(maxDuration) > 0) {
            return CommandResult.say("Senhor, o cronômetro aceita no máximo " + SpeechFormatter.duration(maxDuration) + ".");
        }
        TimerService.TimerInfo info = timerService.start(duration);
        String prefix = info.replacedPrevious()
                ? "Perfeitamente, senhor. Substituí o cronômetro anterior. "
                : "Perfeitamente, senhor. ";
        return CommandResult.say(prefix + "Cronômetro iniciado para " + SpeechFormatter.duration(duration) + ".");
    }

    private CommandResult remaining() {
        Optional<Duration> remaining = timerService.remaining();
        if (remaining.isEmpty()) {
            return CommandResult.say("Senhor, não há nenhum cronômetro ativo no momento.");
        }
        Duration left = remaining.get();
        String verb = SpeechFormatter.isSingular(left) ? "falta " : "faltam ";
        return CommandResult.say("Senhor, " + verb + SpeechFormatter.duration(left) + ".");
    }

    private CommandResult cancel() {
        return timerService.cancel()
                .map(info -> CommandResult.say("Conforme solicitado, senhor. Cronômetro cancelado."))
                .orElseGet(() -> CommandResult.say("Senhor, não há nenhum cronômetro ativo para cancelar."));
    }

    /** Intenções atendidas por este handler. */
    public static Intent[] intents() {
        return new Intent[]{Intent.TIMER_CREATE, Intent.TIMER_REMAINING, Intent.TIMER_CANCEL};
    }
}
