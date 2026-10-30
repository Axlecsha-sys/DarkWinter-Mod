// Persists the protected local identity bundle and signs or encrypts authenticated payloads.
package com.dark.winter.online.identity;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** One owner-protected local profile bundle. Only backup exports are password encrypted. */
public final class IdentityStore implements AutoCloseable {
    public enum Purpose {PROFILE,AUTH,FRIEND,INVITATION,SERVICE_REQUEST,SERVICE_RESPONSE,SIGNAL,CHAT,COMPATIBILITY}
    public enum Section {FRIENDS,INVITATIONS}
    public record SignedPayload(int schema,Purpose purpose,IdentityProfile signer,String data,String signature) {
        @Override public String toString(){return "SignedPayload[purpose="+purpose+"]";}
    }
    @FunctionalInterface public interface BackupValidator {void validate(IdentityProfile owner,JsonObject friends)throws IOException;}
    private static final Gson JSON=new Gson();
    private static final Base64.Encoder B64=Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder UN64=Base64.getUrlDecoder();
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final int PROFILE_LIMIT=8*1024*1024,BACKUP_LIMIT=16*1024*1024,ITERATIONS=600000;
    private final Path base,file;
    private final FileChannel lockChannel;private final FileLock lock;
    private JsonObject bundle;private IdentityProfile profile;private PrivateKey privateKey,encryptionPrivateKey;private boolean closed;
    private IdentityStore(Path base,FileChannel channel,FileLock lock,JsonObject document)throws IOException {
        this.base=base;this.file=base.resolve("profile.json");this.lockChannel=channel;this.lock=lock;
        load(document);
    }
    public static IdentityStore open(Path requested)throws IOException {
        Path base=ProfileFiles.directory(requested);FileChannel channel=FileChannel.open(base.resolve(".profile.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock lock=null;
        try{
            ProfileFiles.protect(base.resolve(".profile.lock"),false);
            lock=channel.tryLock();if(lock==null)throw new IOException("Profile is already open in another process");
            Path file=base.resolve("profile.json");JsonObject document;
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){ProfileFiles.protect(file,false);document=ProfileFiles.read(file,PROFILE_LIMIT);}
            else{
                try{
                    KeyPair keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                    KeyPair encryption=KeyPairGenerator.getInstance("X25519").generateKeyPair();
                    String spki=B64.encodeToString(keys.getPublic().getEncoded());
                    IdentityProfile profile=new IdentityProfile(1,idForPublicKey(spki),spki,B64.encodeToString(encryption.getPublic().getEncoded()),"Player",IdentityProfile.Model.MALE,IdentityProfile.Privacy.defaults(),0,System.currentTimeMillis());
                    document=new JsonObject();document.addProperty("schema",1);document.add("profile",JSON.toJsonTree(profile));
                    document.addProperty("privateKey",B64.encodeToString(keys.getPrivate().getEncoded()));
                    document.addProperty("encryptionPrivateKey",B64.encodeToString(encryption.getPrivate().getEncoded()));document.add("sections",new JsonObject());
                    ProfileFiles.atomic(file,document,false);
                }catch(GeneralSecurityException failure){throw new IOException("Ed25519 is unavailable",failure);}
            }
            return new IdentityStore(base,channel,lock,document);
        }catch(IOException|RuntimeException failure){
            if(lock!=null)lock.release();channel.close();
            if(failure instanceof IOException io)throw io;throw new IOException("Profile could not be opened",failure);
        }
    }
    private void load(JsonObject document)throws IOException {
        ProfileFiles.fields(document,Set.of("schema","profile","privateKey","encryptionPrivateKey","sections"),Set.of());
        if(ProfileFiles.number(document,"schema")!=1)throw new IOException("Unsupported profile schema; original file preserved");
        if(!document.get("profile").isJsonObject())throw new IOException("Invalid profile object");
        ProfileFiles.fields(document.getAsJsonObject("profile"),Set.of("schema","id","publicKey","encryptionPublicKey","nickname","model","privacy","revision","createdAt"),Set.of());
        ProfileFiles.fields(document.getAsJsonObject("profile").getAsJsonObject("privacy"),Set.of("presence","requests","messages","invitations"),Set.of());
        IdentityProfile owner;
        try{owner=validateProfile(JSON.fromJson(document.get("profile"),IdentityProfile.class));}
        catch(RuntimeException failure){throw new IOException("Invalid public profile",failure);}
        if(!document.get("sections").isJsonObject())throw new IOException("Invalid profile sections");
        for(var entry:document.getAsJsonObject("sections").entrySet()){
            try{Section.valueOf(entry.getKey());}catch(IllegalArgumentException failure){throw new IOException("Unsupported profile section",failure);}
            if(!entry.getValue().isJsonObject()||ProfileFiles.number(entry.getValue().getAsJsonObject(),"schema")!=1)
                throw new IOException("Unsupported section schema; original file preserved");
        }
        byte[] encoded=decode(ProfileFiles.string(document,"privateKey",512),256);
        try{
            PrivateKey key=KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
            if(!Arrays.equals(key.getEncoded(),encoded))throw new IOException("Noncanonical private key");
            Signature signer=Signature.getInstance("Ed25519");signer.initSign(key);byte[] probe=new byte[32];RANDOM.nextBytes(probe);signer.update(probe);
            Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(publicKey(owner.publicKey()));verifier.update(probe);
            if(!verifier.verify(signer.sign()))throw new IOException("Profile key pair does not match");
            byte[] encodedEncryption=decode(ProfileFiles.string(document,"encryptionPrivateKey",512),256);
            try{
                PrivateKey encryption=KeyFactory.getInstance("X25519").generatePrivate(new PKCS8EncodedKeySpec(encodedEncryption));
                if(!Arrays.equals(encryption.getEncoded(),encodedEncryption))throw new IOException("Noncanonical encryption key");
                IdentityCipher.verifyPair(encryption,owner.encryptionPublicKey());
                this.encryptionPrivateKey=encryption;
            }finally{Arrays.fill(encodedEncryption,(byte)0);}
            this.privateKey=key;this.profile=owner;this.bundle=document.deepCopy();
        }catch(GeneralSecurityException failure){throw new IOException("Invalid protected identity key",failure);}
        finally{Arrays.fill(encoded,(byte)0);}
    }
    public Path base(){return base;}
    public synchronized IdentityProfile profile(){ensureOpen();return profile;}
    public synchronized IdentityProfile updateProfile(String nickname,IdentityProfile.Model model,IdentityProfile.Privacy privacy)throws IOException {
        ensureOpen();if(profile.revision()==Long.MAX_VALUE)throw new IOException("Profile revision exhausted");
        IdentityProfile changed=new IdentityProfile(1,profile.id(),profile.publicKey(),profile.encryptionPublicKey(),nickname,model,privacy,profile.revision()+1,profile.createdAt());
        JsonObject next=bundle.deepCopy();next.add("profile",JSON.toJsonTree(changed));persist(next);profile=changed;return changed;
    }
    public synchronized JsonObject section(Section section){ensureOpen();var value=bundle.getAsJsonObject("sections").get(section.name());return value==null?new JsonObject():value.getAsJsonObject().deepCopy();}
    /** Trusted local store transaction. A section may never replace identity or another store's section. */
    public synchronized void writeSection(Section section,JsonObject value)throws IOException {
        ensureOpen();if(ProfileFiles.number(value,"schema")!=1)throw new IOException("Unsupported section schema");
        JsonObject next=bundle.deepCopy();next.getAsJsonObject("sections").add(section.name(),value.deepCopy());persist(next);
    }
    private void persist(JsonObject next)throws IOException {
        if(ProfileFiles.bytes(next).length>PROFILE_LIMIT)throw new IOException("Profile storage limit reached");
        ProfileFiles.atomic(file,next,true);bundle=next;
    }
    public synchronized SignedPayload sign(Purpose purpose,byte[] data)throws IOException {
        ensureOpen();Objects.requireNonNull(purpose);if(data==null||data.length>65536)throw new IOException("Signed data exceeds 64 KiB");
        try{
            Signature signature=Signature.getInstance("Ed25519");signature.initSign(privateKey);signature.update(signingBytes(purpose,profile,data));
            return new SignedPayload(1,purpose,profile,B64.encodeToString(data),B64.encodeToString(signature.sign()));
        }catch(GeneralSecurityException failure){throw new IOException("Identity signing failed",failure);}
    }
    public synchronized SignedPayload publicProof()throws IOException {return sign(Purpose.PROFILE,new byte[0]);}
    public static IdentityProfile validateProfileProof(SignedPayload proof)throws IOException {
        if(!verify(proof,Purpose.PROFILE)||decodeData(proof).length!=0)throw new IOException("Invalid self-signed public profile");
        return proof.signer();
    }
    public synchronized SignedPayload seal(SignedPayload recipientProof,Purpose purpose,byte[] plaintext)throws IOException {
        return seal(validateProfileProof(recipientProof),purpose,plaintext);
    }
    public synchronized SignedPayload seal(IdentityProfile recipient,Purpose purpose,byte[] plaintext)throws IOException {
        ensureOpen();return IdentityCipher.seal(this,recipient,purpose,plaintext);
    }
    public synchronized byte[] openSealed(SignedPayload envelope,Purpose purpose)throws IOException {
        ensureOpen();return IdentityCipher.open(this,encryptionPrivateKey,envelope,purpose);
    }
    public static boolean verify(SignedPayload value,Purpose expected){
        try{
            if(value==null||value.schema()!=1||value.purpose()==null||value.purpose()!=expected)return false;
            IdentityProfile signer=validateProfile(value.signer());byte[] bytes=decodeData(value),signed=decode(value.signature(),64);
            if(signed.length!=64)return false;
            Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(publicKey(signer.publicKey()));verifier.update(signingBytes(expected,signer,bytes));
            return verifier.verify(signed);
        }catch(IOException|GeneralSecurityException|RuntimeException failure){return false;}
    }
    public static byte[] decodeData(SignedPayload value)throws IOException {if(value==null)throw new IOException("Missing signed envelope");return decode(value.data(),65536);}
    public static String idForPublicKey(String spki)throws IOException {
        PublicKey key=publicKey(spki);
        try{return "dw1_"+B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));}
        catch(NoSuchAlgorithmException impossible){throw new IOException("SHA-256 unavailable",impossible);}
    }
    public static IdentityProfile validateProfile(IdentityProfile value)throws IOException {
        if(value==null)throw new IOException("Missing public profile");
        try{
            new IdentityProfile(value.schema(),value.id(),value.publicKey(),value.encryptionPublicKey(),value.nickname(),value.model(),value.privacy(),value.revision(),value.createdAt());
            IdentityCipher.publicKey(value.encryptionPublicKey());
            if(!idForPublicKey(value.publicKey()).equals(value.id()))throw new IOException("Identity/public-key binding mismatch");
            return value;
        }catch(RuntimeException failure){throw new IOException("Invalid public profile",failure);}
    }
    private static PublicKey publicKey(String value)throws IOException {
        byte[] bytes=decode(value,128);
        try{
            PublicKey key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));
            if(!Arrays.equals(key.getEncoded(),bytes))throw new IOException("Noncanonical public key");
            return key;
        }catch(GeneralSecurityException failure){throw new IOException("Invalid Ed25519 public key",failure);}
    }
    private static byte[] signingBytes(Purpose purpose,IdentityProfile profile,byte[] data)throws IOException {
        var buffer=new ByteArrayOutputStream();
        try(var output=new DataOutputStream(buffer)){
            field(output,"DarkWinter signed payload v1".getBytes(StandardCharsets.US_ASCII));
            field(output,purpose.name().getBytes(StandardCharsets.US_ASCII));
            field(output,ProfileFiles.bytes(JSON.toJsonTree(profile)));field(output,data);
        }return buffer.toByteArray();
    }
    private static void field(DataOutputStream output,byte[] value)throws IOException {output.writeInt(value.length);output.write(value);}
    public static byte[] randomBytes(int count){if(count<1||count>64)throw new IllegalArgumentException("Random length");byte[] bytes=new byte[count];RANDOM.nextBytes(bytes);return bytes;}
    public static String randomToken(){return B64.encodeToString(randomBytes(32));}
    public static byte[] decode(String value,int maximum)throws IOException {
        if(value==null||value.length()>((maximum+2)/3)*4)throw new IOException("Invalid encoded field");
        try{byte[] bytes=UN64.decode(value);if(bytes.length>maximum||!B64.encodeToString(bytes).equals(value))throw new IOException("Noncanonical encoded field");return bytes;}
        catch(IllegalArgumentException failure){throw new IOException("Invalid encoded field",failure);}
    }

    /** Password input is consumed and wiped, including on failure. Contacts remain inside the authenticated encrypted bundle. */
    public synchronized void exportBackup(Path target,char[] password,BackupValidator validator)throws IOException {
        byte[] plaintext=null,key=null;
        try{
            ensureOpen();ProfileFiles.requireOutsideWorld(target.toAbsolutePath().normalize().getParent());
            JsonObject exported=bundle.deepCopy();exported.getAsJsonObject("sections").remove(Section.INVITATIONS.name());
            JsonObject friends=section(Section.FRIENDS);validateBackupFriends(profile,friends,validator);
            plaintext=ProfileFiles.bytes(exported);byte[] salt=randomBytes(16),nonce=randomBytes(12);
            key=backupKey(password,salt,ITERATIONS);
            JsonObject header=new JsonObject();header.addProperty("schema",1);header.addProperty("kind","DarkWinterProfile");
            header.addProperty("kdf","PBKDF2WithHmacSHA256");header.addProperty("cipher","AES/GCM/NoPadding");
            header.addProperty("iterations",ITERATIONS);header.addProperty("salt",B64.encodeToString(salt));header.addProperty("nonce",B64.encodeToString(nonce));
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(ProfileFiles.bytes(header));header.addProperty("ciphertext",B64.encodeToString(cipher.doFinal(plaintext)));
            ProfileFiles.atomic(target.toAbsolutePath().normalize(),header,false);
        }catch(GeneralSecurityException failure){throw new IOException("Profile backup encryption failed",failure);}
        finally{if(password!=null)Arrays.fill(password,'\0');if(plaintext!=null)Arrays.fill(plaintext,(byte)0);if(key!=null)Arrays.fill(key,(byte)0);}
    }
    public synchronized void exportBackup(Path target,char[] password)throws IOException {exportBackup(target,password,null);}
    public static IdentityStore importBackup(Path base,Path source,char[] password,boolean replaceExisting,BackupValidator validator)throws IOException {
        byte[] plaintext=null,key=null;JsonObject restored;
        try{
            JsonObject envelope=ProfileFiles.read(source,BACKUP_LIMIT);
            ProfileFiles.fields(envelope,Set.of("schema","kind","kdf","cipher","iterations","salt","nonce","ciphertext"),Set.of());
            if(ProfileFiles.number(envelope,"schema")!=1||!ProfileFiles.string(envelope,"kind",32).equals("DarkWinterProfile")
                    ||!ProfileFiles.string(envelope,"kdf",64).equals("PBKDF2WithHmacSHA256")||!ProfileFiles.string(envelope,"cipher",64).equals("AES/GCM/NoPadding"))
                throw new IOException("Unsupported encrypted backup format");
            long iterations=ProfileFiles.number(envelope,"iterations");if(iterations<210000||iterations>2000000)throw new IOException("Invalid backup KDF cost");
            byte[] salt=decode(ProfileFiles.string(envelope,"salt",64),16),nonce=decode(ProfileFiles.string(envelope,"nonce",64),12);
            if(salt.length!=16||nonce.length!=12)throw new IOException("Invalid backup cryptographic parameters");
            byte[] ciphertext=decode(ProfileFiles.string(envelope,"ciphertext",BACKUP_LIMIT),PROFILE_LIMIT+16);
            JsonObject aad=envelope.deepCopy();aad.remove("ciphertext");key=backupKey(password,salt,(int)iterations);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(ProfileFiles.bytes(aad));plaintext=cipher.doFinal(ciphertext);restored=ProfileFiles.parse(plaintext,PROFILE_LIMIT);
            IdentityProfile owner=validateProfile(JSON.fromJson(restored.get("profile"),IdentityProfile.class));
            JsonObject sections=restored.getAsJsonObject("sections");
            validateBackupFriends(owner,sections.has(Section.FRIENDS.name())?sections.getAsJsonObject(Section.FRIENDS.name()):new JsonObject(),validator);
        }catch(GeneralSecurityException|RuntimeException failure){throw new IOException("Wrong backup password or invalid authenticated backup",failure);}
        finally{if(password!=null)Arrays.fill(password,'\0');if(plaintext!=null)Arrays.fill(plaintext,(byte)0);if(key!=null)Arrays.fill(key,(byte)0);}
        // Validate both private/public key pairs before creating anything at the target.
        IdentityStore probe=new IdentityStore(Path.of("."),null,null,restored);
        probe.privateKey=null;probe.encryptionPrivateKey=null;probe.bundle=null;
        Path directory=ProfileFiles.directory(base),file=directory.resolve("profile.json");
        FileChannel channel=FileChannel.open(directory.resolve(".profile.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock lock=null;
        try{
            ProfileFiles.protect(directory.resolve(".profile.lock"),false);lock=channel.tryLock();
            if(lock==null)throw new IOException("Profile is already open in another process");
            boolean exists=Files.exists(file,LinkOption.NOFOLLOW_LINKS);
            if(exists&&!replaceExisting)throw new IOException("Replacing an existing identity requires explicit approval");
            if(exists){
                // Unsupported existing versions are never overwritten, even by a backup import.
                new IdentityStore(directory,null,null,ProfileFiles.read(file,PROFILE_LIMIT));
            }
            IdentityStore imported=new IdentityStore(directory,channel,lock,restored);
            ProfileFiles.atomic(file,restored,exists);return imported;
        }catch(IOException|RuntimeException failure){
            if(lock!=null)lock.release();channel.close();
            if(failure instanceof IOException io)throw io;throw new IOException("Backup could not be installed",failure);
        }
    }
    public static IdentityStore importBackup(Path base,Path source,char[] password,boolean replaceExisting)throws IOException {
        return importBackup(base,source,password,replaceExisting,null);
    }
    private static void validateBackupFriends(IdentityProfile owner,JsonObject friends,BackupValidator validator)throws IOException {
        if(friends.isEmpty())return;
        if(validator==null)throw new IOException("Contact-bearing backup requires the FriendsStore validator");
        validator.validate(owner,friends);
    }
    private static byte[] backupKey(char[] password,byte[] salt,int iterations)throws GeneralSecurityException,IOException {
        if(password==null||password.length<8||password.length>1024)throw new IOException("Backup password must contain 8–1024 characters");
        PBEKeySpec spec=new PBEKeySpec(password,salt,iterations,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
    private void ensureOpen(){if(closed)throw new IllegalStateException("Identity store is closed");}
    @Override public synchronized void close()throws IOException {
        if(closed)return;closed=true;privateKey=null;encryptionPrivateKey=null;bundle=null;lock.release();lockChannel.close();
    }
}
