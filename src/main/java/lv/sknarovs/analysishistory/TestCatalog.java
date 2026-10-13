package lv.sknarovs.analysishistory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lv.sknarovs.analysishistory.CatalogMatch.Matched;
import lv.sknarovs.analysishistory.CatalogMatch.UnitMismatch;
import lv.sknarovs.analysishistory.CatalogMatch.Unknown;

/**
 * The tests the laboratories report, and which printed names are the same test. For each laboratory and specimen the
 * catalog maps the names a test is printed under to one {@link CatalogTest}, whose unit the merged row uses, and to
 * the factor that converts the laboratory's unit into it.
 * <p>
 * The catalog is the text file {@code catalog.txt}: UTF-8, {@code #} starts a comment line, and a line holding only
 * {@code [Name]} starts a section. A section implies the specimen of its tests ({@link #specimenOf}). Every other
 * line is one test, as the test's name and unit in the workbook followed by its aliases:
 * <pre>
 * display name | unit | EGL: name as printed | CLab: name as printed [printed unit ×factor] | ...
 * </pre>
 * An alias is a laboratory's label ({@link Lab#label}), a colon and the name as the laboratory prints it. It may end
 * with {@code [printed unit]} when the laboratory prints another unit than the test's, and with
 * {@code [printed unit ×factor]} when a value in that unit times the factor (10, 100, ...) is a value in the test's
 * unit; for example {@code [g/dL ×10]}. The unit is in square brackets because printed names themselves contain
 * parentheses. A test may have several aliases, also for one laboratory; no alias may belong to two tests.
 * <p>
 * A printed result matches an alias when laboratory, specimen and name agree ({@link #normaliseName}) and its unit
 * equals the alias's printed unit, or the test's unit when the alias has none ({@link #normaliseUnit}), so that units
 * are never mixed silently.
 */
final class TestCatalog {
    private static final String RESOURCE = "catalog.txt";
    private static final String URINE_SECTION = "Urīna analīze";
    private static final String URINE_SEDIMENT_SECTION = "Urīna mikroskopija";
    /** A section header: the whole line is a name in square brackets. */
    private static final Pattern SECTION = Pattern.compile("\\[([^\\[\\]|]+)\\]");
    /** The factor after {@code ×} in a unit override. */
    private static final Pattern FACTOR = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** A hyphen or comma (group 1) with a space on either side, which the laboratories print inconsistently. */
    private static final Pattern NAME_SEPARATOR = Pattern.compile(" ?([-,]) ?");
    /** The powers of ten that appear in units, written with an {@code x} or an {@code e} (group 1 is the exponent). */
    private static final Pattern POWER = Pattern.compile("10[xe](9|12)");
    /** Units that mean the same once normalised: CLab's urine strip prints per microlitre as {@code Ery/uL}. */
    private static final Map<String, String> UNIT_EQUIVALENTS = Map.of("ery/ul", "/ul", "leu/ul", "/ul");

    /** The key an alias is found by: normalised name, because the laboratories vary case and spacing. */
    private record Key(Lab lab, Specimen specimen, String name) {
    }

    /** What an alias stands for: the unit its laboratory prints (as written) and the factor into the test's unit. */
    private record Alias(CatalogTest test, String expectedUnit, BigDecimal factor) {
    }

    private final List<String> sections = new ArrayList<>();
    private final Map<Key, Alias> aliases = new HashMap<>();
    private int testCount;

    private TestCatalog() {
    }

    /**
     * The catalog shipped inside the program, {@code catalog.txt} next to this class.
     *
     * @throws IllegalArgumentException if the file is malformed, which the unit tests rule out
     */
    static TestCatalog bundled() {
        try (InputStream in = TestCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the program lacks its " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads a catalog from its text.
     *
     * @throws IllegalArgumentException naming the line number and the line, for a line that is not a section header
     *         or a test with at least one alias, a test above the first section, an unknown laboratory, an alias
     *         that is already taken, a unit override that is not {@code [unit]} or {@code [unit ×factor]}, or a
     *         factor that is not 1, 10, 100, ...
     */
    static TestCatalog parse(String text) {
        TestCatalog catalog = new TestCatalog();
        int number = 0;
        for (String raw : text.lines().toList()) {
            number++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                catalog.read(line);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("catalog line " + number + " '" + line + "': " + e.getMessage(), e);
            }
        }
        return catalog;
    }

    /**
     * What the catalog makes of a result that {@code lab} printed with {@code name} and {@code unit}: {@link Matched}
     * when it is a known test in that unit, {@link UnitMismatch} when the name is known (for this laboratory and
     * specimen) but the unit is not the expected one, {@link Unknown} when the name is not known. {@code unit} is
     * {@code ""} when the report prints none.
     */
    CatalogMatch match(Lab lab, Specimen specimen, String name, String unit) {
        Alias alias = aliases.get(new Key(lab, specimen, normaliseName(name)));
        if (alias == null) {
            return new Unknown();
        }
        if (!normaliseUnit(alias.expectedUnit()).equals(normaliseUnit(unit))) {
            return new UnitMismatch(alias.test(), alias.expectedUnit(), unit);
        }
        return new Matched(alias.test(), alias.factor());
    }

    /** The section names in the order of the file. */
    List<String> sections() {
        return List.copyOf(sections);
    }

    /** The specimen of the tests in {@code section}: urine, urine sediment, and blood for any other section. */
    static Specimen specimenOf(String section) {
        return switch (section) {
            case URINE_SECTION -> Specimen.URINE;
            case URINE_SEDIMENT_SECTION -> Specimen.URINE_SEDIMENT;
            default -> Specimen.BLOOD;
        };
    }

    /**
     * {@code name} in the form names are compared in: lower case, whitespace collapsed to single spaces, and no
     * spaces around hyphens and commas, so {@code TSH-Tireotropais hormons} and {@code TSH - Tireotropais  hormons}
     * are one name.
     */
    static String normaliseName(String name) {
        String spaced = WHITESPACE.matcher(name.strip()).replaceAll(" ");
        return NAME_SEPARATOR.matcher(spaced).replaceAll("$1").toLowerCase(Locale.ROOT);
    }

    /**
     * {@code unit} in the form units are compared in: no spaces, lower case, {@code µ} (micro sign or Greek mu) as
     * {@code u}, {@code 10x9}, {@code 10e9} and {@code 10⁹} as {@code 10^9} (likewise for 12), {@code m²} as
     * {@code m2}, and {@code Ery/uL} and {@code Leu/uL} as {@code /uL}.
     */
    static String normaliseUnit(String unit) {
        String compact = WHITESPACE.matcher(unit).replaceAll("")
                .replace('µ', 'u') // micro sign, U+00B5
                .replace('μ', 'u') // Greek small letter mu, U+03BC
                .toLowerCase(Locale.ROOT);
        String normal = POWER.matcher(compact).replaceAll("10^$1")
                .replace("10⁹", "10^9")
                .replace("10¹²", "10^12")
                .replace("m²", "m2");
        return UNIT_EQUIVALENTS.getOrDefault(normal, normal);
    }

    /** Reads one line that is not blank and not a comment: a section header, or a test, which never starts with [. */
    private void read(String line) {
        Matcher header = SECTION.matcher(line);
        if (header.matches()) {
            addSection(header.group(1).strip());
        } else if (line.startsWith("[")) {
            throw new IllegalArgumentException("expected a section header '[Name]'");
        } else {
            addTest(line);
        }
    }

    private void addSection(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("empty section name");
        }
        if (sections.contains(name)) {
            throw new IllegalArgumentException("duplicate section '" + name + "'");
        }
        sections.add(name);
    }

    private void addTest(String line) {
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("no section header above this test");
        }
        List<String> fields = Arrays.stream(line.split("\\|", -1)).map(String::strip).toList();
        if (fields.size() < 3) {
            throw new IllegalArgumentException("expected '<name> | <unit> | <laboratory>: <printed name>'");
        }
        if (fields.get(0).isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }
        String section = sections.get(sections.size() - 1);
        CatalogTest test = new CatalogTest(section, fields.get(0), fields.get(1), testCount++);
        for (String alias : fields.subList(2, fields.size())) {
            addAlias(test, alias);
        }
    }

    /** Adds {@code alias}, {@code <laboratory>: <printed name>} and an optional {@code [unit]} or {@code [unit ×n]}. A
     * {@code [unit]} without a factor must be a spelling of the test's unit, or empty (the laboratory prints no unit). */
    private void addAlias(CatalogTest test, String alias) {
        int colon = alias.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("alias '" + alias + "' is not '<laboratory>: <printed name>'");
        }
        Lab lab = labOf(alias.substring(0, colon).strip());
        String printed = alias.substring(colon + 1).strip();
        String expectedUnit = test.unit();
        BigDecimal factor = BigDecimal.ONE;
        int open = printed.indexOf('[');
        if (open >= 0) {
            if (!printed.endsWith("]")) {
                throw new IllegalArgumentException(
                        "unit override '" + printed.substring(open) + "' must end with a closing ']'");
            }
            String override = printed.substring(open + 1, printed.length() - 1);
            printed = printed.substring(0, open).strip();
            int times = override.lastIndexOf('×');
            if (times >= 0) {
                factor = factorOf(override.substring(times + 1).strip());
                override = override.substring(0, times);
            }
            expectedUnit = override.strip();
            if (times < 0) {
                if (expectedUnit.matches(".*[^\\s][\\sxX]*[xX]\\s*\\d+")) {
                    throw new IllegalArgumentException("unit override '[" + expectedUnit
                            + "]' ends in an ASCII 'x' factor; write the factor as ×10");
                }
                if (!expectedUnit.isEmpty() && !normaliseUnit(expectedUnit).equals(normaliseUnit(test.unit()))) {
                    throw new IllegalArgumentException("unit override '[" + expectedUnit + "]' differs from the unit '"
                            + test.unit() + "' but has no conversion factor; write it, e.g. [" + expectedUnit
                            + " ×10]");
                }
            }
        }
        if (printed.isEmpty()) {
            throw new IllegalArgumentException("empty printed name");
        }
        Key key = new Key(lab, specimenOf(test.section()), normaliseName(printed));
        if (aliases.putIfAbsent(key, new Alias(test, expectedUnit, factor)) != null) {
            throw new IllegalArgumentException("duplicate alias '" + alias + "'");
        }
    }

    private static Lab labOf(String label) {
        for (Lab lab : Lab.values()) {
            if (lab.label.equals(label)) {
                return lab;
            }
        }
        String labels = Arrays.stream(Lab.values()).map(lab -> lab.label).collect(Collectors.joining(", "));
        throw new IllegalArgumentException("unknown laboratory '" + label + "' (use " + labels + ")");
    }

    private static BigDecimal factorOf(String text) {
        if (!FACTOR.matcher(text).matches()) {
            throw new IllegalArgumentException("the factor after '×' must be a whole number, not '" + text + "'");
        }
        BigDecimal factor = new BigDecimal(text);
        // Converting 1 is only the check that the factor is 1, 10, 100, ..., which the conversion of values needs.
        Decimals.convert(BigDecimal.ONE, factor);
        return factor;
    }
}
