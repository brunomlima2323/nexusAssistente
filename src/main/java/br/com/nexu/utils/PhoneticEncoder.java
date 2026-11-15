package br.com.nexu.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Codificação fonética simplificada para o português brasileiro.
 * <p>
 * A ideia é reduzir palavras que <em>soam</em> parecidas à mesma chave:
 * "nexu", "nexo", "nexus", "nessu" e "neksu" viram todas {@code "nesu"}.
 * Regras principais: x/ks/cs/ss/z/ç viram "s", "ch" vira "x" (e depois "s"),
 * "o" final soa como "u", "e" final soa como "i", letras repetidas colapsam
 * e o "s" final de plural é descartado.
 */
public final class PhoneticEncoder {

    private static final Pattern NON_LETTER = Pattern.compile("[^a-z]");
    private static final Pattern KS_SOUND = Pattern.compile("cs|ks|xs|kz|cz|xc");
    private static final Pattern SC_SOFT = Pattern.compile("sc(?=[eiy])");
    private static final Pattern C_SOFT = Pattern.compile("c(?=[eiy])");
    private static final Pattern GU_SOFT = Pattern.compile("gu(?=[ei])");
    private static final Pattern REPEATED = Pattern.compile("(.)\\1+");

    private PhoneticEncoder() {
    }

    public static String encode(String word) {
        if (word == null || word.isBlank()) {
            return "";
        }
        String w = word.toLowerCase(Locale.ROOT).replace('ç', 's');
        w = NON_LETTER.matcher(TextNormalizer.stripAccents(w)).replaceAll("");
        if (w.isEmpty()) {
            return "";
        }
        w = w.replace("ch", "x").replace("sh", "x").replace("lh", "l").replace("nh", "n").replace("ph", "f");
        w = w.replace("qu", "k");
        w = GU_SOFT.matcher(w).replaceAll("g");
        w = KS_SOUND.matcher(w).replaceAll("s");
        w = SC_SOFT.matcher(w).replaceAll("s");
        w = C_SOFT.matcher(w).replaceAll("s");
        w = w.replace('c', 'k')
                .replace('x', 's')
                .replace('z', 's')
                .replace('w', 'v')
                .replace('y', 'i')
                .replace("h", "");
        if (w.length() > 3 && w.endsWith("s")) {
            w = w.substring(0, w.length() - 1);
        }
        if (w.length() > 1 && w.endsWith("o")) {
            w = w.substring(0, w.length() - 1) + "u";
        } else if (w.length() > 1 && w.endsWith("e")) {
            w = w.substring(0, w.length() - 1) + "i";
        }
        return REPEATED.matcher(w).replaceAll("$1");
    }
}
