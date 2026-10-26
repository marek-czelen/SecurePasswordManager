package com.cretorix.securepasswordmanager.crypto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One decrypted text entry or a reference to an independently encrypted attachment. */
public final class VaultItem {
    public static final String TYPE_LOGIN = "login";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_FILE = "file";
    public static final String TYPE_DOCUMENT = "document";
    public static final int MAX_DOCUMENT_PAGES = 20;
    public static final int MAX_PASSWORD_HISTORY_ENTRIES = 5;

    public final String entryId;
    public final String title;
    public final String username;
    public final String password;
    public final String url;
    public final String appPackageName;
    public final String notes;
    public final String tags;
    public final String folder;
    public final String totpSecret;
    public final boolean favorite;
    public final String type;
    public final String attachmentId;
    public final String thumbnailId;
    public final String contentType;
    public final long attachmentSize;
    public final List<String> documentPageIds;
    public final List<String> passwordHistory;

    public VaultItem(String title, String username, String password, String url,
                     String notes, String tags) {
        this(randomEntryId(), title, username, password, url, notes, tags,
                "", "", false, TYPE_LOGIN, null, null, "", 0);
    }

    public VaultItem(String title, String username, String password, String url,
                     String notes, String tags, String folder, String totpSecret,
                     boolean favorite) {
        this(randomEntryId(), title, username, password, url, notes, tags, folder,
                totpSecret, favorite, TYPE_LOGIN, null, null, "", 0);
    }

    public VaultItem(String title, String username, String password, String url,
                     String notes, String tags, String folder, String totpSecret,
                     boolean favorite, String appPackageName) {
        this(randomEntryId(), title, username, password, url, notes, tags, folder,
                totpSecret, favorite, TYPE_LOGIN, null, null, "", 0,
                Collections.emptyList(), Collections.emptyList(), appPackageName);
    }

    public VaultItem(String entryId, String title, String username, String password, String url,
                     String notes, String tags, String folder, String totpSecret,
                     boolean favorite, String type, String attachmentId,
                     String thumbnailId, String contentType, long attachmentSize) {
        this(entryId, title, username, password, url, notes, tags, folder, totpSecret,
                favorite, type, attachmentId, thumbnailId, contentType, attachmentSize,
                Collections.emptyList(), Collections.emptyList());
    }

    public VaultItem(String entryId, String title, String username, String password, String url,
                     String notes, String tags, String folder, String totpSecret,
                     boolean favorite, String type, String attachmentId,
                     String thumbnailId, String contentType, long attachmentSize,
                     List<String> passwordHistory) {
        this(entryId, title, username, password, url, notes, tags, folder, totpSecret,
                favorite, type, attachmentId, thumbnailId, contentType, attachmentSize,
                Collections.emptyList(), passwordHistory);
    }

    public VaultItem(String entryId, String title, String username, String password, String url,
                     String notes, String tags, String folder, String totpSecret,
                     boolean favorite, String type, String attachmentId,
                     String thumbnailId, String contentType, long attachmentSize,
                     List<String> documentPageIds, List<String> passwordHistory) {
        this(entryId, title, username, password, url, notes, tags, folder, totpSecret,
                favorite, type, attachmentId, thumbnailId, contentType, attachmentSize,
                documentPageIds, passwordHistory, "");
    }

    public VaultItem(String entryId, String title, String username, String password, String url,
                     String notes, String tags, String folder, String totpSecret,
                     boolean favorite, String type, String attachmentId,
                     String thumbnailId, String contentType, long attachmentSize,
                     List<String> documentPageIds, List<String> passwordHistory,
                     String appPackageName) {
        if (!isHexId(entryId, 16)
                || (!TYPE_LOGIN.equals(type) && !TYPE_IMAGE.equals(type)
                && !TYPE_FILE.equals(type) && !TYPE_DOCUMENT.equals(type))
                || attachmentSize < 0 || passwordHistory == null
                || documentPageIds == null
                || (appPackageName != null && !appPackageName.isEmpty()
                && (!TYPE_LOGIN.equals(type) || !isValidPackageName(appPackageName)))
                || passwordHistory.size() > MAX_PASSWORD_HISTORY_ENTRIES
                || (!TYPE_LOGIN.equals(type) && !passwordHistory.isEmpty())
                || (TYPE_DOCUMENT.equals(type)
                ? documentPageIds.isEmpty() || documentPageIds.size() > MAX_DOCUMENT_PAGES
                || attachmentId != null || thumbnailId != null || attachmentSize != 0
                || (contentType != null && !contentType.isEmpty())
                : !documentPageIds.isEmpty())) {
            throw new IllegalArgumentException("Invalid vault entry");
        }
        this.entryId = entryId.toLowerCase(java.util.Locale.ROOT);
        this.title = value(title);
        this.username = value(username);
        this.password = value(password);
        this.url = value(url);
        this.appPackageName = value(appPackageName);
        this.notes = value(notes);
        this.tags = value(tags);
        this.folder = value(folder);
        this.totpSecret = value(totpSecret);
        this.favorite = favorite;
        this.type = type;
        this.attachmentId = normalizeId(attachmentId);
        this.thumbnailId = normalizeId(thumbnailId);
        this.contentType = value(contentType);
        this.attachmentSize = attachmentSize;
        List<String> pageIds = new ArrayList<>(documentPageIds);
        for (String pageId : pageIds) {
            if (!isHexId(pageId, 32)) {
                throw new IllegalArgumentException("Invalid document page reference");
            }
        }
        this.documentPageIds = Collections.unmodifiableList(pageIds);
        List<String> history = new ArrayList<>(passwordHistory);
        Set<String> uniquePasswords = new HashSet<>();
        for (String previousPassword : history) {
            if (previousPassword == null || previousPassword.isEmpty()
                    || previousPassword.equals(this.password)
                    || !uniquePasswords.add(previousPassword)) {
                throw new IllegalArgumentException("Invalid password history");
            }
        }
        this.passwordHistory = Collections.unmodifiableList(history);
    }

    public static VaultItem imageReference(String title, String imageId,
                                           String thumbnailId, long imageSize) {
        return new VaultItem(randomEntryId(), title, "", "", "", "", "", "", "",
                false, TYPE_IMAGE, imageId, thumbnailId, "image/jpeg", imageSize);
    }

    public static VaultItem fileReference(String title, String fileId,
                                          String contentType, long fileSize) {
        return new VaultItem(randomEntryId(), title, "", "", "", "", "", "", "",
                false, TYPE_FILE, fileId, null, contentType, fileSize);
    }

    public static VaultItem documentReference(String title, List<String> pageIds) {
        return new VaultItem(randomEntryId(), title, "", "", "", "", "", "", "",
                false, TYPE_DOCUMENT, null, null, "", 0, pageIds, Collections.emptyList());
    }

    public boolean isImage() {
        return TYPE_IMAGE.equals(type);
    }

    public boolean isFile() {
        return TYPE_FILE.equals(type);
    }

    public boolean isDocument() {
        return TYPE_DOCUMENT.equals(type);
    }

    public VaultItem withFavorite(boolean value) {
        return new VaultItem(entryId, title, username, password, url, notes, tags, folder,
                totpSecret, value, type, attachmentId, thumbnailId, contentType, attachmentSize,
                documentPageIds, passwordHistory, appPackageName);
    }

    public VaultItem withAppPackageName(String packageName) {
        return new VaultItem(entryId, title, username, password, url, notes, tags, folder,
                totpSecret, favorite, type, attachmentId, thumbnailId, contentType,
                attachmentSize, documentPageIds, passwordHistory, packageName);
    }

    /** Returns a copy with the immediately previous password at the front of its history. */
    public VaultItem withPreviousPassword(String previousPassword) {
        if (!TYPE_LOGIN.equals(type) || previousPassword == null || previousPassword.isEmpty()
                || previousPassword.equals(password)) {
            return this;
        }
        List<String> history = new ArrayList<>(passwordHistory);
        history.remove(password);
        history.remove(previousPassword);
        history.add(0, previousPassword);
        if (history.size() > MAX_PASSWORD_HISTORY_ENTRIES) {
            history.remove(history.size() - 1);
        }
        return new VaultItem(entryId, title, username, password, url, notes, tags, folder,
                totpSecret, favorite, type, attachmentId, thumbnailId, contentType,
                attachmentSize, documentPageIds, history, appPackageName);
    }

    /** Computes the retained history for an edit, optionally recording this item's password. */
    public List<String> passwordHistoryAfterChange(String newPassword, boolean recordPrevious) {
        String nextPassword = value(newPassword);
        List<String> history = new ArrayList<>(passwordHistory);
        history.remove(nextPassword);
        boolean passwordChanged = !password.isEmpty() && !password.equals(nextPassword);
        if (recordPrevious && passwordChanged) {
            history.remove(password);
            history.add(0, password);
        }
        if (history.size() > MAX_PASSWORD_HISTORY_ENTRIES) {
            history.remove(history.size() - 1);
        }
        return Collections.unmodifiableList(history);
    }

    private static String randomEntryId() {
        byte[] id = Crypto.randomBytes(16);
        StringBuilder output = new StringBuilder(32);
        for (byte item : id) {
            output.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            output.append(Character.forDigit(item & 0x0f, 16));
        }
        Crypto.wipe(id);
        return output.toString();
    }

    static boolean isHexId(String value, int bytes) {
        if (value == null || value.length() != bytes * 2) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidPackageName(String value) {
        if (value == null || value.length() < 3 || value.length() > 255) {
            return false;
        }
        boolean hasSeparator = false;
        boolean segmentEmpty = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '.') {
                if (segmentEmpty) {
                    return false;
                }
                hasSeparator = true;
                segmentEmpty = true;
            } else if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9') || character == '_') {
                segmentEmpty = false;
            } else {
                return false;
            }
        }
        return hasSeparator && !segmentEmpty;
    }

    private static String normalizeId(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
