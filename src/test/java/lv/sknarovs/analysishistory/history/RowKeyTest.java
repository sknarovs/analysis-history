package lv.sknarovs.analysishistory.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import lv.sknarovs.analysishistory.catalog.CatalogTest;
import lv.sknarovs.analysishistory.catalog.TestCatalog;
import lv.sknarovs.analysishistory.model.Flag;
import lv.sknarovs.analysishistory.model.Lab;
import lv.sknarovs.analysishistory.model.ReferenceRange;
import lv.sknarovs.analysishistory.model.Result;
import lv.sknarovs.analysishistory.model.ResultValue;
import lv.sknarovs.analysishistory.model.Specimen;
import org.junit.jupiter.api.Test;

class RowKeyTest {
    private static final TestCatalog CATALOG = TestCatalog.parse("""
            [Hematoloģija]
            Hemoglobīns | g/L | EGL: Hemoglobīns | CLab: Hemoglobīns [g/dL ×10]
            """);

    @Test void matchedResultsOfBothLabsShareTheirTestsKey() {
        RowKey egl = key(Lab.EGL, Specimen.BLOOD, "Hemoglobīns", "g/L");

        assertEquals(egl, key(Lab.CLAB, Specimen.BLOOD, "HEMOGLOBĪNS", "g/dL"));
        assertEquals(new CatalogTest("Hematoloģija", "Hemoglobīns", "g/L", 0), egl.test());
    }

    @Test void otherResultsAreKeyedByLabSpecimenNameAndUnit() {
        RowKey homocysteine = key(Lab.EGL, Specimen.BLOOD, "Homocisteīns", "µmol/L");

        assertNull(homocysteine.test());
        assertEquals(homocysteine, key(Lab.EGL, Specimen.BLOOD, " HOMOCISTEĪNS ", "umol/L"));
        assertNotEquals(homocysteine, key(Lab.CLAB, Specimen.BLOOD, "Homocisteīns", "µmol/L"));
        assertNotEquals(homocysteine, key(Lab.EGL, Specimen.URINE, "Homocisteīns", "µmol/L"));
        assertNotEquals(homocysteine, key(Lab.EGL, Specimen.BLOOD, "Homocisteīns", "mg/L"));
        assertNotEquals(homocysteine, key(Lab.EGL, Specimen.BLOOD, "Homocisteīns B", "µmol/L"));
    }

    @Test void resultInAnotherUnitThanTheCatalogExpectsIsKeptApartFromTheTest() {
        RowKey mismatch = key(Lab.EGL, Specimen.BLOOD, "Hemoglobīns", "g/dL");

        assertNull(mismatch.test());
        assertNotEquals(key(Lab.EGL, Specimen.BLOOD, "Hemoglobīns", "g/L"), mismatch);
        assertEquals(mismatch, key(Lab.EGL, Specimen.BLOOD, "hemoglobīns", "G/DL"));
    }

    private static RowKey key(Lab lab, Specimen specimen, String name, String unit) {
        Result result = new Result("Hematoloģija", null, specimen, name, ResultValue.parse("1.0"), unit,
                new ReferenceRange.None(), Flag.NONE, null);
        return RowKey.of(lab, result, CATALOG);
    }
}
