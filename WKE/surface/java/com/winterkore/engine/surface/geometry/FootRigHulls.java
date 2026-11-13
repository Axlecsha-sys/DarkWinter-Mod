// Immutable host geometry for shared articulated contact support; no renderer/game objects cross this boundary.
package com.winterkore.engine.surface.geometry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class FootRigHulls {
    public static final int MAX_HULLS=64,MAX_VERTICES=16384;
    public record Vertex(double x,double y,double z){
        public Vertex{if(!Double.isFinite(x+y+z)||Math.abs(x)>64||Math.abs(y)>64||Math.abs(z)>64)
            throw new IllegalArgumentException("Bounded finite foot-local rig vertex required");}
    }
    public record Hull(int soleProfile,List<Vertex> vertices){
        public Hull{if(!SoleProfiles.valid(soleProfile)||vertices==null||vertices.isEmpty()||vertices.size()>MAX_VERTICES)
            throw new IllegalArgumentException("Invalid foot rig hull");vertices=List.copyOf(vertices);}
    }
    public record Snapshot(long version,boolean authored,Map<Integer,Hull> hulls){
        public Snapshot{hulls=Map.copyOf(hulls);}
        public Hull hull(int rigKey,boolean left,int profile){
            return hulls.getOrDefault(key(rigKey,left),fallback(profile,left));
        }
    }
    private static final Map<Integer,Hull> FALLBACK=createFallbacks();
    private static final AtomicReference<Snapshot> CURRENT=new AtomicReference<>(new Snapshot(0,false,Map.of()));
    private FootRigHulls(){}
    public static int key(int rigKey,boolean left){
        if(rigKey<0||rigKey>31)throw new IllegalArgumentException("Bounded rig key");return rigKey*2+(left?1:0);
    }
    public static Snapshot snapshot(){return CURRENT.get();}
    /** A complete bake/reload atomically publishes immutable numbers. Existing walkers retain their captured version. */
    public static void replaceAll(Map<Integer,Hull> hulls){
        if(hulls==null||hulls.size()>MAX_HULLS)throw new IllegalArgumentException("Bounded foot rig registry");
        for(var e:hulls.entrySet())if(e.getKey()<0||e.getKey()>=MAX_HULLS||e.getValue()==null)throw new IllegalArgumentException("Invalid rig entry");
        // Bare, socks and all thirteen authored footwear variants must exist before initial gameplay.
        for(int rig=0;rig<=21;rig++)if(rig<=3||rig>=9)for(boolean left:new boolean[]{false,true})
            if(!hulls.containsKey(key(rig,left)))throw new IllegalArgumentException("Incomplete authored foot rig bake");
        CURRENT.updateAndGet(old->new Snapshot(old.version+1,true,hulls));
    }
    public static Hull fallback(int profile,boolean left){
        if(!SoleProfiles.valid(profile))throw new IllegalArgumentException("Unknown sole");return FALLBACK.get(key(profile,left));
    }
    private static Map<Integer,Hull> createFallbacks(){
        var out=new LinkedHashMap<Integer,Hull>();
        for(int profile=0;profile<SoleProfiles.COUNT;profile++)for(boolean left:new boolean[]{false,true}){
            var vertices=new ArrayList<Vertex>();double side=left?1:-1;var p=SoleProfiles.profile(profile);
            // Exact known bare lower-foot sections, shared by every clothed rig.
            ring(vertices,16,11.45,2.30,2.75,0,-.04,2.4);
            ring(vertices,16,11.7,2.35,2.85,0,-.08,2.3);
            ring(vertices,16,12.5,2.75,4.10,0,-.55,2.45);
            ring(vertices,16,13.2,2.95,4.45,0,-.68,2.5);
            ring(vertices,16,13.8,2.87,4.35,0,-.67,2.5);
            if(profile==SoleProfiles.CINEMATIC_BOOT||profile==SoleProfiles.NORTHERN_BOOT){
                double q=profile==SoleProfiles.CINEMATIC_BOOT?.1:0;
                ring(vertices,20,12.86,4.43,6.06,side*.34,-.98,4.1+q);
                ring(vertices,20,13.08,4.53,6.16,side*.34,-.98,4.1+q);
                ring(vertices,20,13.61,4.52,6.15,side*.34,-.98,4.0+q);
                ring(vertices,20,13.80,4.40,6.05,side*.34,-.98,3.9+q);
                ring(vertices,20,13.03,4.56,6.20,side*.34,-.98,4.1+q);
                ring(vertices,20,13.23,4.56,6.20,side*.34,-.98,4.1+q);
                if(profile==SoleProfiles.NORTHERN_BOOT)for(double x:new double[]{-1.43,0,1.43})
                    ring(vertices,8,13.78,.48,.22,side*.34+x,-3.98,3.5);
            }else if(profile==SoleProfiles.LEGACY_BOOT){
                ring(vertices,20,13.03,4.13,5.77,0,-.88,2.6);
                ring(vertices,20,13.30,4.20,5.84,0,-.88,2.6);
                ring(vertices,20,13.80,4.15,5.80,0,-.88,2.6);
            }else if(profile==SoleProfiles.SOCK){
                ring(vertices,16,12.15,3.26,4.05,0,-.3,2.4);
                ring(vertices,16,12.70,3.42,4.88,0,-.65,2.5);
                ring(vertices,16,13.40,3.47,4.95,0,-.69,2.5);
                ring(vertices,16,13.80,3.40,4.87,0,-.67,2.5);
            }
            out.put(key(profile,left),new Hull(profile,vertices));
        }
        return Map.copyOf(out);
    }
    private static void ring(List<Vertex> out,int sides,double y,double width,double length,double cx,double cz,double power){
        for(int i=0;i<sides;i++){
            double a=-Math.PI*.75+Math.PI*2*i/sides,c=Math.cos(a),s=Math.sin(a);
            out.add(new Vertex((cx+width*.5*Math.copySign(Math.pow(Math.abs(c),2/power),c))*SoleProfiles.LEG_SCALE_X,
                    (y-11.45)*SoleProfiles.LEG_SCALE_Y,
                    (cz+length*.5*Math.copySign(Math.pow(Math.abs(s),2/power),s))*SoleProfiles.LEG_SCALE_Z));
        }
    }
}
