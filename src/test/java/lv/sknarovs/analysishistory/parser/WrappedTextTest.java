package lv.sknarovs.analysishistory.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WrappedTextTest {
    @Test void partsJoinWithASpaceButUnitsGoOnStraightAfterASlash() {
        assertEquals("Nav atrastas", WrappedText.join("Nav", "atrastas", false));
        assertEquals("mL/min/1.73 m2", WrappedText.join("mL/min/", "1.73 m2", true));
        assertEquals("ml/min/1.73 m²", WrappedText.join("ml/min/1.73", "m²", true));
    }
}
