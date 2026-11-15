package br.com.nexu.ui;

import br.com.nexu.core.AssistantState;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

/**
 * Núcleo animado no estilo "reator": anéis giratórios e brilho central que pulsa com o
 * volume do microfone. Desenhado em Canvas a no máximo ~30 quadros por segundo, e
 * pausado quando a janela é minimizada.
 */
public final class ReactorView extends Canvas {

    private static final long FRAME_NANOS = 33_000_000L;

    private static final Color CYAN = Color.web("#00e5ff");
    private static final Color BLUE = Color.web("#2979ff");
    private static final Color SOFT_CYAN = Color.web("#84ffff");
    private static final Color GRAY = Color.web("#546e7a");
    private static final Color RED = Color.web("#ff5252");
    private static final Color DEEP_BLUE = Color.web("#1565c0");

    private volatile double targetLevel;
    private volatile AssistantState state = AssistantState.INITIALIZING;
    private volatile boolean awaitingCommand;

    private double level;
    private double angle;
    private double pulsePhase;
    private long lastFrame;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (lastFrame != 0 && now - lastFrame < FRAME_NANOS) {
                return;
            }
            double dt = lastFrame == 0 ? 0.033 : Math.min(0.1, (now - lastFrame) / 1e9);
            lastFrame = now;
            update(dt);
            draw();
        }
    };

    public ReactorView(double size) {
        super(size, size);
    }

    public void startAnimation() {
        lastFrame = 0;
        timer.start();
    }

    public void stopAnimation() {
        timer.stop();
    }

    /** Pode ser chamado de qualquer thread. */
    public void setLevel(double value) {
        targetLevel = Math.max(0, Math.min(1, value));
    }

    public void setState(AssistantState value) {
        state = value;
    }

    public void setAwaitingCommand(boolean value) {
        awaitingCommand = value;
    }

    private void update(double dt) {
        AssistantState s = state;
        double target = (s == AssistantState.LISTENING) ? targetLevel : (s == AssistantState.SPEAKING ? 0.35 : 0.0);
        level += (target - level) * Math.min(1, dt * 10);
        double speed = switch (s) {
            case PROCESSING -> 220;
            case SPEAKING -> 120;
            case LISTENING -> awaitingCommand ? 90 : 45;
            case PAUSED, ERROR, SHUTTING_DOWN -> 8;
            default -> 30;
        };
        angle = (angle + speed * dt) % 360;
        pulsePhase = (pulsePhase + dt * (s == AssistantState.SPEAKING ? 6 : 2.2)) % (Math.PI * 2);
    }

    private Color accent() {
        return switch (state) {
            case LISTENING -> awaitingCommand ? SOFT_CYAN : CYAN;
            case PROCESSING -> BLUE;
            case SPEAKING -> SOFT_CYAN;
            case PAUSED, SHUTTING_DOWN -> GRAY;
            case ERROR -> RED;
            default -> DEEP_BLUE;
        };
    }

    private void draw() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        double cx = w / 2;
        double cy = h / 2;
        double r = Math.min(w, h) / 2 - 6;
        Color accent = accent();

        gc.clearRect(0, 0, w, h);
        gc.setLineCap(StrokeLineCap.ROUND);

        // brilho de fundo
        double glow = 0.18 + 0.35 * level + 0.05 * Math.sin(pulsePhase);
        gc.setFill(new RadialGradient(0, 0, cx, cy, r, false, CycleMethod.NO_CYCLE,
                new Stop(0, accent.deriveColor(0, 1, 1, Math.min(0.6, glow))),
                new Stop(0.55, accent.deriveColor(0, 1, 1, 0.06)),
                new Stop(1, Color.TRANSPARENT)));
        gc.fillOval(cx - r, cy - r, r * 2, r * 2);

        // anel externo e marcações
        gc.setStroke(accent.deriveColor(0, 1, 1, 0.35));
        gc.setLineWidth(1.5);
        gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
        for (int k = 0; k < 60; k++) {
            double a = Math.toRadians(k * 6 + angle * 0.2);
            double inner = r * (k % 5 == 0 ? 0.86 : 0.91);
            gc.setStroke(accent.deriveColor(0, 1, 1, k % 5 == 0 ? 0.7 : 0.3));
            gc.strokeLine(cx + Math.cos(a) * inner, cy + Math.sin(a) * inner,
                    cx + Math.cos(a) * r * 0.96, cy + Math.sin(a) * r * 0.96);
        }

        // arcos giratórios
        gc.setStroke(accent);
        gc.setLineWidth(4);
        double r1 = r * 0.78;
        for (int k = 0; k < 4; k++) {
            gc.strokeArc(cx - r1, cy - r1, r1 * 2, r1 * 2, angle + k * 90, 52, ArcType.OPEN);
        }
        gc.setStroke(BLUE.deriveColor(0, 1, 1, 0.85));
        gc.setLineWidth(2);
        double r2 = r * 0.62;
        for (int k = 0; k < 6; k++) {
            gc.strokeArc(cx - r2, cy - r2, r2 * 2, r2 * 2, -angle * 1.6 + k * 60, 26, ArcType.OPEN);
        }

        // anel de nível do microfone
        double r3 = r * (0.40 + 0.12 * level);
        gc.setStroke(accent.deriveColor(0, 1, 1, 0.5 + 0.5 * level));
        gc.setLineWidth(2 + 4 * level);
        gc.strokeOval(cx - r3, cy - r3, r3 * 2, r3 * 2);

        // núcleo
        double core = r * (0.24 + 0.05 * Math.sin(pulsePhase) * (state == AssistantState.SPEAKING ? 1 : 0.4) + 0.08 * level);
        gc.setFill(new RadialGradient(0, 0, cx, cy, core, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE),
                new Stop(0.35, accent.deriveColor(0, 0.6, 1, 1)),
                new Stop(1, accent.deriveColor(0, 1, 0.6, 0.0))));
        gc.fillOval(cx - core, cy - core, core * 2, core * 2);
    }
}
