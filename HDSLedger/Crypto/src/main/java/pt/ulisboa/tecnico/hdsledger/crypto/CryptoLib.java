package pt.ulisboa.tecnico.hdsledger.crypto;

import pt.ulisboa.tecnico.hdsledger.utilities.SerenitySerializer;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class CryptoLib {

    public static PrivateKey loadPrivateKey(String privateKeyFilePath) throws Exception {
        byte[] keyBytes = Files.readAllBytes(Paths.get(privateKeyFilePath));
        String stringAfter = new String(keyBytes)
                .replaceAll("\\s+", "")
                .replaceAll("-----BEGINPRIVATEKEY-----", "")
                .replaceAll("-----ENDPRIVATEKEY-----", "")
                .trim();

        byte[] decoded = Base64.getDecoder().decode(stringAfter);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    public static PublicKey loadPublicKey(String publicKeyFilePath) throws Exception {
        byte[] keyBytes = Files.readAllBytes(Paths.get(publicKeyFilePath));

        String stringAfter = new String(keyBytes)
                .replaceAll("\\s+", "")
                .replaceAll("-----BEGINPUBLICKEY-----", "")
                .replaceAll("-----ENDPUBLICKEY-----", "")
                .trim();

        byte[] decoded = Base64.getDecoder().decode(stringAfter);
        KeySpec keySpec = new X509EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(keySpec);
    }

    public static <T extends Serializable> String signData(T object, PrivateKey privateKey) {
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            ObjectOutputStream objectStream = new ObjectOutputStream(byteStream);
            objectStream.writeObject(object);
            objectStream.close();

            byte[] dataToSign = byteStream.toByteArray();

            Signature privateSignature = Signature.getInstance("SHA256withRSA");
            privateSignature.initSign(privateKey);
            privateSignature.update(dataToSign);

            byte[] signatureBytes = privateSignature.sign();
            return Base64.getEncoder().encodeToString(signatureBytes);
        } catch (Exception e) {
            e.printStackTrace();

            //If we fail to encrypt the data, [Message] will state a null signature which will fail on [CryptoLib.validateSignature]
            return null;
        }
    }

    public static <T extends Serializable> boolean validateSignature(T object, String signature, PublicKey publicKey) {
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            ObjectOutputStream objectStream = new ObjectOutputStream(byteStream);
            objectStream.writeObject(object);
            objectStream.close();

            byte[] dataToVerify = byteStream.toByteArray();

            Signature publicSignature = Signature.getInstance("SHA256withRSA");
            publicSignature.initVerify(publicKey);
            publicSignature.update(dataToVerify);

            byte[] signatureBytes = Base64.getDecoder().decode(signature);

            return publicSignature.verify(signatureBytes);
        } catch (Exception e) {
            System.out.println("Failed to validate signature.");
            return false;
        }
    }

    public static <T extends Serializable> String hashData(T data) {
        try {

            byte[] dataBytes = SerenitySerializer.serialize(data);


            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(dataBytes);

            // Convert it to hex
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static <T extends Serializable> boolean validateHash(T data, String providedHash) {
        String computedHash = hashData(data);
        return providedHash.equals(computedHash);
    }

    // Used for test purposes only
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (Exception e) {
            return null;
        }
    }
}