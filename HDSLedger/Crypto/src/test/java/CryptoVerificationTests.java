import org.junit.jupiter.api.Test;
import pt.ulisboa.tecnico.hdsledger.crypto.CryptoLib;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the CryptoLib class to ensure the correctness of cryptographic operations.
 */
public class CryptoVerificationTests {
    private static final String testData = "TEST DATA";

    /* For simplicity we will test the CryptoLib based on [testData]
    private static final Message testMessage =
            new Message(
                    testData,
                    Message.Type.APPEND,
                    Message.ClientType.CLIENT
            );
     */

    /**
     * Test the signing and verification process using valid, and unmodified, data.
     */
    @Test
    public void testSignAndVerify() throws Exception {
        KeyPair keyPair = generateKeyPair();

        String signature = CryptoLib
                .signData(testData, keyPair.getPrivate());

        boolean isSignatureValid = CryptoLib
                .validateSignature(testData, signature, keyPair.getPublic());

        assertTrue(isSignatureValid, "Signature should be valid");
    }

    /**
     * Test the verification of an invalid signature.
     */
    @Test
    public void testInvalidSignature() throws Exception {
        KeyPair keyPair = generateKeyPair();

        String signature = CryptoLib
                .signData(testData, keyPair.getPrivate());

        String modifiedSignature = signature + "corrupted";

        boolean isSignatureValid = CryptoLib
                .validateSignature(testData, modifiedSignature, keyPair.getPublic());

        assertFalse(isSignatureValid, "Modified signature should be invalid");
    }

    /**
     * Test the verification of a message with corrupted content.
     * Let's say that a [Node] receives a message and attempts to modify its content.
     */
    @Test
    public void testCorruptedMessage() throws Exception {
        KeyPair keyPair = generateKeyPair();

        String signature = CryptoLib
                .signData(testData, keyPair.getPrivate());

        String invalidData = "CORRUPTED";

        boolean isSignatureValid = CryptoLib
                .validateSignature(invalidData, signature, keyPair.getPublic());

        assertFalse(isSignatureValid, "Corrupted message should be invalid");
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.generateKeyPair();
    }
}