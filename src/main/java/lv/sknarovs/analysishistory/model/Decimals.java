package lv.sknarovs.analysishistory.model;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Numbers as the laboratories print them, and the conversion between units, whose factors are powers of ten. */
public final class Decimals {
    /** A printed number: an optional minus sign, digits, and decimals after a dot or a decimal comma. */
    static final String NUMBER = "-?\\d+(?:[.,]\\d+)?";

    private Decimals() {
    }

    /** The printed {@link #NUMBER} with every printed decimal kept; a decimal comma becomes a dot. */
    static BigDecimal parse(String number) {
        return new BigDecimal(number.replace(',', '.'));
    }

    /**
     * {@code value} times {@code factor}, which must be a power of ten from 1 up (1, 10, 100, ...). The result has as
     * many decimals fewer as the factor has zeros, but never fewer than none: 14.1 times 10 is 141, 1.5 times 100 is
     * 150.
     *
     * @throws IllegalArgumentException if {@code factor} is not a power of ten from 1 up
     */
    public static BigDecimal convert(BigDecimal value, BigDecimal factor) {
        BigDecimal power = factor.stripTrailingZeros();
        if (!power.unscaledValue().equals(BigInteger.ONE) || power.scale() > 0) {
            throw new IllegalArgumentException("conversion factor must be 1, 10, 100, ...: " + factor);
        }
        // Moving the point right by n drops n decimals, down to zero, exactly like multiplying by 10^n.
        return value.movePointRight(-power.scale());
    }
}
