package com.winterkore.engine.surface;

/** Bounded stream focus from observed positions, including ticks between movement packets. */
public final class SnowStreamLookAhead {
    public static final int HORIZON_TICKS=50;
    private double x=Double.NaN,z=Double.NaN,velocityX,velocityZ;
    private int quietTicks;
    public void update(double nextX,double nextZ){
        if(!Double.isFinite(nextX+nextZ)){x=z=Double.NaN;velocityX=velocityZ=0;quietTicks=0;return;}
        if(!Double.isFinite(x+z)){x=nextX;z=nextZ;velocityX=velocityZ=0;quietTicks=0;return;}
        double dx=nextX-x,dz=nextZ-z;
        // Teleports establish a new focus; they must not request a trail of intermediate terrain.
        double distance=Math.hypot(dx,dz);
        if(distance>4){velocityX=velocityZ=0;quietTicks=0;}
        else if(distance<=1e-7){if(++quietTicks>2){velocityX*=.6;velocityZ*=.6;}}
        else {int interval=quietTicks<=2?quietTicks+1:1;velocityX=velocityX*.6+dx/interval*.4;velocityZ=velocityZ*.6+dz/interval*.4;quietTicks=0;}
        x=nextX;z=nextZ;
    }
    public int chunkX(){return (int)Math.floor(x/16);}
    public int chunkZ(){return (int)Math.floor(z/16);}
    public int projectedChunkX(){return (int)Math.floor((x+Math.clamp(velocityX*HORIZON_TICKS,-16,16))/16);}
    public int projectedChunkZ(){return (int)Math.floor((z+Math.clamp(velocityZ*HORIZON_TICKS,-16,16))/16);}
    public double priority(int chunkX,int chunkZ){
        int dx=chunkX-chunkX(),dz=chunkZ-chunkZ();double speed=Math.hypot(velocityX,velocityZ);
        return dx*dx+dz*dz-(speed>.005?.9*(dx*velocityX+dz*velocityZ)/speed:0);
    }
    /** This full field includes the exact one-column halo of the focus's three-by-three chunks. */
    public static boolean criticalColumn(int chunkX,int chunkZ,int localX,int localZ,int focusX,int focusZ){
        int x=(chunkX-focusX)*16+localX,z=(chunkZ-focusZ)*16+localZ;
        return x>=-17&&x<=32&&z>=-17&&z<=32;
    }
}
