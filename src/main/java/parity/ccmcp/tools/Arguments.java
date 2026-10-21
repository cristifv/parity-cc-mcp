package parity.ccmcp.tools;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;
import parity.ccmcp.mask.SensitiveData;

/**
 * Checks of the tools' arguments. Each failure says what was wrong and gives a correct
 * example, so the agent can fix its call.
 */
public final class Arguments {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    public static final int MAX_ITEMS = 100;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:@-]{1,128}");

    private Arguments() {
    }

    /** A page: {@code limit} items from {@code offset}. */
    public record Page(int limit, int offset) {

        /** The paging fields of a result, for {@code total} items in all and {@code count} here. */
        public boolean hasMore(int total, int count) {
            return offset + count < total;
        }

        public int nextOffset(int count) {
            return offset + count;
        }
    }

    public static Page page(Integer limit, Integer offset) {
        int l = limit == null ? DEFAULT_LIMIT : limit;
        int o = offset == null ? 0 : offset;
        if (l < 1 || l > MAX_LIMIT) {
            throw ToolError.invalid("limit must be between 1 and " + MAX_LIMIT + "; it was " + l
                    + ". Ask for a page, for example limit=20, and get the next one with offset.");
        }
        if (o < 0) {
            throw ToolError.invalid("offset can't be negative; it was " + o
                    + ". Start with offset=0 and use next_offset from the previous result.");
        }
        return new Page(l, o);
    }

    /** {@code 2026-10-01} (midnight UTC) or {@code 2026-10-01T09:00:00Z}. */
    public static Instant since(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return v.length() == 10 ? LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant() : OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException e) {
            throw ToolError.invalid("since must be a date such as 2026-10-01 or a date and time with its offset such as "
                    + "2026-10-01T09:00:00Z; it was " + value + ".");
        }
    }

    /**
     * Free text the agent writes, at most {@code max} characters, with card numbers and IBANs
     * removed before it is stored. Null when it's optional and not given.
     */
    public static String text(String name, String value, int max, boolean required, String example) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw ToolError.invalid(name + " is required. Example: " + example);
            }
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw ToolError.invalid(name + " can have at most " + max + " characters; it had " + v.length()
                    + ". Shorten it. Example: " + example);
        }
        return SensitiveData.mask(v);
    }

    public static String id(String name, String value, String whereFrom) {
        if (value == null || value.isBlank()) {
            throw ToolError.invalid(name + " is required. " + whereFrom);
        }
        if (!ID.matcher(value.strip()).matches()) {
            throw ToolError.invalid(name + " " + value + " isn't an id. " + whereFrom);
        }
        return value.strip();
    }

    public static List<String> ids(String name, List<String> values, String whereFrom) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_ITEMS) {
            throw ToolError.invalid(name + " can have at most " + MAX_ITEMS + " ids; it had " + values.size()
                    + ". Add the rest with parity_update_case.");
        }
        return values.stream().map(v -> id(name, v, whereFrom)).distinct().toList();
    }
}
