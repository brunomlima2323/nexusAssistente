package br.com.nexu.features;

import br.com.nexu.utils.SpeechFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Dados meteorológicos reais retornados pela API. Campos ausentes ficam {@code null}
 * e são simplesmente omitidos da fala — nada é inventado.
 *
 * @param locationName       nome da cidade
 * @param currentLocation    {@code true} se é a localização atual do usuário
 * @param dayOffset          0 = hoje, 1 = amanhã, 2 = depois de amanhã
 * @param temperature        temperatura atual (°C) — apenas para hoje
 * @param apparentTemperature sensação térmica (°C) — apenas para hoje
 * @param humidity           umidade relativa (%) — apenas para hoje
 * @param max                máxima do dia (°C)
 * @param min                mínima do dia (°C)
 * @param rainProbability    probabilidade máxima de chuva no dia (%)
 * @param weatherCode        código WMO da condição
 */
public record WeatherReport(String locationName, boolean currentLocation, int dayOffset,
                            Double temperature, Double apparentTemperature, Integer humidity,
                            Double max, Double min, Integer rainProbability, Integer weatherCode) {

    public String condition() {
        return WeatherCodes.describe(weatherCode);
    }

    public String toSpeech() {
        List<String> sentences = new ArrayList<>();
        if (dayOffset == 0) {
            StringBuilder intro = new StringBuilder("Senhor, ");
            intro.append(currentLocation ? "neste momento temos " : "em " + locationName + " temos atualmente ");
            if (temperature != null) {
                intro.append(SpeechFormatter.degrees(temperature));
                if (apparentTemperature != null) {
                    intro.append(", com sensação térmica de ").append(SpeechFormatter.degrees(apparentTemperature));
                }
            } else {
                intro.setLength(0);
                intro.append("Senhor, ").append(currentLocation ? "" : "em " + locationName + ", ")
                        .append("a temperatura atual não está disponível");
            }
            sentences.add(intro.append('.').toString());
            if (humidity != null) {
                sentences.add("A umidade está em " + humidity + "%.");
            }
        } else {
            String when = dayOffset == 1 ? "amanhã" : "depois de amanhã";
            sentences.add("Senhor, esta é a previsão para " + when + (currentLocation ? "" : " em " + locationName) + ".");
        }

        if (max != null && min != null) {
            sentences.add("A máxima prevista é de " + SpeechFormatter.degrees(max)
                    + " e a mínima de " + Math.round(min) + ".");
        } else if (max != null) {
            sentences.add("A máxima prevista é de " + SpeechFormatter.degrees(max) + ".");
        } else if (min != null) {
            sentences.add("A mínima prevista é de " + SpeechFormatter.degrees(min) + ".");
        }
        if (rainProbability != null) {
            sentences.add("A probabilidade de chuva é de " + rainProbability + "%.");
        }
        String condition = condition();
        if (condition != null) {
            sentences.add((dayOffset == 0 ? "As condições são de " : "As condições previstas são de ") + condition + ".");
        }
        return String.join(" ", sentences);
    }
}
