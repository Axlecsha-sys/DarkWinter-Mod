// Renders the authored player model and skin through the native GUI pipeline.
package com.dark.winter.client.online;

import com.dark.winter.client.equipment.FemaleHumanModel;
import com.dark.winter.client.equipment.ThirdPersonBodyModel;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;

/** The same authored Human roots and atlas used in the world, through Minecraft's native GUI skin pipeline. */
public final class OnlineProfilePreview implements FriendsScreen.ProfilePreviewRenderer {
    private static final Identifier BODY=Identifier.fromNamespaceAndPath("darkwinter","textures/entity/northern_survivor.png");
    private EntityModelSet modelSet;
    private Model.Simple male,female;
    private long maleBeardFaces,femaleBeardFaces;
    private long extractedFrames,lastBeardFaces;
    private PlayerModelType lastModel;
    private Identifier lastTexture;
    private boolean lastFemale;
    private OnlineProfilePreview() {}
    public static void install(){FriendsScreen.installProfilePreview(new OnlineProfilePreview());}
    @Override public boolean available(JsonObject profile){
        String model=OnlineUiBridge.string(profile,"model","");
        return (model.equals("male")||model.equals("female"))&&Minecraft.getInstance().getEntityModels()!=null;
    }
    @Override public void render(GuiGraphicsExtractor graphics,JsonObject profile,FriendsScreen.PreviewRect rectangle,int mx,int my,float delta){
        if(!available(profile))return;
        EntityModelSet models=Minecraft.getInstance().getEntityModels();
        if(male==null||modelSet!=models){
            modelSet=models;
            ModelPart maleRoot=ThirdPersonBodyModel.createRoot(false),femaleRoot=FemaleHumanModel.createRoot(true);
            male=new Model.Simple(maleRoot,RenderTypes::entityTranslucent);
            female=new Model.Simple(femaleRoot,RenderTypes::entityTranslucent);
            maleBeardFaces=beardFaces(maleRoot);femaleBeardFaces=beardFaces(femaleRoot);
        }
        boolean selected=OnlineUiBridge.string(profile,"model","male").equals("female");
        Model.Simple model=selected?female:male;
        // Native PlayerSkinWidget uses these same framing/rotation values; the roots here are actual Human meshes.
        graphics.skin(model,BODY,.97f*rectangle.height()/2.125f,-5,30,-1.0625f,
                rectangle.x(),rectangle.y(),rectangle.x()+rectangle.width(),rectangle.y()+rectangle.height());
        lastFemale=selected;lastModel=selected?PlayerModelType.SLIM:PlayerModelType.WIDE;
        lastBeardFaces=selected?femaleBeardFaces:maleBeardFaces;lastTexture=BODY;extractedFrames++;
    }
    private static long beardFaces(ModelPart root){
        long[] result={0};root.getChild("head").getChild("beard").visit(new PoseStack(),(poses,path,index,cube)->result[0]+=cube.polygons.length);return result[0];
    }
    @Override public String unavailableReason(){return "Модель персонажа ещё загружается.";}
    public long extractedFrames(){return extractedFrames;}
    public PlayerModelType lastModel(){return lastModel;}
    public Identifier lastTexture(){return lastTexture;}
    public boolean lastFemale(){return lastFemale;}
    public long lastBeardFaces(){return lastBeardFaces;}
}
