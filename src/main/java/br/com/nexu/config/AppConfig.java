package br.com.nexu.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Configuração da aplicação, carregada em camadas (a última sobrescreve as anteriores):
 * <ol>
 *     <li>{@code application.properties} do classpath (valores padrão);</li>
 *     <li>{@code ~/.nexu/application.properties};</li>
 *     <li>{@code ./nexu.properties};</li>
 *     <li>arquivo indicado por {@code -Dnexu.config=...};</li>
 *     <li>variáveis de ambiente {@code NEXU_CHAVE_COM_UNDERSCORE};</li>
 *     <li>propriedades de sistema {@code -Dnexu.chave=valor}.</li>
 * </ol>
 * Chaves de API nunca precisam ficar no código-fonte: use variáveis de ambiente ou arquivos externos.
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static final String ENV_PREFIX = "NEXU_";
    private static final String SYS_PREFIX = "nexu.";

    private final Properties properties;

    private AppConfig(Properties properties) {
        this.properties = properties;
    }

    /** Cria uma configuração a partir de propriedades já prontas (útil para testes). */
    public static AppConfig of(Properties properties) {
        Properties copy = new Properties();
        copy.putAll(properties);
        return new AppConfig(copy);
    }

    public static AppConfig load() {
        Properties props = new Properties();
        loadClasspath(props, "/application.properties");
        loadFile(props, Path.of(System.getProperty("user.home"), ".nexu", "application.properties"));
        loadFile(props, Path.of("nexu.properties"));
        String custom = System.getProperty("nexu.config");
        if (custom != null && !custom.isBlank()) {
            loadFile(props, Path.of(custom));
        }
        applyEnvironment(props, System.getenv());
        applySystemProperties(props);
        return new AppConfig(props);
    }

    private static void loadClasspath(Properties target, String resource) {
        try (InputStream in = AppConfig.class.getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("Recurso de configuração {} não encontrado no classpath", resource);
                return;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                target.load(reader);
            }
        } catch (IOException e) {
            log.warn("Falha ao ler {}: {}", resource, e.getMessage());
        }
    }

    private static void loadFile(Properties target, Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Properties layer = new Properties();
            layer.load(reader);
            target.putAll(layer);
            log.info("Configuração carregada de {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Falha ao ler configuração {}: {}", path, e.getMessage());
        }
    }

    static void applyEnvironment(Properties target, Map<String, String> env) {
        env.forEach((name, value) -> {
            if (name.startsWith(ENV_PREFIX) && name.length() > ENV_PREFIX.length()) {
                String key = name.substring(ENV_PREFIX.length()).toLowerCase(Locale.ROOT).replace('_', '.');
                target.setProperty(key, value);
            }
        });
    }

    private static void applySystemProperties(Properties target) {
        System.getProperties().forEach((k, v) -> {
            String key = String.valueOf(k);
            if (key.startsWith(SYS_PREFIX) && !key.equals("nexu.config")) {
                target.setProperty(key.substring(SYS_PREFIX.length()), String.valueOf(v));
            }
        });
    }

    public String getString(String key, String defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        value = value.trim();
        return value.isEmpty() ? defaultValue : value;
    }

    public String getString(String key) {
        return getString(key, "");
    }

    public int getInt(String key, int defaultValue) {
        String value = getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Valor inválido para {}: '{}'. Usando {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String value = getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Valor inválido para {}: '{}'. Usando {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        String value = getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (NumberFormatException e) {
            log.warn("Valor inválido para {}: '{}'. Usando {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /** Retorna {@code null} quando a chave não existe ou não é um número. */
    public Double getOptionalDouble(String key) {
        String value = getString(key, null);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (NumberFormatException e) {
            log.warn("Valor inválido para {}: '{}'", key, value);
            return null;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "sim", "yes", "1", "on" -> true;
            case "false", "nao", "não", "no", "0", "off" -> false;
            default -> defaultValue;
        };
    }

    /** Lista separada por vírgulas, sem itens vazios. */
    public List<String> getList(String key) {
        String value = getString(key, null);
        if (value == null) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(items::add);
        return List.copyOf(items);
    }
}
