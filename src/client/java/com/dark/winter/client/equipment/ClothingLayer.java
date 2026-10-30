// Real tapered garment meshes attached to the animated human skeleton, innermost layer first.
package com.dark.winter.client.equipment;

import com.dark.winter.equipment.*;
import com.dark.winter.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Real tapered garment meshes attached to the animated human skeleton, innermost layer first. */
public final class ClothingLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    public static final RenderStateDataKey<Integer> OUTFIT = AppearanceRenderState.OUTFIT;
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("darkwinter", "textures/entity/clothing.png");
    private static final Identifier COTTON = material("cotton"), WOOL = material("wool"), CANVAS = material("canvas"),
            LEATHER = material("leather"), RUBBER = material("rubber");
    private static final GearSlot[] SLOTS = {GearSlot.TSHIRT,GearSlot.SWEATER,GearSlot.PANTS,GearSlot.SOCKS,
            GearSlot.JACKET,GearSlot.FOOTWEAR,GearSlot.GLOVES,GearSlot.HAT};
    private static final Map<GearSlot,List<Item>> ITEMS = wardrobeItems();
    private static Map<GearSlot,List<Item>> wardrobeItems() {
        Map<GearSlot,List<Item>> result=new java.util.EnumMap<>(GearSlot.class);
        for(GearSlot slot:SLOTS) result.put(slot,ModItems.all().values().stream()
                .filter(item->item instanceof GearItem gear&&gear.slot()==slot).toList());
        return Map.copyOf(result);
    }
    private static final Map<Integer,List<ClothingMeshes.Piece>> WARDROBE = ClothingMeshes.WARDROBE;
    private static final Map<ClothingMeshes.Piece,ModelPart> FIRST_PERSON_GRIPS = new IdentityHashMap<>();

    public ClothingLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) { super(parent); }

    public static int outfit(Player player) {
        int result=0;
        var inventory=EquipmentAccess.of(player);
        for (GearSlot slot:SLOTS) {
            ItemStack stack=inventory.getItem(slot.ordinal());
            if (!slot.accepts(stack)) continue;
            int variant=ITEMS.get(slot).indexOf(stack.getItem())+1;
            result=withVariant(result,slot,variant);
        }
        var pack=inventory.getItem(GearSlot.BACKPACK.ordinal());
        int backpack=pack.is(ModItems.CINEMATIC_NORTHERN_WANDERER_BACKPACK)?2:pack.is(ModItems.HIKING_BACKPACK)?1:0;
        return result|(backpack<<(GearSlot.BACKPACK.ordinal()*3));
    }

    // Keep all original 27 bits intact; one extra bit for each expanded slot occupies bits 27..31.
    private static int extensionBit(GearSlot slot) {
        return switch(slot) {case JACKET->27;case PANTS->28;case GLOVES->29;case FOOTWEAR->30;case HAT->31;default->-1;};
    }
    public static int withVariant(int outfit,GearSlot slot,int variant) {
        int high=extensionBit(slot);
        if(variant<0||variant>(high<0?7:15))throw new IllegalArgumentException("Outfit variant "+variant+" for "+slot);
        int shift=slot.ordinal()*3;
        int result=(outfit&~(7<<shift))|((variant&7)<<shift);
        if(high>=0)result=(result&~(1<<high))|((variant>>>3)<<high);
        return result;
    }
    public static int variant(int outfit,GearSlot slot) {
        int low=(outfit>>>(slot.ordinal()*3))&7, high=extensionBit(slot);
        return low|(high<0?0:((outfit>>>high)&1)<<3);
    }

    @Override public void submit(PoseStack poses,SubmitNodeCollector collector,int light,AvatarRenderState state,float yaw,float pitch) {
        Integer outfit=((FabricRenderState)state).getData(OUTFIT);
        if (!state.isInvisible && outfit!=null) draw(getParentModel(),outfit,null,false,poses,collector,light,state.outlineColor);
    }

    public static void submitHand(PlayerModel model,ModelPart arm,Player player,PoseStack poses,SubmitNodeCollector collector,int light) {
        if (player!=null && !player.isInvisible()) draw(model,outfit(player),arm,false,poses,collector,light,0);
    }

    public static void submitBody(PlayerModel model,int outfit,PoseStack poses,SubmitNodeCollector collector,int light) {
        draw(model,outfit,null,true,poses,collector,light,0);
    }

    static ModelPart firstPersonBodyGeometry(ClothingMeshes.Piece piece) {
        return FirstPersonSurfaces.garment(piece);
    }

    static void submitGrip(PlayerModel neutral,ModelPart arm,Player player,PoseStack poses,SubmitNodeCollector collector,int light) {
        if (player.isInvisible()) return;
        int outfit = outfit(player);
        for (GearSlot slot:SLOTS) {
            var pieces=WARDROBE.get(ClothingMeshes.key(slot,variant(outfit,slot)));
            if(pieces==null)continue;
            for(var piece:pieces) {
                if(HumanJoints.parent(neutral,piece.bone())!=arm)continue;
                ModelPart geometry=FIRST_PERSON_GRIPS.computeIfAbsent(piece,ignored->{
                    PoseStack bone=new PoseStack();HumanJoints.transform(neutral,piece.bone(),bone);
                    return FirstPersonSurfaces.grip(piece.geometry(),bone,path->true);
                });
                collector.submitModelPart(geometry,poses,RenderTypes.entityCutout(texture(slot,variant(outfit,slot),piece.color())),
                        light,OverlayTexture.NO_OVERLAY,null,piece.color(),0);
            }
        }
    }

    private static void draw(PlayerModel model,int outfit,ModelPart onlyArm,boolean bodyOnly,PoseStack poses,SubmitNodeCollector collector,int light,int outline) {
        boolean thirdPerson=ThirdPersonBodyModel.isThirdPerson(model);
        int bootDesign=thirdPerson&&!bodyOnly&&onlyArm==null
                ?CinematicWardrobe.design(GearSlot.FOOTWEAR,variant(outfit,GearSlot.FOOTWEAR)):0;
        for (GearSlot slot:SLOTS) {
            List<ClothingMeshes.Piece> pieces=renderPieces(model,slot,variant(outfit,slot),onlyArm,bodyOnly);
            if (pieces==null) continue;
            for (ClothingMeshes.Piece piece:pieces) {
                ModelPart bone=HumanJoints.parent(model,piece.bone());
                if (onlyArm!=null && bone!=onlyArm) continue;
                if (bodyOnly && (bone==model.head || bone==model.leftArm || bone==model.rightArm)) continue;
                // Only fabric hidden inside the new boot shafts is fitted; FP and legacy outfits keep their source geometry.
                ClothingMeshes.Piece fitted=slot==GearSlot.PANTS&&bootDesign>0?CinematicBoots.tuckTrousers(piece,bootDesign):piece;
                ModelPart geometry=thirdPerson?ThirdPersonBodyModel.garment(fitted):piece.geometry();
                if(bodyOnly) { geometry=firstPersonBodyGeometry(piece); if(geometry==null)continue; }
                poses.pushPose();
                HumanJoints.transform(model,piece.bone(),poses);
                if(ThirdPersonBodyModel.isThirdPerson(model)) ThirdPersonBodyModel.fit(piece.bone(),poses);
                collector.submitModelPart(geometry,poses,RenderTypes.entityCutout(renderTexture(model,slot,variant(outfit,slot),onlyArm,bodyOnly,piece.color())),light,OverlayTexture.NO_OVERLAY,null,piece.color(),outline);
                poses.popPose();
            }
        }
    }

    /** The approved parka replaces only the complete third-person garment, never FP arms/body. */
    private static boolean expeditionView(PlayerModel model,GearSlot slot,int variant,ModelPart onlyArm,boolean bodyOnly) {
        return onlyArm==null&&!bodyOnly&&ThirdPersonBodyModel.isThirdPerson(model)
                &&slot==GearSlot.JACKET&&CinematicWardrobe.design(slot,variant)==1;
    }

    static List<ClothingMeshes.Piece> renderPieces(PlayerModel model,GearSlot slot,int variant,ModelPart onlyArm,boolean bodyOnly) {
        return expeditionView(model,slot,variant,onlyArm,bodyOnly)?CinematicExpeditionParka.pieces()
                :WARDROBE.get(ClothingMeshes.key(slot,variant));
    }

    static Identifier renderTexture(PlayerModel model,GearSlot slot,int variant,ModelPart onlyArm,boolean bodyOnly,int color) {
        return expeditionView(model,slot,variant,onlyArm,bodyOnly)?CinematicExpeditionParka.texture(color)
                :texture(slot,variant,color);
    }

    private static Identifier material(String name) {
        return Identifier.fromNamespaceAndPath("darkwinter","textures/entity/clothing_"+name+".png");
    }

    /** Material grain follows the garment rather than making every surface look like painted armour. */
    static Identifier texture(GearSlot slot,int variant,int color) {
        int design=CinematicWardrobe.design(slot,variant);
        if(design>0)return CinematicClothing.texture(slot,design,color);
        if((slot==GearSlot.JACKET&&variant==7)||(variant==5&&(slot==GearSlot.HAT||slot==GearSlot.PANTS||slot==GearSlot.GLOVES||slot==GearSlot.FOOTWEAR)))
            return NorthernWandererClothing.texture(slot,color);
        if(color==0xFF303335) return RUBBER;
        if(color==0xFFB9B5A4 && slot!=GearSlot.TSHIRT) return WOOL;
        return switch(slot) {
            case TSHIRT -> COTTON;
            case SWEATER -> variant<=2?WOOL:variant==4?COTTON:TEXTURE;
            case SOCKS -> WOOL;
            case PANTS -> variant==1||variant==3?CANVAS:COTTON;
            case JACKET -> variant==1||variant>=5?CANVAS:TEXTURE;
            case FOOTWEAR -> LEATHER;
            case GLOVES -> variant==3?LEATHER:variant==1?WOOL:TEXTURE;
            case HAT -> variant==1||variant==3?WOOL:CANVAS;
            default -> TEXTURE;
        };
    }

}
