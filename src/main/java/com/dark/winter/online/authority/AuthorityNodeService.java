// Adapts authenticated directory requests to the coordinator authority lease store.
package com.dark.winter.online.authority;

import com.dark.winter.online.identity.*;
import com.dark.winter.online.identity.IdentityStore.SignedPayload;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Small adapter for an existing, authenticated/rate-limited DirectoryNode route.
 * The node must call this AFTER verifying and registering the outer SERVICE_REQUEST.
 * No second identity, HTTP server or native game service is created here.
 */
public final class AuthorityNodeService implements AutoCloseable {
    private final AuthorityLeaseStore store;private final String nodeId;
    private AuthorityNodeService(AuthorityLeaseStore store,String nodeId){this.store=store;this.nodeId=nodeId;}
    public static AuthorityNodeService open(Path protectedNodeDirectory,IdentityStore actualSharedNodeIdentity,Clock clock,LongSupplier nano)throws IOException{
        return new AuthorityNodeService(AuthorityLeaseStore.open(protectedNodeDirectory.resolve("world-authority"),actualSharedNodeIdentity,clock,nano),
            actualSharedNodeIdentity.profile().id());
    }
    public String nodeId(){return nodeId;}
    /** Outer operation "authority", arguments exactly {request: actual peer-signed SERVICE_REQUEST}. */
    public JsonObject execute(IdentityProfile authenticatedOuterPeer,JsonObject arguments)throws IOException{
        IdentityStore.validateProfile(authenticatedOuterPeer);ProfileFiles.fields(arguments,Set.of("request"),Set.of());
        JsonElement inner=arguments.get("request");if(inner==null||!inner.isJsonObject())throw new IOException("Missing signed authority request");
        SignedPayload request=WorldAuthority.signedFromJson(inner.getAsJsonObject());
        if(!IdentityStore.verify(request,IdentityStore.Purpose.SERVICE_REQUEST)||
            !authenticatedOuterPeer.id().equals(request.signer().id())||
            !authenticatedOuterPeer.publicKey().equals(request.signer().publicKey()))
            throw new IOException("Inner authority request does not belong to the authenticated outer peer");
        SignedPayload response=store.handle(request);JsonObject result=new JsonObject();
        // Keep this original signature: the generic directory client may otherwise return only parsed outer JSON.
        result.add("authorityReceipt",new Gson().toJsonTree(response));return result;
    }
    /** Only the authority file lock is closed. The owning runtime retains its single shared node identity. */
    @Override public void close()throws IOException{store.close();}
}
