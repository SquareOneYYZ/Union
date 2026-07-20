package org.traccar.helper;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public final class BulkUploadDevice {

    private BulkUploadDevice() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

    public static class FileTooLargeException extends RuntimeException { }

    public static class InvalidFileFormatException extends RuntimeException {
        public InvalidFileFormatException(String msg) {
            super(msg);
        }
    }

    public static List<String[]> readAndParse(InputStream is, String contentType) throws Exception {
        byte[] fileBytes = readWithSizeLimit(is, MAX_UPLOAD_BYTES);
        boolean isXlsx = contentType != null && contentType.contains("spreadsheetml");
        return isXlsx
                ? parseXlsx(new java.io.ByteArrayInputStream(fileBytes))
                : parseCsv(new java.io.ByteArrayInputStream(fileBytes));
    }

    private static List<String[]> parseCsv(InputStream is) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (lineNum == 1) {
                    validateCsvHeader(line);
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                rows.add(splitCsvLine(line));
            }
        }
        return rows;
    }

    private static void validateCsvHeader(String headerLine) {
        String[] parts = splitCsvLine(headerLine);
        if (parts.length < 2
                || !parts[0].trim().equalsIgnoreCase("name")
                || !parts[1].trim().equalsIgnoreCase("uniqueId")) {
            throw new InvalidFileFormatException(
                    "Invalid file format. First row must be: name,uniqueId — got: " + headerLine);
        }
    }

    private static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private static List<String[]> parseXlsx(InputStream is) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);
            boolean headerChecked = false;
            for (Row row : sheet) {
                if (row == null) {
                    continue;
                }
                String col0 = cellToString(row.getCell(0));
                String col1 = cellToString(row.getCell(1));
                if (!headerChecked) {
                    if (!col0.equalsIgnoreCase("name")
                            || !col1.equalsIgnoreCase("uniqueId")) {
                        throw new InvalidFileFormatException(
                                "Invalid XLSX format. First row must have columns: name, uniqueId");
                    }
                    headerChecked = true;
                    continue;
                }
                if (col0.isBlank() && col1.isBlank()) {
                    continue;
                }
                rows.add(new String[]{col0, col1});
            }
        }
        return rows;
    }

    private static String cellToString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                yield (v == Math.floor(v)) ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default      -> "";
        };
    }

    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return v;
    }

    private static byte[] readWithSizeLimit(InputStream is, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0, read;
        while ((read = is.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new FileTooLargeException();
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
