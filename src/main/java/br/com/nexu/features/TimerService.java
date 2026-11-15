package br.com.nexu.features;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Cronômetro regressivo independente do restante do assistente.
 * <p>
 * O tempo é controlado por {@link System#nanoTime()} (relógio monotônico): o prazo final é
 * calculado no início, então atrasos de agendamento, pausas do coletor de lixo ou mudanças no
 * relógio do sistema não acumulam erro. Roda em thread própria, portanto continua contando
 * enquanto o Nexu escuta, fala ou consulta a internet.
 */
public final class TimerService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TimerService.class);

    /** Informações de um cronômetro. */
    public record TimerInfo(long id, Duration total, boolean replacedPrevious) {
    }

    /** Eventos do cronômetro. Chamados na thread do cronômetro: não bloqueie. */
    public interface Listener {
        default void onTimerStarted(TimerInfo timer) {
        }

        default void onTimerTick(TimerInfo timer, Duration remaining) {
        }

        default void onTimerFinished(TimerInfo timer) {
        }

        default void onTimerCancelled(TimerInfo timer) {
        }
    }

    private record ActiveTimer(TimerInfo info, long deadlineNanos, ScheduledFuture<?> ticker, ScheduledFuture<?> alarm) {
    }

    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoClock;
    private final long tickMillis;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private ActiveTimer active; // protegido por "this"

    public TimerService(long tickMillis) {
        this(tickMillis, System::nanoTime);
    }

    public TimerService(long tickMillis, LongSupplier nanoClock) {
        this.tickMillis = Math.max(50, tickMillis);
        this.nanoClock = nanoClock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexu-timer");
            t.setDaemon(true);
            return t;
        });
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Inicia um cronômetro; um cronômetro já ativo é substituído. */
    public synchronized TimerInfo start(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Duração inválida: " + duration);
        }
        boolean replaced = false;
        if (active != null) {
            stopTasks(active);
            replaced = true;
        }
        TimerInfo info = new TimerInfo(ids.incrementAndGet(), duration, replaced);
        long deadline = nanoClock.getAsLong() + duration.toNanos();
        ScheduledFuture<?> ticker = scheduler.scheduleAtFixedRate(() -> tick(info.id()),
                tickMillis, tickMillis, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> alarm = scheduler.schedule(() -> checkFinished(info.id()),
                duration.toNanos(), TimeUnit.NANOSECONDS);
        active = new ActiveTimer(info, deadline, ticker, alarm);
        log.info("Cronômetro #{} iniciado para {}", info.id(), duration);
        notifyListeners(l -> l.onTimerStarted(info));
        return info;
    }

    public synchronized Optional<Duration> remaining() {
        if (active == null) {
            return Optional.empty();
        }
        long left = active.deadlineNanos() - nanoClock.getAsLong();
        return Optional.of(Duration.ofNanos(Math.max(0, left)));
    }

    public synchronized boolean isActive() {
        return active != null;
    }

    public synchronized Optional<TimerInfo> current() {
        return active == null ? Optional.empty() : Optional.of(active.info());
    }

    public synchronized Optional<TimerInfo> cancel() {
        if (active == null) {
            return Optional.empty();
        }
        ActiveTimer cancelled = active;
        active = null;
        stopTasks(cancelled);
        log.info("Cronômetro #{} cancelado", cancelled.info().id());
        notifyListeners(l -> l.onTimerCancelled(cancelled.info()));
        return Optional.of(cancelled.info());
    }

    private void tick(long id) {
        TimerInfo info;
        Duration left;
        synchronized (this) {
            if (active == null || active.info().id() != id) {
                return;
            }
            long remainingNanos = active.deadlineNanos() - nanoClock.getAsLong();
            if (remainingNanos <= 0) {
                finish();
                return;
            }
            info = active.info();
            left = Duration.ofNanos(remainingNanos);
        }
        notifyListeners(l -> l.onTimerTick(info, left));
    }

    private void checkFinished(long id) {
        synchronized (this) {
            if (active == null || active.info().id() != id) {
                return;
            }
            long remainingNanos = active.deadlineNanos() - nanoClock.getAsLong();
            if (remainingNanos > 0) {
                // O agendador acordou cedo: reagenda para o restante exato.
                ActiveTimer current = active;
                ScheduledFuture<?> alarm = scheduler.schedule(() -> checkFinished(id), remainingNanos, TimeUnit.NANOSECONDS);
                active = new ActiveTimer(current.info(), current.deadlineNanos(), current.ticker(), alarm);
                return;
            }
            finish();
        }
    }

    /** Deve ser chamado com o monitor adquirido. */
    private void finish() {
        ActiveTimer done = active;
        active = null;
        stopTasks(done);
        log.info("Cronômetro #{} finalizado", done.info().id());
        // Notifica fora do monitor para não travar novas chamadas.
        scheduler.execute(() -> notifyListeners(l -> l.onTimerFinished(done.info())));
    }

    private static void stopTasks(ActiveTimer timer) {
        timer.ticker().cancel(false);
        timer.alarm().cancel(false);
    }

    private void notifyListeners(java.util.function.Consumer<Listener> action) {
        for (Listener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException e) {
                log.warn("Listener do cronômetro falhou", e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (active != null) {
                stopTasks(active);
                active = null;
            }
        }
        scheduler.shutdownNow();
    }
}
