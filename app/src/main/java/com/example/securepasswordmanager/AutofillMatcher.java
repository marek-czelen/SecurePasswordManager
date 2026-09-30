package com.example.securepasswordmanager;

import com.example.securepasswordmanager.crypto.VaultItem;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class AutofillMatcher {
    private AutofillMatcher() {
    }

    static List<VaultItem> matchingLogins(List<VaultItem> items, String webDomain) {
        String requestedHost = webDomain == null || webDomain.trim().isEmpty()
                ? null : hostFromUrl(webDomain);
        if (requestedHost == null && webDomain != null && !webDomain.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<VaultItem> matches = new ArrayList<>();
        for (VaultItem item : items) {
            if (!VaultItem.TYPE_LOGIN.equals(item.type) || item.password.isEmpty()) {
                continue;
            }
            if (requestedHost == null) {
                matches.add(item);
                continue;
            }
            String entryHost = hostFromUrl(item.url);
            if (requestedHost.equals(entryHost)) {
                matches.add(item);
            }
        }
        return matches;
    }

    static String hostFromUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String input = value.trim();
        if (!input.contains("://")) {
            input = "https://" + input;
        }
        try {
            URI uri = new URI(input);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            String authority = uri.getRawAuthority();
            if (host == null && authority != null && !authority.contains("@")) {
                host = authority;
                int portSeparator = host.lastIndexOf(':');
                if (portSeparator > 0 && host.substring(portSeparator + 1).matches("[0-9]+")) {
                    host = host.substring(0, portSeparator);
                }
            }
            if (host == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme))) {
                return null;
            }
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            if (host.isEmpty()) {
                return null;
            }
            return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException | URISyntaxException exception) {
            return null;
        }
    }
}
