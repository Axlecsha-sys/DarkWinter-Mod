// Caches view-only first-person surfaces without changing source meshes or their pivots.
package com.dark.winter.client.equipment;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

/** Cached view-only surfaces. Source skin/garment parts and their animated pivots are never changed. */
final class FirstPersonSurfaces {
    static final float PALM_Y = 11.7f;
    private static final Map<ModelPart,ModelPart> TORSOS = new IdentityHashMap<>();

    static ModelPart garment(ClothingMeshes.Piece piece) {
        if(piece.bone()!=ClothingMeshes.Bone.BODY)return piece.geometry();
        if(piece.role()==ClothingMeshes.SurfaceRole.COLLAR || piece.role()==ClothingMeshes.SurfaceRole.HOOD
                || piece.role()==ClothingMeshes.SurfaceRole.FLAP)return null;
        return piece.role()==ClothingMeshes.SurfaceRole.MAIN
                ? TORSOS.computeIfAbsent(piece.geometry(),FirstPersonSurfaces::torso) : piece.geometry();
    }

    static ModelPart bodyRoot(boolean slim) {
        ModelPart source = HumanBodyModel.createRoot(slim);
        Map<String, ModelPart> children = new LinkedHashMap<>();
        for (String name : List.of("darkwinter_human", "head", "left_arm", "right_arm", "left_leg", "right_leg")) children.put(name, source.getChild(name));
        ModelPart body = source.getChild("body");
        Map<String, ModelPart> layers = Map.of("jacket", torso(body.getChild("jacket")),
                "neck", new ModelPart(List.of(), Map.of()), "underwear", body.getChild("underwear"));
        ModelPart fitted = new ModelPart(torsoCubes(body), layers);
        fitted.setInitialPose(body.getInitialPose()); fitted.resetPose();
        children.put("body", fitted);
        return new ModelPart(List.of(), children);
    }

    static ModelPart torso(ModelPart source) { return new ModelPart(torsoCubes(source), Map.of()); }

    private static List<ModelPart.Cube> torsoCubes(ModelPart source) {
        List<ModelPart.Cube> cubes = new ArrayList<>();
        source.visit(new PoseStack(), (pose, path, index, cube) -> {
            if (!path.isEmpty()) return;
            for (var polygon : cube.polygons) {
                var vertices = polygon.vertices();
                float firstY = vertices[0].y();
                boolean flat = true;
                for (var vertex : vertices) flat &= Math.abs(vertex.y() - firstY) < .0001f;
                // The close top disk is a closed-volume construction surface, not the coat's chest.
                if (flat && firstY < 1 && polygon.normal().y() < -.9f) continue;
                append(cubes, vertices);
            }
        });
        return List.copyOf(cubes);
    }

    static ModelPart grip(ModelPart source, PoseStack bone, Predicate<String> includePath) {
        List<ModelPart.Cube> cubes = new ArrayList<>();
        source.visit(bone, (pose, path, index, cube) -> {
            if (!includePath.test(path)) return;
            for (var polygon : cube.polygons) {
                ModelPart.Vertex[] vertices = new ModelPart.Vertex[4];
                for (int i = 0; i < 4; i++) {
                    var v = polygon.vertices()[i];
                    Vector3f p = new Vector3f(v.worldX(), v.worldY(), v.worldZ()).mulPosition(pose.pose()).mul(16);
                    curl(p);
                    vertices[i] = new ModelPart.Vertex(p.x, p.y, p.z, v.u(), v.v());
                }
                append(cubes, vertices);
            }
        });
        return new ModelPart(List.copyOf(cubes), Map.of());
    }

    /** One continuous bend in the distal palm; skin, thumb and glove use exactly the same map. */
    static Vector3f curl(Vector3f point) {
        float start = 10.6f + .1f;
        if (point.y > start) {
            float angle = (point.y - start) * .53f;
            point.y = start + (float)Math.sin(angle) / .53f;
            point.z -= (1 - (float)Math.cos(angle)) / .53f;
        }
        return point;
    }

    private static void append(List<ModelPart.Cube> cubes, ModelPart.Vertex[] source) {
        List<ModelPart.Vertex> unique = new ArrayList<>();
        for (var v : source) if (unique.stream().noneMatch(p -> p.x() == v.x() && p.y() == v.y() && p.z() == v.z())) unique.add(v);
        if (unique.size() < 3) return;
        var a = unique.get(0); var b = unique.get(1); var c = unique.get(2);
        Vector3f normal = new Vector3f(b.x()-a.x(), b.y()-a.y(), b.z()-a.z()).cross(c.x()-a.x(), c.y()-a.y(), c.z()-a.z());
        if (normal.lengthSquared() < 1e-12f) return;
        normal.normalize();
        if (unique.size() == 4) {
            var d = unique.get(3);
            if (Math.abs(normal.dot(d.x()-a.x(), d.y()-a.y(), d.z()-a.z())) > .00005f) {
                append(cubes, new ModelPart.Vertex[]{a,b,c,c}); append(cubes, new ModelPart.Vertex[]{a,c,d,d}); return;
            }
        }
        ModelPart.Vertex[] vertices = {a,b,c,unique.getLast()};
        float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for (var p : vertices) {minX=Math.min(minX,p.x()); minY=Math.min(minY,p.y()); minZ=Math.min(minZ,p.z()); maxX=Math.max(maxX,p.x()); maxY=Math.max(maxY,p.y()); maxZ=Math.max(maxZ,p.z());}
        ModelPart.Cube cube = new ModelPart.Cube(0,0,minX,minY,minZ,maxX-minX,maxY-minY,maxZ-minZ,0,0,0,false,1,1,Set.of(Direction.NORTH));
        cube.polygons[0] = new ModelPart.Polygon(vertices, normal);
        cubes.add(cube);
    }

    private FirstPersonSurfaces() { }
}
