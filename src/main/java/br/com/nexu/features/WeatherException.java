package br.com.nexu.features;

/** Falha ao obter a previsão do tempo, com uma mensagem adequada para ser falada ao usuário. */
public final class WeatherException extends RuntimeException {

    private final String userMessage;

    public WeatherException(String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
    }

    public WeatherException(String userMessage) {
        this(userMessage, null);
    }

    public String getUserMessage() {
        return userMessage;
    }
}
