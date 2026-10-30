// Binds native player UUID aliases to a Node-confirmed complete checkpoint.
package com.dark.winter.online.replica;

import com.dark.winter.online.authority.*;
import java.io.IOException;
import java.util.*;

/**
 * Opaque native UUID table bound to an actual Node-confirmed complete checkpoint.
 * A mutable world owner field or a peer-supplied raw map never creates this evidence.
 * The encoded complete index may be durably stored outside the world before Node CAS;
 * after restart it must be rebound to a new verified permit, never trusted by filename alone.
 */
public final class NativeActorAliases {
    private final OnlineHostPermit permit;
    private final ReplicaProtocol.Index index;
    private final String authority,actor;
    private final UUID nativePlayer,session;
    private final long sequence;
    private final Map<String,UUID> aliases;
    private NativeActorAliases(OnlineHostPermit permit,ReplicaProtocol.Index index,String actor,UUID nativePlayer) {
        this.permit=permit;this.index=index;this.actor=actor;this.nativePlayer=nativePlayer;authority=permit.authorityId();
        session=permit.nativeSessionId();sequence=permit.checkpointSequence();aliases=Map.copyOf(index.actorAliases());
    }
    public static NativeActorAliases bind(OnlineHostPermit permit,ReplicaProtocol.Index nodeConfirmedIndex,
                                          String actualLocalIdentity,UUID actualNativePlayer)throws IOException {
        if(permit==null||nodeConfirmedIndex==null||actualLocalIdentity==null||actualNativePlayer==null)
            throw new IOException("Missing Node-bound native actor evidence");
        permit.requireUsableFor(actualLocalIdentity,permit.nativeSessionId());
        Map<String,UUID> values=nodeConfirmedIndex.actorAliases();
        if(values.isEmpty()||values.size()>ReplicaProtocol.MAX_ACTOR_ALIASES||new HashSet<>(values.values()).size()!=values.size()
                ||!actualNativePlayer.equals(values.get(actualLocalIdentity))||!values.containsKey(permit.ownerId()))
            throw new IOException("Checkpoint lacks the actual local/permanent-owner bijective native actor bindings");
        if(!nodeConfirmedIndex.contentRoot().equals(permit.checkpointSha256()))
            throw new IOException("Native actor table is not in the current Node-confirmed full checkpoint");
        NativeActorAliases result=new NativeActorAliases(permit,nodeConfirmedIndex,actualLocalIdentity,actualNativePlayer);
        result.requireFor(permit,actualLocalIdentity,actualNativePlayer);return result;
    }
    public void requireFor(OnlineHostPermit current,String actualLocalIdentity,UUID actualNativePlayer)throws IOException {
        if(current!=permit||!actor.equals(actualLocalIdentity)||!nativePlayer.equals(actualNativePlayer)
                ||!authority.equals(current.authorityId())||!session.equals(current.nativeSessionId()))
            throw new IOException("Native actor evidence belongs to another actual host/session");
        current.requireUsableFor(actualLocalIdentity,session);
        if(sequence!=current.checkpointSequence()||!index.contentRoot().equals(current.checkpointSha256()))
            throw new IOException("Native actor checkpoint advanced or changed");
    }
    public Map<String,UUID> aliases(){return aliases;}
    public ReplicaProtocol.Index index(){return index;}
    public byte[] encodedIndex(){return index.encoded();}
    public String authorityId(){return authority;}
    public long checkpointSequence(){return sequence;}
    public String checkpointSha256(){return index.contentRoot();}
    public UUID localNativePlayer(){return nativePlayer;}
    /** Existing actors retain their exact UUID; an unknown derived UUID may never collide with an offline known actor. */
    public UUID resolve(String actualPeerIdentity,UUID deterministicNewPlayer)throws IOException {
        UUID known=aliases.get(Objects.requireNonNull(actualPeerIdentity));if(known!=null)return known;
        if(aliases.size()>=ReplicaProtocol.MAX_ACTOR_ALIASES)throw new IOException("Historical native identity capacity reached; known actors remain available");
        if(deterministicNewPlayer==null||aliases.containsValue(deterministicNewPlayer))
            throw new IOException("New native identity collides with a checkpoint-bound actor");
        return deterministicNewPlayer;
    }
    @Override public String toString(){return "NativeActorAliases[root="+authority+",actors="+aliases.size()+"]";}
}
