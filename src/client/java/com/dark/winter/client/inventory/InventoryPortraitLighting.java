package com.dark.winter.client.inventory;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

/** Portrait-only key and fill lights. The surrounding PIP scope restores the previous lighting UBO. */
public final class InventoryPortraitLighting {
    public static final RenderStateDataKey<Boolean> PORTRAIT=RenderStateDataKey.create(()->"DarkWinter inventory portrait");
    private static GpuBuffer lights;
    private InventoryPortraitLighting() {}
    public static void initialize() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->{if(lights!=null){lights.close();lights=null;}});
    }
    public static boolean matches(PictureInPictureRenderState state) {
        return state instanceof GuiEntityRenderState entity
                &&Boolean.TRUE.equals(((FabricRenderState)entity.renderState()).getData(PORTRAIT));
    }
    public static void bind() {
        RenderSystem.assertOnRenderThread();
        if(lights==null)try(var stack=MemoryStack.stackPush()) {
            var key=new Vector3f(-.58f,-.86f,.50f).normalize().mul(.82f);
            var fill=new Vector3f(.50f,-.20f,.70f).normalize().mul(.12f);
            var data=Std140Builder.onStack(stack,Lighting.UBO_SIZE).putVec3(key).putVec3(fill).get();
            lights=RenderSystem.getDevice().createBuffer(()->"DarkWinter portrait key/fill",GpuBuffer.USAGE_UNIFORM,data);
        }
        RenderSystem.setShaderLights(lights.slice());
    }
}
