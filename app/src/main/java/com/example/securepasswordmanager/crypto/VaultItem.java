package com.example.securepasswordmanager.crypto;

/**
 * One encrypted-vault record. The object is plaintext only while the vault is unlocked.
 * TOTP secrets and organization metadata are encrypted together with the password.
 */
public final class VaultItem {
    public static final String TYPE_LOGIN = "login";
    public static final String TYPE_IMAGE = "image";
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
    public final byte[] imageJpeg;

    public VaultItem(String title, String username, String password, String url, String notes, String tags) {
        this(title, username, password, url, notes, tags, "", "", false);
    }

    public VaultItem(String title, String username, String password, String url, String notes,
                     String tags, String folder, String totpSecret, boolean favorite) {
        this(title, username, password, url, notes, tags, folder, totpSecret, favorite,
                TYPE_LOGIN, null);
    }

    public VaultItem(String title, String username, String password, String url, String notes,
                     String tags, String folder, String totpSecret, boolean favorite,
                     String type, byte[] imageJpeg) {
        this.title = title == null ? "" : title;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.url = url == null ? "" : url;
        this.notes = notes == null ? "" : notes;
        this.tags = tags == null ? "" : tags;
        this.folder = folder == null ? "" : folder;
        this.totpSecret = totpSecret == null ? "" : totpSecret;
        this.favorite = favorite;
        this.type = TYPE_IMAGE.equals(type) ? TYPE_IMAGE : TYPE_LOGIN;
        this.imageJpeg = imageJpeg == null ? null : imageJpeg.clone();
    }

    public static VaultItem image(String title, byte[] jpeg) {
        return new VaultItem(title, "", "", "", "", "", "", "", false,
                TYPE_IMAGE, jpeg);
    }

    public boolean isImage() {
        return TYPE_IMAGE.equals(type);
    }
}
