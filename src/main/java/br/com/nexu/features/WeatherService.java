package br.com.nexu.features;

import br.com.nexu.config.AppConfig;
import br.com.nexu.utils.TextNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Previsão do tempo pela Open-Meteo (gratuita, sem chave de API, dados de serviços
 * meteorológicos nacionais). Geocodificação de cidades pela API de geocoding da Open-Meteo
 * e localização atual por geolocalização de IP (ip-api.com, com ipapi.co como alternativa).
 * Todas as chamadas são assíncronas.
 */
public final class WeatherService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final String USER_AGENT = "Nexu/1.0 (assistente virtual desktop)";

    /** Localização resolvida. */
    public record GeoLocation(String name, double latitude, double longitude, boolean current) {
    }

    private record Cached<T>(T value, long createdNanos) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final ExecutorService executor;
    private final Duration requestTimeout;
    private final long cacheNanos;

    private final String forecastUrl;
    private final String geocodingUrl;
    private final String apiKey;
    private final String preferredCountry;
    private final String defaultCity;
    private final Double defaultLatitude;
    private final Double defaultLongitude;
    private final boolean ipLocationEnabled;
    private final String ipPrimaryUrl;
    private final String ipFallbackUrl;

    private final Map<String, Cached<GeoLocation>> locationCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<JsonNode>> forecastCache = new ConcurrentHashMap<>();

    public WeatherService(AppConfig config) {
        this.requestTimeout = Duration.ofSeconds(config.getInt("weather.http.timeout.seconds", 10));
        this.cacheNanos = Duration.ofMinutes(config.getInt("weather.cache.minutes", 10)).toNanos();
        this.forecastUrl = config.getString("weather.api.forecast.url", "https://api.open-meteo.com/v1/forecast");
        this.geocodingUrl = config.getString("weather.api.geocoding.url", "https://geocoding-api.open-meteo.com/v1/search");
        this.apiKey = config.getString("weather.api.key", "");
        this.preferredCountry = config.getString("weather.preferred.country", "BR").toUpperCase(Locale.ROOT);
        this.defaultCity = config.getString("weather.default.city", "");
        this.defaultLatitude = config.getOptionalDouble("weather.default.latitude");
        this.defaultLongitude = config.getOptionalDouble("weather.default.longitude");
        this.ipLocationEnabled = config.getBoolean("weather.ip.location.enabled", true);
        this.ipPrimaryUrl = config.getString("weather.ip.location.primary.url",
                "http://ip-api.com/json/?fields=status,message,city,lat,lon&lang=pt-BR");
        this.ipFallbackUrl = config.getString("weather.ip.location.fallback.url", "https://ipapi.co/json/");
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "nexu-http");
            t.setDaemon(true);
            return t;
        });
        this.http = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
    }

    /**
     * @param city      cidade mencionada pelo usuário ou {@code null} para a localização atual
     * @param dayOffset 0 (hoje) a 2 (depois de amanhã)
     */
    public CompletableFuture<WeatherReport> fetch(String city, int dayOffset) {
        int day = Math.max(0, Math.min(2, dayOffset));
        return resolveLocation(city)
                .thenCompose(location -> forecast(location).thenApply(json -> toReport(location, json, day)));
    }

    // ------------------------------------------------------------------ localização

    private CompletableFuture<GeoLocation> resolveLocation(String city) {
        if (city != null && !city.isBlank()) {
            return geocode(city, false);
        }
        if (defaultLatitude != null && defaultLongitude != null) {
            String name = defaultCity.isBlank() ? "sua região" : TextNormalizer.titleCase(defaultCity);
            return CompletableFuture.completedFuture(new GeoLocation(name, defaultLatitude, defaultLongitude, true));
        }
        if (!defaultCity.isBlank()) {
            return geocode(defaultCity, true);
        }
        if (!ipLocationEnabled) {
            return CompletableFuture.failedFuture(new WeatherException(
                    "Senhor, nenhuma cidade padrão está configurada. Por favor, informe a cidade desejada."));
        }
        return currentLocationByIp();
    }

    private CompletableFuture<GeoLocation> geocode(String city, boolean current) {
        String key = "geo:" + TextNormalizer.normalize(city);
        GeoLocation cached = fromCache(locationCache, key);
        if (cached != null) {
            return CompletableFuture.completedFuture(new GeoLocation(cached.name(), cached.latitude(), cached.longitude(), current));
        }
        String url = geocodingUrl + "?name=" + encode(city) + "&count=10&language=pt&format=json";
        return getJson(url).thenApply(json -> {
            JsonNode results = json.path("results");
            if (!results.isArray() || results.isEmpty()) {
                throw new WeatherException("Senhor, não encontrei a cidade " + city + ".");
            }
            List<JsonNode> list = new ArrayList<>();
            results.forEach(list::add);
            JsonNode best = list.stream()
                    .min(Comparator
                            .comparing((JsonNode n) -> !preferredCountry.equalsIgnoreCase(n.path("country_code").asText("")))
                            .thenComparing(n -> -n.path("population").asLong(0)))
                    .orElseThrow();
            GeoLocation location = new GeoLocation(best.path("name").asText(TextNormalizer.titleCase(city)),
                    best.path("latitude").asDouble(), best.path("longitude").asDouble(), current);
            locationCache.put(key, new Cached<>(location, System.nanoTime()));
            return location;
        });
    }

    private CompletableFuture<GeoLocation> currentLocationByIp() {
        GeoLocation cached = fromCache(locationCache, "ip");
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return getJson(ipPrimaryUrl)
                .thenApply(json -> {
                    if (!"success".equalsIgnoreCase(json.path("status").asText())) {
                        throw new WeatherException("Localização por IP falhou: " + json.path("message").asText());
                    }
                    return new GeoLocation(json.path("city").asText("sua região"),
                            json.path("lat").asDouble(), json.path("lon").asDouble(), true);
                })
                .exceptionallyCompose(primaryError -> {
                    log.info("Serviço principal de localização falhou ({}), tentando alternativo", rootMessage(primaryError));
                    return getJson(ipFallbackUrl).thenApply(json -> {
                        if (json.path("error").asBoolean(false) || !json.has("latitude")) {
                            throw new WeatherException("Localização por IP alternativa falhou");
                        }
                        return new GeoLocation(json.path("city").asText("sua região"),
                                json.path("latitude").asDouble(), json.path("longitude").asDouble(), true);
                    });
                })
                .handle((location, error) -> {
                    if (error != null) {
                        throw new CompletionException(new WeatherException(
                                "Senhor, não consegui identificar sua localização atual. "
                                        + "Configure uma cidade padrão ou informe a cidade desejada.", unwrap(error)));
                    }
                    locationCache.put("ip", new Cached<>(location, System.nanoTime()));
                    return location;
                });
    }

    // ------------------------------------------------------------------ previsão

    private CompletableFuture<JsonNode> forecast(GeoLocation location) {
        String key = String.format(Locale.ROOT, "fc:%.3f:%.3f", location.latitude(), location.longitude());
        JsonNode cached = fromCache(forecastCache, key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        String url = String.format(Locale.ROOT,
                "%s?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code"
                        + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code"
                        + "&timezone=auto&forecast_days=3",
                forecastUrl, location.latitude(), location.longitude());
        if (!apiKey.isBlank()) {
            url += "&apikey=" + encode(apiKey);
        }
        return getJson(url).thenApply(json -> {
            if (json.path("error").asBoolean(false)) {
                throw new WeatherException("Senhor, o serviço meteorológico recusou a consulta.",
                        new IOException(json.path("reason").asText()));
            }
            forecastCache.put(key, new Cached<>(json, System.nanoTime()));
            return json;
        });
    }

    static WeatherReport toReport(GeoLocation location, JsonNode json, int day) {
        JsonNode current = json.path("current");
        JsonNode daily = json.path("daily");
        Double temperature = day == 0 ? number(current.path("temperature_2m")) : null;
        Double apparent = day == 0 ? number(current.path("apparent_temperature")) : null;
        Double humidityValue = day == 0 ? number(current.path("relative_humidity_2m")) : null;
        Integer humidity = humidityValue == null ? null : (int) Math.round(humidityValue);
        Double max = number(daily.path("temperature_2m_max").path(day));
        Double min = number(daily.path("temperature_2m_min").path(day));
        Double rain = number(daily.path("precipitation_probability_max").path(day));
        Double code = day == 0 ? number(current.path("weather_code")) : null;
        if (code == null) {
            code = number(daily.path("weather_code").path(day));
        }
        if (temperature == null && max == null && min == null) {
            throw new WeatherException("Senhor, o serviço meteorológico não retornou dados para essa localização.");
        }
        return new WeatherReport(location.name(), location.current(), day, temperature, apparent, humidity,
                max, min, rain == null ? null : (int) Math.round(rain), code == null ? null : code.intValue());
    }

    private static Double number(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    // ------------------------------------------------------------------ HTTP

    private CompletableFuture<JsonNode> getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> {
                    if (error != null) {
                        throw new CompletionException(translateNetworkError(unwrap(error)));
                    }
                    int status = response.statusCode();
                    if (status == 429) {
                        throw new WeatherException("Senhor, o limite de consultas ao serviço meteorológico foi atingido. "
                                + "Tente novamente em alguns minutos.");
                    }
                    if (status >= 500) {
                        throw new WeatherException("Senhor, o serviço meteorológico está indisponível no momento.",
                                new IOException("HTTP " + status + " em " + hideKey(url)));
                    }
                    try {
                        JsonNode json = mapper.readTree(response.body());
                        if (status >= 400 && !json.path("error").asBoolean(false)) {
                            throw new WeatherException("Senhor, o serviço meteorológico recusou a consulta.",
                                    new IOException("HTTP " + status));
                        }
                        return json;
                    } catch (IOException e) {
                        throw new WeatherException("Senhor, recebi uma resposta inválida do serviço meteorológico.", e);
                    }
                });
    }

    private static WeatherException translateNetworkError(Throwable error) {
        if (error instanceof WeatherException weather) {
            return weather;
        }
        if (error instanceof HttpConnectTimeoutException || error instanceof ConnectException
                || error instanceof java.nio.channels.UnresolvedAddressException
                || error instanceof java.net.UnknownHostException) {
            return new WeatherException("Senhor, não consegui me conectar ao serviço meteorológico. "
                    + "Verifique sua conexão com a internet.", error);
        }
        if (error instanceof HttpTimeoutException) {
            return new WeatherException("Senhor, o serviço meteorológico demorou demais para responder.", error);
        }
        return new WeatherException("Senhor, ocorreu uma falha de comunicação com o serviço meteorológico.", error);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String rootMessage(Throwable error) {
        Throwable t = unwrap(error);
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private <T> T fromCache(Map<String, Cached<T>> cache, String key) {
        Cached<T> entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.nanoTime() - entry.createdNanos() > cacheNanos) {
            cache.remove(key);
            return null;
        }
        return entry.value();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String hideKey(String url) {
        return url.replaceAll("apikey=[^&]+", "apikey=***");
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
