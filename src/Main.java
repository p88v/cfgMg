import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.zip.GZIPInputStream;

public class Main {
    private static final String DEFAULT_CONFIG = "config.toml";

    public static void main(String[] args) {
        String configPath = DEFAULT_CONFIG;
        if (args.length > 0 && args[0].startsWith("--config")) {
            configPath = args[0].split("=")[1];
        }

        try {
            Map<String, String> config = parseToml(configPath);

            String[] requiredKeys = {"package", "url_or_path", "test_mode", "filter_substring"};
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
            String version = config.getOrDefault("version", "");
            String urlOrPath = config.get("url_or_path");
            String filterSubstring = config.get("filter_substring");

            if (!testMode && version.isEmpty()) {
                throw new IllegalArgumentException("Параметр 'version' не может быть пустым для реального режима.");
            }

            Map<String, List<String>> graph;
            String root;
            if (testMode) {
                graph = parseTestGraph(urlOrPath);
                root = packageName;
            } else {
                String dist = "noble";
                String component = "main";
                String arch = "amd64";
                String packagesUrl = urlOrPath.replaceAll("/+$", "") + "/dists/" + dist + "/" + component + "/binary-" + arch + "/Packages.gz";
                String content = downloadAndUnzip(packagesUrl);
                graph = parseRealGraph(content, filterSubstring);
                root = packageName + "-" + version;
            }

            if (!graph.containsKey(root)) {
                throw new IllegalArgumentException("Корневой пакет '" + root + "' не найден.");
            }

            Map<String, List<String>> transitiveGraph = buildTransitiveGraph(root, graph, filterSubstring);

            System.out.println("Транзитивный граф зависимостей от " + root + ":");
            for (Map.Entry<String, List<String>> entry : transitiveGraph.entrySet()) {
                System.out.println(entry.getKey() + " -> " + entry.getValue());
            }

        } catch (IOException e) {
            System.err.println("Ошибка: Проблема с файлом или сетью: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        } catch (CycleDetectedException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    private static class CycleDetectedException extends Exception {
        public CycleDetectedException(String message) {
            super("Обнаружен цикл в графе зависимостей: " + message);
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

    private static Map<String, List<String>> parseRealGraph(String content, String filterSub) {
        Map<String, List<String>> graph = new HashMap<>();
        String[] stanzas = content.split("\n\n");
        for (String stanza : stanzas) {
            String pkg = extractField(stanza, "Package");
            String ver = extractField(stanza, "Version");
            if (pkg == null || ver == null || pkg.contains(filterSub)) continue;
            String node = pkg + "-" + ver;
            String dependsStr = extractMultiLineField(stanza, "Depends");
            if (dependsStr != null) {
                List<String> deps = parseDepends(dependsStr, filterSub);
                graph.put(node, deps);
            } else {
                graph.put(node, new ArrayList<>());
            }
        }
        return graph;
    }

    private static Map<String, List<String>> parseTestGraph(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) {
            throw new IOException("Тестовый файл не найден: " + path);
        }
        Map<String, List<String>> graph = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(":")) {
                    String[] parts = line.split(":", 2);
                    String node = parts[0].trim();
                    String depsStr = parts[1].trim();
                    List<String> deps = new ArrayList<>();
                    if (!depsStr.isEmpty()) {
                        deps = Arrays.asList(depsStr.split("\\s+"));
                    }
                    graph.put(node, deps);
                }
            }
        }
        return graph;
    }

    private static Map<String, List<String>> buildTransitiveGraph(String root, Map<String, List<String>> fullGraph, String filterSub) throws CycleDetectedException {
        Map<String, List<String>> transitive = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Set<String> inQueue = new HashSet<>(); // Для отслеживания узлов, которые уже в очереди
        Queue<String> queue = new LinkedList<>();

        queue.add(root);
        inQueue.add(root);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            inQueue.remove(current);

            // Если узел уже обработан, пропускаем
            if (visited.contains(current)) {
                continue;
            }

            visited.add(current);

            // Если пакет не найден в графе, добавляем его без зависимостей
            if (!fullGraph.containsKey(current)) {
                transitive.put(current, new ArrayList<>());
                continue;
            }

            if (!current.contains(filterSub)) {
                List<String> deps = fullGraph.get(current);
                List<String> filteredDeps = new ArrayList<>();

                // Фильтруем зависимости по подстроке
                for (String dep : deps) {
                    if (!dep.contains(filterSub)) {
                        filteredDeps.add(dep);
                    }
                }

                transitive.put(current, filteredDeps);

                // Обрабатываем зависимости
                for (String dep : filteredDeps) {
                    // Проверяем цикл: если зависимость уже посещена И находится в текущем пути
                    if (visited.contains(dep) && inQueue.contains(dep)) {
                        throw new CycleDetectedException("зависимость " + current + " -> " + dep);
                    }

                    // Добавляем в очередь только если еще не посещали и не в очереди
                    if (!visited.contains(dep) && !inQueue.contains(dep)) {
                        queue.add(dep);
                        inQueue.add(dep);
                    }
                }
            }
        }
        return transitive;
    }

    private static String extractField(String stanza, String field) {
        Pattern pattern = Pattern.compile("^" + field + ":\\s*(.*)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(stanza);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private static String extractMultiLineField(String stanza, String field) {
        String[] lines = stanza.split("\n");
        StringBuilder value = new StringBuilder();
        boolean inField = false;

        for (String line : lines) {
            if (line.startsWith(field + ":")) {
                inField = true;
                value.append(line.substring(field.length() + 1).trim());
            } else if (inField && line.startsWith(" ")) {
                // Многострочное поле - продолжается с пробела
                value.append(" ").append(line.trim());
            } else if (inField) {
                // Конец многострочного поля
                break;
            }
        }

        return inField ? value.toString() : null;
    }

    private static List<String> parseDepends(String dependsStr, String filterSub) {
        List<String> deps = new ArrayList<>();
        if (dependsStr == null || dependsStr.trim().isEmpty()) {
            return deps;
        }

        // Разделяем по запятым, но учитываем, что могут быть альтернативы через |
        String[] items = dependsStr.split(",\\s*");
        for (String item : items) {
            // Обрабатываем альтернативы (через |)
            String[] alts = item.split("\\s*\\|\\s*");
            if (alts.length > 0) {
                // Берем первую альтернативу (самый строгий вариант)
                String dep = alts[0].trim();
                // Удаляем информацию о версии в скобках
                dep = dep.replaceAll("\\s*\\([^)]*\\)", "").trim();
                if (!dep.isEmpty() && !dep.contains(filterSub)) {
                    deps.add(dep);
                }
            }
        }
        return deps;
    }
}