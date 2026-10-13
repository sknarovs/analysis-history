package lv.sknarovs.analysishistory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads back what the tests check in an .xlsx workbook: cell values, number formats, fonts, fills, notes, hyperlinks
 * and frozen panes. It uses only the JDK's zip and XML APIs, so the tests run unchanged as a native image.
 */
final class XlsxReader {
    private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private XlsxReader() {
    }

    /** The workbook's sheets by name, in workbook order. */
    static Map<String, Sheet> read(Path xlsx) throws IOException {
        try (ZipFile zip = new ZipFile(xlsx.toFile())) {
            List<String> strings = sharedStrings(zip);
            Styles styles = styles(zip);
            Map<String, Relationship> parts = relationships(zip, "xl/workbook.xml");
            Map<String, Sheet> sheets = new LinkedHashMap<>();
            for (Element sheet : descendants(root(zip, "xl/workbook.xml"), "sheet")) {
                String part = parts.get(sheet.getAttributeNS(RELATIONSHIPS, "id")).target();
                sheets.put(sheet.getAttribute("name"), sheet(zip, part, strings, styles));
            }
            return sheets;
        }
    }

    /** One worksheet. Cells are addressed like {@code "D1"}; a cell without a value reads as {@code null} text. */
    static final class Sheet {
        private final Map<String, CellValue> cells;
        private final Styles styles;
        private final Map<String, String> notes;
        private final Map<String, String> links;
        private final String frozenTopLeft;
        private final Map<Integer, Double> widths;
        private final Map<Integer, Integer> outlines;

        private Sheet(Map<String, CellValue> cells, Styles styles, Map<String, String> notes,
                Map<String, String> links, String frozenTopLeft, Map<Integer, Double> widths,
                Map<Integer, Integer> outlines) {
            this.widths = widths;
            this.outlines = outlines;
            this.cells = cells;
            this.styles = styles;
            this.notes = notes;
            this.links = links;
            this.frozenTopLeft = frozenTopLeft;
        }

        /** The cell's value as text: shared and inline strings resolved, numbers as written in the file. */
        String text(String ref) {
            CellValue cell = cells.get(ref);
            return cell == null ? null : cell.text();
        }

        boolean isNumber(String ref) {
            CellValue cell = cells.get(ref);
            return cell != null && cell.number();
        }

        /** The format code of the cell's number format; {@code "General"} for the default one. */
        String numberFormat(String ref) {
            return styles.numberFormat(style(ref));
        }

        boolean bold(String ref) {
            return styles.font(style(ref)).bold();
        }

        /** The font colour as RGB hex, for example {@code "C00000"}; {@code null} when the font names no RGB colour. */
        String fontColor(String ref) {
            return styles.font(style(ref)).color();
        }

        /** The fill colour as RGB hex, for example {@code "FDE2E2"}; {@code null} when the cell has no fill. */
        String fill(String ref) {
            return styles.fill(style(ref));
        }

        /** The text of the cell's note (comment), or {@code null}. */
        String note(String ref) {
            return notes.get(ref);
        }

        /** The cell's hyperlink target as stored in the workbook, or {@code null}. */
        String hyperlink(String ref) {
            return links.get(ref);
        }

        /** The first cell below and right of the frozen panes, for example {@code "D4"}; {@code null} if none. */
        String frozenTopLeft() {
            return frozenTopLeft;
        }

        /** The outline (grouping) level of a 1-based row; 0 when the row is not grouped. */
        int outlineLevel(int row) {
            return outlines.getOrDefault(row, 0);
        }

        /** The width of a column given by letters such as {@code "A"}, in characters; {@code null} if not set. */
        Double width(String column) {
            int index = 0;
            for (char letter : column.toCharArray()) {
                index = index * 26 + (letter - 'A' + 1);
            }
            return widths.get(index);
        }

        private int style(String ref) {
            CellValue cell = cells.get(ref);
            return cell == null ? 0 : cell.style();
        }
    }

    private record CellValue(String text, boolean number, int style) {
    }

    private record Relationship(String type, String target) {
    }

    private record Font(boolean bold, String color) {
    }

    private record CellFormat(int numberFormat, int font, int fill) {
    }

    private record Styles(Map<Integer, String> numberFormats, List<Font> fonts, List<String> fills,
            List<CellFormat> cellFormats) {
        String numberFormat(int style) {
            int id = cellFormats.get(style).numberFormat();
            return id == 0 ? "General" : numberFormats.get(id);
        }

        Font font(int style) {
            return fonts.get(cellFormats.get(style).font());
        }

        String fill(int style) {
            return fills.get(cellFormats.get(style).fill());
        }
    }

    private static Sheet sheet(ZipFile zip, String part, List<String> strings, Styles styles) throws IOException {
        Element root = root(zip, part);
        Map<String, Relationship> related = relationships(zip, part);
        Map<String, CellValue> cells = new HashMap<>();
        for (Element cell : descendants(root, "c")) {
            cells.put(cell.getAttribute("r"), cellValue(cell, strings));
        }
        Map<String, String> links = new HashMap<>();
        for (Element link : descendants(root, "hyperlink")) {
            String id = link.getAttributeNS(RELATIONSHIPS, "id");
            links.put(link.getAttribute("ref"), id.isEmpty() ? link.getAttribute("location") : related.get(id).target());
        }
        Map<String, String> notes = new HashMap<>();
        for (Relationship relationship : related.values()) {
            if (relationship.type().endsWith("/comments")) {
                for (Element comment : descendants(root(zip, relationship.target()), "comment")) {
                    notes.put(comment.getAttribute("ref"), text(child(comment, "text")));
                }
            }
        }
        String frozenTopLeft = null;
        for (Element pane : descendants(root, "pane")) {
            if (pane.getAttribute("state").equals("frozen")) {
                frozenTopLeft = pane.getAttribute("topLeftCell");
            }
        }
        Map<Integer, Double> widths = new HashMap<>();
        for (Element col : descendants(root, "col")) {
            for (int i = Integer.parseInt(col.getAttribute("min")); i <= Integer.parseInt(col.getAttribute("max")); i++) {
                widths.put(i, Double.valueOf(col.getAttribute("width")));
            }
        }
        Map<Integer, Integer> outlines = new HashMap<>();
        for (Element row : descendants(root, "row")) {
            if (row.hasAttribute("outlineLevel")) {
                outlines.put(Integer.parseInt(row.getAttribute("r")), Integer.parseInt(row.getAttribute("outlineLevel")));
            }
        }
        return new Sheet(cells, styles, notes, links, frozenTopLeft, widths, outlines);
    }

    private static CellValue cellValue(Element cell, List<String> strings) {
        String type = cell.getAttribute("t");
        int style = cell.hasAttribute("s") ? Integer.parseInt(cell.getAttribute("s")) : 0;
        Element value = child(cell, "v");
        String text = switch (type) {
            case "s" -> strings.get(Integer.parseInt(value.getTextContent()));
            case "inlineStr" -> text(child(cell, "is"));
            default -> value == null ? null : value.getTextContent();
        };
        boolean number = (type.isEmpty() || type.equals("n")) && value != null;
        return new CellValue(text, number, style);
    }

    private static List<String> sharedStrings(ZipFile zip) throws IOException {
        List<String> strings = new ArrayList<>();
        if (zip.getEntry("xl/sharedStrings.xml") != null) {
            for (Element item : children(root(zip, "xl/sharedStrings.xml"), "si")) {
                strings.add(text(item));
            }
        }
        return strings;
    }

    private static Styles styles(ZipFile zip) throws IOException {
        Element root = root(zip, "xl/styles.xml");
        Map<Integer, String> numberFormats = new HashMap<>();
        for (Element format : children(child(root, "numFmts"), "numFmt")) {
            numberFormats.put(Integer.parseInt(format.getAttribute("numFmtId")), format.getAttribute("formatCode"));
        }
        List<Font> fonts = new ArrayList<>();
        for (Element font : children(child(root, "fonts"), "font")) {
            fonts.add(new Font(child(font, "b") != null, rgb(child(font, "color"))));
        }
        List<String> fills = new ArrayList<>();
        for (Element fill : children(child(root, "fills"), "fill")) {
            Element pattern = child(fill, "patternFill");
            fills.add(pattern == null ? null : rgb(child(pattern, "fgColor")));
        }
        List<CellFormat> cellFormats = new ArrayList<>();
        for (Element format : children(child(root, "cellXfs"), "xf")) {
            cellFormats.add(new CellFormat(Integer.parseInt(format.getAttribute("numFmtId")),
                    Integer.parseInt(format.getAttribute("fontId")), Integer.parseInt(format.getAttribute("fillId"))));
        }
        return new Styles(numberFormats, fonts, fills, cellFormats);
    }

    /** {@code rgb="FFC00000"} or {@code rgb="C00000"} as {@code "C00000"}; {@code null} without an RGB colour. */
    private static String rgb(Element color) {
        if (color == null || color.getAttribute("rgb").isEmpty()) {
            return null;
        }
        String rgb = color.getAttribute("rgb");
        return (rgb.length() == 8 ? rgb.substring(2) : rgb).toUpperCase(Locale.ROOT);
    }

    /** The relationships of a part by id; internal targets are resolved to zip entry names. */
    private static Map<String, Relationship> relationships(ZipFile zip, String part) throws IOException {
        int slash = part.lastIndexOf('/');
        String rels = part.substring(0, slash + 1) + "_rels/" + part.substring(slash + 1) + ".rels";
        Map<String, Relationship> relationships = new HashMap<>();
        if (zip.getEntry(rels) == null) {
            return relationships;
        }
        for (Element relationship : descendants(root(zip, rels), "Relationship")) {
            String target = relationship.getAttribute("Target");
            if (!relationship.getAttribute("TargetMode").equals("External")) {
                target = resolve(part, target);
            }
            relationships.put(relationship.getAttribute("Id"),
                    new Relationship(relationship.getAttribute("Type"), target));
        }
        return relationships;
    }

    /** Resolves a relationship target such as {@code ../comments1.xml} against the folder of {@code part}. */
    private static String resolve(String part, String target) {
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        Deque<String> path = new ArrayDeque<>(List.of(part.split("/")));
        path.removeLast();
        for (String segment : target.split("/")) {
            if (segment.equals("..")) {
                path.removeLast();
            } else if (!segment.equals(".")) {
                path.addLast(segment);
            }
        }
        return String.join("/", path);
    }

    /** The text of a string item or note: its {@code t} element, or the {@code t} of each rich-text run. */
    private static String text(Element item) {
        StringBuilder text = new StringBuilder();
        for (Element t : descendants(item, "t")) {
            Node parent = t.getParentNode();
            if (parent == item || "r".equals(parent.getLocalName())) {
                text.append(t.getTextContent());
            }
        }
        return text.toString();
    }

    private static Element root(ZipFile zip, String part) throws IOException {
        ZipEntry entry = zip.getEntry(part);
        if (entry == null) {
            throw new IOException(zip.getName() + " has no " + part);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(in).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("cannot parse " + part + " in " + zip.getName(), e);
        }
    }

    private static List<Element> descendants(Element element, String localName) {
        NodeList nodes = element.getElementsByTagNameNS("*", localName);
        List<Element> elements = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            elements.add((Element) nodes.item(i));
        }
        return elements;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> children = new ArrayList<>();
        if (parent != null) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && localName.equals(element.getLocalName())) {
                    children.add(element);
                }
            }
        }
        return children;
    }

    private static Element child(Element parent, String localName) {
        List<Element> children = children(parent, localName);
        return children.isEmpty() ? null : children.get(0);
    }
}
