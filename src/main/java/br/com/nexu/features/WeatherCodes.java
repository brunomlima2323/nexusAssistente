package br.com.nexu.features;

/** Descrições em português para os códigos meteorológicos WMO usados pela Open-Meteo. */
public final class WeatherCodes {

    private WeatherCodes() {
    }

    /** Retorna {@code null} para códigos desconhecidos (a condição é omitida da fala). */
    public static String describe(Integer code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case 0 -> "céu limpo";
            case 1 -> "céu predominantemente limpo";
            case 2 -> "céu parcialmente nublado";
            case 3 -> "céu encoberto";
            case 45, 48 -> "nevoeiro";
            case 51 -> "garoa fraca";
            case 53 -> "garoa moderada";
            case 55 -> "garoa intensa";
            case 56, 57 -> "garoa congelante";
            case 61 -> "chuva fraca";
            case 63 -> "chuva moderada";
            case 65 -> "chuva forte";
            case 66, 67 -> "chuva congelante";
            case 71 -> "neve fraca";
            case 73 -> "neve moderada";
            case 75 -> "neve forte";
            case 77 -> "grãos de neve";
            case 80 -> "pancadas de chuva fracas";
            case 81 -> "pancadas de chuva moderadas";
            case 82 -> "pancadas de chuva fortes";
            case 85, 86 -> "pancadas de neve";
            case 95 -> "trovoadas";
            case 96, 99 -> "trovoadas com granizo";
            default -> null;
        };
    }
}
