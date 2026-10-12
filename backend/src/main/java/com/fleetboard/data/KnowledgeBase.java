package com.fleetboard.data;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads every sheet of the workbook into {@link SheetRecord}s.
 * Each sheet has a title block on top; the header row is the first row with two or more filled cells.
 */
@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    public static final String FEATURE_MATRIX = "Feature Matrix";
    public static final String FEATURE_DICTIONARY = "Feature Dictionary";
    public static final String EVIDENCE = "Evidence";
    public static final String OEM_STP = "OEM STP";

    /** Status codes used in the feature matrix and evidence register. */
    public static final Map<String, String> STATUS_LABELS = Map.of(
            "D", "Documented",
            "P", "Partner",
            "A", "Announced",
            "NV", "Not verified");

    private final List<SheetRecord> records = new ArrayList<>();
    private final Map<String, String> sheetDescriptions = new LinkedHashMap<>();
    private final List<String> oemNames = new ArrayList<>();

    public KnowledgeBase(@Value("${fleet.data.file:}") String dataFile) throws IOException {
        try (InputStream in = open(dataFile); Workbook workbook = WorkbookFactory.create(in)) {
            DataFormatter formatter = new DataFormatter();
            for (Sheet sheet : workbook) {
                loadSheet(sheet, formatter);
            }
        }
        log.info("Loaded {} records from {} sheets; OEMs: {}", records.size(), sheetDescriptions.size(), oemNames);
    }

    private static InputStream open(String dataFile) throws IOException {
        if (dataFile != null && !dataFile.isBlank()) {
            log.info("Reading workbook from {}", dataFile);
            return new FileInputStream(dataFile);
        }
        return new ClassPathResource("data/fleet-data.xlsx").getInputStream();
    }

    private void loadSheet(Sheet sheet, DataFormatter formatter) {
        String name = sheet.getSheetName().trim();
        int headerRow = -1;
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            if (cellValues(sheet.getRow(r), formatter).stream().filter(v -> !v.isBlank()).count() >= 2) {
                headerRow = r;
                break;
            }
        }
        if (headerRow < 0) {
            return;
        }

        // The rows above the header hold the sheet title and a one-line description.
        StringBuilder description = new StringBuilder();
        for (int r = sheet.getFirstRowNum(); r < headerRow; r++) {
            for (String v : cellValues(sheet.getRow(r), formatter)) {
                if (!v.isBlank()) {
                    description.append(description.length() > 0 ? " — " : "").append(v);
                }
            }
        }
        sheetDescriptions.put(name, description.toString());

        List<String> headers = cellValues(sheet.getRow(headerRow), formatter);
        if (name.equals(FEATURE_MATRIX)) {
            int start = headers.indexOf("Feature") + 1;
            int end = headers.indexOf("Proposed project priority");
            if (start > 0 && end > start) {
                oemNames.addAll(headers.subList(start, end));
            }
        }

        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            List<String> values = cellValues(sheet.getRow(r), formatter);
            Map<String, String> fields = new LinkedHashMap<>();
            for (int c = 0; c < Math.min(values.size(), headers.size()); c++) {
                String header = headers.get(c).trim();
                String value = values.get(c).trim();
                if (!header.isEmpty() && !value.isEmpty()) {
                    fields.put(header, value);
                }
            }
            if (fields.isEmpty()) {
                continue;
            }
            records.add(new SheetRecord(name + "#" + (r + 1), name, titleFor(fields),
                    Collections.unmodifiableMap(fields), searchText(fields)));
        }
    }

    private static List<String> cellValues(Row row, DataFormatter formatter) {
        List<String> values = new ArrayList<>();
        if (row == null) {
            return values;
        }
        for (int c = 0; c < Math.max(row.getLastCellNum(), 0); c++) {
            Cell cell = row.getCell(c);
            values.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
        }
        return values;
    }

    private static String titleFor(Map<String, String> f) {
        String id = first(f, "ID", "Feature ID");
        String feature = f.get("Feature");
        if (feature != null) {
            String oem = f.get("OEM family");
            return (id != null ? id + " · " : "") + feature + (oem != null ? " — " + oem : "");
        }
        if (f.containsKey("Source title")) {
            return (id != null ? id + " · " : "") + f.getOrDefault("Publisher", "") + " — " + f.get("Source title");
        }
        List<String> values = new ArrayList<>(f.values());
        String title = values.get(0);
        if (title.length() < 30 && values.size() > 1) {
            title += " — " + values.get(1);
        }
        return title.length() > 110 ? title.substring(0, 107) + "…" : title;
    }

    private static String searchText(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> {
            sb.append(v).append(' ');
            String label = STATUS_LABELS.get(v);
            if (label != null) {
                // Lets "documented"/"partner" queries match matrix cells holding only a code.
                sb.append(k).append(' ').append(label).append(' ');
            }
        });
        return sb.toString();
    }

    private static String first(Map<String, String> f, String... keys) {
        for (String k : keys) {
            if (f.containsKey(k)) {
                return f.get(k);
            }
        }
        return null;
    }

    public List<SheetRecord> records() {
        return Collections.unmodifiableList(records);
    }

    public List<SheetRecord> sheet(String name) {
        return records.stream().filter(r -> r.sheet().equals(name)).toList();
    }

    public Map<String, String> sheetDescriptions() {
        return Collections.unmodifiableMap(sheetDescriptions);
    }

    public List<String> oemNames() {
        return Collections.unmodifiableList(oemNames);
    }

    public Optional<SheetRecord> matrixRow(String featureId) {
        return sheet(FEATURE_MATRIX).stream().filter(r -> featureId.equals(r.field("ID"))).findFirst();
    }

    public Optional<SheetRecord> dictionaryRow(String featureId) {
        return sheet(FEATURE_DICTIONARY).stream().filter(r -> featureId.equals(r.field("ID"))).findFirst();
    }

    public Optional<SheetRecord> evidence(String featureId, String oem) {
        return sheet(EVIDENCE).stream()
                .filter(r -> featureId.equals(r.field("Feature ID")) && oem.equals(r.field("OEM family")))
                .findFirst();
    }

    public Optional<SheetRecord> oemStp(String oem) {
        return sheet(OEM_STP).stream().filter(r -> oem.equals(r.field("OEM"))).findFirst();
    }
}
