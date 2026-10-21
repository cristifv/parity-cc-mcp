package parity.ccmcp.tools;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The case tools' results. Markdown, the default, is for reading: readable times, names with
 * their ids in parentheses, no metadata. JSON has every field the owner sent, plus the paging
 * fields {@code total}, {@code count}, {@code offset}, {@code has_more} and {@code next_offset}.
 */
final class CaseFormat {

    private static final DateTimeFormatter READABLE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private CaseFormat() {
    }

    // --- parity_get_customer_context ---------------------------------------------------

    static String context(JsonNode result, Arguments.Page page) {
        JsonNode customer = result.path("customer");
        JsonNode items = result.path("items");
        int total = result.path("total").asInt(items.size());
        StringBuilder md = new StringBuilder("# Context of ");
        md.append(customer.hasNonNull("name") ? customer.path("name").asString() + " (" + customer.path("customer_key").asString() + ")"
                : "customer " + customer.path("customer_key").asString(""));
        md.append("\n\n");
        if (items.isEmpty()) {
            md.append(page.offset() == 0 ? "Nothing recorded for this customer" : "No items at offset " + page.offset());
            md.append(" with these filters.\n");
            return md.toString();
        }
        md.append("Items ").append(page.offset() + 1).append("–").append(page.offset() + items.size())
                .append(" of ").append(total).append(", newest first.\n\n");
        for (JsonNode item : items) {
            md.append("- ").append(readable(item.path("at").asString(""))).append(" · ")
                    .append(item.path("channel").asString("no channel")).append(" · ").append(what(item))
                    .append(" (item ").append(item.path("item_id").asString("?"));
            if (item.hasNonNull("case_id")) {
                md.append(", case ").append(item.path("case_id").asString());
            }
            md.append(")");
            if (item.hasNonNull("text")) {
                md.append(": ").append(item.path("text").asString());
            }
            md.append("\n");
        }
        more(md, page, total, items.size());
        return md.toString();
    }

    private static String what(JsonNode item) {
        String who = item.path("who").asString("someone");
        return switch (item.path("kind").asString("")) {
            case "said" -> who + " said";
            case "note" -> "note by " + who;
            case "signal" -> "signal " + item.path("signal").path("type").asString("?") + ", "
                    + item.path("signal").path("severity").asString("?");
            default -> item.path("kind").asString("item") + " by " + who;
        };
    }

    static ObjectNode contextJson(JsonNode result, Arguments.Page page, JsonMapper json) {
        ObjectNode out = json.createObjectNode();
        out.set("customer", result.path("customer"));
        paging(out, result.path("items"), result, page);
        out.set("items", result.path("items"));
        return out;
    }

    // --- parity_list_cases, parity_open_case, parity_update_case -------------------------

    static String cases(JsonNode result, Arguments.Page page, boolean includeSolved) {
        JsonNode cases = result.path("cases");
        int total = result.path("total").asInt(cases.size());
        StringBuilder md = new StringBuilder("# Cases of the customer\n\n");
        if (cases.isEmpty()) {
            md.append(page.offset() == 0 ? "This customer has no " + (includeSolved ? "" : "unsolved ") + "cases.\n"
                    : "No cases at offset " + page.offset() + ".\n");
            return md.toString();
        }
        md.append("Cases ").append(page.offset() + 1).append("–").append(page.offset() + cases.size()).append(" of ")
                .append(total).append(includeSolved ? "" : " unsolved").append(", newest first.\n\n");
        for (JsonNode c : cases) {
            md.append(caseCard(c, "##")).append("\n");
        }
        more(md, page, total, cases.size());
        return md.toString();
    }

    static ObjectNode casesJson(JsonNode result, Arguments.Page page, JsonMapper json) {
        ObjectNode out = json.createObjectNode();
        paging(out, result.path("cases"), result, page);
        out.set("cases", result.path("cases"));
        return out;
    }

    static String caseCard(JsonNode c, String heading) {
        StringBuilder md = new StringBuilder(heading).append(" ").append(c.path("case_id").asString("?")).append(": ")
                .append(c.path("problem").asString("(no problem stated)")).append("\n");
        md.append("- State: ").append(c.path("state").asString("?"));
        if (c.hasNonNull("risk")) {
            md.append(" · risk ").append(c.path("risk").asString());
        }
        md.append(" · ").append(c.path("item_count").asInt(0)).append(" items\n");
        if (c.hasNonNull("summary")) {
            md.append("- Summary: ").append(c.path("summary").asString()).append("\n");
        }
        if (c.hasNonNull("action")) {
            md.append("- Recommended action: ").append(c.path("action").asString()).append("\n");
        }
        md.append("- Opened ").append(readable(c.path("opened_at").asString("")));
        if (c.hasNonNull("updated_at")) {
            md.append(", updated ").append(readable(c.path("updated_at").asString()));
        }
        md.append("\n");
        JsonNode q = c.path("question");
        if (q.isObject()) {
            md.append("- Question to the ").append(q.path("ask").asString("person")).append(" (")
                    .append(q.path("question_id").asString("?")).append(", ").append(readable(q.path("asked_at").asString("")))
                    .append("): ").append(q.path("question").asString(""));
            if (q.hasNonNull("answer")) {
                md.append("\n  - Answer from ").append(q.path("answered_by").asString("the person")).append(" (")
                        .append(readable(q.path("answered_at").asString(""))).append("): ").append(q.path("answer").asString());
            } else {
                md.append("\n  - Not answered yet.");
            }
            md.append("\n");
        }
        return md.toString();
    }

    // --- parity_ask_person ---------------------------------------------------------------

    static String asked(JsonNode result) {
        return "Asked the " + result.path("ask").asString("person") + " about case " + result.path("case_id").asString("?")
                + " (question " + result.path("question_id").asString("?") + ", " + readable(result.path("asked_at").asString(""))
                + "). Their answer will show on the case in parity_list_cases; don't ask again while it's open.";
    }

    // --- shared --------------------------------------------------------------------------

    private static void paging(ObjectNode out, JsonNode list, JsonNode result, Arguments.Page page) {
        int count = list.size();
        int total = result.path("total").asInt(count);
        out.put("total", total);
        out.put("count", count);
        out.put("offset", page.offset());
        out.put("has_more", page.hasMore(total, count));
        if (page.hasMore(total, count)) {
            out.put("next_offset", page.nextOffset(count));
        }
    }

    private static void more(StringBuilder md, Arguments.Page page, int total, int count) {
        if (page.hasMore(total, count)) {
            md.append("\nMore: call again with offset=").append(page.nextOffset(count)).append(".\n");
        }
    }

    static String readable(String iso) {
        if (iso == null || iso.isBlank()) {
            return "unknown time";
        }
        try {
            return READABLE.format(Instant.parse(iso));
        } catch (DateTimeParseException e) {
            try {
                return READABLE.format(OffsetDateTime.parse(iso).toInstant());
            } catch (DateTimeParseException again) {
                return iso;
            }
        }
    }
}
