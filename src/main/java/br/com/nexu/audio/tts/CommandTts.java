package br.com.nexu.audio.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * Motor de voz externo configurável ({@code tts.command}), por exemplo um script que use o Piper.
 * {@code {text}} é substituído pelo texto; sem esse marcador o texto vai pela entrada padrão.
 */
public final class CommandTts extends ProcessTts {

    private static final String PLACEHOLDER = "{text}";

    private final List<String> template;
    private final boolean usesPlaceholder;

    public CommandTts(String commandLine) {
        this.template = split(commandLine);
        if (template.isEmpty()) {
            throw new IllegalArgumentException("tts.command está vazio");
        }
        this.usesPlaceholder = template.stream().anyMatch(arg -> arg.contains(PLACEHOLDER));
    }

    @Override
    public String name() {
        return "Comando externo (" + template.get(0) + ")";
    }

    @Override
    protected List<String> command(String text) {
        List<String> cmd = new ArrayList<>(template.size());
        for (String arg : template) {
            cmd.add(arg.replace(PLACEHOLDER, text));
        }
        return cmd;
    }

    @Override
    protected boolean textViaStdin() {
        return !usesPlaceholder;
    }

    /** Divide a linha de comando respeitando aspas simples e duplas. */
    static List<String> split(String commandLine) {
        List<String> args = new ArrayList<>();
        if (commandLine == null) {
            return args;
        }
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inArg = false;
        for (char c : commandLine.toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inArg = true;
            } else if (Character.isWhitespace(c)) {
                if (inArg) {
                    args.add(current.toString());
                    current.setLength(0);
                    inArg = false;
                }
            } else {
                current.append(c);
                inArg = true;
            }
        }
        if (inArg) {
            args.add(current.toString());
        }
        return args;
    }
}
