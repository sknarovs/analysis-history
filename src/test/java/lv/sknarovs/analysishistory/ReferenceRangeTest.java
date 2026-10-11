package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lv.sknarovs.analysishistory.ReferenceRange.Above;
import lv.sknarovs.analysishistory.ReferenceRange.Below;
import lv.sknarovs.analysishistory.ReferenceRange.Between;
import lv.sknarovs.analysishistory.ReferenceRange.None;
import lv.sknarovs.analysishistory.ReferenceRange.TextReference;
import org.junit.jupiter.api.Test;

class ReferenceRangeTest {
    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Optional<Flag> HIGH = Optional.of(Flag.HIGH);
    private static final Optional<Flag> LOW = Optional.of(Flag.LOW);
    private static final Optional<Flag> NONE = Optional.of(Flag.NONE);
    private static final Optional<Flag> UNCERTAIN = Optional.empty();

    @Test void parsesAllShapes() {
        assertEquals(new Between(new BigDecimal("4.5"), new BigDecimal("5.9")), ReferenceRange.parse("4.5 - 5.9"));
        assertEquals(new Between(new BigDecimal("9.1"), new BigDecimal("12.6")), ReferenceRange.parse("9.1-12.6"));
        assertEquals(new Between(new BigDecimal("13.2"), new BigDecimal("17.5")),
                ReferenceRange.parse("(13.2 - 17.5)"));
        assertEquals(new Between(new BigDecimal("1.002"), new BigDecimal("1.035")),
                ReferenceRange.parse("(1.002-1.035)"));
        assertEquals(new Below(new BigDecimal("41"), false), ReferenceRange.parse("<41"));
        assertEquals(new Below(new BigDecimal("3.9"), true), ReferenceRange.parse("≤3.9"));
        assertEquals(new Above(new BigDecimal("90"), false), ReferenceRange.parse(">90"));
        assertEquals(new Above(new BigDecimal("1.0"), true), ReferenceRange.parse("≥1.0"));
        for (String none : List.of("-", "(-)", "")) {
            assertEquals(new None(), ReferenceRange.parse(none), none);
        }
        assertEquals(new TextReference("negatīvs"), ReferenceRange.parse("negatīvs"));
        assertEquals(new TextReference("Norma"), ReferenceRange.parse("Norma"));
        assertEquals(new TextReference("nav atrastas"), ReferenceRange.parse("(nav atrastas)"));
    }

    @Test void readsNumbersTheWayValuesAreRead() {
        assertEquals(new Between(new BigDecimal("4.5"), new BigDecimal("5.9")), ReferenceRange.parse("4,5 - 5,9"));
        assertEquals(new Between(new BigDecimal("-2.0"), new BigDecimal("3.0")), ReferenceRange.parse("-2.0 - 3.0"));
        assertEquals(new Between(new BigDecimal("-2.0"), new BigDecimal("-1.0")), ReferenceRange.parse("(-2.0--1.0)"));
        assertEquals(new Below(new BigDecimal("5"), false), ReferenceRange.parse("< 5"));
        assertEquals(new Above(new BigDecimal("1.5"), true), ReferenceRange.parse("(≥ 1,5)"));
        assertEquals(new Between(new BigDecimal("13.2"), new BigDecimal("17.5")),
                ReferenceRange.parse("  ( 13.2 - 17.5 )  "));
        assertEquals(new None(), ReferenceRange.parse("  ( )  "));
    }

    @Test void anythingElseIsATextReference() {
        for (String text : List.of("5", "4.5 - x", "1 - 2 - 3", "(a) (b)", "+", ">= 5", "(13.2 - 17.5")) {
            assertEquals(new TextReference(text), ReferenceRange.parse(text), text);
        }
    }

    @Test void judgesInclusiveBetween() {
        assertJudged(NONE, "62.0 - 106.0", "106.0");
        assertJudged(HIGH, "62.0 - 106.0", "107.0");
        assertJudged(LOW, "62.0 - 106.0", "61.9");
        assertJudged(NONE, "62.0 - 106.0", "62.0");
        assertJudged(NONE, "62.0 - 106.0", "88");
    }

    @Test void judgesOpenBounds() {
        assertJudged(HIGH, "<5.00", "5.04");
        assertJudged(HIGH, "<5.00", "5.00");
        assertJudged(NONE, "<5.00", "4.99");
        assertJudged(LOW, ">90", "88");
        assertJudged(LOW, ">90", "90");
        assertJudged(NONE, ">90", "91");
        assertJudged(NONE, "≥1.0", "1.0");
        assertJudged(LOW, "≥1.0", "0.9");
        assertJudged(NONE, "≤3.9", "3.9");
        assertJudged(HIGH, "≤3.9", "4.0");
    }

    @Test void judgesComparatorsOnlyWhenCertain() {
        // The true value of "<x" or ">x" lies beyond x: a flag is given only if every such value gets it.
        assertJudged(HIGH, "<60", ">9000");
        assertJudged(HIGH, "<60", ">60");
        assertJudged(UNCERTAIN, "<60", ">59");
        assertJudged(NONE, "<25", "<25");
        assertJudged(NONE, "<0.5", "<0.5");
        assertJudged(NONE, "<60", "<10");
        assertJudged(UNCERTAIN, "<10", "<25");
        assertJudged(UNCERTAIN, "<60", "<61");

        assertJudged(NONE, ">90", ">90");
        assertJudged(NONE, ">90", ">100");
        assertJudged(UNCERTAIN, ">90", ">89");
        assertJudged(LOW, ">90", "<90");
        assertJudged(LOW, ">90", "<10");
        assertJudged(UNCERTAIN, ">90", "<91");

        assertJudged(LOW, "62.0 - 106.0", "<62");
        assertJudged(LOW, "62.0 - 106.0", "<10");
        assertJudged(UNCERTAIN, "62.0 - 106.0", "<70");
        assertJudged(UNCERTAIN, "62.0 - 106.0", "<106");
        assertJudged(HIGH, "62.0 - 106.0", ">106");
        assertJudged(HIGH, "62.0 - 106.0", ">500");
        assertJudged(UNCERTAIN, "62.0 - 106.0", ">100");
        assertJudged(UNCERTAIN, "62.0 - 106.0", ">62");
    }

    @Test void whetherABoundIsInclusiveDoesNotMatterForComparators() {
        assertJudged(HIGH, "≤60", ">60");
        assertJudged(NONE, "≤60", "<60");
        assertJudged(NONE, "≥90", ">90");
        assertJudged(LOW, "≥90", "<90");
    }

    @Test void doesNotJudgeInclusiveComparatorsAtABoundary() {
        // "≤x" may equal x, which a range can place on either side of its edge: no certain outcome.
        assertJudged(UNCERTAIN, "<60", "≤60");
        assertJudged(UNCERTAIN, ">90", "≥90");
        assertJudged(UNCERTAIN, "≤60", "≥60");
        assertJudged(UNCERTAIN, "≥90", "≤90");
        assertJudged(UNCERTAIN, "62.0 - 106.0", "≤62");
        assertJudged(UNCERTAIN, "62.0 - 106.0", "≥106");
    }

    @Test void cannotJudgeTextOrMissingRanges() {
        for (String range : List.of("negatīvs", "(nav atrastas)", "-", "")) {
            for (String value : List.of("5", "<5", ">5", "Negatīvs")) {
                assertJudged(UNCERTAIN, range, value);
            }
        }
        for (String range : List.of("4.5 - 5.9", "<5", ">1", "≥1.0")) {
            assertJudged(UNCERTAIN, range, "Negatīvs");
        }
    }

    @Test void judgesConvertedValuesAgainstConvertedRanges() {
        ReferenceRange grams = ReferenceRange.parse("(13.2 - 17.5)").scaled(TEN);
        assertEquals(NONE, grams.judge(ResultValue.parse("14.1").scaled(TEN)));
        assertEquals(LOW, grams.judge(ResultValue.parse("12.9").scaled(TEN)));
        assertEquals(HIGH, grams.judge(ResultValue.parse("17.6").scaled(TEN)));
    }

    @Test void displaysWithEnDashAndScales() {
        assertEquals("132–175", ReferenceRange.parse("(13.2 - 17.5)").scaled(TEN).display());
        assertEquals("4.0–9.8", ReferenceRange.parse("4.0 - 9.8").display());
        assertEquals("≥1.0", ReferenceRange.parse("≥1.0").display());
        assertEquals(">90", ReferenceRange.parse(">90").display());
        assertEquals("<41", ReferenceRange.parse("<41").display());
        assertEquals("≤3.9", ReferenceRange.parse("≤3.9").display());
        assertEquals("negatīvs", ReferenceRange.parse("(negatīvs)").display());
        assertEquals("", ReferenceRange.parse("(-)").display());

        assertEquals("620–1060", ReferenceRange.parse("62.0 - 106.0").scaled(TEN).display());
        assertEquals("100.2–103.5", ReferenceRange.parse("(1.002-1.035)").scaled(HUNDRED).display());
        assertEquals("<50.0", ReferenceRange.parse("<5.00").scaled(TEN).display());
        assertEquals("≥10", ReferenceRange.parse("≥1.0").scaled(TEN).display());
        assertEquals(">900", ReferenceRange.parse(">90").scaled(TEN).display());
        assertEquals("4.0–9.8", ReferenceRange.parse("4.0 - 9.8").scaled(BigDecimal.ONE).display());
        assertEquals("negatīvs", ReferenceRange.parse("negatīvs").scaled(TEN).display());
        assertEquals("", ReferenceRange.parse("-").scaled(TEN).display());
    }

    @Test void separatesBoundsWithAnEnDashNotAHyphen() {
        assertEquals('–', ReferenceRange.parse("4.0 - 9.8").display().charAt(3));
    }

    @Test void scalingKeepsWhetherABoundIsInclusive() {
        assertEquals(new Below(new BigDecimal("50.0"), true), ReferenceRange.parse("≤5.00").scaled(TEN));
        assertEquals(new Below(new BigDecimal("50.0"), false), ReferenceRange.parse("<5.00").scaled(TEN));
        assertEquals(new Above(new BigDecimal("10"), true), ReferenceRange.parse("≥1.0").scaled(TEN));
        assertEquals(new Above(new BigDecimal("900"), false), ReferenceRange.parse(">90").scaled(TEN));
    }

    @Test void scalingRejectsFactorsThatAreNotPowersOfTenFromOne() {
        for (String range : List.of("4.5 - 5.9", "<5", ">1")) {
            for (String factor : List.of("0", "-10", "2", "0.1")) {
                assertThrows(IllegalArgumentException.class,
                        () -> ReferenceRange.parse(range).scaled(new BigDecimal(factor)), range + " by " + factor);
            }
        }
    }

    @Test void onlyBoundedRangesAreNumeric() {
        assertTrue(ReferenceRange.parse("4.5 - 5.9").isNumeric());
        assertTrue(ReferenceRange.parse("<41").isNumeric());
        assertTrue(ReferenceRange.parse("≥1.0").isNumeric());
        assertFalse(ReferenceRange.parse("negatīvs").isNumeric());
        assertFalse(ReferenceRange.parse("-").isNumeric());
    }

    private static void assertJudged(Optional<Flag> expected, String range, String value) {
        assertEquals(expected, ReferenceRange.parse(range).judge(ResultValue.parse(value)),
                "'" + value + "' against '" + range + "'");
    }
}
