package com.cretorix.securepasswordmanager.security;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PinPolicyTest {
    @Test
    public void acceptsEightCharacterMixedSecret() {
        assertTrue(PinPolicy.isValid("Abcd123!".toCharArray()));
    }

    @Test
    public void rejectsShortNumericAndOverlongSecrets() {
        assertFalse(PinPolicy.isValid("Abc123!".toCharArray()));
        assertFalse(PinPolicy.isValid("12345678".toCharArray()));
        assertFalse(PinPolicy.isValid("Abcdefgh12345678901234567".toCharArray()));
    }
}
