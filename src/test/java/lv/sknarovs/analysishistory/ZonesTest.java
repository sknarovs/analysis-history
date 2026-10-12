package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import lv.sknarovs.analysishistory.Zones.Zone;
import org.junit.jupiter.api.Test;

class ZonesTest {
    private static final String EGL_HEADER = "179:Rezultāts | 269:References | 340:Vienības | 403:Iepr.rezult./datums";
    private static final String CLAB_HEADER = "49:*Tests | 194:*Rezultāts | 257:*Vienības | 318:*References intervāls"
            + " | 417:*Iepriekšējais rezultāts | 522:*Datums";

    @Test void zonesStartTwelvePointsLeftOfTheirHeaderCells() {
        assertEquals(Optional.of(new Zones(167, 328, 257, 391)), Zones.egl(row(EGL_HEADER)));
        assertEquals(Optional.of(new Zones(182, 245, 306, 405)), Zones.clab(row(CLAB_HEADER)));
    }

    @Test void onlyAWholeHeaderOfThatLabIsAHeader() {
        assertEquals(Optional.empty(), Zones.egl(row(CLAB_HEADER)));
        assertEquals(Optional.empty(), Zones.clab(row(EGL_HEADER)));
        assertEquals(Optional.empty(), Zones.egl(row("179:Rezultāts | 269:References | 340:Vienības")));
        assertEquals(Optional.empty(), Zones.clab(row("194:*Rezultāts | 257:*Vienības | 318:*References intervāls"
                + " | 417:*Iepriekšējais rezultāts | 522:*Datums")));
        assertEquals(Optional.empty(), Zones.clab(row("49:*Tests | 194:*Rezultāts | 257:*Vienības"
                + " | 318:*References intervāls | 417:*Iepriekšējais rezultāts")));
        assertEquals(Optional.empty(), Zones.egl(row("28:1/4 A | 62:Hemoglobīns | 179:150 | 269:132 - 175 | 340:g/L")));
    }

    @Test void xIsInTheZoneThatStartsNearestToItsLeft() {
        // EGL prints value, range, unit, previous result; CLab prints value, unit, range, previous result.
        Zones egl = new Zones(167, 328, 257, 391);
        Map.of(62f, Zone.NAME, 166.9f, Zone.NAME, 167f, Zone.VALUE, 249f, Zone.VALUE, 257f, Zone.RANGE,
                327.9f, Zone.RANGE, 340f, Zone.UNIT, 391f, Zone.PREVIOUS, 445f, Zone.PREVIOUS)
                .forEach((x, zone) -> assertEquals(zone, egl.of(x), "EGL x " + x));
        Zones clab = new Zones(182, 245, 306, 405);
        Map.of(46f, Zone.NAME, 213f, Zone.VALUE, 253f, Zone.UNIT, 278f, Zone.UNIT, 349f, Zone.RANGE,
                420f, Zone.PREVIOUS, 505f, Zone.PREVIOUS)
                .forEach((x, zone) -> assertEquals(zone, clab.of(x), "CLab x " + x));
    }

    private static Row row(String cells) {
        return Fixtures.parse(cells).get(0).rows().get(0);
    }
}
