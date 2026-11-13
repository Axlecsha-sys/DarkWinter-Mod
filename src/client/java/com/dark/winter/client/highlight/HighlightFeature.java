// Submits model outline segments through the native depth, transparency and accessibility phases.
package com.dark.winter.client.highlight;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.ARGB;
import org.joml.Vector3f;

final class HighlightFeature extends RenderTypeFeatureRenderer<HighlightFeature.Submit> {
    static final FeatureRendererType<Submit> TYPE=FeatureRendererType.create("DarkWinter Model Outline");
    record Submit(PoseStack.Pose pose,RenderType renderType,float[] lines,int color,float width) implements SubmitNode {
        @Override public FeatureRendererType<Submit> featureType(){return TYPE;}
    }
    static void submit(PoseStack poses,SubmitNodeCollector collector,RenderType type,ModelHighlight.Frame frame,int color,float width,boolean afterTerrain){
        poses.pushPose();poses.translate(frame.offset().x,frame.offset().y,frame.offset().z);
        Submit node=new Submit(poses.last().copy(),type,frame.segments(),color,width);poses.popPose();
        collector.submitCustom(ARGB.alpha(color)==255?SubmitRenderPhases.SOLID:afterTerrain?SubmitRenderPhases.AFTER_TERRAIN:SubmitRenderPhases.SHAPE_OUTLINES,node);
    }
    @Override protected void buildGroup(FeatureFrameContext context,List<Submit> submits){
        Vector3f direction=new Vector3f();
        for(Submit node:submits){var buffer=getVertexBuilder(node.renderType);float[] lines=node.lines;
            for(int i=0;i<lines.length;i+=6){
                direction.set(lines[i+3]-lines[i],lines[i+4]-lines[i+1],lines[i+5]-lines[i+2]);
                if(direction.lengthSquared()<1e-12f)continue;direction.normalize();
                buffer.addVertex(node.pose,lines[i],lines[i+1],lines[i+2]).setColor(node.color).setNormal(node.pose,direction).setLineWidth(node.width);
                buffer.addVertex(node.pose,lines[i+3],lines[i+4],lines[i+5]).setColor(node.color).setNormal(node.pose,direction).setLineWidth(node.width);
            }
        }
    }
}
