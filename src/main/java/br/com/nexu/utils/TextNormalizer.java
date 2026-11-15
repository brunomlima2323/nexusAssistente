package br.com.nexu.utils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalização de texto para comparação: minúsculas, sem acentos, sem pontuação,
 * com números separados de letras ("10min" vira "10 min").
 */
public final class TextNormalizer {

    public static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIGIT_LETTER = Pattern.compile("(?<=\\p{N})(?=\\p{L})|(?<=\\p{L})(?=\\p{N})");
    private static final Pattern NON_ASCII_ALNUM = Pattern.compile("[^a-z0-9]");

    /** Palavra original (minúscula, com acentos) e sua forma normalizada. */
    public record Token(String raw, String norm) {
    }

    private TextNormalizer() {
    }

    public static String stripAccents(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
    }

    /**
     * Quebra o texto em palavras mantendo, lado a lado, a forma original (com acentos)
     * e a forma normalizada. As duas listas sempre têm o mesmo tamanho.
     */
    public static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String lower = text.toLowerCase(PT_BR);
        for (String piece : SEPARATORS.split(lower)) {
            if (piece.isEmpty()) {
                continue;
            }
            for (String part : DIGIT_LETTER.split(piece)) {
                if (part.isEmpty()) {
                    continue;
                }
                String norm = NON_ASCII_ALNUM.matcher(stripAccents(part)).replaceAll("");
                if (!norm.isEmpty()) {
                    tokens.add(new Token(part, norm));
                }
            }
        }
        return tokens;
    }

    /** Lista de palavras normalizadas. */
    public static List<String> tokens(String text) {
        List<Token> tokens = tokenize(text);
        List<String> out = new ArrayList<>(tokens.size());
        for (Token t : tokens) {
            out.add(t.norm());
        }
        return out;
    }

    /** Texto normalizado em uma única linha. */
    public static String normalize(String text) {
        return String.join(" ", tokens(text));
    }

    public static String joinRaw(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token t : tokens) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(t.raw());
        }
        return sb.toString();
    }

    /** "rio de janeiro" -> "Rio de Janeiro". */
    public static String titleCase(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] words = text.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            boolean connector = i > 0 && switch (stripAccents(w)) {
                case "de", "do", "da", "dos", "das", "e" -> true;
                default -> false;
            };
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            if (connector || w.isEmpty()) {
                sb.append(w);
            } else {
                sb.append(w.substring(0, 1).toUpperCase(PT_BR)).append(w.substring(1));
            }
        }
        return sb.toString();
    }
}
