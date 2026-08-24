package org.traccar.vinmapping;

import org.apache.poi.ss.usermodel.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class VinMappingFileParser {

    public static class ParsedRow {
        private final int rowNumber;
        private String imei;
        private String vin;
        private Long groupId;
        private Long organizationId;
        private String parseError;

        public ParsedRow(int rowNumber) {
            this.rowNumber = rowNumber;
        }
        public int getRowNumber() {
            return rowNumber;
        }

        public String getImei() {
            return imei;
        }

        public String getVin() {
            return vin;
        }

        public Long getGroupId() {
            return groupId;
        }

        public Long getOrganizationId() {
            return organizationId;
        }

        public String getParseError() {
            return parseError;
        }
    }

    private VinMappingFileParser() {
    }

    public static List<ParsedRow> parse(String filename, InputStream inputStream) throws IOException {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return parseXlsx(inputStream);
        } else {
            return parseCsv(inputStream);
        }
    }

    private static List<ParsedRow> parseCsv(InputStream inputStream) throws IOException {
        List<ParsedRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                return rows;
            }
            Map<String, Integer> columnIndex = indexHeaders(splitCsvLine(headerLine));

            String line;
            int rowNumber = 0;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = splitCsvLine(line);
                rows.add(toParsedRow(rowNumber, columnIndex, fields));
            }
        }
        return rows;
    }

    private static List<ParsedRow> parseXlsx(InputStream inputStream) throws IOException {
        List<ParsedRow> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                return rows;
            }

            Map<String, Integer> columnIndex = new HashMap<>();
            for (Cell cell : headerRow) {
                columnIndex.put(cellToString(cell).trim().toLowerCase(), cell.getColumnIndex());
            }

            int rowNumber = 0;
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowBlank(row)) {
                    continue;
                }
                rowNumber++;
                String[] fields = new String[columnIndex.size() == 0 ? 0
                        : columnIndex.values().stream().mapToInt(i -> i).max().orElse(0) + 1];
                for (Map.Entry<String, Integer> entry : columnIndex.entrySet()) {
                    Cell cell = row.getCell(entry.getValue());
                    fields[entry.getValue()] = cell == null ? "" : cellToString(cell);
                }
                rows.add(toParsedRow(rowNumber, columnIndex, fields));
            }
        }
        return rows;
    }

    private static Map<String, Integer> indexHeaders(String[] headers) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            index.put(headers[i].trim().toLowerCase(), i);
        }
        return index;
    }

    private static ParsedRow toParsedRow(int rowNumber, Map<String, Integer> columnIndex, String[] fields) {
        ParsedRow row = new ParsedRow(rowNumber);

        Integer imeiIdx = columnIndex.get("imei");
        Integer vinIdx = columnIndex.get("vin");
        Integer groupIdx = columnIndex.get("groupid");
        Integer orgIdx = columnIndex.get("organizationid");

        if (imeiIdx == null || vinIdx == null) {
            row.parseError = "File must contain 'imei' and 'vin' columns";
            return row;
        }

        row.imei = safeGet(fields, imeiIdx);
        row.vin = safeGet(fields, vinIdx);

        if (groupIdx != null) {
            row.groupId = parseLongOrNull(safeGet(fields, groupIdx));
        }
        if (orgIdx != null) {
            row.organizationId = parseLongOrNull(safeGet(fields, orgIdx));
        }

        return row;
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String safeGet(String[] fields, int index) {
        return index >= 0 && index < fields.length && fields[index] != null ? fields[index].trim() : "";
    }

    private static boolean isRowBlank(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.BLANK && !cellToString(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String cellToString(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double value = cell.getNumericCellValue();
                yield value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    private static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
