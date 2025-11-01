import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final String DEFAULT_CONFIG = "config.toml";

    public static void main(String[] args) {
        String configPath = DEFAULT_CONFIG;
        if (args.length > 0 && args[0].startsWith("--config")) {
            configPath = args[0].split("=")[1];
        }

        try {
            Map<String, String> config = parseToml(configPath);

            String[] requiredKeys = {"package", "url_or_path", "test_mode", "version", "filter_substring"};
            for (String key : requiredKeys) {
                if (!config.containsKey(key)) {
                    throw new IllegalArgumentException("Отсутствует обязательный параметр '" + key + "'.");
                }
                if (config.get(key).trim().isEmpty()) {
                    throw new IllegalArgumentException("Параметр '" + key + "' не может быть пустым.");
                }
            }

            String testModeStr = config.get("test_mode").toLowerCase();
            if (!testModeStr.equals("true") && !testModeStr.equals("false")) {
                throw new IllegalArgumentException("Параметр 'test_mode' должен быть true или false.");
            }
            boolean testMode = Boolean.parseBoolean(testModeStr);

            System.out.println("package: " + config.get("package"));
            System.out.println("url_or_path: " + config.get("url_or_path"));
            System.out.println("test_mode: " + testMode);
            System.out.println("version: " + config.get("version"));
            System.out.println("filter_substring: " + config.get("filter_substring"));


        } catch (IOException e) {
            System.err.println("Ошибка: Конфигурационный файл не найден или не может быть прочитан: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Map<String, String> parseToml(String path) throws IOException {
        Map<String, String> config = new HashMap<>();
        Pattern pattern = Pattern.compile("^(\\w+)\\s*=\\s*(.+)$");

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                Matcher matcher = pattern.matcher(line);
                if (matcher.matches()) {
                    String key = matcher.group(1).trim();
                    String value = matcher.group(2).trim();
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    config.put(key, value);
                } else {
                    throw new IllegalArgumentException("Неверный формат строки в TOML: " + line);
                }
            }
        }
        return config;
    }
}
