// Signs persistent connection descriptors and protects shared information with an optional password.
package com.dark.winter.online.rooms;

import com.dark.winter.online.identity.IdentityStore;
import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Explicit persistent connection descriptor, separate from expiring dwroom1 bootstrap codes.
 * Possession of its random route key opens only the signed directory route, never an island.
 * Password protected shared information encrypts that key with PBKDF2 and AES-GCM.
 */
public final class ConnectionInfo {
    public static final String PREFIX="dwconnect1_";
    public static final int MAX_LENGTH=12288;
    private static final int ITERATIONS=600000,MAX_BYTES=8192;
    private static final byte[] AAD="DarkWinter persistent connection v1".getBytes(StandardCharsets.US_ASCII);
    private static final Gson JSON=new Gson();
    private static final Base64.Encoder B64=Base64.getUrlEncoder().withoutPadding();
    private ConnectionInfo(){}
    public static final class Invalid extends IOException {
        public Invalid(){super("Connection information or password rejected");}
    }
    public static final class Descriptor {
        private final IdentityStore.SignedPayload signed;
        private final UUID id;
        private final String name,nodeId,transportKey;
        private final byte[] routeKey;
        private Descriptor(IdentityStore.SignedPayload signed,UUID id,String name,String nodeId,String transportKey,byte[] routeKey){
            this.signed=signed;this.id=id;this.name=name;this.nodeId=nodeId;this.transportKey=transportKey;this.routeKey=routeKey.clone();
        }
        public UUID id(){return id;}
        public String name(){return name;}
        public String ownerId(){return signed.signer().id();}
        public String nodeId(){return nodeId;}
        public String transportKey(){return transportKey;}
        public byte[] routeKey(){return routeKey.clone();}
        /** Local credential only; callers must protect it and must never render or log it. */
        public JsonObject localCredential(){return JSON.toJsonTree(signed).getAsJsonObject();}
        @Override public String toString(){return "ConnectionDescriptor[protectedRouteOnly]";}
    }
    public record Created(Descriptor descriptor,String shareInfo,boolean passwordRequired){
        @Override public String toString(){return "ConnectionInfo.Created[protectedRouteOnly]";}
    }
    public static String validateName(String requested)throws IOException{
        if(requested==null)throw new Invalid();
        String name=requested.strip();
        if(name.isEmpty()||name.codePointCount(0,name.length())>48||name.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x00a7))
            throw new Invalid();
        return name;
    }
    public static Created create(IdentityStore owner,String name,UUID id,String nodeId,String transportKey,char[] password)throws IOException{
        try{
            name=validateName(name);validateNode(nodeId);validateTransport(transportKey);
            if(owner==null||id==null)throw new Invalid();
            JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("type","persistent_connection");
            body.addProperty("id",id.toString());body.addProperty("name",name);body.addProperty("nodeId",nodeId);
            body.addProperty("transportKey",transportKey);body.addProperty("routeKey",B64.encodeToString(IdentityStore.randomBytes(32)));
            Descriptor descriptor=inspectCredential(JSON.toJsonTree(owner.sign(IdentityStore.Purpose.INVITATION,ProfileFiles.bytes(body))).getAsJsonObject());
            boolean required=password!=null&&password.length>0;
            JsonObject envelope=new JsonObject();envelope.addProperty("schema",1);envelope.addProperty("mode",required?"password":"open");
            if(required){
                if(password.length>128)throw new Invalid();
                byte[] salt=IdentityStore.randomBytes(32),nonce=IdentityStore.randomBytes(12);
                envelope.addProperty("salt",B64.encodeToString(salt));envelope.addProperty("nonce",B64.encodeToString(nonce));
                envelope.addProperty("ciphertext",B64.encodeToString(crypt(Cipher.ENCRYPT_MODE,password,salt,nonce,ProfileFiles.bytes(descriptor.localCredential()))));
            }else envelope.add("credential",descriptor.localCredential());
            return new Created(descriptor,encode(envelope),required);
        }catch(GeneralSecurityException|RuntimeException invalid){throw new Invalid();}
        finally{if(password!=null)Arrays.fill(password,'\0');}
    }
    public static Created open(String info,char[] password)throws IOException{
        byte[] plaintext=null;
        try{
            JsonObject envelope=decode(info);
            if(ProfileFiles.number(envelope,"schema")!=1)throw new Invalid();
            String mode=ProfileFiles.string(envelope,"mode",16);JsonObject credential;
            boolean required=mode.equals("password");
            if(required){
                ProfileFiles.fields(envelope,Set.of("schema","mode","salt","nonce","ciphertext"),Set.of());
                if(password==null||password.length<1||password.length>128)throw new Invalid();
                byte[] salt=IdentityStore.decode(ProfileFiles.string(envelope,"salt",64),32);
                byte[] nonce=IdentityStore.decode(ProfileFiles.string(envelope,"nonce",32),12);
                byte[] ciphertext=IdentityStore.decode(ProfileFiles.string(envelope,"ciphertext",MAX_LENGTH),MAX_BYTES);
                if(salt.length!=32||nonce.length!=12||ciphertext.length<16)throw new Invalid();
                plaintext=crypt(Cipher.DECRYPT_MODE,password,salt,nonce,ciphertext);
                credential=ProfileFiles.parse(plaintext,MAX_BYTES);
                if(!Arrays.equals(plaintext,ProfileFiles.bytes(credential)))throw new Invalid();
            }else{
                if(!mode.equals("open"))throw new Invalid();
                ProfileFiles.fields(envelope,Set.of("schema","mode","credential"),Set.of());
                if(!envelope.has("credential")||!envelope.get("credential").isJsonObject())throw new Invalid();
                credential=envelope.getAsJsonObject("credential");
            }
            return new Created(inspectCredential(credential),info,required);
        }catch(IOException|GeneralSecurityException|RuntimeException invalid){throw new Invalid();}
        finally{if(password!=null)Arrays.fill(password,'\0');if(plaintext!=null)Arrays.fill(plaintext,(byte)0);}
    }
    public static Descriptor inspectCredential(JsonObject credential)throws IOException{
        try{
            ProfileFiles.fields(credential,Set.of("schema","purpose","signer","data","signature"),Set.of());
            if(!credential.has("signer")||!credential.get("signer").isJsonObject())throw new Invalid();
            JsonObject signer=credential.getAsJsonObject("signer");
            ProfileFiles.fields(signer,Set.of("schema","id","publicKey","encryptionPublicKey","nickname","model","privacy","revision","createdAt"),Set.of());
            if(!signer.has("privacy")||!signer.get("privacy").isJsonObject())throw new Invalid();
            ProfileFiles.fields(signer.getAsJsonObject("privacy"),Set.of("presence","requests","messages","invitations"),Set.of());
            IdentityStore.SignedPayload signed=JSON.fromJson(credential,IdentityStore.SignedPayload.class);
            if(!IdentityStore.verify(signed,IdentityStore.Purpose.INVITATION))throw new Invalid();
            byte[] data=IdentityStore.decodeData(signed);JsonObject body=ProfileFiles.parse(data,2048);
            if(!Arrays.equals(data,ProfileFiles.bytes(body)))throw new Invalid();
            ProfileFiles.fields(body,Set.of("schema","type","id","name","nodeId","transportKey","routeKey"),Set.of());
            if(ProfileFiles.number(body,"schema")!=1||!ProfileFiles.string(body,"type",32).equals("persistent_connection"))throw new Invalid();
            String rawId=ProfileFiles.string(body,"id",36);UUID id=UUID.fromString(rawId);if(!id.toString().equals(rawId))throw new Invalid();
            String name=validateName(ProfileFiles.string(body,"name",96));
            String nodeId=ProfileFiles.string(body,"nodeId",64),transport=ProfileFiles.string(body,"transportKey",64);
            validateNode(nodeId);validateTransport(transport);
            byte[] key=IdentityStore.decode(ProfileFiles.string(body,"routeKey",64),32);if(key.length!=32)throw new Invalid();
            return new Descriptor(signed,id,name,nodeId,transport,key);
        }catch(IOException|RuntimeException invalid){throw new Invalid();}
    }
    public static boolean passwordRequired(String info)throws IOException{
        try{return ProfileFiles.string(decode(info),"mode",16).equals("password");}catch(IOException|RuntimeException invalid){throw new Invalid();}
    }
    private static JsonObject decode(String info)throws IOException{
        if(info==null||!info.startsWith(PREFIX)||info.length()>MAX_LENGTH||info.length()<=PREFIX.length())throw new Invalid();
        byte[] bytes=IdentityStore.decode(info.substring(PREFIX.length()),MAX_BYTES);JsonObject envelope=ProfileFiles.parse(bytes,MAX_BYTES);
        if(!Arrays.equals(bytes,ProfileFiles.bytes(envelope)))throw new Invalid();return envelope;
    }
    private static String encode(JsonObject envelope)throws IOException{
        byte[] bytes=ProfileFiles.bytes(envelope);String info=PREFIX+B64.encodeToString(bytes);
        if(bytes.length>MAX_BYTES||info.length()>MAX_LENGTH)throw new Invalid();return info;
    }
    private static byte[] crypt(int mode,char[] password,byte[] salt,byte[] nonce,byte[] input)throws GeneralSecurityException{
        PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);byte[] key=null;
        try{
            key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(AAD);return cipher.doFinal(input);
        }finally{spec.clearPassword();if(key!=null)Arrays.fill(key,(byte)0);}
    }
    private static void validateNode(String id)throws IOException{if(id==null||!id.matches("dw1_[A-Za-z0-9_-]{43}"))throw new Invalid();}
    private static void validateTransport(String key)throws IOException{if(key==null||!key.matches("[0-9a-f]{64}"))throw new Invalid();}
}
