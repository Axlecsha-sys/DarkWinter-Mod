// Encrypts authenticated payloads with X25519, HKDF-SHA256, and AES-256-GCM.
package com.dark.winter.online.identity;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** JDK 25 X25519, standard HKDF-SHA256 and AES-256-GCM. The service can route an opaque signed envelope. */
public final class IdentityCipher {
    private static final Base64.Encoder B64=Base64.getUrlEncoder().withoutPadding();
    private IdentityCipher(){}
    public static PublicKey publicKey(String encoded)throws IOException {
        byte[] bytes=IdentityStore.decode(encoded,128);
        try{
            PublicKey key=KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(bytes));
            if(!Arrays.equals(bytes,key.getEncoded()))throw new IOException("Noncanonical encryption public key");
            return key;
        }catch(GeneralSecurityException failure){throw new IOException("Invalid X25519 public key",failure);}
    }
    static void verifyPair(PrivateKey privateKey,String publicKey)throws IOException,GeneralSecurityException {
        KeyPair probe=KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] one=agree(privateKey,probe.getPublic()),two=agree(probe.getPrivate(),publicKey(publicKey));
        try{if(!MessageDigest.isEqual(one,two))throw new IOException("Encryption key pair does not match");}
        finally{Arrays.fill(one,(byte)0);Arrays.fill(two,(byte)0);}
    }
    static IdentityStore.SignedPayload seal(IdentityStore identity,IdentityProfile recipient,IdentityStore.Purpose purpose,byte[] plaintext)throws IOException {
        IdentityStore.validateProfile(recipient);
        if(purpose==null||plaintext==null||plaintext.length>32768)throw new IOException("Encrypted payload exceeds 32 KiB");
        byte[] shared=null;
        try{
            KeyPair ephemeral=KeyPairGenerator.getInstance("X25519").generateKeyPair();
            byte[] salt=IdentityStore.randomBytes(32),nonce=IdentityStore.randomBytes(12);
            JsonObject header=new JsonObject();header.addProperty("schema",1);header.addProperty("kind","DarkWinterE2E");
            header.addProperty("purpose",purpose.name());header.addProperty("sender",identity.profile().id());header.addProperty("recipient",recipient.id());
            header.addProperty("recipientKey",recipient.encryptionPublicKey());header.addProperty("ephemeral",B64.encodeToString(ephemeral.getPublic().getEncoded()));
            header.addProperty("salt",B64.encodeToString(salt));header.addProperty("nonce",B64.encodeToString(nonce));
            shared=agree(ephemeral.getPrivate(),publicKey(recipient.encryptionPublicKey()));
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,derive(shared,salt,header),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(ProfileFiles.bytes(header));header.addProperty("ciphertext",B64.encodeToString(cipher.doFinal(plaintext)));
            return identity.sign(purpose,ProfileFiles.bytes(header));
        }catch(GeneralSecurityException failure){throw new IOException("Encrypted envelope creation failed",failure);}
        finally{if(shared!=null)Arrays.fill(shared,(byte)0);}
    }
    /** Syntax/signature/audience validation for a routing service. No private key or decryption. */
    public static void validateEnvelope(IdentityStore.SignedPayload payload,IdentityStore.Purpose purpose,IdentityProfile recipient)throws IOException {
        IdentityStore.validateProfile(recipient);
        if(!IdentityStore.verify(payload,purpose))throw new IOException("Invalid signed encrypted envelope");
        JsonObject value=ProfileFiles.parse(IdentityStore.decodeData(payload),65536);
        ProfileFiles.fields(value,Set.of("schema","kind","purpose","sender","recipient","recipientKey","ephemeral","salt","nonce","ciphertext"),Set.of());
        if(ProfileFiles.number(value,"schema")!=1||!ProfileFiles.string(value,"kind",32).equals("DarkWinterE2E")
                ||!ProfileFiles.string(value,"purpose",32).equals(purpose.name())
                ||!ProfileFiles.string(value,"sender",64).equals(payload.signer().id())
                ||!ProfileFiles.string(value,"recipient",64).equals(recipient.id())
                ||!ProfileFiles.string(value,"recipientKey",256).equals(recipient.encryptionPublicKey()))
            throw new IOException("Encrypted envelope recipient or purpose mismatch");
        if(IdentityStore.decode(ProfileFiles.string(value,"salt",64),32).length!=32
                ||IdentityStore.decode(ProfileFiles.string(value,"nonce",64),12).length!=12
                ||IdentityStore.decode(ProfileFiles.string(value,"ciphertext",50000),32784).length<16)
            throw new IOException("Invalid encrypted envelope parameters");
        publicKey(ProfileFiles.string(value,"ephemeral",256));
    }
    static byte[] open(IdentityStore identity,PrivateKey privateKey,IdentityStore.SignedPayload payload,IdentityStore.Purpose purpose)throws IOException {
        validateEnvelope(payload,purpose,identity.profile());
        JsonObject value=ProfileFiles.parse(IdentityStore.decodeData(payload),65536);
        ProfileFiles.fields(value,Set.of("schema","kind","purpose","sender","recipient","recipientKey","ephemeral","salt","nonce","ciphertext"),Set.of());
        if(ProfileFiles.number(value,"schema")!=1||!ProfileFiles.string(value,"kind",32).equals("DarkWinterE2E")
                ||!ProfileFiles.string(value,"purpose",32).equals(purpose.name())
                ||!ProfileFiles.string(value,"sender",64).equals(payload.signer().id())
                ||!ProfileFiles.string(value,"recipient",64).equals(identity.profile().id())
                ||!ProfileFiles.string(value,"recipientKey",256).equals(identity.profile().encryptionPublicKey()))
            throw new IOException("Encrypted envelope recipient or purpose mismatch");
        byte[] salt=IdentityStore.decode(ProfileFiles.string(value,"salt",64),32),nonce=IdentityStore.decode(ProfileFiles.string(value,"nonce",64),12);
        if(salt.length!=32||nonce.length!=12)throw new IOException("Invalid encrypted envelope parameters");
        byte[] ciphertext=IdentityStore.decode(ProfileFiles.string(value,"ciphertext",50000),32784),shared=null;
        JsonObject header=value.deepCopy();header.remove("ciphertext");
        try{
            shared=agree(privateKey,publicKey(ProfileFiles.string(value,"ephemeral",256)));
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,derive(shared,salt,header),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(ProfileFiles.bytes(header));return cipher.doFinal(ciphertext);
        }catch(GeneralSecurityException failure){throw new IOException("Encrypted envelope authentication failed",failure);}
        finally{if(shared!=null)Arrays.fill(shared,(byte)0);}
    }
    private static byte[] agree(PrivateKey privateKey,PublicKey publicKey)throws GeneralSecurityException {
        KeyAgreement agreement=KeyAgreement.getInstance("X25519");agreement.init(privateKey);agreement.doPhase(publicKey,true);
        byte[] secret=agreement.generateSecret();int nonzero=0;for(byte value:secret)nonzero|=value;
        if(nonzero==0){Arrays.fill(secret,(byte)0);throw new InvalidKeyException("Invalid X25519 peer point");}
        return secret;
    }
    private static SecretKey derive(byte[] shared,byte[] salt,JsonObject header)throws GeneralSecurityException {
        byte[] info=("DarkWinter E2E v1\n"+new String(ProfileFiles.bytes(header),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        return KDF.getInstance("HKDF-SHA256").deriveKey("AES",HKDFParameterSpec.ofExtract().addIKM(shared).addSalt(salt).thenExpand(info,32));
    }
}
