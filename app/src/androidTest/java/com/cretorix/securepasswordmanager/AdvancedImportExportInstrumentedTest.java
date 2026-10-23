package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.AdvancedImportExport;
import com.cretorix.securepasswordmanager.crypto.VaultItem;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class AdvancedImportExportInstrumentedTest {
    @Test
    public void importsBitwardenPlainJsonAndRejectsEncryptedJson() throws Exception {
        String json = "{\"encrypted\":false,\"folders\":[{\"id\":\"f1\",\"name\":\"Work\"}],"
                + "\"items\":[{\"type\":1,\"name\":\"Site\",\"folderId\":\"f1\","
                + "\"login\":{\"username\":\"alice\",\"password\":\"secret\","
                + "\"totp\":\"seed\",\"uris\":[{\"uri\":\"https://site\"}]}},"
                + "{\"type\":2,\"name\":\"Note\",\"notes\":\"private\"},"
                + "{\"type\":3,\"name\":\"Card\"}]}";
        AdvancedImportExport.ParsedImport parsed = AdvancedImportExport.parse(
                "vault.json", json.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, parsed.items.size());
        assertEquals("Work", parsed.items.get(0).folder);
        assertEquals("seed", parsed.items.get(0).totpSecret);
        assertEquals("private", parsed.items.get(1).notes);
        assertTrue(parsed.warning.startsWith(
                AdvancedImportExport.WARNING_BITWARDEN_ITEMS_IGNORED));
        assertThrows(java.io.IOException.class, () -> AdvancedImportExport.parse(
                "encrypted.json", "{\"encrypted\":true}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void importsKeepassXmlEntriesWithGroupTagsAndTotp() throws Exception {
        String xml = "<?xml version=\"1.0\"?><KeePassFile><Root><Group><Name>Work</Name>"
                + "<Entry><String><Key>Title</Key><Value>Site</Value></String>"
                + "<String><Key>UserName</Key><Value>alice</Value></String>"
                + "<String><Key>Password</Key><Value>secret</Value></String>"
                + "<String><Key>TimeOtp-Secret-Base32</Key><Value>seed</Value></String>"
                + "<Tags>blue,important</Tags></Entry></Group></Root></KeePassFile>";
        AdvancedImportExport.ParsedImport parsed = AdvancedImportExport.parse(
                "vault.xml", xml.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, parsed.items.size());
        VaultItem item = parsed.items.get(0);
        assertEquals("Work", item.folder);
        assertEquals("blue,important", item.tags);
        assertEquals("seed", item.totpSecret);
    }

    @Test
    public void importsOnePasswordPlainPackage() throws Exception {
        String data = "{\"accounts\":[{\"vaults\":[{\"attrs\":{\"name\":\"Personal\"},"
                + "\"items\":[{\"overview\":{\"title\":\"Site\","
                + "\"urls\":[{\"url\":\"https://site\"}],\"tags\":[\"tag\"]},"
                + "\"details\":{\"notesPlain\":\"note\",\"loginFields\":["
                + "{\"designation\":\"username\",\"value\":\"alice\"},"
                + "{\"designation\":\"password\",\"value\":\"secret\"},"
                + "{\"designation\":\"otp\",\"value\":\"seed\"}]}}]}]}]}";
        byte[] packageBytes = zip(new String[][]{
                {"export.attributes", "{\"version\":1}"},
                {"export.data", data},
                {"files/document.txt", "not imported"}
        });

        AdvancedImportExport.ParsedImport parsed =
                AdvancedImportExport.parse("vault.1pux", packageBytes);
        assertEquals(1, parsed.items.size());
        assertEquals("Personal", parsed.items.get(0).folder);
        assertEquals("seed", parsed.items.get(0).totpSecret);
        assertEquals("note", parsed.items.get(0).notes);
        assertEquals(AdvancedImportExport.WARNING_1PASSWORD_ATTACHMENTS_IGNORED,
                parsed.warning);
    }

    @Test
    public void parsesXlsxSheetsAndSharedStringValues() throws Exception {
        String workbook = "<workbook xmlns:r=\"http://schemas.openxmlformats.org/"
                + "officeDocument/2006/relationships\"><sheets>"
                + "<sheet name=\"Logins\" sheetId=\"1\" r:id=\"rId1\"/>"
                + "<sheet name=\"Other\" sheetId=\"2\" r:id=\"rId2\"/>"
                + "</sheets></workbook>";
        String relationships = "<Relationships xmlns=\"http://schemas.openxmlformats.org/"
                + "package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\" "
                + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/worksheet\"/>"
                + "<Relationship Id=\"rId2\" Target=\"worksheets/sheet2.xml\" "
                + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/worksheet\"/></Relationships>";
        String shared = "<sst><si><t>Name</t></si><si><t>Username</t></si>"
                + "<si><t>Password</t></si><si><t>Site</t></si>"
                + "<si><t>alice</t></si><si><t>secret</t></si></sst>";
        String firstSheet = "<worksheet><sheetData><row r=\"1\">"
                + "<c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c>"
                + "<c r=\"C1\" t=\"s\"><v>2</v></c></row><row r=\"2\">"
                + "<c r=\"A2\" t=\"s\"><v>3</v></c><c r=\"B2\" t=\"s\"><v>4</v></c>"
                + "<c r=\"C2\" t=\"s\"><v>5</v></c></row></sheetData></worksheet>";
        String secondSheet = "<worksheet><sheetData><row r=\"1\">"
                + "<c r=\"A1\" t=\"inlineStr\"><is><t>Ignored</t></is></c>"
                + "</row></sheetData></worksheet>";
        AdvancedImportExport.ParsedImport parsed = AdvancedImportExport.parse("source.xlsx",
                zip(new String[][]{
                        {"xl/workbook.xml", workbook},
                        {"xl/_rels/workbook.xml.rels", relationships},
                        {"xl/sharedStrings.xml", shared},
                        {"xl/worksheets/sheet1.xml", firstSheet},
                        {"xl/worksheets/sheet2.xml", secondSheet}
                }));

        assertEquals(2, parsed.tables.size());
        AdvancedImportExport.Table table = parsed.tables.get(0);
        assertEquals("Logins", table.name);
        int[] mapping = AdvancedImportExport.suggestMapping(table);
        assertEquals(1, AdvancedImportExport.map(table, mapping).items.size());
    }

    @Test
    public void identifiesXlsxRowsWithMoreColumnsThanHeaders() throws Exception {
        String workbook = "<workbook xmlns:r=\"http://schemas.openxmlformats.org/"
                + "officeDocument/2006/relationships\"><sheets>"
                + "<sheet name=\"Logins\" sheetId=\"1\" r:id=\"rId1\"/>"
                + "</sheets></workbook>";
        String relationships = "<Relationships xmlns=\"http://schemas.openxmlformats.org/"
                + "package/2006/relationships\"><Relationship Id=\"rId1\" "
                + "Target=\"worksheets/sheet1.xml\" Type=\"http://schemas.openxmlformats.org/"
                + "officeDocument/2006/relationships/worksheet\"/></Relationships>";
        String sheet = "<worksheet><sheetData><row r=\"1\">"
                + "<c r=\"A1\" t=\"inlineStr\"><is><t>Name</t></is></c>"
                + "<c r=\"B1\" t=\"inlineStr\"><is><t>Password</t></is></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Site</t></is></c>"
                + "<c r=\"B2\" t=\"inlineStr\"><is><t>secret</t></is></c>"
                + "<c r=\"C2\" t=\"inlineStr\"><is><t>extra</t></is></c>"
                + "</row></sheetData></worksheet>";

        assertThrows(AdvancedImportExport.XlsxRowWidthException.class,
                () -> AdvancedImportExport.parse("source.xlsx", zip(new String[][]{
                        {"xl/workbook.xml", workbook},
                        {"xl/_rels/workbook.xml.rels", relationships},
                        {"xl/worksheets/sheet1.xml", sheet}
                })));
    }

    @Test
    public void importsPasswordProtectedXlsxLikePlainXlsx() throws Exception {
        byte[] plainXlsx = xlsx();
        byte[] encryptedXlsx = encrypt(plainXlsx, "correct horse");
        AdvancedImportExport.PasswordRequiredException required = assertThrows(
                AdvancedImportExport.PasswordRequiredException.class,
                () -> AdvancedImportExport.parse("source.xlsx", encryptedXlsx));
        assertTrue(required.getMessage().contains("requires a password"));
        assertThrows(AdvancedImportExport.InvalidPasswordException.class,
                () -> AdvancedImportExport.parse("source.xlsx", encryptedXlsx, "wrong"));

        AdvancedImportExport.ParsedImport plain =
                AdvancedImportExport.parse("source.xlsx", plainXlsx);
        AdvancedImportExport.ParsedImport protectedImport =
                AdvancedImportExport.parse("source.xlsx", encryptedXlsx, "correct horse");
        assertEquals(plain.tables.size(), protectedImport.tables.size());
        assertEquals(plain.tables.get(0).name, protectedImport.tables.get(0).name);
        assertEquals(plain.tables.get(0).columns, protectedImport.tables.get(0).columns);
        assertEquals(plain.tables.get(0).rows, protectedImport.tables.get(0).rows);
    }

    private static byte[] xlsx() throws Exception {
        String workbook = "<workbook xmlns:r=\"http://schemas.openxmlformats.org/"
                + "officeDocument/2006/relationships\"><sheets>"
                + "<sheet name=\"Logins\" sheetId=\"1\" r:id=\"rId1\"/>"
                + "</sheets></workbook>";
        String relationships = "<Relationships xmlns=\"http://schemas.openxmlformats.org/"
                + "package/2006/relationships\"><Relationship Id=\"rId1\" "
                + "Target=\"worksheets/sheet1.xml\" Type=\"http://schemas.openxmlformats.org/"
                + "officeDocument/2006/relationships/worksheet\"/></Relationships>";
        String shared = "<sst><si><t>Name</t></si><si><t>Username</t></si>"
                + "<si><t>Password</t></si><si><t>Site</t></si>"
                + "<si><t>alice</t></si><si><t>secret</t></si></sst>";
        String sheet = "<worksheet><sheetData><row r=\"1\">"
                + "<c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c>"
                + "<c r=\"C1\" t=\"s\"><v>2</v></c></row><row r=\"2\">"
                + "<c r=\"A2\" t=\"s\"><v>3</v></c><c r=\"B2\" t=\"s\"><v>4</v></c>"
                + "<c r=\"C2\" t=\"s\"><v>5</v></c></row></sheetData></worksheet>";
        return zip(new String[][]{
                {"xl/workbook.xml", workbook},
                {"xl/_rels/workbook.xml.rels", relationships},
                {"xl/sharedStrings.xml", shared},
                {"xl/worksheets/sheet1.xml", sheet}
        });
    }

    private static byte[] encrypt(byte[] bytes, String password) throws Exception {
        try (POIFSFileSystem filesystem = new POIFSFileSystem()) {
            EncryptionInfo encryptionInfo = new EncryptionInfo(EncryptionMode.agile);
            Encryptor encryptor = encryptionInfo.getEncryptor();
            encryptor.confirmPassword(password);
            try (OutputStream encrypted = encryptor.getDataStream(filesystem)) {
                encrypted.write(bytes);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            filesystem.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static byte[] zip(String[][] files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String[] file : files) {
                zip.putNextEntry(new ZipEntry(file[0]));
                zip.write(file[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
