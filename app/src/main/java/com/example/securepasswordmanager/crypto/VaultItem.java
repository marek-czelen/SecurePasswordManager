package com.example.securepasswordmanager.crypto;

/** One decrypted text entry or a reference to an independently encrypted attachment. */
public final class VaultItem {
    public static final String TYPE_LOGIN = "login";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_FILE = "file";

    public final String entryId;
    public final String title;
    public final String username;
    public final String password;
    public final String url;
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

    public VaultItem(String entryId, String title, String username, String password, String url,
                     String notes, String tags, String folder, String totpSecret,
                     boolean favorite, String type, String attachmentId,
                     String thumbnailId, String contentType, long attachmentSize) {
        if (!isHexId(entryId, 16)
                || (!TYPE_LOGIN.equals(type) && !TYPE_IMAGE.equals(type)
                && !TYPE_FILE.equals(type))
                || attachmentSize < 0) {
            throw new IllegalArgumentException("Invalid vault entry");
        }
        this.entryId = entryId.toLowerCase(java.util.Locale.ROOT);
        this.title = value(title);
        this.username = value(username);
        this.password = value(password);
        this.url = value(url);
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

    public boolean isImage() {
        return TYPE_IMAGE.equals(type);
    }

    public boolean isFile() {
        return TYPE_FILE.equals(type);
    }

    public VaultItem withFavorite(boolean value) {
        return new VaultItem(entryId, title, username, password, url, notes, tags, folder,
                totpSecret, value, type, attachmentId, thumbnailId, contentType, attachmentSize);
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

    private static String normalizeId(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
