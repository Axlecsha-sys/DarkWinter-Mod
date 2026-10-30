// Stabilizes shadow capture around a nearby world-space light lattice.
package com.dark.winter.visuals;

/**
 * RC2 shadow-grid hotfix: stable nearby pivot for the rotating light lattice.
 *
 * The public Capture/capture/reset contract is unchanged. This class only
 * controls the shadow capture transform; it does not change camera motion,
 * world time, light colour, resolution, filtering, fog or the film pass.
 */
public final class ShadowGrid {
    private static final double CELL_HYSTERESIS = 0.70;
    private static final double ANCHOR_CELL = 16.0;

    private double anchorX = Double.NaN;
    private double anchorY = Double.NaN;
    private double anchorZ = Double.NaN;
    private double sx = Double.NaN;
    private double sy = Double.NaN;
    private double phaseX;
    private double phaseY;
    private double previousTexel = Double.NaN;
    private double previousRadius = Double.NaN;

    public record Capture(float[] view, double gridX, double gridY, double texel) { }

    public Capture capture(double cameraX, double cameraY, double cameraZ,
                           double sunAngle, double radius, int resolution) {
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY)
                || !Double.isFinite(cameraZ) || !Double.isFinite(sunAngle)
                || !Double.isFinite(radius) || radius <= 0.0 || resolution < 16) {
            throw new IllegalArgumentException("Non-finite camera or invalid shadow grid");
        }

        double texel = 2.0 * radius / resolution;
        if (texel != previousTexel || radius != previousRadius) {
            reset();
            previousTexel = texel;
            previousRadius = radius;
        }
        if (!Double.isFinite(anchorX)) {
            anchorX = nearestCell(cameraX);
            anchorY = nearestCell(cameraY);
            anchorZ = nearestCell(cameraZ);
        }

        // Use exactly the same float coefficients for the uploaded matrix
        // and for double-precision translation compensation. In particular,
        // do not subtract two large, almost equal float world coordinates.
        float rx = (float) -Math.cos(sunAngle);
        float ry = (float) -Math.sin(sunAngle);
        float bx = ry;
        float by = -rx;

        double dx = cameraX - anchorX;
        double dy = cameraY - anchorY;
        double dz = cameraZ - anchorZ;
        double cx = rx * dx + ry * dy;
        double cy = dz;

        sx = snapWithHysteresis(cx, sx, texel, phaseX);
        sy = snapWithHysteresis(cy, sy, texel, phaseY);

        // Rotation must stay local even after a long journey. Rebase without
        // re-zeroing the lattice phase: a blind anchor reset causes a jump.
        double rebaseDistance = Math.max(32.0, radius);
        if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz))
                > rebaseDistance) {
            double nextX = nearestCell(cameraX);
            double nextY = nearestCell(cameraY);
            double nextZ = nearestCell(cameraZ);
            double lightShiftX = rx * (nextX - anchorX) + ry * (nextY - anchorY);
            double lightShiftY = nextZ - anchorZ;

            // Local coordinate systems differ only by this translation.
            // Shift the remembered cell AND the infinite lattice together.
            sx -= lightShiftX;
            sy -= lightShiftY;
            phaseX = wrapPhase(phaseX - lightShiftX, texel);
            phaseY = wrapPhase(phaseY - lightShiftY, texel);
            cx -= lightShiftX;
            cy -= lightShiftY;
            anchorX = nextX;
            anchorY = nextY;
            anchorZ = nextZ;
        }

        // Input terrain positions are (world - camera). The result is:
        //   lightXY = R * (world - anchor) - snappedLocalCentre.
        // Thus camera translation is cancelled, while sun rotation happens
        // around a nearby world anchor rather than the remote world origin.
        float[] view = {
                rx, 0.0f, bx, 0.0f,
                ry, 0.0f, by, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                (float) (cx - sx), (float) (cy - sy),
                (float) (-3.0 * radius), 1.0f
        };
        return new Capture(view, sx, sy, texel);
    }


    /**
     * 3D geographic sun direction variant. The original angular capture above
     * stays UNCHANGED for any user who disables geographicSun.
     *
     * Basis (right, up, toward Sun) rotates with solar azimuth/elevation while
     * world-anchored hysteresis remains local to the player's nearby anchor.
     */
    public Capture captureSun(double cameraX, double cameraY, double cameraZ,
                              float sunX,float sunY,float sunZ,double radius,int resolution) {
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY)
                || !Double.isFinite(cameraZ) || !Float.isFinite(sunX)
                || !Float.isFinite(sunY) || !Float.isFinite(sunZ)
                || !Double.isFinite(radius) || radius <= 0.0 || resolution < 16)
            throw new IllegalArgumentException("Invalid geographic shadow capture");
        double norm=Math.sqrt((double)sunX*sunX+(double)sunY*sunY+(double)sunZ*sunZ);
        if(norm<1e-5) throw new IllegalArgumentException("Zero sun direction");
        float fx=(float)(sunX/norm),fy=(float)(sunY/norm),fz=(float)(sunZ/norm);
        // Right = normalize(worldUp x towardSun); north/south noon yields +X.
        double horizontal=Math.hypot(fx,fz);
        float rx=horizontal>1e-5 ? (float)(fz/horizontal) : 1.0f;
        float ry=0.0f;
        float rz=horizontal>1e-5 ? (float)(-fx/horizontal) : 0.0f;
        // Up = towardSun x Right.
        float ux=fy*rz-fz*ry,uy=fz*rx-fx*rz,uz=fx*ry-fy*rx;

        double texel=2.0*radius/resolution;
        if(texel!=previousTexel || radius!=previousRadius) {
            reset();previousTexel=texel;previousRadius=radius;
        }
        if(!Double.isFinite(anchorX)) {
            anchorX=nearestCell(cameraX);anchorY=nearestCell(cameraY);anchorZ=nearestCell(cameraZ);
        }
        double dx=cameraX-anchorX,dy=cameraY-anchorY,dz=cameraZ-anchorZ;
        double cx=rx*dx+ry*dy+rz*dz;
        double cy=ux*dx+uy*dy+uz*dz;
        double cz=fx*dx+fy*dy+fz*dz;
        sx=snapWithHysteresis(cx,sx,texel,phaseX);
        sy=snapWithHysteresis(cy,sy,texel,phaseY);
        double rebaseDistance=Math.max(32.0,radius);
        if(Math.max(Math.max(Math.abs(dx),Math.abs(dy)),Math.abs(dz))>rebaseDistance) {
            double nx=nearestCell(cameraX),ny=nearestCell(cameraY),nz=nearestCell(cameraZ);
            double ax=nx-anchorX,ay=ny-anchorY,az=nz-anchorZ;
            double shiftX=rx*ax+ry*ay+rz*az;
            double shiftY=ux*ax+uy*ay+uz*az;
            sx-=shiftX;sy-=shiftY;
            phaseX=wrapPhase(phaseX-shiftX,texel);
            phaseY=wrapPhase(phaseY-shiftY,texel);
            cx-=shiftX;cy-=shiftY;
            cz-=fx*ax+fy*ay+fz*az;
            anchorX=nx;anchorY=ny;anchorZ=nz;
        }
        return new Capture(new float[] {
            rx,ux,fx,0f,
            ry,uy,fy,0f,
            rz,uz,fz,0f,
            (float)(cx-sx),(float)(cy-sy),(float)(cz-3.0*radius),1f
        },sx,sy,texel);
    }

    private static double snapWithHysteresis(double value, double previous,
                                             double step, double phase) {
        if (Double.isFinite(previous)
                && Math.abs(value - previous) <= step * CELL_HYSTERESIS) {
            return previous;
        }
        return phase + Math.floor((value - phase) / step + 0.5) * step;
    }

    private static double nearestCell(double value) {
        return Math.floor(value / ANCHOR_CELL + 0.5) * ANCHOR_CELL;
    }

    private static double wrapPhase(double value, double step) {
        return value - Math.floor(value / step + 0.5) * step;
    }

    public void reset() {
        anchorX = anchorY = anchorZ = Double.NaN;
        sx = sy = previousTexel = previousRadius = Double.NaN;
        phaseX = phaseY = 0.0;
    }
}
