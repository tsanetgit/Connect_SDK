package com.tsanet.e2ee;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * {@link KeyOperations} over private keys held in memory, for tests and local use. A deployment
 * that keeps keys in a key service implements {@link KeyOperations} against that service instead.
 */
public final class SoftwareKeyOperations implements KeyOperations {
    private final String encryptionKid;
    private final Map<String, PrivateKey> encryptionKeys = new HashMap<>();
    private final String signingKid;
    private final PrivateKey signingKey;

    /**
     * @param encryption this member's RSA encryption key, private part included, with its {@code kid}
     * @param signing    this member's P-256 signing key, private part included, with its {@code kid}
     */
    public SoftwareKeyOperations(RSAKey encryption, ECKey signing) throws JOSEException {
        this(encryption, List.of(), signing);
    }

    /**
     * @param earlierEncryption earlier encryption key versions, still held after a rotation so
     *                          records encrypted to them stay readable; not published
     */
    public SoftwareKeyOperations(RSAKey encryption, List<RSAKey> earlierEncryption, ECKey signing) throws JOSEException {
        if (encryption.getKeyID() == null || signing.getKeyID() == null) {
            throw new IllegalArgumentException("Both keys need a kid");
        }
        if (!encryption.isPrivate() || !signing.isPrivate()) {
            throw new IllegalArgumentException("Both keys need their private part");
        }
        this.encryptionKid = encryption.getKeyID();
        for (RSAKey earlier : earlierEncryption) {
            if (earlier.getKeyID() == null || !earlier.isPrivate()) {
                throw new IllegalArgumentException("An earlier encryption key needs a kid and its private part");
            }
            encryptionKeys.put(earlier.getKeyID(), earlier.toPrivateKey());
        }
        encryptionKeys.put(encryptionKid, encryption.toPrivateKey());
        this.signingKid = signing.getKeyID();
        this.signingKey = signing.toPrivateKey();
    }

    @Override
    public String encryptionKid() {
        return encryptionKid;
    }

    @Override
    public boolean holdsEncryptionKey(String kid) {
        return encryptionKeys.containsKey(kid);
    }

    @Override
    public String signingKid() {
        return signingKid;
    }

    @Override
    public byte[] unwrap(String kid, byte[] encryptedKey) throws GeneralSecurityException {
        PrivateKey encryptionKey = kid == null ? null : encryptionKeys.get(kid);
        if (encryptionKey == null) {
            throw new InvalidKeyException("Not this member's encryption key");
        }
        // Set SHA-256 for MGF1 explicitly: some providers default the transformation's MGF1 to SHA-1.
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return cipher.doFinal(encryptedKey);
    }

    @Override
    public byte[] sign(byte[] signingInput) throws GeneralSecurityException {
        // The P1363 form is the raw r || s that JWS uses; plain SHA256withECDSA would give DER.
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(signingKey);
        signature.update(signingInput);
        return signature.sign();
    }

    @Override
    public String toString() {
        return "SoftwareKeyOperations[encryptionKid=" + encryptionKid + ", signingKid=" + signingKid + "]";
    }
}
