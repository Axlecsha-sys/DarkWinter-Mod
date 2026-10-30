// Calculates conservative terrain-step candidates from native collision shapes.
package com.dark.winter.movement;

import java.util.List;

/** Conservative shape policy only. Minecraft still performs the actual collision/step motion. */
public final class TerrainStepGeometry {
    public static final double MAX_RISE=1.125; // one block + one collision eighth of snow
    private static final double EPS=1e-6;
    public record Box(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) { }
    public record Piece(Box box,boolean terrain) { }
    private TerrainStepGeometry() { }

    /** Returns zero to preserve the vanilla step height; positive means an eligible terrain lip. */
    public static double rise(Box body,double dx,double dz,double ordinaryStep,List<Piece> pieces) {
        if (!Double.isFinite(dx+dz+ordinaryStep) || ordinaryStep<0.1 || ordinaryStep>=MAX_RISE) return 0;
        double loX=body.minX()+Math.min(0,dx),hiX=body.maxX()+Math.max(0,dx);
        double loZ=body.minZ()+Math.min(0,dz),hiZ=body.maxZ()+Math.max(0,dz);
        double foot=body.minY(),rise=0;
        for (Piece p:pieces) {
            Box b=p.box();
            if (b.maxX()<=loX+EPS || b.minX()>=hiX-EPS
                    || b.maxZ()<=loZ+EPS || b.minZ()>=hiZ-EPS
                    || b.maxY()<=foot+EPS || b.minY()>=foot+MAX_RISE-EPS) continue;
            // The whitelist must approve every encountered piece, not only the top snow layer.
            if (!p.terrain() || b.maxY()-foot>MAX_RISE+EPS) return 0;
            if (b.maxY()>foot+ordinaryStep+EPS) rise=Math.max(rise,b.maxY()-foot);
        }
        return rise;
    }
}
