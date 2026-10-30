// Issues and verifies expiring owner-signed network discovery codes.
package com.dark.winter.online.rooms;

import com.dark.winter.online.identity.IdentityStore;
import com.dark.winter.online.identity.IdentityStore.SignedPayload;
import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * Owner-signed network discovery only. This code grants no friendship, invitation,
 * native game admission, island permission or authority lease. The advertised
 * Node identity must still be checked against its real response on the channel.
 */
public final class RoomConnectionCode {
    public static final String PREFIX = "dwroom1_";
    public static final int MAX_CODE_LENGTH = 8192;
    private static final int MAX_ENVELOPE_BYTES = 6144;
    private static final Duration MAX_TTL = Duration.ofMinutes(30);
    private static final long MAX_TTL_MILLIS = MAX_TTL.toMillis();
    private static final Gson JSON = new Gson();
    private static final Set<String> BODY_FIELDS = Set.of(
            "schema", "type", "routeId", "nodeId", "hostTransportKey", "createdAt", "expiresAt");

    public enum Code { INVALID, CLOCK }
    /** Stable local classification; parser, signature and input text never escape through a cause. */
    public static final class Failure extends IOException {
        private final Code code;
        private Failure(Code code) { super("Room bootstrap rejected: " + code.name()); this.code = code; }
        public Code code() { return code; }
    }

    /** Descriptive bootstrap data; never an authorization token. */
    public record VerifiedSignedRoom(
            String ownerId, UUID routeId, String nodeId, String hostTransportKey,
            long expiresAt, SignedPayload ownerProfileProof) {
        @Override public String toString() {
            return "VerifiedSignedRoom[networkBootstrapOnly]";
        }
    }

    private RoomConnectionCode() {}

    public static String issue(
            IdentityStore identity, UUID routeId, String nodeId, String hostTransportKey,
            Duration ttl, Clock clock) throws IOException {
        if (identity == null || routeId == null || ttl == null || clock == null)
            throw new IOException("Missing room bootstrap input");
        peerId(nodeId);
        transportKey(hostTransportKey);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0)
            throw new IOException("Room lifetime must be positive and at most thirty minutes");
        final long createdAt;
        final long expiresAt;
        try {
            long millis = ttl.toMillis();
            if (millis < 1) throw new IOException("Room lifetime is shorter than one millisecond");
            createdAt = clock.millis();
            if (createdAt < 0) throw new IOException("Invalid room clock");
            expiresAt = Math.addExact(createdAt, millis);
        } catch (ArithmeticException invalid) {
            throw new IOException("Invalid room lifetime", invalid);
        }
        JsonObject body = new JsonObject();
        body.addProperty("schema", 1);
        body.addProperty("type", "network_room");
        body.addProperty("routeId", routeId.toString());
        body.addProperty("nodeId", nodeId);
        body.addProperty("hostTransportKey", hostTransportKey);
        body.addProperty("createdAt", createdAt);
        body.addProperty("expiresAt", expiresAt);

        // Keep the room and public profile at the same actual IdentityStore revision.
        final SignedPayload room;
        final SignedPayload profile;
        synchronized (identity) {
            room = identity.sign(IdentityStore.Purpose.INVITATION, ProfileFiles.bytes(body));
            profile = identity.publicProof();
        }
        JsonObject envelope = new JsonObject();
        envelope.add("room", JSON.toJsonTree(room));
        envelope.add("ownerProfileProof", JSON.toJsonTree(profile));
        byte[] encoded = ProfileFiles.bytes(envelope);
        String code = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
        if (encoded.length > MAX_ENVELOPE_BYTES || code.length() > MAX_CODE_LENGTH)
            throw new IOException("Room bootstrap exceeds its size limit");
        return code;
    }

    public static VerifiedSignedRoom inspect(String code, Clock clock) throws IOException {
        try {
            if (clock == null || code == null || !code.startsWith(PREFIX)
                    || code.length() <= PREFIX.length() || code.length() > MAX_CODE_LENGTH)
                throw new IOException("Invalid room bootstrap code");
            byte[] encoded = IdentityStore.decode(code.substring(PREFIX.length()), MAX_ENVELOPE_BYTES);
            JsonObject envelope = ProfileFiles.parse(encoded, MAX_ENVELOPE_BYTES);
            if (!Arrays.equals(encoded, ProfileFiles.bytes(envelope)))
                throw new IOException("Noncanonical room bootstrap document");
            ProfileFiles.fields(envelope, Set.of("room", "ownerProfileProof"), Set.of());
            SignedPayload room = payload(envelope.getAsJsonObject("room"));
            SignedPayload profile = payload(envelope.getAsJsonObject("ownerProfileProof"));
            if (!IdentityStore.verify(room, IdentityStore.Purpose.INVITATION))
                throw new IOException("Invalid room signature");
            var owner = IdentityStore.validateProfileProof(profile);
            if (!owner.equals(room.signer()))
                throw new IOException("Room issuer differs from its public profile proof");

            JsonObject body = ProfileFiles.parse(IdentityStore.decodeData(room), 2048);
            ProfileFiles.fields(body, BODY_FIELDS, Set.of());
            if (ProfileFiles.number(body, "schema") != 1
                    || !ProfileFiles.string(body, "type", 32).equals("network_room"))
                throw new IOException("Invalid room bootstrap type");
            String route = ProfileFiles.string(body, "routeId", 36);
            UUID routeId = UUID.fromString(route);
            if (!routeId.toString().equals(route)) throw new IOException("Noncanonical room route");
            String nodeId = peerId(ProfileFiles.string(body, "nodeId", 64));
            String key = transportKey(ProfileFiles.string(body, "hostTransportKey", 64));
            long createdAt = ProfileFiles.number(body, "createdAt");
            long expiresAt = ProfileFiles.number(body, "expiresAt");
            long now = clock.millis();
            if (now < 0 || createdAt < 0 || expiresAt <= createdAt
                    || expiresAt - createdAt > MAX_TTL_MILLIS)
                throw new IOException("Invalid room lifetime");
            if (createdAt > now || expiresAt <= now) throw new Failure(Code.CLOCK);
            return new VerifiedSignedRoom(owner.id(), routeId, nodeId, key, expiresAt, profile);
        } catch (Failure classified) {
            throw classified;
        } catch (IOException | RuntimeException invalid) {
            // Neither exception text nor its cause is a safe diagnostic surface.
            throw new Failure(Code.INVALID);
        }
    }

    private static SignedPayload payload(JsonObject envelope) throws IOException {
        if (envelope == null) throw new IOException("Missing signed room envelope");
        ProfileFiles.fields(envelope, Set.of("schema", "purpose", "signer", "data", "signature"), Set.of());
        if (ProfileFiles.number(envelope, "schema") != 1)
            throw new IOException("Invalid signed room schema");
        ProfileFiles.string(envelope, "purpose", 32);
        ProfileFiles.string(envelope, "data", MAX_CODE_LENGTH);
        ProfileFiles.string(envelope, "signature", 88);
        JsonObject signer = envelope.getAsJsonObject("signer");
        ProfileFiles.fields(signer, Set.of(
                "schema", "id", "publicKey", "encryptionPublicKey", "nickname",
                "model", "privacy", "revision", "createdAt"), Set.of());
        ProfileFiles.number(signer, "schema");
        ProfileFiles.number(signer, "revision");
        ProfileFiles.number(signer, "createdAt");
        ProfileFiles.fields(signer.getAsJsonObject("privacy"),
                Set.of("presence", "requests", "messages", "invitations"), Set.of());
        return JSON.fromJson(envelope, SignedPayload.class);
    }

    private static String peerId(String id) throws IOException {
        if (id == null || !id.matches("dw1_[A-Za-z0-9_-]{43}"))
            throw new IOException("Invalid room Node identity");
        return id;
    }

    private static String transportKey(String key) throws IOException {
        if (key == null || !key.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid room transport public key");
        return key;
    }
}
