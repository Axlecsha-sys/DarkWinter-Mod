// A client-only camera body, never added to a level, saved or sent to the server.
package com.dark.winter.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * A client-only camera body, never added to a level, saved or sent to the server.
 * The authoring player keeps its real entity, mode, inventory and world position.
 */
public final class TestingDrone {
    private static final class CameraBody extends ArmorStand {
        CameraBody(net.minecraft.world.level.Level level,double x,double y,double z){super(level,x,y,z);refreshDimensions();}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.fixed(.01f,.01f).withEyeHeight(0);}
        @Override public float getViewYRot(float partialTick){return getYRot();}
        @Override public float getViewXRot(float partialTick){return getXRot();}
    }
    private static CameraBody camera;
    private static LocalPlayer player;
    private static Object level;
    private static ClientInput previousInput;
    private static CameraType previousType;
    private static Entity previousCamera;
    private static float bodyYaw,bodyPitch;
    private static String message="";
    private static long suppressedHandsFrames;
    private TestingDrone(){}
    public static boolean active(){return camera!=null;}
    public static Entity entity(){return camera;}
    public static String status(){return message;}
    public static long suppressedHandsFrames(){return suppressedHandsFrames;}
    public static void noteHandsSuppressed(){suppressedHandsFrames++;}
    public static boolean start(){
        Minecraft c=Minecraft.getInstance();
        if(active())return true;
        if(!TestingClient.active()||TestingClient.value("mode").startsWith("TEST_")||c.player.isPassenger()||!c.player.isAlive()){
            message="Дрон доступен живому персонажу вне транспорта и полноценного теста";return false;
        }
        player=c.player;level=c.level;bodyYaw=player.getYRot();bodyPitch=player.getXRot();
        previousInput=player.input;previousType=c.options.getCameraType();previousCamera=c.getCameraEntity();
        Vec3 eye=c.gameRenderer.mainCamera().position();
        camera=new CameraBody(c.level,eye.x,eye.y,eye.z);
        camera.absSnapTo(eye.x,eye.y,eye.z,c.gameRenderer.mainCamera().yRot(),c.gameRenderer.mainCamera().xRot());
        camera.setOldPosAndRot();
        player.input=new ClientInput();player.input.keyPresses=Input.EMPTY;
        player.setDeltaMovement(Vec3.ZERO);player.setSprinting(false);
        c.options.setCameraType(CameraType.FIRST_PERSON);c.setCameraEntity(camera);
        message="Свободный дрон: тело остаётся на месте";return true;
    }
    public static void stop(){
        if(!active())return;
        Minecraft c=Minecraft.getInstance();
        if(c.player==player&&c.level==level){
            if(previousInput!=null){previousInput.keyPresses=Input.EMPTY;player.input=previousInput;}
            player.setDeltaMovement(Vec3.ZERO);player.setYRot(bodyYaw);player.setXRot(bodyPitch);
            c.setCameraEntity(previousCamera==null?player:previousCamera);
            if(previousType!=null)c.options.setCameraType(previousType);
        }else if(c.getCameraEntity()==camera)c.setCameraEntity(c.player);
        camera=null;player=null;level=null;previousInput=null;previousCamera=null;previousType=null;
        message="Камера и управление персонажем восстановлены";
    }
    public static boolean locks(LocalPlayer candidate){return active()&&candidate==player&&Minecraft.getInstance().level==level;}
    public static void lockPlayer(LocalPlayer candidate){
        if(!locks(candidate))return;
        candidate.input.keyPresses=Input.EMPTY;candidate.setDeltaMovement(Vec3.ZERO);candidate.setSprinting(false);
        candidate.setYRot(bodyYaw);candidate.setXRot(bodyPitch);
    }
    public static void tick(Minecraft c){
        if(!active())return;
        if(!TestingClient.active()||c.level!=level||c.player!=player||TestingClient.value("mode").startsWith("TEST_")){
            stop();return;
        }
        camera.setOldPosAndRot();lockPlayer(player);
        if(c.gui.screen()!=null||c.isPaused())return;
        double forward=(c.options.keyUp.isDown()?1:0)-(c.options.keyDown.isDown()?1:0);
        double side=(c.options.keyRight.isDown()?1:0)-(c.options.keyLeft.isDown()?1:0);
        // DarkWinter binds sprint to Shift and sneak to Ctrl. Use the actual remappable actions.
        double vertical=(c.options.keyJump.isDown()?1:0)-(c.options.keySprint.isDown()?1:0);
        Vec3 direction=Vec3.directionFromRotation(camera.getXRot(),camera.getYRot()).scale(forward)
                .add(Vec3.directionFromRotation(0,camera.getYRot()+90).scale(side)).add(0,vertical,0);
        if(direction.lengthSqr()>1)direction=direction.normalize();
        float speed;
        try{speed=Float.parseFloat(TestingClient.value("flySpeed"));}catch(NumberFormatException e){speed=.05f;}
        double step=Math.clamp(speed,.01f,.5f)*10*(c.options.keyShift.isDown()?3:1);
        Vec3 next=camera.position().add(direction.scale(step));
        double y=Math.clamp(next.y,c.level.getMinY()-128,c.level.getMaxY()+512);
        camera.setPos(Math.clamp(next.x,-29999984,29999984),y,Math.clamp(next.z,-29999984,29999984));
    }
    /** Entity.turn receives the same sensitivity-scaled mouse units as vanilla player look. */
    public static void turn(double yaw,double pitch){if(active())camera.turn(yaw,pitch);}
    public static void teleport(String coordinates){
        if(!active())return;
        String[] p=coordinates.split(",");
        if(p.length!=5)throw new IllegalArgumentException("Камера: x,y,z,yaw,pitch");
        double x=Double.parseDouble(p[0]),y=Double.parseDouble(p[1]),z=Double.parseDouble(p[2]);
        float yaw=Float.parseFloat(p[3]),pitch=Float.parseFloat(p[4]);
        if(!Double.isFinite(x+y+z)||!Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(x)>29999984||Math.abs(z)>29999984)
            throw new IllegalArgumentException("Недопустимые координаты камеры");
        Minecraft c=Minecraft.getInstance();
        y=Math.clamp(y,c.level.getMinY()-128,c.level.getMaxY()+512);
        camera.absSnapTo(x,y,z,yaw,Math.clamp(pitch,-90,90));camera.setOldPosAndRot();
    }
}
