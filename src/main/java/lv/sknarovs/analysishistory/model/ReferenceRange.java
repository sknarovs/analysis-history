package lv.sknarovs.analysishistory.model;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reference range printed beside a result. A numeric range ({@link Between}, {@link Below}, {@link Above}) can
 * flag results; a {@link TextReference} is only shown, and {@link None} means the report printed no range.
 */
public sealed interface ReferenceRange {
    /** Text inside one pair of parentheses, the way CLab prints its ranges. */
    Pattern PARENTHESISED = Pattern.compile("\\(([^()]*)\\)");
    /** Two numbers with a hyphen between them. */
    Pattern BETWEEN = Pattern.compile("(" + Decimals.NUMBER + ")\\s*-\\s*(" + Decimals.NUMBER + ")");

    /** From {@code lo} to {@code hi}, both inclusive. */
    record Between(BigDecimal lo, BigDecimal hi) implements ReferenceRange {
        /** An en dash (U+2013) separates the bounds, as in the workbook's reference column. */
        private static final String EN_DASH = "–";

        @Override
        public ReferenceRange scaled(BigDecimal factor) {
            return new Between(Decimals.convert(lo, factor), Decimals.convert(hi, factor));
        }

        @Override
        public String display() {
            return lo.toPlainString() + EN_DASH + hi.toPlainString();
        }

        @Override
        public boolean sameAs(ReferenceRange other) {
            return other instanceof Between that && lo.compareTo(that.lo) == 0 && hi.compareTo(that.hi) == 0;
        }
    }

    /** Up to {@code limit}: {@code <41} excludes it, {@code ≤3.9} includes it. */
    record Below(BigDecimal limit, boolean inclusive) implements ReferenceRange {
        private boolean admits(BigDecimal number) {
            int order = number.compareTo(limit);
            return inclusive ? order <= 0 : order < 0;
        }

        @Override
        public ReferenceRange scaled(BigDecimal factor) {
            return new Below(Decimals.convert(limit, factor), inclusive);
        }

        @Override
        public String display() {
            return (inclusive ? "≤" : "<") + limit.toPlainString();
        }

        @Override
        public boolean sameAs(ReferenceRange other) {
            return other instanceof Below that && inclusive == that.inclusive && limit.compareTo(that.limit) == 0;
        }
    }

    /** From {@code limit} up: {@code >90} excludes it, {@code ≥1.0} includes it. */
    record Above(BigDecimal limit, boolean inclusive) implements ReferenceRange {
        private boolean admits(BigDecimal number) {
            int order = number.compareTo(limit);
            return inclusive ? order >= 0 : order > 0;
        }

        @Override
        public ReferenceRange scaled(BigDecimal factor) {
            return new Above(Decimals.convert(limit, factor), inclusive);
        }

        @Override
        public String display() {
            return (inclusive ? "≥" : ">") + limit.toPlainString();
        }

        @Override
        public boolean sameAs(ReferenceRange other) {
            return other instanceof Above that && inclusive == that.inclusive && limit.compareTo(that.limit) == 0;
        }
    }

    /** A range in words ({@code negatīvs}, {@code Norma}), shown as printed and never used to flag. */
    record TextReference(String text) implements ReferenceRange {
        private static final Pattern WHITE_SPACE = Pattern.compile("\\s+");

        @Override
        public ReferenceRange scaled(BigDecimal factor) {
            return this;
        }

        @Override
        public String display() {
            return text;
        }

        @Override
        public boolean sameAs(ReferenceRange other) {
            return other instanceof TextReference that
                    && withoutWhiteSpace(text).equalsIgnoreCase(withoutWhiteSpace(that.text));
        }

        private static String withoutWhiteSpace(String text) {
            return WHITE_SPACE.matcher(text).replaceAll("");
        }
    }

    /** The report printed no range. */
    record None() implements ReferenceRange {
        @Override
        public ReferenceRange scaled(BigDecimal factor) {
            return this;
        }

        @Override
        public String display() {
            return "";
        }

        @Override
        public boolean sameAs(ReferenceRange other) {
            return other instanceof None;
        }
    }

    /**
     * Reads a printed range. Parentheses around it are dropped; {@code a - b} is between, {@code <x} and {@code ≤x}
     * have an upper bound, {@code >x} and {@code ≥x} a lower one, {@code -} or nothing is no range, and any other
     * text is a text reference.
     */
    static ReferenceRange parse(String raw) {
        String text = raw.strip();
        Matcher parenthesised = PARENTHESISED.matcher(text);
        if (parenthesised.matches()) {
            text = parenthesised.group(1).strip();
        }
        if (text.isEmpty() || text.equals("-")) {
            return new None();
        }
        Matcher between = BETWEEN.matcher(text);
        if (between.matches()) {
            return new Between(Decimals.parse(between.group(1)), Decimals.parse(between.group(2)));
        }
        if (ResultValue.parse(text) instanceof ResultValue.Compared bound) {
            return switch (bound.op()) {
                case "<" -> new Below(bound.value(), false);
                case "≤" -> new Below(bound.value(), true);
                case ">" -> new Above(bound.value(), false);
                default -> new Above(bound.value(), true);
            };
        }
        return new TextReference(text);
    }

    /**
     * Whether {@code value} is high, low or within this range; empty when that cannot be told: a text value, a text
     * or missing range, or a comparator value ({@code <25}) that could lie on either side of a bound.
     */
    default Optional<Flag> judge(ResultValue value) {
        return switch (value) {
            case ResultValue.Numeric number -> judgeNumber(number.value());
            case ResultValue.Compared compared -> switch (compared.op()) {
                case "<" -> judgeBelow(compared.value());
                case ">" -> judgeAbove(compared.value());
                // "≤x" and "≥x" may equal x, which a range can put on either side of its edge: not judged.
                default -> Optional.empty();
            };
            case ResultValue.Text _ -> Optional.empty();
        };
    }

    /** Whether results can be judged against this range. */
    default boolean isNumeric() {
        return switch (this) {
            case Between _, Below _, Above _ -> true;
            case TextReference _, None _ -> false;
        };
    }

    /** The range in another unit; {@code factor} is a power of ten from 1 up (see {@link Decimals}). */
    ReferenceRange scaled(BigDecimal factor);

    /** The printed form with a dot as the decimal separator, bounds separated by an en dash ({@code 4.0–9.8}). */
    String display();

    /**
     * Whether both are the same range: of one kind, with equal bounds by value ({@code 4.0} is {@code 4.00}) that are
     * equally inclusive, or the same text ignoring case and white space.
     */
    boolean sameAs(ReferenceRange other);

    private Optional<Flag> judgeNumber(BigDecimal number) {
        return switch (this) {
            case Between range -> Optional.of(number.compareTo(range.lo()) < 0 ? Flag.LOW
                    : number.compareTo(range.hi()) > 0 ? Flag.HIGH : Flag.NONE);
            case Below range -> Optional.of(range.admits(number) ? Flag.NONE : Flag.HIGH);
            case Above range -> Optional.of(range.admits(number) ? Flag.NONE : Flag.LOW);
            case TextReference _, None _ -> Optional.empty();
        };
    }

    /**
     * The flag for the comparator value {@code <x}: the true value lies somewhere below x, so there is a flag only if
     * every such value gets it. Values below x never include x itself, so whether a bound belongs to the range does
     * not change the outcome.
     */
    private Optional<Flag> judgeBelow(BigDecimal x) {
        return switch (this) {
            case Between range -> flagIf(x.compareTo(range.lo()) <= 0, Flag.LOW);
            case Below range -> flagIf(x.compareTo(range.limit()) <= 0, Flag.NONE);
            case Above range -> flagIf(x.compareTo(range.limit()) <= 0, Flag.LOW);
            case TextReference _, None _ -> Optional.empty();
        };
    }

    /** The flag for the comparator value {@code >x}, by the same reasoning as {@link #judgeBelow}. */
    private Optional<Flag> judgeAbove(BigDecimal x) {
        return switch (this) {
            case Between range -> flagIf(x.compareTo(range.hi()) >= 0, Flag.HIGH);
            case Below range -> flagIf(x.compareTo(range.limit()) >= 0, Flag.HIGH);
            case Above range -> flagIf(x.compareTo(range.limit()) >= 0, Flag.NONE);
            case TextReference _, None _ -> Optional.empty();
        };
    }

    private static Optional<Flag> flagIf(boolean certain, Flag flag) {
        return certain ? Optional.of(flag) : Optional.empty();
    }
}
