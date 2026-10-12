package com.fleetboard.data;

import java.util.Map;

/**
 * One data row from the workbook.
 *
 * @param id     stable id: sheet name + row number
 * @param sheet  sheet the row came from
 * @param title  human-readable headline for the row
 * @param fields column header -> cell value (non-blank cells only, in column order)
 * @param text   all searchable text for the row
 */
public record SheetRecord(String id, String sheet, String title, Map<String, String> fields, String text) {

    public String field(String... names) {
        for (String name : names) {
            String value = fields.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** First http(s) URL found in the row, if any. */
    public String firstUrl() {
        for (String value : fields.values()) {
            for (String part : value.split("\\s+")) {
                if (part.startsWith("http://") || part.startsWith("https://")) {
                    return part;
                }
            }
        }
        return null;
    }

    /** Compact one-line form used as context for the AI model. */
    public String toContextLine() {
        StringBuilder sb = new StringBuilder("[").append(sheet).append("] ");
        fields.forEach((k, v) -> sb.append(k).append(": ").append(v.replace('\n', ' ')).append(" | "));
        return sb.toString();
    }
}
