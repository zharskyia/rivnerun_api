import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Перевіряє scrape-config.json, щоб Android- та iOS-застосунок прочитали його однаково:
 * файл — строгий JSON, у ньому є всі секції й поля і немає зайвих, кожен патерн компілюється
 * й містить стільки груп захоплення, скільки з нього читають парсери.
 *
 * Запуск (Java 17 або новіша):
 *   java "-Dfile.encoding=UTF-8" .github/scripts/ValidateScrapeConfig.java scrape-config.json
 */
public class ValidateScrapeConfig {
    /**
     * Секції, їхні поля й кількість груп захоплення, яку з поля читають парсери. Має збігатися
     * з ScrapeConfig.validationError() у rivnerun.club_android (ScrapeConfig.kt): додаючи або
     * змінюючи поле в застосунках, оновіть і цей список.
     */
    private static final Map<String, Map<String, Integer>> FIELDS = new LinkedHashMap<>();

    static {
        field("home", "descriptionMeta", 1);
        field("article", "articleTag", 1);
        field("article", "bodyDivMarker", 0);
        field("article", "image", 1);
        field("news", "articleLink", 2);
        field("news", "image", 1);
        field("news", "title", 1);
        field("news", "date", 1);
        field("training", "table", 1);
        field("training", "row", 1);
        field("training", "cell", 1);
        field("training", "firstParagraph", 1);
        field("training", "date", 1);
        field("training", "time", 1);
        field("training", "removeStrong", 0);
        field("training", "removeSpan", 0);
        field("training", "paragraph", 1);
        field("races", "listDivMarker", 0);
        field("races", "link", 2);
        field("races", "titleStrong", 1);
        field("races", "titleHeading", 1);
        field("races", "date", 1);
        field("products", "link", 2);
        field("products", "image", 1);
        field("products", "title", 1);
        field("products", "price", 1);
        field("records", "tabsList", 1);
        field("records", "tabTitle", 1);
        field("records", "tabsContentMarker", 0);
        field("records", "paragraph", 1);
        field("partners", "listDivMarker", 0);
        field("partners", "link", 2);
        field("partners", "image", 1);
    }

    private static void field(String section, String name, int requiredGroups) {
        FIELDS.computeIfAbsent(section, key -> new LinkedHashMap<>()).put(name, requiredGroups);
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        String file = args.length > 0 ? args[0] : "scrape-config.json";
        Report report = new Report(file, out, "true".equals(System.getenv("GITHUB_ACTIONS")));
        int patterns = 0;
        try {
            byte[] bytes = Files.readAllBytes(Path.of(file));
            report.text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            patterns = check(report.text, report);
        } catch (CharacterCodingException error) {
            report.error("файл має бути збережено в кодуванні UTF-8");
        } catch (NoSuchFileException error) {
            report.error("файл не знайдено");
        } catch (IOException error) {
            report.error("не вдалося прочитати файл (" + error + ")");
        }

        if (report.errors > 0) {
            out.println("Знайдено помилок: " + report.errors
                + ". Поки їх не виправлено, застосунки можуть розбирати сайт не за правилами з цього файлу.");
            System.exit(1);
        }
        out.println(file + ": помилок немає, перевірено патернів: " + patterns + ".");
    }

    /** Перевіряє текст конфіга й повертає кількість перевірених патернів. */
    private static int check(String text, Report report) {
        int start = 0;
        if (text.startsWith("﻿")) {
            report.error(0, "файл починається з BOM (U+FEFF): збережіть його як UTF-8 без BOM");
            start = 1;
        }
        Node root;
        try {
            root = new JsonParser(text, start, report).parseDocument();
        } catch (JsonException error) {
            report.error(error.position, "некоректний JSON: " + error.getMessage());
            return 0;
        }
        if (!(root.value() instanceof JsonObject config)) {
            report.error(root.position(), "конфіг має бути JSON-об'єктом { ... }");
            return 0;
        }

        Node version = config.members().get("version");
        if (version == null) {
            report.error(root.position(), "немає поля «version»");
        } else if (!(version.value() instanceof JsonNumber number) || !number.isInt()) {
            report.error(version.position(), "version: потрібне ціле число, а знайдено " + describeValue(version));
        }
        for (Map.Entry<String, Node> entry : config.members().entrySet()) {
            if (!entry.getKey().equals("version") && !FIELDS.containsKey(entry.getKey())) {
                report.error(entry.getValue().position(),
                    "невідома секція «" + entry.getKey() + "»: застосунки її ігнорують");
            }
        }

        int patterns = 0;
        for (Map.Entry<String, Map<String, Integer>> section : FIELDS.entrySet()) {
            String sectionName = section.getKey();
            Node sectionNode = config.members().get(sectionName);
            if (sectionNode == null) {
                report.error(root.position(), "немає секції «" + sectionName + "»");
                continue;
            }
            if (!(sectionNode.value() instanceof JsonObject fields)) {
                report.error(sectionNode.position(),
                    sectionName + ": потрібен об'єкт із полями, а знайдено " + describeValue(sectionNode));
                continue;
            }
            for (Map.Entry<String, Node> entry : fields.members().entrySet()) {
                if (!section.getValue().containsKey(entry.getKey())) {
                    report.error(entry.getValue().position(),
                        "невідоме поле «" + sectionName + "." + entry.getKey() + "»: застосунки його ігнорують");
                }
            }
            for (Map.Entry<String, Integer> field : section.getValue().entrySet()) {
                String name = sectionName + "." + field.getKey();
                Node value = fields.members().get(field.getKey());
                if (value == null) {
                    report.error(sectionNode.position(), "немає поля «" + name + "»");
                } else if (!(value.value() instanceof String pattern)) {
                    report.error(value.position(),
                        name + ": потрібен рядок з регулярним виразом, а знайдено " + describeValue(value));
                } else {
                    patterns++;
                    String problem = patternProblem(pattern, field.getValue());
                    if (problem != null) {
                        report.error(value.position(), name + ": " + problem);
                    }
                }
            }
        }
        return patterns;
    }

    /** Повертає опис проблеми з патерном або null, якщо він придатний. */
    private static String patternProblem(String pattern, int requiredGroups) {
        if (pattern.isBlank()) {
            return "порожній патерн";
        }
        if (pattern.indexOf('\b') >= 0) {
            return "патерн містить символ backspace: мабуть, у JSON написано \\b замість \\\\b";
        }
        int groups;
        try {
            groups = Pattern.compile(pattern).matcher("").groupCount();
        } catch (PatternSyntaxException error) {
            return "некоректний регулярний вираз: " + error.getMessage();
        }
        if (groups < requiredGroups) {
            return "потрібно груп захоплення — " + requiredGroups + ", знайдено — " + groups;
        }
        return null;
    }

    private static String describeValue(Node node) {
        Object value = node.value();
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "рядок";
        }
        if (value instanceof JsonNumber number) {
            return number.literal();
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        return value instanceof JsonObject ? "об'єкт" : "масив";
    }

    /** Друкує помилки: у GitHub Actions — як анотації до рядків файлу, локально — як file:line:column. */
    private static final class Report {
        private final String file;
        private final PrintStream out;
        private final boolean github;
        private String text = "";
        private int errors;

        Report(String file, PrintStream out, boolean github) {
            this.file = file;
            this.out = out;
            this.github = github;
        }

        void error(String message) {
            errors++;
            out.println(github ? "::error file=" + file + "::" + escape(message) : file + ": " + message);
        }

        void error(int position, String message) {
            errors++;
            int line = 1;
            int lineStart = 0;
            for (int i = 0; i < position; i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    lineStart = i + 1;
                }
            }
            int column = position - lineStart + 1;
            out.println(github
                ? "::error file=" + file + ",line=" + line + ",col=" + column + "::" + escape(message)
                : file + ":" + line + ":" + column + ": " + message);
        }

        private static String escape(String message) {
            return message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
        }
    }

    /** Значення JSON і його позиція в тексті (для номера рядка в повідомленні). */
    private record Node(Object value, int position) {}

    private record JsonObject(Map<String, Node> members) {}

    private record JsonNumber(String literal) {
        boolean isInt() {
            try {
                Integer.parseInt(literal);
                return true;
            } catch (NumberFormatException error) {
                return false;
            }
        }
    }

    private static final class JsonException extends RuntimeException {
        final int position;

        JsonException(int position, String message) {
            super(message);
            this.position = position;
        }
    }

    /**
     * Строгий розбір JSON (RFC 8259). Android-застосунок читає конфіг поблажливіше, а iOS — строго,
     * тож перевірка тримається строгих правил.
     */
    private static final class JsonParser {
        private final String text;
        private final Report report;
        private int pos;

        JsonParser(String text, int start, Report report) {
            this.text = text;
            this.pos = start;
            this.report = report;
        }

        Node parseDocument() {
            skipWhitespace();
            Node root = parseValue();
            skipWhitespace();
            if (pos < text.length()) {
                throw unexpected("кінець файлу");
            }
            return root;
        }

        private Node parseValue() {
            int start = pos;
            char c = peek();
            if (c == '{') {
                return new Node(parseObject(), start);
            }
            if (c == '[') {
                return new Node(parseArray(), start);
            }
            if (c == '"') {
                return new Node(parseString(), start);
            }
            if (c == '-' || isDigit(c)) {
                return new Node(parseNumber(), start);
            }
            if (text.startsWith("true", pos)) {
                pos += 4;
                return new Node(Boolean.TRUE, start);
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return new Node(Boolean.FALSE, start);
            }
            if (text.startsWith("null", pos)) {
                pos += 4;
                return new Node(null, start);
            }
            throw unexpected("значення");
        }

        private JsonObject parseObject() {
            Map<String, Node> members = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return new JsonObject(members);
            }
            while (true) {
                skipWhitespace();
                if (peek() == '}') {
                    throw new JsonException(pos, "зайва кома перед «}»");
                }
                if (peek() != '"') {
                    throw unexpected("назва поля в лапках");
                }
                int keyPosition = pos;
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw unexpected("«:» після «" + key + "»");
                }
                pos++;
                skipWhitespace();
                Node value = parseValue();
                int valueEnd = pos;
                if (members.put(key, value) != null) {
                    report.error(keyPosition, "ключ «" + key + "» повторюється: залиште одне значення");
                }
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else if (peek() == '}') {
                    pos++;
                    return new JsonObject(members);
                } else if (peek() == '"') {
                    throw new JsonException(valueEnd, "після значення бракує коми");
                } else if (value.value() instanceof String && pos == valueEnd) {
                    throw new JsonException(valueEnd - 1, "лапки всередині значення треба записувати як \\\"");
                } else {
                    throw unexpected("«,» або «}»");
                }
            }
        }

        private List<Node> parseArray() {
            List<Node> items = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return items;
            }
            while (true) {
                skipWhitespace();
                if (peek() == ']') {
                    throw new JsonException(pos, "зайва кома перед «]»");
                }
                items.add(parseValue());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else if (peek() == ']') {
                    pos++;
                    return items;
                } else {
                    throw unexpected("«,» або «]»");
                }
            }
        }

        private String parseString() {
            StringBuilder value = new StringBuilder();
            pos++;
            while (true) {
                if (pos >= text.length()) {
                    throw new JsonException(pos, "рядок не закрито лапками");
                }
                char c = text.charAt(pos);
                if (c == '"') {
                    pos++;
                    return value.toString();
                }
                if (c == '\n' || c == '\r') {
                    throw new JsonException(pos, "рядок не закрито лапками до кінця рядка");
                }
                if (c < 0x20) {
                    throw new JsonException(pos,
                        "символ " + describeChar(c) + " у рядку треба записати як escape-послідовність (наприклад, \\t)");
                }
                if (c != '\\') {
                    value.append(c);
                    pos++;
                    continue;
                }
                if (pos + 1 >= text.length()) {
                    throw new JsonException(pos, "рядок не закрито лапками");
                }
                char escaped = text.charAt(pos + 1);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        String hex = text.substring(pos + 2, Math.min(pos + 6, text.length()));
                        if (!hex.matches("[0-9a-fA-F]{4}")) {
                            throw new JsonException(pos, "після \\u мають іти чотири шістнадцяткові цифри");
                        }
                        value.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> throw new JsonException(pos, "у JSON немає послідовності «\\" + escaped
                        + "»: зворотний слеш регулярного виразу в JSON подвоюють — «\\\\" + escaped + "»");
                }
                pos += 2;
            }
        }

        private JsonNumber parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (peek() == '0') {
                pos++;
            } else {
                digits();
            }
            if (peek() == '.') {
                pos++;
                digits();
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                digits();
            }
            return new JsonNumber(text.substring(start, pos));
        }

        private void digits() {
            if (!isDigit(peek())) {
                throw unexpected("цифра");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }

        private void skipWhitespace() {
            while (pos < text.length() && " \t\n\r".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private JsonException unexpected(String expected) {
            if (pos >= text.length()) {
                return new JsonException(pos, "файл обривається, а очікується " + expected);
            }
            if (text.startsWith("//", pos) || text.startsWith("/*", pos)) {
                return new JsonException(pos, "коментарі в JSON не підтримуються");
            }
            return new JsonException(pos, "очікується " + expected + ", а знайдено " + describeChar(text.charAt(pos)));
        }

        private static String describeChar(char c) {
            boolean invisible = Character.isISOControl(c) || Character.isWhitespace(c)
                || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT;
            return invisible ? String.format("U+%04X", (int) c) : "«" + c + "»";
        }
    }
}
