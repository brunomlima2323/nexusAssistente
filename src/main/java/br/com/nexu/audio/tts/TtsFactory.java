package br.com.nexu.audio.tts;

import br.com.nexu.audio.TextToSpeech;
import br.com.nexu.config.AppConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Escolhe o motor de voz conforme {@code tts.engine} e o sistema operacional. */
public final class TtsFactory {

    private TtsFactory() {
    }

    public static TextToSpeech create(AppConfig config) {
        String engine = config.getString("tts.engine", "auto").toLowerCase(Locale.ROOT);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (engine.equals("auto")) {
            if (!config.getString("tts.command").isBlank()) {
                engine = "command";
            } else if (os.contains("win")) {
                engine = "windows";
            } else if (os.contains("mac")) {
                engine = "macos";
            } else {
                engine = "espeak";
            }
        }
        return switch (engine) {
            case "windows" -> new WindowsSapiTts(
                    config.getString("tts.voice"),
                    config.getString("tts.culture", "pt-BR"),
                    config.getInt("tts.rate", 0),
                    config.getInt("tts.volume", 100));
            case "macos" -> new MacSayTts(config.getString("tts.mac.voice", "Luciana"), config.getInt("tts.mac.rate", 185));
            case "espeak" -> findExecutable("espeak-ng").or(() -> findExecutable("espeak"))
                    .<TextToSpeech>map(exe -> new EspeakTts(exe,
                            config.getString("tts.espeak.voice", "pt-br"),
                            config.getInt("tts.espeak.speed", 160)))
                    .orElseGet(() -> findExecutable("spd-say")
                            .<TextToSpeech>map(exe -> new CommandTts(exe + " -w -l pt-BR {text}"))
                            .orElseGet(() -> new NoVoiceTts("instale o espeak-ng (sudo apt install espeak-ng)")));
            case "command" -> {
                String command = config.getString("tts.command");
                yield command.isBlank() ? new NoVoiceTts("tts.command não configurado") : new CommandTts(command);
            }
            case "none" -> new NoVoiceTts("desativada em tts.engine");
            default -> new NoVoiceTts("motor desconhecido: " + engine);
        };
    }

    /** Procura um executável no PATH. */
    public static Optional<String> findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir.trim(), windows ? name + ".exe" : name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate.toString());
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // entrada inválida no PATH: ignora
            }
        }
        return Optional.empty();
    }
}
