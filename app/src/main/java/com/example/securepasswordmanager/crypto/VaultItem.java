package com.example.securepasswordmanager.crypto;

public final class VaultItem {
    public final String title;
    public final String username;
    public final String password;
    public final String url;
    public final String notes;
    public final String tags;
    public final String folder;
    public final String totpSecret;
    public final boolean favorite;

    public VaultItem(String title, String username, String password, String url, String notes, String tags) {
        this(title, username, password, url, notes, tags, "", "", false);
    }

    public VaultItem(String title, String username, String password, String url, String notes,
                     String tags, String folder, String totpSecret, boolean favorite) {
        this.title = title == null ? "" : title;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.url = url == null ? "" : url;
        this.notes = notes == null ? "" : notes;
        this.tags = tags == null ? "" : tags;
        this.folder = folder == null ? "" : folder;
        this.totpSecret = totpSecret == null ? "" : totpSecret;
        this.favorite = favorite;
    }
}
