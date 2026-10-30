// Only rearranges existing content; icon loading, warnings, tooltips and world actions remain vanilla.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only rearranges existing content; icon loading, warnings, tooltips and world actions remain vanilla. */
@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListCardMixin {
    @Redirect(method="extractContent",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
    private void darkwinter$iconShade(GuiGraphicsExtractor g,int x,int y,int right,int bottom,int color) {
        // Replace only the old gray icon hover square, leaving warning decorations intact.
        g.fill(x,y,right,bottom,color==-1601138544 && right-x==32 && bottom-y==32?0x40081521:color);
    }
    @Redirect(method="extractContent",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void darkwinter$launch(GuiGraphicsExtractor g,RenderPipeline pipeline,Identifier sprite,int x,int y,int w,int h) {
        String path=sprite.getPath();
        if(sprite.getNamespace().equals("minecraft") && (path.equals("world_list/join") || path.equals("world_list/join_highlighted"))) {
            boolean hover=path.endsWith("highlighted");
            int color=hover?HudStyle.STABLE:HudStyle.INK;
            g.fill(x+6,y+7,x+26,y+25,0xB0081521);
            HudStyle.halo(g,x+9,y+15,13,1,color,hover?.9f:.3f);
            g.fill(x+10,y+15,x+22,y+16,color);
            for(int i=0;i<5;i++) {
                g.fill(x+17+i,y+11+i,x+18+i,y+12+i,color);
                g.fill(x+17+i,y+19-i,x+18+i,y+20-i,color);
            }
        } else g.blitSprite(pipeline,sprite,x,y,w,h); // Keep all safety/error/backup badges.
    }
    @Shadow @Final private LevelSummary summary;
    @Shadow @Final private StringWidget worldNameText;
    @Shadow @Final private StringWidget idAndLastPlayedText;
    @Shadow @Final private StringWidget infoText;
    @Unique private String darkwinter$date,darkwinter$time;
    @Inject(method="<init>",at=@At("TAIL"))
    private void darkwinter$labels(CallbackInfo ci) {
        worldNameText.setMessage(HudStyle.strong(worldNameText.getMessage()));
        infoText.setMessage(HudStyle.font(infoText.getMessage()));
        var date=java.time.Instant.ofEpochMilli(Math.max(0,summary.getLastPlayed())).atZone(java.time.ZoneId.systemDefault());
        darkwinter$date=summary.getLastPlayed()<0?"—":java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy").format(date);
        darkwinter$time=summary.getLastPlayed()<0?"":java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(date);
    }
    @Redirect(method="extractContent",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/StringWidget;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void darkwinter$columns(StringWidget widget,GuiGraphicsExtractor g,int mx,int my,float dt) {
        var entry=(WorldSelectionList.WorldListEntry)(Object)this;
        int right=entry.getX()+entry.getWidth(),dateWidth=66;
        if(widget==idAndLastPlayedText) {
            HudStyle.text(g,darkwinter$date,right-dateWidth,entry.getY()+10,.85f,HudStyle.MUTED);
            HudStyle.text(g,darkwinter$time,right-31,entry.getY()+24,.85f,HudStyle.MUTED);
        } else {
            widget.setMaxWidth(Math.max(40,entry.getWidth()-100-dateWidth));
            widget.setPosition(entry.getX()+94,entry.getY()+(widget==worldNameText?6:22));
            widget.extractRenderState(g,mx,my,dt);
        }
    }
    @Redirect(method="extractContent",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
    private void darkwinter$thumbnail(GuiGraphicsExtractor g,RenderPipeline pipeline,Identifier image,int x,int y,float u,float v,int w,int h,int tw,int th) {
        int height=((WorldSelectionList.WorldListEntry)(Object)this).getHeight()-8,sourceHeight=64*height/84;
        g.blit(pipeline,image,x+2,y+2,0,(64-sourceHeight)/2f,84,height,64,sourceHeight,64,64);
    }
}
