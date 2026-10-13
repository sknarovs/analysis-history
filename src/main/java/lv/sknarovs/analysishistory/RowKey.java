package lv.sknarovs.analysishistory;

import lv.sknarovs.analysishistory.CatalogMatch.Matched;

/**
 * The row of the History sheet a result belongs in, and so whether results of different reports are the same test. A
 * result the catalog matches is keyed by its catalog {@code test} alone, so that both laboratories' results of the test
 * share its row; the other components are then null. Any other result, unknown to the catalog or printed in a unit the
 * catalog does not expect, has its own row: it is keyed by its {@code lab}, {@code specimen}, and its {@code name} and
 * {@code unit} normalised as the catalog compares them, and {@code test} is null.
 */
record RowKey(CatalogTest test, Lab lab, Specimen specimen, String name, String unit) {
    /** The key of {@code result}, which {@code lab} printed. */
    static RowKey of(Lab lab, Result result, TestCatalog catalog) {
        if (catalog.match(lab, result.specimen(), result.name(), result.unit()) instanceof Matched matched) {
            return new RowKey(matched.test(), null, null, null, null);
        }
        return new RowKey(null, lab, result.specimen(), TestCatalog.normaliseName(result.name()),
                TestCatalog.normaliseUnit(result.unit()));
    }
}
