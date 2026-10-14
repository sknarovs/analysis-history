package lv.sknarovs.analysishistory.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A result as printed on a report: a number that keeps its printed decimals, a number the true value lies beyond
 * ({@code <25}), or any other text. {@link #display()} gives the printed form with a dot as the decimal separator.
 */
public sealed interface ResultValue {
    /** An optional comparator (group 1), then a number (group 2). */
    Pattern PRINTED = Pattern.compile("([<>≤≥])?\\s*(" + Decimals.NUMBER + ")");

    /**
     * A number with the decimals it was printed with: {@code 0.290} has three, {@code 152} none. The constructor sets
     * {@code value} to exactly that many decimals, so equal numbers printed differently ({@code 4.8}, {@code 4.80})
     * stay different records.
     */
    record Numeric(BigDecimal value, int decimals) implements ResultValue {
        public Numeric {
            value = value.setScale(decimals, RoundingMode.HALF_UP);
        }

        @Override
        public String display() {
            return value.toPlainString();
        }

        @Override
        public ResultValue scaled(BigDecimal factor) {
            BigDecimal converted = Decimals.convert(value, factor);
            return new Numeric(converted, converted.scale());
        }

        @Override
        public boolean sameAs(ResultValue other) {
            return other instanceof Numeric number && value.compareTo(number.value) == 0;
        }
    }

    /** A number with a comparator, such as {@code <25} or {@code >9000}; {@code op} is one of {@code < > ≤ ≥}. */
    record Compared(String op, BigDecimal value, int decimals) implements ResultValue {
        private static final List<String> COMPARATORS = List.of("<", ">", "≤", "≥");

        public Compared {
            if (!COMPARATORS.contains(op)) {
                throw new IllegalArgumentException("not a comparator: '" + op + "'");
            }
            value = value.setScale(decimals, RoundingMode.HALF_UP);
        }

        @Override
        public String display() {
            return op + value.toPlainString();
        }

        @Override
        public ResultValue scaled(BigDecimal factor) {
            BigDecimal converted = Decimals.convert(value, factor);
            return new Compared(op, converted, converted.scale());
        }

        @Override
        public boolean sameAs(ResultValue other) {
            return other instanceof Compared compared && op.equals(compared.op)
                    && value.compareTo(compared.value) == 0;
        }
    }

    /** Anything else, trimmed and with every run of white space as one space. */
    record Text(String text) implements ResultValue {
        private static final Pattern WHITE_SPACE = Pattern.compile("\\s+");

        public Text {
            text = WHITE_SPACE.matcher(text.strip()).replaceAll(" ");
        }

        @Override
        public String display() {
            return text;
        }

        @Override
        public ResultValue scaled(BigDecimal factor) {
            return this;
        }

        @Override
        public boolean sameAs(ResultValue other) {
            return other instanceof Text that && text.equalsIgnoreCase(that.text);
        }
    }

    /** Reads a printed result: a decimal comma becomes a dot and the number keeps every printed decimal. */
    static ResultValue parse(String raw) {
        Text text = new Text(raw);
        Matcher printed = PRINTED.matcher(text.text());
        if (!printed.matches()) {
            return text;
        }
        BigDecimal number = Decimals.parse(printed.group(2));
        return printed.group(1) == null
                ? new Numeric(number, number.scale())
                : new Compared(printed.group(1), number, number.scale());
    }

    /** The printed form, with a dot as the decimal separator and no space after a comparator ({@code <0.25}). */
    String display();

    /** The value converted to another unit; {@code factor} is a power of ten from 1 up (see {@link Decimals}). */
    ResultValue scaled(BigDecimal factor);

    /** Whether both are the same result: numbers by value, text ignoring case, comparators by operator and value. */
    boolean sameAs(ResultValue other);
}
