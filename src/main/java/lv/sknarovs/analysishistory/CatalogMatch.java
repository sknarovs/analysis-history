package lv.sknarovs.analysishistory;

import java.math.BigDecimal;

/**
 * What the catalog makes of one printed result: it is {@link Matched} to a test, the catalog does not know it
 * ({@link Unknown}), or it names a test but prints another unit than the catalog expects ({@link UnitMismatch}).
 */
sealed interface CatalogMatch {
    /**
     * The printed name and unit belong to {@code test}. A value printed in this unit becomes a value in the test's
     * unit when multiplied by {@code factor}, a power of ten from 1 up ({@code 10} turns 14.1 g/dL into 141 g/L).
     */
    record Matched(CatalogTest test, BigDecimal factor) implements CatalogMatch {
    }

    /** The catalog has no test of this name for this laboratory and specimen. */
    record Unknown() implements CatalogMatch {
    }

    /**
     * The name is {@code test}'s, but the unit printed is not the one the catalog expects of this laboratory, so the
     * result must not be merged into the test's row. Both units are shown as written, not normalised.
     */
    record UnitMismatch(CatalogTest test, String expectedUnit, String printedUnit) implements CatalogMatch {
    }
}
