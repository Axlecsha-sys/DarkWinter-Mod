// Applies the editor's orthographic orbit to both native rendering and native visibility culling.
package com.dark.winter.biomeeditor.mixin;
import com.dark.winter.biomeeditor.BiomeEditorWorker;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.Projection;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Camera.class)
public abstract class EditorCameraMixin {
    @Shadow @Final private Projection projection;
    @Shadow protected abstract void setPosition(double x,double y,double z);
    @Shadow protected abstract void setRotation(float yaw,float pitch);
    @Shadow public abstract Matrix4f getViewRotationMatrix(Matrix4f target);
    @Shadow private void setupOrtho(float near,float far,float width,float height,boolean invertY){throw new AssertionError();}
    @Shadow private void prepareCullFrustum(Matrix4fc rotation,Matrix4f projection,Vec3 position){throw new AssertionError();}
    @Inject(method="update",at=@At("TAIL"),require=1)
    private void editor$orbit(DeltaTracker tracker,CallbackInfo ci){
        if(!BiomeEditorWorker.orbitActive())return;var c=BiomeEditorWorker.camera();if(!c.has("span"))return;
        double span=c.get("span").getAsDouble(),yaw=c.get("yaw").getAsDouble(),pitch=c.get("pitch").getAsDouble();
        double distance=Math.max(80,Math.min(256,span*1.2)),yr=Math.toRadians(yaw),pr=Math.toRadians(pitch);
        double x=c.get("targetX").getAsDouble()+Math.sin(yr)*Math.cos(pr)*distance;
        double y=c.get("targetY").getAsDouble()+Math.sin(pr)*distance;
        double z=c.get("targetZ").getAsDouble()-Math.cos(yr)*Math.cos(pr)*distance;
        setPosition(x,y,z);setRotation((float)yaw,(float)pitch);
        setupOrtho(.05f,4096,(float)(span*1280/720),(float)span,false);
        projection.getMatrix(new Matrix4f());
        // Native UI orthographic projection starts at (0,0); a world orbit needs its optical axis at the image center.
        ((EditorProjectionAccess)(Object)projection).editor$matrix().m30(0).m31(0);
        Matrix4f matrix=projection.getMatrix(new Matrix4f());
        prepareCullFrustum(getViewRotationMatrix(new Matrix4f()),matrix,new Vec3(x,y,z));
    }
}
