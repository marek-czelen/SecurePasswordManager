package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.VaultItem;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AutofillMatcherTest {
    @Test
    public void matchesOnlyExactWebHost() {
        VaultItem exact = login("Exact", "https://Example.com/sign-in");
        VaultItem subdomain = login("Subdomain", "https://accounts.example.com");
        VaultItem other = login("Other", "https://example.net");

        List<VaultItem> matches = AutofillMatcher.matchingLogins(
                Arrays.asList(exact, subdomain, other), "example.com");

        assertEquals(Arrays.asList(exact), matches);
    }

    @Test
    public void acceptsHostWithoutSchemeAndIgnoresPathAndPort() {
        assertEquals("example.com",
                AutofillMatcher.hostFromUrl("Example.com:8443/login"));
        assertEquals("example.com",
                AutofillMatcher.hostFromUrl("https://example.com/account"));
        assertEquals("xn--bcher-kva.de",
                AutofillMatcher.hostFromUrl("https://bücher.de/login"));
    }

    @Test
    public void rejectsInvalidOrNonWebDomains() {
        assertNull(AutofillMatcher.hostFromUrl("file://example.com/passwords"));
        assertEquals(0, AutofillMatcher.matchingLogins(
                Arrays.asList(login("Example", "https://example.com")),
                "not a valid domain").size());
    }

    @Test
    public void domainlessFormsCanChooseOnlyUsableLoginEntries() {
        VaultItem login = login("Login", "");
        VaultItem emptyPassword = new VaultItem("Empty", "user", "", "", "", "");
        VaultItem image = VaultItem.imageReference("Image", "0123456789abcdef0123456789abcdef",
                "abcdef0123456789abcdef0123456789", 1);

        List<VaultItem> matches = AutofillMatcher.matchingLogins(
                Arrays.asList(login, emptyPassword, image), null);

        assertEquals(Arrays.asList(login), matches);
    }

    private static VaultItem login(String title, String url) {
        return new VaultItem(title, "user", "secret", url, "", "");
    }
}
