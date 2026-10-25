package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.VaultItem;

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
        return matchingLogins(items, webDomain, null);
    }

    static List<VaultItem> matchingLogins(List<VaultItem> items, String webDomain,
                                          String appPackageName) {
        String requestedHost = webDomain == null || webDomain.trim().isEmpty()
                ? null : hostFromUrl(webDomain);
        if (requestedHost == null && webDomain != null && !webDomain.trim().isEmpty()) {
            return Collections.emptyList();
        }
        boolean hasAppContext = requestedHost == null && appPackageName != null
                && !appPackageName.trim().isEmpty();
        String requestedPackage = validPackageName(appPackageName);
        if (hasAppContext && requestedPackage == null) {
            return Collections.emptyList();
        }

        List<VaultItem> matches = new ArrayList<>();
        List<VaultItem> appMatches = new ArrayList<>();
        for (VaultItem item : items) {
            if (!VaultItem.TYPE_LOGIN.equals(item.type) || item.password.isEmpty()) {
                continue;
            }
            if (requestedPackage != null) {
                if (requestedPackage.equals(item.appPackageName)) {
                    appMatches.add(item);
                }
            }
            if (requestedHost == null) {
                if (requestedPackage == null && item.appPackageName.isEmpty()) {
                    matches.add(item);
                }
                continue;
            }
            if (item.appPackageName.isEmpty()
                    && requestedHost.equals(hostFromUrl(item.url))) {
                matches.add(item);
            }
        }
        return matches.isEmpty() ? appMatches : matches;
    }

    static boolean shouldOfferNewLogin(List<VaultItem> items, String webDomain) {
        return shouldOfferNewLogin(items, webDomain, null);
    }

    static boolean shouldOfferNewLogin(List<VaultItem> items, String webDomain,
                                       String appPackageName) {
        boolean hasWebDomain = webDomain != null && !webDomain.trim().isEmpty();
        if (hasWebDomain) {
            return hostFromUrl(webDomain) != null
                    && matchingLogins(items, webDomain, appPackageName).isEmpty();
        }
        String packageName = validPackageName(appPackageName);
        return packageName != null
                && matchingLogins(items, null, packageName).isEmpty();
    }

    private static String validPackageName(String value) {
        return VaultItem.isValidPackageName(value) ? value : null;
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
