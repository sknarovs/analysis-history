package lv.sknarovs.analysishistory.catalog;

/**
 * A test in the catalog, as the History sheet shows it: the {@code name} and {@code unit} of its row ({@code ""} for a
 * test without a unit), the {@code section} it is listed under, and its {@code order}, the 0-based position among the
 * catalog's tests in file order.
 */
public record CatalogTest(String section, String name, String unit, int order) {
}
