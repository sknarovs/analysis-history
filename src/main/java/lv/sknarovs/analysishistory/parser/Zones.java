package lv.sknarovs.analysishistory.parser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lv.sknarovs.analysishistory.pdf.Cell;
import lv.sknarovs.analysishistory.pdf.Row;

/**
 * The columns of a results table, found from its header row: each field is the x, in points, where that column's
 * zone starts. Everything left of all of them is the name column. The order differs between laboratories (EGL prints
 * value, range, unit, previous result; CLab value, unit, range, previous result), so nothing here assumes one.
 */
record Zones(float value, float unit, float range, float previous) {
    /** Cells may start this many points left of their column's header text. */
    private static final float MARGIN = 12;

    /** The columns of a results table. */
    enum Zone {
        NAME,
        VALUE,
        UNIT,
        RANGE,
        PREVIOUS
    }

    /** The zones of an EGL header row ({@code Rezultāts | References | Vienības | Iepr.rezult./datums}). */
    static Optional<Zones> egl(Row header) {
        return fromHeader(header, "Rezultāts", "Vienības", "References", "Iepr.rezult./datums");
    }

    /**
     * The zones of a CLab header row ({@code Tests | Rezultāts | Vienības | References intervāls |
     * Iepriekšējais rezultāts | Datums}).
     */
    static Optional<Zones> clab(Row header) {
        return fromHeader(header, "Rezultāts", "Vienības", "References intervāls", "Iepriekšējais rezultāts", "Tests",
                "Datums");
    }

    /** The zone of a cell that starts at {@code x}: the one whose start is nearest to its left, else the name. */
    Zone of(float x) {
        Zone zone = Zone.NAME;
        for (Zone candidate : Zone.values()) {
            if (start(candidate) <= x && start(candidate) > start(zone)) {
                zone = candidate;
            }
        }
        return zone;
    }

    private float start(Zone zone) {
        return switch (zone) {
            case NAME -> Float.NEGATIVE_INFINITY;
            case VALUE -> value;
            case UNIT -> unit;
            case RANGE -> range;
            case PREVIOUS -> previous;
        };
    }

    /**
     * Zones from the cells titled {@code value}, {@code unit}, {@code range} and {@code previous}; empty unless the row
     * has a cell with each of those titles and of {@code others}.
     */
    private static Optional<Zones> fromHeader(Row row, String value, String unit, String range, String previous,
            String... others) {
        Map<String, Float> x = new HashMap<>();
        for (Cell cell : row.cells()) {
            x.putIfAbsent(cell.text(), cell.x());
        }
        Set<String> titles = x.keySet();
        if (!titles.containsAll(List.of(value, unit, range, previous)) || !titles.containsAll(List.of(others))) {
            return Optional.empty();
        }
        return Optional.of(new Zones(x.get(value) - MARGIN, x.get(unit) - MARGIN, x.get(range) - MARGIN,
                x.get(previous) - MARGIN));
    }
}
