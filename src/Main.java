import java.io.*;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.ArrayList;
import java.util.List;

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

            String packageName = config.get("package");
            String version = config.get("version");
            String urlOrPath = config.get("url_or_path");
            String filterSubstring = config.get("filter_substring");








            if (testMode) {
                System.out.println("Режим тестирования: Логика для тестового файла будет в Этапе 3. Для Этапа 2 используйте test_mode = false.");
                return;
            }


            String dist = "noble";
            String component = "main";
            String arch = "amd64";
            String packagesUrl = urlOrPath.replaceAll("/+$", "") + "/dists/" + dist + "/" + component + "/binary-" + arch + "/Packages.gz";


            String content = downloadAndUnzip(packagesUrl);


            List<String> directDeps = parseDirectDependencies(content, packageName, version, filterSubstring);

            if (directDeps.isEmpty()) {
                throw new IllegalArgumentException("Пакет '" + packageName + "' версии '" + version + "' не найден или нет зависимостей.");
            }


            System.out.println("Прямые зависимости пакета " + packageName + "-" + version + ":");
            for (String dep : directDeps) {
                System.out.println("- " + dep);
            }

        } catch (IOException e) {
            System.err.println("Ошибка: Проблема с файлом или сетью: " + e.getMessage());
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
                    value = value.split("#")[0].trim();
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


    private static String downloadAndUnzip(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        try (InputStream is = url.openStream();
             GZIPInputStream gis = new GZIPInputStream(is);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gis))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        }
    }


    private static List<String> parseDirectDependencies(String content, String pkg, String ver, String filterSub) {
        String[] stanzas = content.split("\n\n");
        for (String stanza : stanzas) {
            if (stanza.contains("Package: " + pkg) && stanza.contains("Version: " + ver)) {
                String[] lines = stanza.split("\n");
                String dependsStr = "";
                boolean inDepends = false;
                for (String line : lines) {
                    if (line.startsWith("Depends:")) {
                        inDepends = true;
                        dependsStr = line.substring("Depends:".length()).trim();
                    } else if (inDepends && line.startsWith(" ")) {
                        dependsStr += " " + line.trim();
                    } else if (inDepends) {
                        break;
                    }
                }
                if (!dependsStr.isEmpty()) {
                    List<String> deps = new ArrayList<>();
                    String[] items = dependsStr.split(",\\s*");
                    for (String item : items) {
                        String[] alts = item.split("\\s*\\|\\s*");
                        for (String alt : alts) {
                            String depName = alt.replaceAll("\\s*\\(.*\\)", "").trim();
                            if (!depName.isEmpty() && !depName.contains(filterSub)) {
                                deps.add(depName);
                            }
                        }
                    }
                    return deps;
                }
            }
        }
        return new ArrayList<>();
    }
}