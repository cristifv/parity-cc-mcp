package parity.ccmcp.mask;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes payment card numbers and IBANs from what a tool returns, so they never reach the
 * model (ARCHITECTURE.md §3.23). A card keeps its last four digits, the most PCI DSS allows
 * to be shown: {@code [card ending 1111]}; an IBAN likewise: {@code [IBAN ending 3000]}.
 * Cards are found typed or spoken ({@link DigitRuns}), with brand, length and Luhn
 * ({@link PaymentCards}); IBANs by country code, check digits and the mod-97 check.
 */
public final class SensitiveData {

    private static final Pattern IBAN = Pattern.compile("\\b[A-Za-z]{2}[0-9]{2}(?: ?[A-Za-z0-9]){11,30}\\b");

    private record Span(int start, int end, String replacement) {
    }

    private SensitiveData() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String withoutIbans = maskIbans(text);
        return maskCards(withoutIbans);
    }

    /** A copy of {@code node} with every string masked. */
    public static JsonNode mask(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return JsonNodeFactory.instance.stringNode(mask(node.asString()));
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> copy.add(mask(element)));
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                copy.set(field.getKey(), mask(field.getValue()));
            }
            return copy;
        }
        return node;
    }

    /**
     * The pattern also takes the words after an IBAN ("...0130 00 please"), so each match is
     * cut back, group by group, to its longest prefix that is a valid IBAN.
     */
    private static String maskIbans(String text) {
        Matcher m = IBAN.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        int from = 0;
        while (from < text.length() && m.find(from)) {
            int length = validIbanPrefix(m.group());
            if (length > 0) {
                String compact = m.group().substring(0, length).replace(" ", "").toUpperCase(Locale.ROOT);
                out.append(text, last, m.start()).append("[IBAN ending ").append(compact.substring(compact.length() - 4)).append("]");
                last = m.start() + length;
                from = last;
            } else {
                from = m.start() + 1;
            }
        }
        return out.append(text.substring(last)).toString();
    }

    /** The length of the longest prefix of {@code candidate}, ending at a group, that is a valid IBAN; or -1. */
    private static int validIbanPrefix(String candidate) {
        for (int end = candidate.length(); end > 0; end = candidate.lastIndexOf(' ', end - 1)) {
            if (validIban(candidate.substring(0, end).replace(" ", "").toUpperCase(Locale.ROOT))) {
                return end;
            }
        }
        return -1;
    }

    static boolean validIban(String compact) {
        if (compact.length() < 15 || compact.length() > 34) {
            return false;
        }
        String rearranged = compact.substring(4) + compact.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                digits.append(c - 'A' + 10);
            } else {
                return false;
            }
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    private static String maskCards(String text) {
        List<Span> spans = new ArrayList<>();
        for (DigitRuns.Run run : DigitRuns.find(text)) {
            for (PaymentCards.Card card : PaymentCards.find(run.digits(), run.groupStarts())) {
                spans.add(new Span(run.charStart(card.start()), run.charEnd(card.end() - 1),
                        "[card ending " + card.last4(run.digits()) + "]"));
            }
        }
        if (spans.isEmpty()) {
            return text;
        }
        spans.sort(Comparator.comparingInt(Span::start).reversed());
        StringBuilder out = new StringBuilder(text);
        int limit = Integer.MAX_VALUE;
        for (Span span : spans) {
            if (span.end() <= limit) {
                out.replace(span.start(), span.end(), span.replacement());
                limit = span.start();
            }
        }
        return out.toString();
    }
}
