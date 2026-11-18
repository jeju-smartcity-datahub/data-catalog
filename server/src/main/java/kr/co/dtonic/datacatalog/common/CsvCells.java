package kr.co.dtonic.datacatalog.common;

/** Quotes CSV text without allowing spreadsheet formulas hidden behind Unicode prefixes. */
public final class CsvCells {
    private CsvCells() { }

    public static String quote(Object value) {
        String text = value == null ? "" : value.toString();
        if (requiresTextPrefix(text)) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static boolean requiresTextPrefix(String text) {
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            // These characters can start a new expression when a spreadsheet imports a cell.
            if (codePoint == '\t' || codePoint == '\r' || codePoint == '\n') return true;
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                    && Character.getType(codePoint) != Character.FORMAT
                    && Character.getType(codePoint) != Character.CONTROL) break;
            offset += Character.charCount(codePoint);
        }
        if (offset == text.length()) return false;
        return switch (text.codePointAt(offset)) {
            case '=', '+', '-', '@', '＝', '＋', '－', '＠' -> true;
            default -> false;
        };
    }
}
