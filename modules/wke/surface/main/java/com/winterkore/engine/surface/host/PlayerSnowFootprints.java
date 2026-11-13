// Real footwear supplies contact positions; persistent fixed templates supply snow geometry.
package com.winterkore.engine.surface.host;

import com.dark.winter.equipment.EquipmentAccess;
import com.dark.winter.equipment.GearSlot;
import com.winterkore.engine.surface.geometry.HumanFootPose;
import com.winterkore.engine.surface.geometry.FootRigHulls;
import com.winterkore.engine.surface.geometry.HumanGaitTimeline;
import com.winterkore.engine.surface.geometry.SoleProfiles;
import com.winterkore.engine.surface.geometry.FixedSnowTrack;
import com.winterkore.engine.surface.geometry.SnowFootContactPolicy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;

/** Shared fixed-track emission for rendered contact and the authoritative fallback. */
public final class PlayerSnowFootprints {
    private PlayerSnowFootprints(){}
    public record FootPlant(double x,double y,double z,float yaw,float width,float length,int soleProfile,boolean left,boolean channel,float pressure,boolean loading){
        public FootPlant(double x,double y,double z,float yaw,float width,float length,int soleProfile,boolean left,boolean channel,float pressure){
            this(x,y,z,yaw,width,length,soleProfile,left,channel,pressure,true);
        }
        public FootPlant(double x,double y,double z,float yaw,float width,float length,int soleProfile,boolean left,boolean channel){
            this(x,y,z,yaw,width,length,soleProfile,left,channel,channel?.82f:1);
        }
        public FootPlant(double x,double y,double z,float yaw,float width,float length,int soleProfile,boolean left){
            this(x,y,z,yaw,width,length,soleProfile,left,false,1);
        }
    }
    private static int footwearVariant(Player player){
        var shoe=EquipmentAccess.of(player).getItem(GearSlot.FOOTWEAR.ordinal());if(!GearSlot.FOOTWEAR.accepts(shoe))return 0;
        return switch(BuiltInRegistries.ITEM.getKey(shoe.getItem()).getPath()){
            case "work_boots" -> 1;case "winter_boots" -> 2;case "insulated_work_boots" -> 3;case "leather_hiking_boots" -> 4;
            case "cinematic_northern_wanderer_boots" -> 5;case "cinematic_expedition_snow_boots" -> 6;
            case "cinematic_lace_up_work_boots" -> 7;case "cinematic_insulated_rubber_winter_boots" -> 8;
            case "cinematic_tactical_winter_boots" -> 9;case "cinematic_heavy_mountaineering_boots" -> 10;
            case "cinematic_felt_lined_field_boots" -> 11;case "cinematic_reinforced_survival_boots" -> 12;
            case "cinematic_fur_lined_cold_weather_boots" -> 13;default -> 1;
        };
    }
    public static int soleProfile(Player player){
        int shoe=footwearVariant(player);return shoe>=6?SoleProfiles.CINEMATIC_BOOT:shoe==5?SoleProfiles.NORTHERN_BOOT:
                shoe>0?SoleProfiles.LEGACY_BOOT:GearSlot.SOCKS.accepts(EquipmentAccess.of(player).getItem(GearSlot.SOCKS.ordinal()))?SoleProfiles.SOCK:SoleProfiles.BARE;
    }
    public static int rigKey(Player player){
        int shoe=footwearVariant(player);return shoe>0?8+shoe:GearSlot.SOCKS.accepts(EquipmentAccess.of(player).getItem(GearSlot.SOCKS.ordinal()))?1:0;
    }
    public static final class Walker {
        private Player owner;
        private HumanGaitTimeline timeline=new HumanGaitTimeline();
        private HumanGaitTimeline.Input last;
        private HumanGaitTimeline.Snapshot lastPose;
        private FootRigHulls.Snapshot rigs=FootRigHulls.snapshot();
        private final FixedSnowTrack rightTrack=new FixedSnowTrack(),leftTrack=new FixedSnowTrack();
        private int lastRigKey=Integer.MIN_VALUE;
        public Walker(Player player){owner=player;}
        private void resetContacts(){rightTrack.reset();leftTrack.reset();}
        private void owner(Player player){
            if(owner==player)return;
            owner=player;timeline=new HumanGaitTimeline();last=null;lastPose=null;lastRigKey=Integer.MIN_VALUE;resetContacts();
        }
        private static HumanGaitTimeline.Input input(Player player){
            boolean enabled=player.isAlive()&&!player.isSpectator()&&!player.isPassenger()&&!player.isSleeping()
                    &&!player.isFallFlying()&&!player.isVisuallySwimming()&&!player.isInWater()&&!player.isInLava()
                    &&!player.onClimbable()&&!player.getAbilities().flying;
            return new HumanGaitTimeline.Input(player.tickCount,player.getX(),player.getY(),player.getZ(),player.yBodyRot,
                    (float)player.getDeltaMovement().y,SnowLocomotion.grounded(player),player.isSprinting(),player.isCrouching(),enabled,
                    player.isUsingItem()||player.isSwinging(),SnowLocomotion.gaitWeight(player));
        }
        /** Authoritative sampling retains its own tick-owned gait. */
        public List<FootPlant> update(Player player){return update(player,false);}
        /** A live rendered source owns fixed stamps; the server falls back only when that source stops. */
        public List<FootPlant> update(Player player,boolean loadOnly){
            owner(player);var input=input(player);timeline.tick(input);
            return update(player,input,timeline.current(),loadOnly);
        }
        /** Local prediction passes the exact animation snapshot, rather than advancing a second gait clock. */
        public List<FootPlant> update(Player player,HumanGaitTimeline.Snapshot pose){
            if(pose==null)throw new IllegalArgumentException("A shared foot pose is required");
            owner(player);return update(player,input(player),pose,false);
        }
        /** Render extraction supplies its actual interpolated pose and transform; serial advances once per frame. */
        public List<FootPlant> update(Player player,HumanGaitTimeline.Snapshot pose,int sampleSerial,
                double x,double y,double z,float bodyYaw){
            if(pose==null)throw new IllegalArgumentException("A shared foot pose is required");
            if(!Double.isFinite(x+y+z)||!Float.isFinite(bodyYaw))throw new IllegalArgumentException("A finite foot transform is required");
            owner(player);var live=input(player);
            return update(player,new HumanGaitTimeline.Input(sampleSerial,x,y,z,bodyYaw,live.vertical(),live.grounded(),
                    live.sprinting(),live.crouching(),live.enabled(),live.acting(),live.snow()),pose,false);
        }
        /** Admission samples the actual foot, while template depth depends on total snow thickness. */
        private static double contactDepth(Player player,HumanFootPose.Plant sole){
            var level=player.level();
            if(level.isClientSide()){
                var support=SnowSurfaceRuntime.captureRenderedSupport(level,sole.x(),sole.y(),sole.z());
                return support==null||support.sample(false,1)==null?Double.NaN:support.physicalColumnDepthMillimeters();
            }
            var geometry=SnowSurfaceRuntime.findGeometry(level,sole.x(),sole.y(),sole.z());
            if(geometry==null)return Double.NaN;
            double top=SnowHostGeometry.sample(geometry,sole.x(),sole.z(),SnowSurfaceRuntime.fineCornersMillimeters(level,geometry)).height();
            double delta=SnowSurfaceRuntime.knownFineDeltaMillimeters(level,geometry.pos());
            double depth=Math.max(0,geometry.layers()*125f+(float)delta);
            return SnowFootContactPolicy.sampleFixed(depth,geometry.layers()*125.0,top,sole.y(),false,1)==null?Double.NaN:depth;
        }
        private void pressure(Player player,HumanGaitTimeline.Input input,HumanGaitTimeline.Snapshot pose,
                double scale,int profile,int key,List<FootPlant> result){
            for(int foot=0;foot<2;foot++){
                boolean left=foot!=0;var track=left?leftTrack:rightTrack;
                var p=HumanFootPose.sample(pose,left,profile,input.x(),input.y(),input.z(),input.yaw(),scale,rigs,key);
                double depth=contactDepth(player,p);
                if(!Double.isFinite(depth)){track.release();continue;}
                long cycle=(long)Math.floor((pose.phase()+(left?Math.PI:0))/(Math.PI*2));
                boolean interrupted=false;
                for(var stamp:track.sample(p.x(),p.y(),p.z(),p.yaw(),profile,scale,cycle,depth)){
                    // The middle of a segment may cross a shallow patch or a missing support.
                    // Do not consume that path and lose the current valid endpoint.
                    var midpoint=new HumanFootPose.Plant(stamp.x(),stamp.y(),stamp.z(),stamp.yaw(),stamp.width(),stamp.length(),profile,left);
                    double middleDepth=contactDepth(player,midpoint);
                    if(!Double.isFinite(middleDepth)||stamp.channel()&&middleDepth<FixedSnowTrack.DEEP_SNOW_MM){
                        interrupted=true;continue;
                    }
                    result.add(new FootPlant(stamp.x(),stamp.y(),stamp.z(),stamp.yaw(),stamp.width(),stamp.length(),
                            profile,left,stamp.channel(),1,!stamp.channel()));
                }
                if(interrupted){
                    track.release();
                    for(var stamp:track.sample(p.x(),p.y(),p.z(),p.yaw(),profile,scale,cycle,depth))
                        result.add(new FootPlant(stamp.x(),stamp.y(),stamp.z(),stamp.yaw(),stamp.width(),stamp.length(),
                                profile,left,false,1,true));
                }
            }
        }
        private List<FootPlant> update(Player player,HumanGaitTimeline.Input input,HumanGaitTimeline.Snapshot pose,boolean loadOnly){
            if(last!=null&&input.tick()==last.tick())return List.of();
            var before=last;var previous=lastPose;
            if(before!=null){
                int elapsed=input.tick()-before.tick();
                if(elapsed<1||elapsed>5||Math.hypot(input.x()-before.x(),input.z()-before.z())>1.6*elapsed
                        ||Math.abs(input.y()-before.y())>2.5*elapsed||previous!=null&&pose.distance()<previous.distance()){
                    resetContacts();before=null;previous=null;
                }
            }
            int key=rigKey(player);
            if(key!=lastRigKey)resetContacts();
            if(before==null||previous==null||previous.movement()<.001f||key!=lastRigKey){
                rigs=FootRigHulls.snapshot();lastRigKey=key;
            }
            last=input;lastPose=pose;
            if(!input.enabled()||!input.grounded()){resetContacts();return List.of();}
            double scale=player.getScale();if(!Double.isFinite(scale)||scale<=0||scale>4){resetContacts();return List.of();}
            // Rendered fixed contacts already establish their depth. A second tick-owned stance
            // must not press, reshape or re-open the same mark after local publication.
            if(loadOnly){resetContacts();return List.of();}
            var result=new ArrayList<FootPlant>(4);
            pressure(player,input,pose,scale,soleProfile(player),key,result);
            return List.copyOf(result);
        }
    }
}
