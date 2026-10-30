// Public self-certified identity.
package com.dark.winter.online.identity;

import java.util.Objects;

/** Public self-certified identity. Never contains a private key. */
public record IdentityProfile(int schema,String id,String publicKey,String encryptionPublicKey,String nickname,Model model,Privacy privacy,long revision,long createdAt) {
    public enum Model {MALE,FEMALE}
    public enum Visibility {NOBODY,FRIENDS,PUBLIC}
    public enum ContactPolicy {NOBODY,FRIENDS,ANYONE}
    public record Privacy(Visibility presence,ContactPolicy requests,ContactPolicy messages,ContactPolicy invitations) {
        public Privacy {Objects.requireNonNull(presence);Objects.requireNonNull(requests);Objects.requireNonNull(messages);Objects.requireNonNull(invitations);}
        public static Privacy defaults(){return new Privacy(Visibility.FRIENDS,ContactPolicy.ANYONE,ContactPolicy.FRIENDS,ContactPolicy.FRIENDS);}
    }
    public IdentityProfile {
        if(schema!=1||id==null||!id.matches("dw1_[A-Za-z0-9_-]{43}")||publicKey==null||publicKey.length()>256
                ||encryptionPublicKey==null||encryptionPublicKey.length()>256||revision<0||createdAt<0)throw new IllegalArgumentException("Invalid public identity");
        Objects.requireNonNull(model);Objects.requireNonNull(privacy);
        nickname=validateNickname(nickname);
    }
    public static String validateNickname(String name){
        if(name==null||name.isBlank()||name.codePointCount(0,name.length())>32||!name.equals(name.strip()))
            throw new IllegalArgumentException("Nickname must contain 1–32 visible characters");
        for(int i=0;i<name.length();i++){
            char c=name.charAt(i);
            if(Character.isISOControl(c)||c=='§')throw new IllegalArgumentException("Control/format codes are forbidden");
            if(Character.isHighSurrogate(c)){if(i+1>=name.length()||!Character.isLowSurrogate(name.charAt(++i)))throw new IllegalArgumentException("Invalid Unicode nickname");}
            else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Invalid Unicode nickname");
        }
        return name;
    }
    @Override public String toString(){return "IdentityProfile[id="+id+", nickname="+nickname+", model="+model+"]";}
}
