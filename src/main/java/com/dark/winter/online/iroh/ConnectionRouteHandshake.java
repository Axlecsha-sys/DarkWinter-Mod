// Authenticates named connection routes inside encrypted QUIC streams.
package com.dark.winter.online.iroh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/** A bounded route prelude inside authenticated, encrypted QUIC; it grants no game or friend authority. */
public final class ConnectionRouteHandshake {
    public static final int SECRET_BYTES=32;
    public static final int REQUEST_BYTES=52;
    public static final int ACK_BYTES=20;
    private static final int MAGIC=0x44574332; // ASCII DWC2.

    private ConnectionRouteHandshake(){}

    public static byte[] request(UUID route,byte[] secret){
        if(route==null||secret==null||secret.length!=SECRET_BYTES)
            throw new IllegalArgumentException("A route and a 32-byte secret are required");
        return header(REQUEST_BYTES,route).put(secret).array();
    }

    public static boolean accepts(UUID route,byte[] secret,byte[] request){
        if(route==null||secret==null||secret.length!=SECRET_BYTES||request==null||request.length!=REQUEST_BYTES)
            return false;
        byte[] expected=ConnectionRouteHandshake.request(route,secret);
        try{return MessageDigest.isEqual(expected,request);}
        finally{Arrays.fill(expected,(byte)0);}
    }

    public static byte[] acknowledgement(UUID route){
        if(route==null)throw new IllegalArgumentException("A route is required");
        return header(ACK_BYTES,route).array();
    }

    public static boolean confirms(UUID route,byte[] acknowledgement){
        return route!=null&&acknowledgement!=null&&acknowledgement.length==ACK_BYTES
                &&MessageDigest.isEqual(ConnectionRouteHandshake.acknowledgement(route),acknowledgement);
    }

    private static ByteBuffer header(int bytes,UUID route){
        return ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN).putInt(MAGIC)
                .putLong(route.getMostSignificantBits()).putLong(route.getLeastSignificantBits());
    }
}
