package com.gzu.shuttle.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHashServiceTest {
    private final PasswordHashService hashes = new PasswordHashService();

    @Test
    void hashesAndVerifiesPasswordWithoutStoringPlaintext() {
        String encoded = hashes.encode("ShuttleFlow-Password-1");
        assertTrue(encoded.startsWith("pbkdf2-sha256$310000$"));
        assertFalse(encoded.contains("ShuttleFlow-Password-1"));
        assertTrue(hashes.matches("ShuttleFlow-Password-1", encoded));
        assertFalse(hashes.matches("wrong-password", encoded));
    }

    @Test
    void generatesUniqueSaltForSamePassword() {
        assertNotEquals(hashes.encode("same-password"), hashes.encode("same-password"));
    }
}
