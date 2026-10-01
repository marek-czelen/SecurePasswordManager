package com.example.securepasswordmanager.crypto;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded, local-only parsers for password-manager exports and spreadsheets. */
public final class AdvancedImportExport {
    public static final int MAX_IMPORT_BYTES = 20 * 1024 * 1024;
    public static final String WARNING_1PASSWORD_ATTACHMENTS_IGNORED = "onepassword-attachments";
    public static final String WARNING_BITWARDEN_ITEMS_IGNORED = "bitwarden-items";
    private static final long MAX_ZIP_EXPANDED_BYTES = 48L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 2048;
    private static final int MAX_ROWS = VaultTextDataCodec.MAX_ITEMS;
    private static final int MAX_CELLS = 100_000;
    private static final int MAX_JSON_NESTING = 64;
    private static final int MAX_KEEPASS_GROUP_NESTING = 64;
    private static final String[] FIELD_KEYS =
            {"name", "username", "password", "url", "notes", "folder", "tags", "totp"};
    private static final int MAX_RETAINED_ZIP_ENTRY_BYTES = 20 * 1024 * 1024;

    private AdvancedImportExport() {
    }

    public static final class Table {
        public final String name;
        public final List<String> columns;
        public final List<List<String>> rows;

        private Table(String name, List<String> columns, List<List<String>> rows) {
            this.name = name;
            this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        }
    }
    public static final class ParsedImport {
        public final List<Table> tables;
        public final List<VaultItem> items;
        public final String warning;

        private ParsedImport(List<Table> tables, List<VaultItem> items, String warning) {
            this.tables = Collections.unmodifiableList(new ArrayList<>(tables));
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.warning = warning == null ? "" : warning;
        }

        public boolean needsColumnMapping() {
            return !tables.isEmpty();
        }
    }

    public static final class MappingResult {
        public final List<VaultItem> items;
        public final int ignoredRows;

        private MappingResult(List<VaultItem> items, int ignoredRows) {
            this.items = Collections.unmodifiableList(items);
            this.ignoredRows = ignoredRows;
        }
    }

    public static final class ImportSizeException extends IOException {
        public ImportSizeException(String message) {
            super(message);
        }
    }

    public static final class PasswordRequiredException extends IOException {
        public PasswordRequiredException() {
            super("The Excel workbook requires a password");
        }
    }

    public static final class InvalidPasswordException extends IOException {
        public InvalidPasswordException() {
            super("The Excel workbook password is incorrect");
        }
    }

    public static final class XlsxRowWidthException extends IOException {
        public XlsxRowWidthException() {
            super("XLSX data row exceeds header width");
        }
    }

    private static final class EmptySheetException extends IOException {
        EmptySheetException() {
            super("XLSX sheet is empty");
        }
    }

    private static final class WipingByteArrayOutputStream extends ByteArrayOutputStream {
        void wipe() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }

    public static String[] mappingChoices(Table table) {
        String[] result = new String[table.columns.size() + 1];
        result[0] = "—";
        for (int i = 0; i < table.columns.size(); i++) {
            String column = table.columns.get(i);
            result[i + 1] = column.length() <= 80 ? column : column.substring(0, 77) + "…";
        }
        return result;
    }

    /** Returns column indices for name, username, password, url, notes, folder, tags and TOTP. */
    public static int[] suggestMapping(Table table) {
        int[] mapping = new int[FIELD_KEYS.length];
        Arrays.fill(mapping, -1);
        for (int column = 0; column < table.columns.size(); column++) {
            String name = normalize(table.columns.get(column));
            for (int field = 0; field < FIELD_KEYS.length; field++) {
                if (mapping[field] < 0 && isField(name, field)) {
                    mapping[field] = column;
                }
            }
        }
        return mapping;
    }

    private static boolean isField(String name, int field) {
        switch (field) {
            case 0:
                return oneOf(name, "name", "title", "account", "site", "item");
            case 1:
                return oneOf(name, "username", "loginusername", "loginname", "login",
                        "user", "email");
            case 2:
                return oneOf(name, "password", "loginpassword", "pass");
            case 3:
                return oneOf(name, "url", "website", "uri", "loginuri", "loginurl");
            case 4:
                return oneOf(name, "notes", "note", "comments", "comment",
                        "fields", "customfields");
            case 5:
                return oneOf(name, "folder", "group", "collection");
            case 6:
                return oneOf(name, "tags", "tag", "labels");
            case 7:
                return oneOf(name, "totp", "otp", "onetimepassword", "totpsecret", "loginotp");
            default:
                return false;
        }
    }

    private static boolean oneOf(String value, String... values) {
        for (String candidate : values) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static MappingResult map(Table table, int[] mapping) throws IOException {
        if (mapping == null || mapping.length != FIELD_KEYS.length) {
            throw new IOException("Invalid column mapping");
        }
        List<VaultItem> items = new ArrayList<>();
        int ignored = 0;
        int rowNumber = 1;
        for (List<String> row : table.rows) {
            rowNumber++;
            String[] values = new String[FIELD_KEYS.length];
            boolean hasMeaningfulData = false;
            for (int field = 0; field < values.length; field++) {
                int index = mapping[field];
                values[field] = index >= 0 && index < row.size() ? row.get(index) : "";
                hasMeaningfulData |= !values[field].trim().isEmpty();
            }
            if (!hasMeaningfulData) {
                ignored++;
                continue;
            }
            String title = values[0].trim();
            if (title.isEmpty()) {
                title = !values[1].trim().isEmpty() ? values[1].trim()
                        : !values[3].trim().isEmpty() ? values[3].trim()
                        : "Imported entry " + rowNumber;
            }
            for (String value : values) {
                if (value.getBytes(StandardCharsets.UTF_8).length
                        > VaultTextDataCodec.MAX_FIELD_LENGTH) {
                    throw new IOException("An imported field exceeds the configured limit");
                }
            }
            values[7] = normalizeTotp(values[7]);
            items.add(new VaultItem(title, values[1], values[2], values[3], values[4],
                    values[6], values[5], values[7], false));
            if (items.size() > MAX_ROWS) {
                throw new IOException("Import exceeds the entry limit");
            }
        }
        return new MappingResult(items, ignored);
    }

    public static Table parseCsv(byte[] raw) throws IOException {
        String text = decodeUtf8(raw);
        if (text.startsWith("\ufeff")) {
            text = text.substring(1);
        }
        char delimiter = guessDelimiter(text);
        List<List<String>> records = parseCsvRecords(text, delimiter);
        while (!records.isEmpty() && records.get(records.size() - 1).size() == 1
                && records.get(records.size() - 1).get(0).isEmpty()) {
            records.remove(records.size() - 1);
        }
        if (records.isEmpty()) {
            throw new IOException("CSV file is empty");
        }
        if (records.size() - 1 > MAX_ROWS) {
            throw new IOException("Import exceeds the entry limit");
        }
        List<String> headers = records.remove(0);
        if (headers.isEmpty() || headers.size() > 512) {
            throw new IOException("Invalid CSV header");
        }
        normalizeHeaders(headers);
        int cells = 0;
        for (List<String> row : records) {
            cells += row.size();
            if (cells > MAX_CELLS) {
                throw new IOException("CSV has too many cells");
            }
            while (row.size() < headers.size()) {
                row.add("");
            }
            if (row.size() > headers.size()) {
                throw new IOException("CSV row has more columns than the header");
            }
        }
        for (String header : headers) {
            if (header.length() > 256) {
                throw new IOException("CSV header is too long");
            }
        }
        return new Table("CSV", headers, records);
    }

    private static char guessDelimiter(String text) {
        char[] candidates = {',', ';', '\t'};
        char best = ',';
        int max = -1;
        boolean quoted = false;
        int[] counts = new int[candidates.length];
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && (ch == '\r' || ch == '\n')) {
                break;
            } else if (!quoted) {
                for (int j = 0; j < candidates.length; j++) {
                    if (ch == candidates[j]) {
                        counts[j]++;
                    }
                }
            }
        }
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > max) {
                best = candidates[i];
                max = counts[i];
            }
        }
        return best;
    }

    private static void normalizeHeaders(List<String> headers) {
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (i == 0 && header.startsWith("\ufeff")) {
                header = header.substring(1);
            }
            header = header.trim().isEmpty() ? "Column " + (i + 1) : header.trim();
            String key = normalize(header);
            if (!unique.add(key)) {
                header = header + " (" + (i + 1) + ")";
                unique.add(normalize(header));
            }
            headers.set(i, header);
        }
    }

    private static List<List<String>> parseCsvRecords(String text, char delimiter)
            throws IOException {
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        int[] totalCells = {0};
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        checkCsvFieldLength(field);
                        i++;
                    } else {
                        quoted = false;
                        closedQuote = true;
                    }
                } else {
                    field.append(ch);
                    checkCsvFieldLength(field);
                }
            } else if (ch == delimiter) {
                addCsvField(row, field, totalCells);
                closedQuote = false;
            } else if (ch == '\r' || ch == '\n') {
                addCsvField(row, field, totalCells);
                addCsvRecord(records, row);
                row = new ArrayList<>();
                closedQuote = false;
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (ch == '"') {
                if (field.length() != 0 || closedQuote) {
                    throw new IOException("Malformed CSV quoting");
                }
                quoted = true;
            } else if (closedQuote) {
                if (ch != ' ' && ch != '\t') {
                    throw new IOException("Malformed CSV quoting");
                }
            } else {
                field.append(ch);
                checkCsvFieldLength(field);
            }
        }
        if (quoted) {
            throw new IOException("Unclosed CSV quote");
        }
        if (field.length() > 0 || !row.isEmpty() || closedQuote) {
            addCsvField(row, field, totalCells);
            addCsvRecord(records, row);
        }
        return records;
    }

    private static void addCsvField(List<String> row, StringBuilder field, int[] totalCells)
            throws IOException {
        if (row.size() >= 512 || ++totalCells[0] > MAX_CELLS) {
            throw new IOException("CSV has too many columns or cells");
        }
        row.add(field.toString());
        field.setLength(0);
    }

    private static void addCsvRecord(List<List<String>> records, List<String> row)
            throws IOException {
        if (records.size() >= MAX_ROWS + 1) {
            throw new IOException("Import exceeds the entry limit");
        }
        records.add(row);
    }

    private static void checkCsvFieldLength(StringBuilder field) throws IOException {
        if (field.length() > VaultTextDataCodec.MAX_FIELD_LENGTH) {
            throw new IOException("CSV field exceeds the configured limit");
        }
    }

    public static ParsedImport parse(String filename, byte[] bytes) throws IOException {
        return parse(filename, bytes, null);
    }

    public static ParsedImport parse(String filename, byte[] bytes, String password)
            throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMPORT_BYTES) {
            throw new IOException("Import file is empty or too large");
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return tables(Collections.singletonList(parseCsv(bytes)));
        }
        if (lower.endsWith(".xlsx")) {
            if (isOleCompoundFile(bytes)) {
                byte[] decrypted = decryptXlsx(bytes, password);
                try {
                    return tables(parseXlsx(decrypted));
                } finally {
                    Arrays.fill(decrypted, (byte) 0);
                }
            }
            return tables(parseXlsx(bytes));
        }
        if (lower.endsWith(".json")) {
            return parseBitwardenJson(decodeUtf8(bytes));
        }
        if (lower.endsWith(".xml")) {
            return direct(parseKeePassXml(bytes));
        }
        if (lower.endsWith(".1pux")) {
            return parseOnePasswordZip(bytes);
        }
        if (isZip(bytes)) {
            Map<String, byte[]> entries = readZip(bytes, ZipRetention.AUTO);
            try {
                if (entries.containsKey("export.data")) {
                    return parseOnePasswordEntries(entries);
                }
                if (entries.containsKey("xl/workbook.xml")) {
                    return tables(parseXlsxEntries(entries));
                }
            } finally {
                clearZipEntries(entries);
            }
        } else {
            String sample = decodeUtf8(bytes).trim();
            if (sample.startsWith("{")) {
                return parseBitwardenJson(sample);
            }
            if (sample.startsWith("<")) {
                return direct(parseKeePassXml(bytes));
            }
            return tables(Collections.singletonList(parseCsv(bytes)));
        }
        throw new IOException("Unsupported import format");
    }

    private static ParsedImport tables(List<Table> tables) throws IOException {
        if (tables.isEmpty()) {
            throw new IOException("No import tables found");
        }
        return new ParsedImport(tables, Collections.emptyList(), "");
    }

    private static ParsedImport direct(List<VaultItem> items) throws IOException {
        if (items.size() > MAX_ROWS) {
            throw new IOException("Import exceeds the entry limit");
        }
        return new ParsedImport(Collections.emptyList(), items, "");
    }

    private static ParsedImport parseBitwardenJson(String text) throws IOException {
        try {
            validateJsonNesting(text);
            JSONObject root = new JSONObject(text);
            if (!root.has("encrypted") || root.optBoolean("encrypted", true)) {
                throw new IOException("Encrypted or unsupported Bitwarden JSON");
            }
            Map<String, String> folders = new HashMap<>();
            JSONArray folderArray = root.optJSONArray("folders");
            if (folderArray != null) {
                for (int i = 0; i < folderArray.length(); i++) {
                    JSONObject folder = folderArray.optJSONObject(i);
                    if (folder != null) {
                        folders.put(folder.optString("id", ""), folder.optString("name", ""));
                    }
                }
            }
            JSONArray entries = root.optJSONArray("items");
            if (entries == null) {
                throw new IOException("Bitwarden export has no items");
            }
            if (entries.length() > MAX_ROWS) {
                throw new IOException("Import exceeds the entry limit");
            }
            List<VaultItem> result = new ArrayList<>();
            int ignoredTypes = 0;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                JSONObject login = item.optJSONObject("login");
                int itemType = item.optInt("type", 1);
                String folder = folders.get(item.optString("folderId", ""));
                String title = item.optString("name", "");
                if (login == null && itemType == 2) {
                    String notes = item.optString("notes", "");
                    if (title.trim().isEmpty() && notes.trim().isEmpty()) {
                        continue;
                    }
                    checkItemFields(title, "", "", "", notes, "", folder, "");
                    result.add(new VaultItem(importTitle(title, ""),
                            "", "", "", notes,
                            "", folder == null ? "" : folder, "",
                            item.optBoolean("favorite", false)));
                    continue;
                }
                if (login == null) {
                    ignoredTypes++;
                    continue;
                }
                JSONArray uris = login.optJSONArray("uris");
                String url = "";
                if (uris != null && uris.length() > 0 && uris.optJSONObject(0) != null) {
                    url = uris.optJSONObject(0).optString("uri", "");
                }
                String username = login.optString("username", "");
                String password = login.optString("password", "");
                String notes = item.optString("notes", "");
                String totp = normalizeTotp(login.optString("totp", ""));
                if (title.trim().isEmpty() && username.isEmpty() && password.isEmpty()
                        && url.isEmpty() && notes.isEmpty() && totp.isEmpty()) {
                    continue;
                }
                checkItemFields(title, username, password, url, notes, "", folder, totp);
                result.add(new VaultItem(importTitle(title, username), username,
                        password, url, notes,
                        "", folder == null ? "" : folder, totp,
                        item.optBoolean("favorite", false)));
            }
            String warning = ignoredTypes == 0 ? ""
                    : WARNING_BITWARDEN_ITEMS_IGNORED + ":" + ignoredTypes;
            return new ParsedImport(Collections.emptyList(), result, warning);
        } catch (org.json.JSONException exception) {
            throw new IOException("Invalid Bitwarden JSON", exception);
        }
    }

    private static List<VaultItem> parseKeePassXml(byte[] bytes) throws IOException {
        try {
            XmlPullParser parser = newSecureParser(bytes);
            int event;
            boolean foundRoot = false;
            List<VaultItem> result = new ArrayList<>();
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.DOCDECL) {
                    throw new IOException("XML declarations are not allowed");
                }
                if (event == XmlPullParser.START_TAG && "KeePassFile".equals(parser.getName())) {
                    foundRoot = true;
                }
                if (event == XmlPullParser.START_TAG && "Group".equals(parser.getName())) {
                    readKeepassGroup(parser, "", result);
                }
                if (result.size() > MAX_ROWS) {
                    throw new IOException("Import exceeds the entry limit");
                }
            }
            if (!foundRoot) {
                throw new IOException("Unsupported XML format");
            }
            return result;
        } catch (IOException exception) {
            throw exception;
        } catch (XmlPullParserException | IllegalArgumentException exception) {
            throw new IOException("Invalid KeePass XML", exception);
        }
    }

    private static void readKeepassGroup(XmlPullParser parser, String parent,
                                         List<VaultItem> result)
            throws IOException, XmlPullParserException {
        int groupDepth = parser.getDepth();
        if (groupDepth > MAX_KEEPASS_GROUP_NESTING) {
            throw new IOException("KeePass group nesting is too deep");
        }
        String name = "";
        List<VaultItem> pending = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.DOCDECL) {
                throw new IOException("XML declarations are not allowed");
            }
            if (parser.getEventType() == XmlPullParser.END_TAG
                    && parser.getDepth() == groupDepth && "Group".equals(parser.getName())) {
                String path = parent.isEmpty() ? name : name.isEmpty() ? parent : parent + "/" + name;
                for (VaultItem item : pending) {
                    result.add(new VaultItem(item.title, item.username, item.password, item.url,
                            item.notes, item.tags, path, item.totpSecret, item.favorite));
                }
                return;
            }
            if (parser.getEventType() == XmlPullParser.START_TAG && "Name".equals(parser.getName())
                    && parser.getDepth() == groupDepth + 1) {
                name = parser.nextText();
            } else if (parser.getEventType() == XmlPullParser.START_TAG
                    && "Entry".equals(parser.getName())) {
                VaultItem entry = readKeepassEntry(parser);
                if (entry != null) {
                    pending.add(entry);
                    if (pending.size() > MAX_ROWS) {
                        throw new IOException("Import exceeds the entry limit");
                    }
                }
            } else if (parser.getEventType() == XmlPullParser.START_TAG
                    && "Group".equals(parser.getName())) {
                String parentPath = parent.isEmpty() ? name : name.isEmpty()
                        ? parent : parent + "/" + name;
                readKeepassGroup(parser, parentPath, result);
            }
        }
        throw new IOException("Incomplete KeePass group");
    }

    private static VaultItem readKeepassEntry(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int depth = parser.getDepth();
        Map<String, String> values = new HashMap<>();
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.DOCDECL) {
                throw new IOException("XML declarations are not allowed");
            }
            if (parser.getEventType() == XmlPullParser.END_TAG
                    && parser.getDepth() == depth && "Entry".equals(parser.getName())) {
                break;
            }
            if (parser.getEventType() == XmlPullParser.START_TAG
                    && "String".equals(parser.getName())) {
                int stringDepth = parser.getDepth();
                String key = "";
                String value = "";
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.getEventType() == XmlPullParser.END_TAG
                            && parser.getDepth() == stringDepth
                            && "String".equals(parser.getName())) {
                        break;
                    }
                    if (parser.getEventType() == XmlPullParser.START_TAG
                            && ("Key".equals(parser.getName()) || "Value".equals(parser.getName()))) {
                        String tag = parser.getName();
                        String text = parser.nextText();
                        if ("Key".equals(tag)) {
                            key = text;
                        } else {
                            value = text;
                        }
                    }
                }
                values.put(key, value);
            } else if (parser.getEventType() == XmlPullParser.START_TAG
                    && "Tags".equals(parser.getName())) {
                values.put("Tags", parser.nextText());
            }
        }
        String title = values.get("Title");
        String username = values.get("UserName");
        String password = values.get("Password");
        String url = values.get("URL");
        String notes = values.get("Notes");
        String totp = normalizeTotp(first(values, "TimeOtp-Secret-Base32",
                "otp", "TOTP Seed", "TOTP Settings"));
        if ((title == null || title.trim().isEmpty())
                && (username == null || username.isEmpty())
                && (password == null || password.isEmpty())
                && (url == null || url.isEmpty()) && (notes == null || notes.isEmpty())
                && totp.isEmpty()) {
            return null;
        }
        checkItemFields(title, username, password, url, notes, values.get("Tags"), "", totp);
        return new VaultItem(importTitle(title, username), username, password,
                url, notes, values.get("Tags"), "", totp, false);
    }

    private static String normalizeTotp(String value) {
        if (value == null || !value.toLowerCase(Locale.ROOT).startsWith("otpauth://")) {
            return value == null ? "" : value;
        }
        android.net.Uri parsed = android.net.Uri.parse(value);
        String secret = parsed.getQueryParameter("secret");
        return secret == null ? "" : secret;
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static ParsedImport parseOnePasswordZip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = readZip(bytes, ZipRetention.ONE_PASSWORD);
        try {
            return parseOnePasswordEntries(entries);
        } finally {
            clearZipEntries(entries);
        }
    }

    private static ParsedImport parseOnePasswordEntries(Map<String, byte[]> entries)
            throws IOException {
        byte[] data = entries.get("export.data");
        if (data == null || !entries.containsKey("export.attributes")) {
            throw new IOException("Invalid or incomplete 1Password export");
        }
        List<VaultItem> items = new ArrayList<>();
        try {
            String json = decodeUtf8(data);
            validateJsonNesting(json);
            JSONObject root = new JSONObject(json);
            JSONArray accounts = root.optJSONArray("accounts");
            if (accounts != null) {
                for (int a = 0; a < accounts.length(); a++) {
                    JSONObject account = accounts.optJSONObject(a);
                    JSONArray vaults = account == null ? null : account.optJSONArray("vaults");
                    if (vaults == null) {
                        continue;
                    }
                    for (int v = 0; v < vaults.length(); v++) {
                        JSONObject vault = vaults.optJSONObject(v);
                        JSONArray vaultItems = vault == null ? null : vault.optJSONArray("items");
                        if (vaultItems == null) {
                            continue;
                        }
                        JSONObject vaultAttrs = vault == null ? null : vault.optJSONObject("attrs");
                        String folder = vaultAttrs == null ? ""
                                : vaultAttrs.optString("name", "");
                        for (int i = 0; i < vaultItems.length(); i++) {
                            JSONObject item = vaultItems.optJSONObject(i);
                            if (item != null) {
                                VaultItem mapped = onePasswordItem(item, folder);
                                if (mapped != null) {
                                    items.add(mapped);
                                    if (items.size() > MAX_ROWS) {
                                        throw new IOException("Import exceeds the entry limit");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (org.json.JSONException exception) {
            throw new IOException("Invalid 1Password export data", exception);
        }
        return new ParsedImport(Collections.emptyList(), items,
                WARNING_1PASSWORD_ATTACHMENTS_IGNORED);
    }

    private static VaultItem onePasswordItem(JSONObject item, String folder) throws IOException {
        JSONObject overview = item.optJSONObject("overview");
        JSONObject details = item.optJSONObject("details");
        if (overview == null) {
            overview = new JSONObject();
        }
        if (details == null) {
            details = new JSONObject();
        }
        String username = "";
        String password = "";
        String totp = "";
        String notes = details.optString("notesPlain", "");
        JSONArray loginFields = details.optJSONArray("loginFields");
        if (loginFields != null) {
            for (int i = 0; i < loginFields.length(); i++) {
                JSONObject field = loginFields.optJSONObject(i);
                if (field == null) {
                    continue;
                }
                String designation = field.optString("designation", "").toLowerCase(Locale.ROOT);
                String value = field.optString("value", "");
                if ("username".equals(designation)) {
                    username = value;
                } else if ("password".equals(designation)) {
                    password = value;
                } else if ("otp".equals(designation) || "one-time password".equals(designation)) {
                    totp = value;
                }
            }
        }
        JSONArray sections = details.optJSONArray("sections");
        if (totp.isEmpty() && sections != null) {
            for (int i = 0; i < sections.length() && totp.isEmpty(); i++) {
                JSONObject section = sections.optJSONObject(i);
                JSONArray fields = section == null ? null : section.optJSONArray("fields");
                if (fields == null) {
                    continue;
                }
                for (int j = 0; j < fields.length(); j++) {
                    JSONObject field = fields.optJSONObject(j);
                    if (field == null) {
                        continue;
                    }
                    String kind = field.optString("id", "")
                            + field.optString("title", "")
                            + field.optString("designation", "")
                            + field.optString("type", "");
                    if (normalize(kind).contains("otp")) {
                        totp = fieldValue(field.opt("value"));
                        if (!totp.isEmpty()) {
                            break;
                        }
                    }
                }
            }
        }
        JSONArray urls = overview.optJSONArray("urls");
        if (urls == null) {
            urls = overview.optJSONArray("URLs");
        }
        String url = overview.optString("url", "");
        if (url.isEmpty() && urls != null && urls.length() > 0) {
            Object first = urls.opt(0);
            url = first instanceof JSONObject ? ((JSONObject) first).optString("url", "")
                    : first instanceof String ? (String) first : "";
        }
        JSONArray tagsArray = item.optJSONArray("tags");
        if (tagsArray == null) {
            tagsArray = overview.optJSONArray("tags");
        }
        List<String> tags = new ArrayList<>();
        if (tagsArray != null) {
            for (int i = 0; i < tagsArray.length(); i++) {
                Object tag = tagsArray.opt(i);
                if (tag instanceof String) {
                    tags.add((String) tag);
                }
            }
        }
        String title = overview.optString("title", item.optString("title", ""));
        if (title.isEmpty() && username.isEmpty() && password.isEmpty() && notes.isEmpty()) {
            return null;
        }
        title = importTitle(title, username);
        String joinedTags = join(tags, ", ");
        totp = normalizeTotp(totp);
        checkItemFields(title, username, password, url, notes, joinedTags, folder, totp);
        return new VaultItem(title, username, password, url, notes,
                joinedTags, folder, totp, false);
    }

    private static String fieldValue(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof JSONObject) {
            JSONObject field = (JSONObject) value;
            return field.optString("value", field.optString("string", ""));
        }
        return "";
    }

    private static String importTitle(String title, String username) {
        String normalizedTitle = title == null ? "" : title.trim();
        if (!normalizedTitle.isEmpty()) {
            return normalizedTitle;
        }
        String normalizedUsername = username == null ? "" : username.trim();
        return normalizedUsername.isEmpty() ? "Imported entry" : normalizedUsername;
    }

    private static void checkItemFields(String... fields) throws IOException {
        for (String field : fields) {
            if (field != null && field.getBytes(StandardCharsets.UTF_8).length
                    > VaultTextDataCodec.MAX_FIELD_LENGTH) {
                throw new IOException("An imported field exceeds the configured limit");
            }
        }
    }

    private static void validateJsonNesting(String json) throws IOException {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char value = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == '{' || value == '[') {
                if (++depth > MAX_JSON_NESTING) {
                    throw new IOException("JSON nesting exceeds the configured limit");
                }
            } else if (value == '}' || value == ']') {
                if (--depth < 0) {
                    throw new IOException("Malformed JSON nesting");
                }
            }
        }
        if (inString || depth != 0) {
            throw new IOException("Malformed JSON nesting");
        }
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(separator);
            }
            result.append(value);
        }
        return result.toString();
    }

    private static List<Table> parseXlsx(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = readZip(bytes, ZipRetention.XLSX);
        try {
            return parseXlsxEntries(entries);
        } finally {
            clearZipEntries(entries);
        }
    }

    private static byte[] decryptXlsx(byte[] bytes, String password) throws IOException {
        if (password == null) {
            throw new PasswordRequiredException();
        }
        try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            EncryptionInfo encryptionInfo = new EncryptionInfo(filesystem);
            Decryptor decryptor = Decryptor.getInstance(encryptionInfo);
            if (!decryptor.verifyPassword(password)) {
                throw new InvalidPasswordException();
            }
            try (InputStream decrypted = decryptor.getDataStream(filesystem)) {
                return readBounded(decrypted);
            }
        } catch (GeneralSecurityException | org.apache.poi.EncryptedDocumentException exception) {
            throw new IOException("Could not decrypt the Excel workbook", exception);
        }
    }

    private static boolean isOleCompoundFile(byte[] bytes) {
        byte[] signature = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static List<Table> parseXlsxEntries(Map<String, byte[]> entries) throws IOException {
        byte[] workbook = entries.get("xl/workbook.xml");
        byte[] relData = entries.get("xl/_rels/workbook.xml.rels");
        if (workbook == null || relData == null) {
            throw new IOException("Invalid XLSX package");
        }
        try {
            Map<String, String> relationships = parseRelationships(relData);
            List<String[]> sheetRefs = parseWorkbookSheets(workbook);
            List<String> sharedStrings = entries.containsKey("xl/sharedStrings.xml")
                    ? parseSharedStrings(entries.get("xl/sharedStrings.xml"))
                    : Collections.emptyList();
            List<Table> tables = new ArrayList<>();
            int totalRows = 0;
            int totalCells = 0;
            for (String[] sheet : sheetRefs) {
                String target = relationships.get(sheet[1]);
                if (target == null) {
                    throw new IOException("Invalid XLSX sheet relationship");
                }
                String path = resolveSheetPath(target);
                byte[] xml = entries.get(path);
                if (xml == null) {
                    throw new IOException("XLSX sheet data is missing");
                }
                Table table;
                try {
                    table = parseWorksheet(sheet[0], xml, sharedStrings);
                } catch (EmptySheetException exception) {
                    continue;
                }
                totalRows += table.rows.size();
                totalCells += table.columns.size();
                for (List<String> row : table.rows) {
                    totalCells += row.size();
                }
                if (totalRows > MAX_ROWS || totalCells > MAX_CELLS) {
                    throw new IOException("Workbook exceeds the row or cell limit");
                }
                tables.add(table);
            }
            return tables;
        } catch (IOException exception) {
            throw exception;
        } catch (XmlPullParserException | IllegalArgumentException exception) {
            throw new IOException("Invalid XLSX workbook", exception);
        }
    }

    private static String resolveSheetPath(String target) throws IOException {
        String path = target.startsWith("/") ? target.substring(1) : target;
        if (!path.startsWith("xl/")) {
            path = "xl/" + path;
        }
        return normalizeZipPath(path);
    }

    private static Map<String, String> parseRelationships(byte[] bytes)
            throws IOException, XmlPullParserException {
        Map<String, String> map = new HashMap<>();
        XmlPullParser parser = newSecureParser(bytes);
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.DOCDECL) {
                throw new IOException("XML declarations are not allowed");
            }
            if (parser.getEventType() == XmlPullParser.START_TAG
                    && "Relationship".equals(parser.getName())) {
                String id = parser.getAttributeValue(null, "Id");
                String target = parser.getAttributeValue(null, "Target");
                String type = parser.getAttributeValue(null, "Type");
                if (id != null && target != null && (type == null || type.endsWith("/worksheet"))) {
                    map.put(id, target);
                }
            }
        }
        return map;
    }

    private static List<String[]> parseWorkbookSheets(byte[] bytes)
            throws IOException, XmlPullParserException {
        List<String[]> sheets = new ArrayList<>();
        XmlPullParser parser = newSecureParser(bytes);
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.DOCDECL) {
                throw new IOException("XML declarations are not allowed");
            }
            if (parser.getEventType() == XmlPullParser.START_TAG
                    && "sheet".equals(parser.getName())) {
                String name = parser.getAttributeValue(null, "name");
                String id = parser.getAttributeValue(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                if (id == null) {
                    id = parser.getAttributeValue("r", "id");
                }
                if (name != null && id != null) {
                    sheets.add(new String[]{name, id});
                }
            }
        }
        if (sheets.isEmpty() || sheets.size() > 256) {
            throw new IOException("XLSX contains no usable sheets or too many sheets");
        }
        return sheets;
    }

    private static List<String> parseSharedStrings(byte[] bytes)
            throws IOException, XmlPullParserException {
        List<String> result = new ArrayList<>();
        XmlPullParser parser = newSecureParser(bytes);
        StringBuilder value = null;
        int siDepth = -1;
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.DOCDECL) {
                throw new IOException("XML declarations are not allowed");
            }
            if (parser.getEventType() == XmlPullParser.START_TAG && "si".equals(parser.getName())) {
                siDepth = parser.getDepth();
                value = new StringBuilder();
            } else if (value != null && parser.getEventType() == XmlPullParser.START_TAG
                    && "t".equals(parser.getName())) {
                value.append(parser.nextText());
            } else if (value != null && parser.getEventType() == XmlPullParser.END_TAG
                    && parser.getDepth() == siDepth && "si".equals(parser.getName())) {
                result.add(value.toString());
                value = null;
                if (result.size() > MAX_CELLS) {
                    throw new IOException("XLSX contains too many shared strings");
                }
            }
        }
        return result;
    }

    private static Table parseWorksheet(String name, byte[] bytes, List<String> shared)
            throws IOException, XmlPullParserException {
        Map<Integer, List<String>> rows = new LinkedHashMap<>();
        XmlPullParser parser = newSecureParser(bytes);
        int rowNumber = 0;
        int cellCount = 0;
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.DOCDECL) {
                throw new IOException("XML declarations are not allowed");
            }
            if (parser.getEventType() == XmlPullParser.START_TAG && "row".equals(parser.getName())) {
                String attr = parser.getAttributeValue(null, "r");
                rowNumber = attr == null ? rowNumber + 1 : Integer.parseInt(attr);
                if (rowNumber > MAX_ROWS + 1) {
                    throw new IOException("XLSX contains too many rows");
                }
            } else if (parser.getEventType() == XmlPullParser.START_TAG
                    && "c".equals(parser.getName())) {
                if (++cellCount > MAX_CELLS) {
                    throw new IOException("XLSX contains too many cells");
                }
                String ref = parser.getAttributeValue(null, "r");
                String type = parser.getAttributeValue(null, "t");
                int column = columnIndex(ref);
                int cellDepth = parser.getDepth();
                String raw = "";
                StringBuilder inline = new StringBuilder();
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.getEventType() == XmlPullParser.DOCDECL) {
                        throw new IOException("XML declarations are not allowed");
                    }
                    if (parser.getEventType() == XmlPullParser.START_TAG
                            && ("v".equals(parser.getName()) || "t".equals(parser.getName()))) {
                        String tag = parser.getName();
                        String text = parser.nextText();
                        if ("v".equals(tag)) {
                            raw = text;
                        } else {
                            inline.append(text);
                        }
                    } else if (parser.getEventType() == XmlPullParser.END_TAG
                            && parser.getDepth() == cellDepth && "c".equals(parser.getName())) {
                        break;
                    }
                }
                String value = raw;
                if ("s".equals(type)) {
                    int stringIndex = Integer.parseInt(raw);
                    if (stringIndex < 0 || stringIndex >= shared.size()) {
                        throw new IOException("Invalid XLSX shared string index");
                    }
                    value = shared.get(stringIndex);
                } else if ("inlineStr".equals(type)) {
                    value = inline.toString();
                } else if ("b".equals(type)) {
                    value = "1".equals(raw) ? "TRUE" : "FALSE";
                }
                if (column >= 0 && column < 512) {
                    List<String> values = rows.get(rowNumber);
                    if (values == null) {
                        values = new ArrayList<>();
                        rows.put(rowNumber, values);
                    }
                    while (values.size() <= column) {
                        values.add("");
                    }
                    values.set(column, value);
                } else {
                    throw new IOException("XLSX has an unsupported column index");
                }
            }
        }
        if (rows.isEmpty()) {
            throw new EmptySheetException();
        }
        List<String> headers = null;
        List<List<String>> dataRows = new ArrayList<>();
        for (List<String> row : rows.values()) {
            if (headers == null) {
                headers = row;
            } else {
                while (row.size() < headers.size()) {
                    row.add("");
                }
                if (row.size() > headers.size()) {
                    throw new XlsxRowWidthException();
                }
                dataRows.add(row);
            }
        }
        if (headers == null) {
            throw new EmptySheetException();
        }
        normalizeHeaders(headers);
        if (headers.size() > 512) {
            throw new IOException("XLSX contains too many columns");
        }
        return new Table(name, headers, dataRows);
    }

    private static int columnIndex(String cellRef) throws IOException {
        if (cellRef == null || cellRef.isEmpty()) {
            throw new IOException("Invalid XLSX cell reference");
        }
        int index = 0;
        int chars = 0;
        for (int i = 0; i < cellRef.length(); i++) {
            char ch = Character.toUpperCase(cellRef.charAt(i));
            if (ch < 'A' || ch > 'Z') {
                break;
            }
            if (++chars > 3) {
                throw new IOException("Invalid XLSX cell reference");
            }
            index = index * 26 + (ch - 'A' + 1);
        }
        int column = chars == 0 ? -1 : index - 1;
        if (column >= 512) {
            throw new IOException("XLSX has an unsupported column index");
        }
        return column;
    }

    private static XmlPullParser newSecureParser(byte[] bytes) throws XmlPullParserException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        parser.setInput(new ByteArrayInputStream(bytes), "UTF-8");
        return parser;
    }

    private enum ZipRetention {
        XLSX,
        ONE_PASSWORD,
        AUTO
    }

    private static Map<String, byte[]> readZip(byte[] bytes, ZipRetention retention)
            throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        long expanded = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ZIP_ENTRIES) {
                    throw new IOException("Archive contains too many entries");
                }
                String path = normalizeZipPath(entry.getName());
                if (!entry.isDirectory()) {
                    boolean retain = retention == ZipRetention.XLSX && isXlsxEntry(path)
                            || retention == ZipRetention.ONE_PASSWORD && isOnePasswordEntry(path)
                            || retention == ZipRetention.AUTO
                            && (isXlsxEntry(path) || isOnePasswordEntry(path));
                    if (retain && entries.containsKey(path)) {
                        throw new IOException("Archive contains duplicate data entries");
                    }
                    WipingByteArrayOutputStream out =
                            retain ? new WipingByteArrayOutputStream() : null;
                    byte[] buffer = new byte[8192];
                    try {
                        int entryBytes = 0;
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            expanded += read;
                            if (expanded > MAX_ZIP_EXPANDED_BYTES) {
                                throw new ImportSizeException(
                                        "Archive expands beyond the allowed size");
                            }
                            entryBytes += read;
                            if (retain) {
                                if (entryBytes > MAX_RETAINED_ZIP_ENTRY_BYTES) {
                                    throw new ImportSizeException(
                                            "Archive entry exceeds the allowed size");
                                }
                                out.write(buffer, 0, read);
                            }
                        }
                        if (retain) {
                            entries.put(path, out.toByteArray());
                        }
                    } finally {
                        Arrays.fill(buffer, (byte) 0);
                        if (out != null) {
                            out.wipe();
                        }
                    }
                }
                zip.closeEntry();
            }
        } catch (IOException exception) {
            clearZipEntries(entries);
            throw exception;
        } catch (IllegalArgumentException exception) {
            clearZipEntries(entries);
            throw new IOException("Invalid archive", exception);
        }
        return entries;
    }

    private static boolean isOnePasswordEntry(String path) {
        return "export.data".equals(path) || "export.attributes".equals(path);
    }

    private static void clearZipEntries(Map<String, byte[]> entries) {
        for (byte[] bytes : entries.values()) {
            Crypto.wipe(bytes);
        }
        entries.clear();
    }

    private static boolean isXlsxEntry(String path) {
        return path.equals("xl/workbook.xml") || path.equals("xl/_rels/workbook.xml.rels")
                || path.equals("xl/sharedStrings.xml")
                || path.startsWith("xl/worksheets/");
    }

    private static String normalizeZipPath(String input) throws IOException {
        if (input == null || input.isEmpty() || input.startsWith("/")
                || input.startsWith("\\") || input.contains("\\")) {
            throw new IOException("Invalid archive path");
        }
        String[] parts = input.split("/");
        for (String part : parts) {
            if ("..".equals(part) || ".".equals(part)) {
                throw new IOException("Invalid archive path");
            }
        }
        return input;
    }

    private static boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7);
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Import file is not valid UTF-8", exception);
        }
    }

    public static void writeCsv(OutputStream output, List<VaultItem> items) throws IOException {
        StringBuilder text = new StringBuilder();
        appendCsvRow(text, Arrays.asList("name", "username", "password", "url",
                "notes", "folder", "tags", "totp"));
        for (VaultItem item : items) {
            if (!VaultItem.TYPE_LOGIN.equals(item.type)) {
                continue;
            }
            appendCsvRow(text, Arrays.asList(item.title, item.username, item.password,
                    item.url, item.notes, item.folder, item.tags, item.totpSecret));
            if (text.length() >= 64 * 1024) {
                output.write(text.toString().getBytes(StandardCharsets.UTF_8));
                text.setLength(0);
            }
        }
        if (text.length() > 0) {
            output.write(text.toString().getBytes(StandardCharsets.UTF_8));
        }
        output.flush();
    }

    private static void appendCsvRow(StringBuilder output, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                output.append(',');
            }
            String field = fields.get(i) == null ? "" : fields.get(i);
            output.append('"').append(field.replace("\"", "\"\"")).append('"');
        }
        output.append("\r\n");
    }

    /** Reads an SAF source with a strict limit; caller owns the returned bytes. */
    public static byte[] readBounded(InputStream input) throws IOException {
        if (input == null) {
            throw new IOException("Import file is unavailable");
        }
        WipingByteArrayOutputStream output = new WipingByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream source = input) {
            int count;
            int total = 0;
            while ((count = source.read(buffer)) != -1) {
                total += count;
                if (total > MAX_IMPORT_BYTES) {
                    throw new ImportSizeException("Import file exceeds the 20 MiB limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            Arrays.fill(buffer, (byte) 0);
            output.wipe();
        }
    }
}
