package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import lv.sknarovs.analysishistory.ResultValue.Compared;
import lv.sknarovs.analysishistory.ResultValue.Numeric;
import lv.sknarovs.analysishistory.ResultValue.Text;
import org.junit.jupiter.api.Test;

class ResultValueTest {
    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Test void parsesNumbersKeepingDecimals() {
        assertEquals(new Numeric(new BigDecimal("0.290"), 3), ResultValue.parse("0.290"));
        assertEquals("0.290", ResultValue.parse("0.290").display());
        assertEquals(new Numeric(new BigDecimal("13.6"), 1), ResultValue.parse("13,6"));
        assertEquals("13.6", ResultValue.parse("13,6").display());
        assertEquals(new Numeric(new BigDecimal("152"), 0), ResultValue.parse("152"));
        assertEquals("152", ResultValue.parse("152").display());
        assertEquals(new Numeric(new BigDecimal("-0.5"), 1), ResultValue.parse("-0.5"));
        assertEquals("4.80", ResultValue.parse(" 4,80\n").display());
    }

    @Test void parsesComparators() {
        assertEquals(new Compared("<", new BigDecimal("25"), 0), ResultValue.parse("<25"));
        assertEquals(new Compared(">", new BigDecimal("9000"), 0), ResultValue.parse(">9000"));
        assertEquals(">9000", ResultValue.parse(">9000").display());
        assertEquals(new Compared("<", new BigDecimal("0.25"), 2), ResultValue.parse("< 0.25"));
        assertEquals("<0.25", ResultValue.parse("< 0.25").display());
        assertEquals(new Compared("≤", new BigDecimal("3.9"), 1), ResultValue.parse("≤3.9"));
        assertEquals("≤3.9", ResultValue.parse("≤3.9").display());
        assertEquals(new Compared("≥", new BigDecimal("1.50"), 2), ResultValue.parse("≥ 1,50"));
        assertEquals("≥1.50", ResultValue.parse("≥ 1,50").display());
    }

    @Test void everythingElseIsText() {
        for (String text : List.of("Negatīvs", "Pozitīvs 150.2 U/mL", "Rh(D) pozitīvs", "-", "+")) {
            assertEquals(new Text(text), ResultValue.parse(text), text);
            assertEquals(text, ResultValue.parse(text).display());
        }
    }

    @Test void numberLookalikesAreText() {
        for (String text : List.of("1 52", "12.3.4", "+5", "5.", ".5", "1e5", "<", "<abc", "<<5", ">=5", "5 %",
                "<5 mg")) {
            assertEquals(new Text(text), ResultValue.parse(text), text);
        }
    }

    @Test void textIsTrimmedWithRunsOfWhiteSpaceCollapsed() {
        assertEquals(new Text("Rh(D) pozitīvs"), ResultValue.parse("  Rh(D) \t pozitīvs\n"));
        assertEquals("IR ATRASTA", new Text("IR  ATRASTA").text());
        assertEquals("Rh(D) pozitīvs", ResultValue.parse("Rh(D)   pozitīvs").display());
    }

    @Test void scalesByPowersOfTen() {
        assertEquals("141", ResultValue.parse("14.1").scaled(TEN).display());
        assertEquals("320", ResultValue.parse("32.0").scaled(TEN).display());
        assertEquals(new Numeric(new BigDecimal("141"), 0), ResultValue.parse("14.1").scaled(TEN));
        assertEquals("2.90", ResultValue.parse("0.290").scaled(TEN).display());
        assertEquals("150", ResultValue.parse("1.5").scaled(HUNDRED).display());
        assertEquals("-15", ResultValue.parse("-1.5").scaled(TEN).display());
        assertEquals("4.80", ResultValue.parse("4.80").scaled(BigDecimal.ONE).display());
        assertEquals(new Compared("<", new BigDecimal("5"), 0), ResultValue.parse("<0.5").scaled(TEN));
        assertEquals("≥2500", ResultValue.parse("≥25").scaled(HUNDRED).display());
        assertEquals(new Text("Negatīvs"), ResultValue.parse("Negatīvs").scaled(TEN));
    }

    @Test void readsTheFactorByValueNotByRepresentation() {
        assertEquals("141", ResultValue.parse("14.1").scaled(new BigDecimal("1E+1")).display());
        assertEquals("141", ResultValue.parse("14.1").scaled(new BigDecimal("10.0")).display());
        assertEquals("1410", ResultValue.parse("14.1").scaled(new BigDecimal("1E+2")).display());
    }

    @Test void rejectsFactorsThatAreNotPowersOfTenFromOne() {
        for (String factor : List.of("0", "-10", "2", "15", "0.1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ResultValue.parse("14.1").scaled(new BigDecimal(factor)), factor);
            assertThrows(IllegalArgumentException.class,
                    () -> ResultValue.parse("<14.1").scaled(new BigDecimal(factor)), factor);
        }
    }

    @Test void sameAsComparesNumbersByValueAndTextLoosely() {
        assertTrue(same("4.80", "4.8"));
        assertTrue(same("4,80", "4.8"));
        assertFalse(same("4.8", "4.9"));
        assertTrue(same("Negatīvs", "negatīvs"));
        assertTrue(same("Pozitīvs", "POZITĪVS"));
        assertTrue(same("IR  ATRASTA", "ir atrasta"));
        assertFalse(same("Negatīvs", "Pozitīvs"));
        assertFalse(same("<25", "25"));
        assertFalse(same("25", "<25"));
        assertFalse(same("<25", ">25"));
        assertFalse(same("<25", "≤25"));
        assertTrue(same("<25", "< 25.0"));
        assertFalse(same("<25", "<26"));
        assertFalse(same("25", "Negatīvs"));
        assertFalse(same("Negatīvs", "25"));
    }

    @Test void comparedAcceptsOnlyTheFourComparators() {
        for (String op : List.of("<", ">", "≤", "≥")) {
            assertEquals(op, new Compared(op, BigDecimal.ONE, 0).op());
        }
        for (String op : List.of("=", "<=", ">=", "", "<>")) {
            assertThrows(IllegalArgumentException.class, () -> new Compared(op, BigDecimal.ONE, 0), op);
        }
    }

    @Test void numbersAlwaysCarryTheirDeclaredDecimals() {
        assertEquals(new Numeric(new BigDecimal("4.80"), 2), new Numeric(new BigDecimal("4.8"), 2));
        assertEquals("4.80", new Numeric(new BigDecimal("4.8"), 2).display());
        assertEquals("<5.0", new Compared("<", new BigDecimal("5"), 1).display());
    }

    private static boolean same(String a, String b) {
        return ResultValue.parse(a).sameAs(ResultValue.parse(b));
    }
}
