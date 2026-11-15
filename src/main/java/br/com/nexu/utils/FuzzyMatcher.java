package br.com.nexu.utils;

import java.util.Collection;
import java.util.List;

/**
 * Algoritmos de correspondência aproximada (Levenshtein e Jaro-Winkler) e helpers
 * para procurar palavras-chave tolerando pequenos erros de transcrição.
 */
public final class FuzzyMatcher {

    private FuzzyMatcher() {
    }

    public static int levenshtein(CharSequence a, CharSequence b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            current[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] tmp = previous;
            previous = current;
            current = tmp;
        }
        return previous[m];
    }

    /** Similaridade normalizada (0..1) baseada na distância de Levenshtein. */
    public static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(a, b) / max;
    }

    public static double jaroWinkler(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int la = a.length();
        int lb = b.length();
        if (la == 0 || lb == 0) {
            return 0.0;
        }
        int window = Math.max(0, Math.max(la, lb) / 2 - 1);
        boolean[] matchedA = new boolean[la];
        boolean[] matchedB = new boolean[lb];
        int matches = 0;
        for (int i = 0; i < la; i++) {
            int start = Math.max(0, i - window);
            int end = Math.min(i + window + 1, lb);
            for (int j = start; j < end; j++) {
                if (!matchedB[j] && a.charAt(i) == b.charAt(j)) {
                    matchedA[i] = true;
                    matchedB[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < la; i++) {
            if (matchedA[i]) {
                while (!matchedB[k]) {
                    k++;
                }
                if (a.charAt(i) != b.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / la + m / lb + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(la, lb)); i++) {
            if (a.charAt(i) == b.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }

    /**
     * Verifica se uma palavra transcrita corresponde a uma palavra-chave, tolerando
     * erros pequenos em palavras longas. Palavras curtas (até 4 letras) exigem igualdade,
     * pois a chance de confusão com outras palavras é grande.
     */
    public static boolean tokenMatches(String token, String keyword) {
        if (token.equals(keyword)) {
            return true;
        }
        int len = keyword.length();
        if (len <= 4 || token.isEmpty() || token.charAt(0) != keyword.charAt(0)) {
            return false;
        }
        int maxDistance = len >= 8 ? 2 : 1;
        if (Math.abs(token.length() - len) > maxDistance) {
            return false;
        }
        return levenshtein(token, keyword) <= maxDistance;
    }

    public static boolean containsAny(List<String> tokens, Collection<String> keywords) {
        return indexOfAny(tokens, keywords) >= 0;
    }

    public static int indexOfAny(List<String> tokens, Collection<String> keywords) {
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            for (String keyword : keywords) {
                if (tokenMatches(token, keyword)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Igualdade exata de palavra (sem tolerância), útil para palavras curtas. */
    public static boolean containsExact(List<String> tokens, Collection<String> words) {
        for (String token : tokens) {
            if (words.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** Procura uma expressão de várias palavras ("ate mais") em sequência. */
    public static boolean containsPhrase(List<String> tokens, String phrase) {
        String[] parts = phrase.split(" ");
        outer:
        for (int i = 0; i + parts.length <= tokens.size(); i++) {
            for (int j = 0; j < parts.length; j++) {
                if (!tokenMatches(tokens.get(i + j), parts[j])) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
