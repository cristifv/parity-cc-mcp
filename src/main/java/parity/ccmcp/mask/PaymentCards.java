package parity.ccmcp.mask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Payment card numbers (PANs) in a string of digits: a known brand's prefix (IIN), a length
 * that brand issues, and a valid Luhn check digit. A brand and length are required as well
 * as Luhn, because one random number in ten passes Luhn.
 */
public final class PaymentCards {

    public enum Brand {
        VISA, MASTERCARD, AMEX, DISCOVER, JCB, DINERS, UNIONPAY, MAESTRO;

        /** {@code visa}. */
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A card at digits {@code [start, start + length)}. */
    public record Card(Brand brand, int start, int length) {

        public int end() {
            return start + length;
        }

        /** The last four digits, the most PCI DSS allows to be shown. */
        public String last4(String digits) {
            return digits.substring(end() - 4, end());
        }
    }

    /** Most common lengths first: a card read with its expiry date after it is still 16 digits. */
    private static final int[] LENGTHS = {16, 15, 19, 18, 17, 14, 13};

    private record Rule(Brand brand, int prefixDigits, int low, int high, int minLength, int maxLength) {

        boolean matches(String number) {
            if (number.length() < minLength || number.length() > maxLength) {
                return false;
            }
            int prefix = Integer.parseInt(number.substring(0, prefixDigits));
            return prefix >= low && prefix <= high;
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Brand.VISA, 1, 4, 4, 16, 16),
            new Rule(Brand.VISA, 1, 4, 4, 19, 19),
            new Rule(Brand.MASTERCARD, 2, 51, 55, 16, 16),
            new Rule(Brand.MASTERCARD, 4, 2221, 2720, 16, 16),
            new Rule(Brand.AMEX, 2, 34, 34, 15, 15),
            new Rule(Brand.AMEX, 2, 37, 37, 15, 15),
            new Rule(Brand.DISCOVER, 4, 6011, 6011, 16, 19),
            new Rule(Brand.DISCOVER, 3, 644, 649, 16, 19),
            new Rule(Brand.DISCOVER, 2, 65, 65, 16, 19),
            new Rule(Brand.JCB, 4, 3528, 3589, 16, 19),
            new Rule(Brand.DINERS, 3, 300, 305, 14, 19),
            new Rule(Brand.DINERS, 2, 36, 36, 14, 19),
            new Rule(Brand.DINERS, 2, 38, 39, 14, 19),
            new Rule(Brand.UNIONPAY, 2, 62, 62, 16, 19),
            new Rule(Brand.MAESTRO, 4, 5018, 5018, 16, 19),
            new Rule(Brand.MAESTRO, 4, 5020, 5020, 16, 19),
            new Rule(Brand.MAESTRO, 4, 5038, 5038, 16, 19),
            new Rule(Brand.MAESTRO, 4, 5893, 5893, 16, 19),
            new Rule(Brand.MAESTRO, 4, 6304, 6304, 16, 19),
            new Rule(Brand.MAESTRO, 4, 6759, 6759, 16, 19),
            new Rule(Brand.MAESTRO, 4, 6761, 6763, 16, 19));

    private PaymentCards() {
    }

    /**
     * The cards in {@code digits} that start at one of {@code starts} (group starts: a card
     * starts where a group does), in order and not overlapping.
     */
    public static List<Card> find(String digits, Collection<Integer> starts) {
        List<Card> cards = new ArrayList<>();
        int next = 0;
        for (int start : starts.stream().sorted().distinct().toList()) {
            if (start < next) {
                continue;
            }
            Card card = at(digits, start);
            if (card != null) {
                cards.add(card);
                next = card.end();
            }
        }
        return cards;
    }

    private static Card at(String digits, int start) {
        for (int length : LENGTHS) {
            if (start + length > digits.length()) {
                continue;
            }
            String number = digits.substring(start, start + length);
            Brand brand = brand(number);
            if (brand != null && luhn(number)) {
                return new Card(brand, start, length);
            }
        }
        return null;
    }

    /** The brand that issues numbers with this prefix and length, or null. */
    static Brand brand(String number) {
        for (Rule rule : RULES) {
            if (rule.matches(number)) {
                return rule.brand();
            }
        }
        return null;
    }

    public static boolean luhn(CharSequence number) {
        int sum = 0;
        boolean doubled = false;
        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = number.charAt(i) - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }
}
