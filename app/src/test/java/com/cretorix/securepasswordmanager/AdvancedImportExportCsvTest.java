package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.AdvancedImportExport;
import com.cretorix.securepasswordmanager.crypto.VaultItem;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class AdvancedImportExportCsvTest {
    @Test
    public void parsesQuotedCsvAndMapsFieldsFromHeaders() throws Exception {
        String csv = "\ufeffTitle;Login Name;Password;Website;Notes;Group;Tags;One-time password\r\n"
                + "\"Acme; Portal\";alice;\"p\"\"ass,word\";https://example.test;"
                + "\"line one\r\nline two\";Work;personal;totp-secret\r\n";
        AdvancedImportExport.Table table = AdvancedImportExport.parseCsv(
                csv.getBytes(StandardCharsets.UTF_8));
        AdvancedImportExport.MappingResult mapped = AdvancedImportExport.map(table,
                AdvancedImportExport.suggestMapping(table));

        assertEquals(8, table.columns.size());
        assertEquals(1, mapped.items.size());
        VaultItem item = mapped.items.get(0);
        assertEquals("Acme; Portal", item.title);
        assertEquals("p\"ass,word", item.password);
        assertEquals("line one\r\nline two", item.notes);
        assertEquals("Work", item.folder);
        assertEquals("personal", item.tags);
        assertEquals("totp-secret", item.totpSecret);
    }

    @Test
    public void exportsAllLoginColumnsAsQuotedRfc4180CsvAndSkipsMedia() throws Exception {
        VaultItem login = new VaultItem("A, B", "alice", "p\"ass", "https://site",
                "two\nlines", "tag", "Work", "JBSWY3DPEHPK3PXP", false);
        VaultItem file = VaultItem.fileReference("doc.pdf", repeat("ab", 32),
                "application/pdf", 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        AdvancedImportExport.writeCsv(output, Arrays.asList(login, file));

        assertEquals("\"name\",\"username\",\"password\",\"url\",\"notes\",\"folder\","
                        + "\"tags\",\"totp\"\r\n"
                        + "\"A, B\",\"alice\",\"p\"\"ass\",\"https://site\","
                        + "\"two\nlines\",\"Work\",\"tag\",\"JBSWY3DPEHPK3PXP\"\r\n",
                new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void prefixesSpreadsheetFormulaAndControlCharacterCells() throws Exception {
        VaultItem login = new VaultItem("=1+1", " \t+cmd", "-1+1", "@SUM(1,2)",
                "\ntext", "\u00a0=2+2", "normal", "JBSWY3DPEHPK3PXP", false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        AdvancedImportExport.writeCsv(output, Arrays.asList(login));

        assertEquals("\"name\",\"username\",\"password\",\"url\",\"notes\",\"folder\","
                        + "\"tags\",\"totp\"\r\n"
                        + "\"'=1+1\",\"' \t+cmd\",\"'-1+1\",\"'@SUM(1,2)\","
                        + "\"'\ntext\",\"normal\",\"'\u00a0=2+2\","
                        + "\"JBSWY3DPEHPK3PXP\"\r\n",
                new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsImportFilesBeyondTheByteLimit() {
        byte[] oversized = new byte[AdvancedImportExport.MAX_IMPORT_BYTES + 1];
        assertThrows(AdvancedImportExport.ImportSizeException.class,
                () -> AdvancedImportExport.readBounded(new ByteArrayInputStream(oversized)));
        Arrays.fill(oversized, (byte) 0);
    }

    private static String repeat(String value, int count) {
        StringBuilder output = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            output.append(value);
        }
        return output.toString();
    }
}
