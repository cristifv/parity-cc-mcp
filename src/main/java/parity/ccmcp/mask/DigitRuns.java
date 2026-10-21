package parity.ccmcp.mask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs of digits in text, typed ("4111 1111-1111") or spoken as a transcript has them
 * ("four one one one, double one…"). A run goes on across spaces, the separators
 * {@code - . ,} and fillers ("uh", "um"); any other word or character ends it.
 */
public final class DigitRuns {

    /**
     * A run: its digits, the text range of each digit (the token it came from), the digit
     * indexes where groups start ("4111 1111" has groups at 0 and 4), and whether only
     * separators and fillers come before it ({@code atStart}) or after it ({@code atEnd}).
     */
    public record Run(String digits, int[] charStarts, int[] charEnds, List<Integer> groupStarts,
                      boolean atStart, boolean atEnd) {

        /** Where digit {@code index} starts in the text. */
        public int charStart(int index) {
            return charStarts[index];
        }

        /** Where digit {@code index} ends in the text (exclusive). */
        public int charEnd(int index) {
            return charEnds[index];
        }
    }

    private static final Pattern TOKEN = Pattern.compile("[0-9]+|[\\p{L}\\p{M}']+|\\S");
    private static final Map<String, Character> DIGIT_WORDS = Map.ofEntries(
            Map.entry("zero", '0'), Map.entry("nought", '0'), Map.entry("oh", '0'), Map.entry("o", '0'),
            Map.entry("one", '1'), Map.entry("two", '2'), Map.entry("three", '3'), Map.entry("four", '4'),
            Map.entry("five", '5'), Map.entry("six", '6'), Map.entry("seven", '7'), Map.entry("eight", '8'),
            Map.entry("nine", '9'));
    /** "oh" and "o" are zero only inside a run: "Oh great" isn't a number. */
    private static final Set<String> ZERO_INSIDE_ONLY = Set.of("oh", "o");
    private static final Map<String, Integer> REPEATS = Map.of("double", 2, "triple", 3);
    private static final Set<String> FILLERS = Set.of("uh", "um", "er", "erm", "ah", "hmm", "mm");
    private static final String SEPARATORS = "-.,";

    private DigitRuns() {
    }

    public static List<Run> find(String text) {
        List<Run> runs = new ArrayList<>();
        if (text == null) {
            return runs;
        }
        Builder run = null;
        boolean contentBefore = false;
        boolean groupBreak = false;
        int repeat = 1;
        Matcher token = TOKEN.matcher(text);
        while (token.find()) {
            String value = token.group();
            char first = value.charAt(0);
            if (first >= '0' && first <= '9') {
                if (run == null) {
                    run = new Builder(!contentBefore);
                }
                run.group();
                run.add(String.valueOf(first).repeat(repeat - 1) + value, token.start(), token.end());
                repeat = 1;
                groupBreak = true;
                continue;
            }
            if (Character.isLetter(first)) {
                String word = value.toLowerCase(Locale.ROOT);
                Character digit = DIGIT_WORDS.get(word);
                boolean zeroWord = ZERO_INSIDE_ONLY.contains(word);
                if (digit != null && (run != null || !zeroWord)) {
                    if (run == null) {
                        run = new Builder(!contentBefore);
                        run.group();
                    } else if (groupBreak) {
                        run.group();
                    }
                    run.add(String.valueOf(digit).repeat(repeat), token.start(), token.end());
                    repeat = 1;
                    groupBreak = false;
                    continue;
                }
                if (REPEATS.containsKey(word)) {
                    repeat = REPEATS.get(word);
                    continue;
                }
                if (FILLERS.contains(word) || zeroWord) {
                    continue;
                }
            } else if (SEPARATORS.indexOf(first) >= 0) {
                groupBreak = true;
                continue;
            }
            // Anything else is content: it ends the run.
            if (run != null) {
                runs.add(run.build(false));
                run = null;
            }
            contentBefore = true;
            groupBreak = false;
            repeat = 1;
        }
        if (run != null) {
            runs.add(run.build(true));
        }
        return runs;
    }

    private static final class Builder {

        private final boolean atStart;
        private final StringBuilder digits = new StringBuilder();
        private final List<Integer> starts = new ArrayList<>();
        private final List<Integer> ends = new ArrayList<>();
        private final List<Integer> groupStarts = new ArrayList<>();

        Builder(boolean atStart) {
            this.atStart = atStart;
        }

        void group() {
            if (groupStarts.isEmpty() || groupStarts.getLast() != digits.length()) {
                groupStarts.add(digits.length());
            }
        }

        void add(String value, int charStart, int charEnd) {
            for (int i = 0; i < value.length(); i++) {
                digits.append(value.charAt(i));
                starts.add(charStart);
                ends.add(charEnd);
            }
        }

        Run build(boolean atEnd) {
            return new Run(digits.toString(),
                    starts.stream().mapToInt(Integer::intValue).toArray(),
                    ends.stream().mapToInt(Integer::intValue).toArray(),
                    List.copyOf(groupStarts), atStart, atEnd);
        }
    }
}
