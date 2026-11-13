"""Prepare native spruce render contexts from DarkWinter's own model definition.

The original captured shape IDs remain stable. New clean trunk variants use the
same eight-facet rings and taper as TestSpruceModels.trunk; no world/save material
IDs are introduced. Geometry is prepared during packaging, never during a frame.
"""
from copy import deepcopy
import struct


def _f32(value):
    return struct.unpack("<f", struct.pack("<f", value))[0]


def _natural_leader_profile(crown):
    """Keep the authored tip and feather the exposed leader below it.

    This profile is selected only by the generator's exact natural-tip predicate.
    It does not add a bough tier, a snow mantle, or a new world block.
    """
    if not crown:
        return []
    profile = deepcopy(crown)
    low = min(p[1] for q in crown for p in q["p"])
    high = max(p[1] for q in crown for p in q["p"])
    span = max(high - low, 0.0001)
    # Translate the reused feather tier uniformly, preserving all card UVs.
    # The bound also protects a future authored crown with a lower silhouette.
    shift = min(0.82, max(0.0, low + 0.98))
    lower = deepcopy(crown)
    for quad in lower:
        for p in quad["p"]:
            position = max(0.0, min(1.0, (p[1] - low) / span))
            scale = 1.4 - 0.35 * position
            p[0] = _f32(0.5 + (p[0] - 0.5) * scale)
            p[2] = _f32(0.5 + (p[2] - 0.5) * scale)
            p[1] = _f32(p[1] - shift)
            if p[1] < -1.0 or not (-1.0 <= p[0] <= 2.0 and -1.0 <= p[2] <= 2.0):
                raise ValueError("Natural spruce leader exceeds neighbouring-cell bounds")
    profile.extend(lower)
    return profile


def _normal(quad):
    a, b, c = quad["p"][:3]
    u = [b[i] - a[i] for i in range(3)]
    v = [c[i] - a[i] for i in range(3)]
    return (u[1] * v[2] - u[2] * v[1],
            u[2] * v[0] - u[0] * v[2],
            u[0] * v[1] - u[1] * v[0])


def _wood_end(quad, bark):
    if quad["texture"] != bark:
        return False
    ys = [p[1] for p in quad["p"]]
    # Authored trunk ends are exactly y=0 or y=1. This excludes sloping bark
    # sides, root snow, branch snow, and crown needle cards.
    if max(ys) - min(ys) > 0.00001:
        return False
    if min(abs(ys[0]), abs(ys[0] - 1.0)) > 0.00001:
        return False
    n = _normal(quad)
    return abs(n[1]) > 0.00000001 and abs(n[0]) + abs(n[2]) < abs(n[1]) * 0.001


def _end_uv(points):
    # A single planar map is shared by every fan triangle, centred on the axis.
    # Repeating a full sprite on each triangular wedge creates false ring seams.
    radius = max(max(abs(p[0] - 0.5), abs(p[2] - 0.5)) for p in points)
    radius = max(radius, 0.00001)
    return [[0.5 + (p[0] - 0.5) / (2.0 * radius) * 0.98,
             0.5 + (p[2] - 0.5) / (2.0 * radius) * 0.98] for p in points]


def _cap_uv(points, radius):
    return [[0.5 + (p[0] - 0.5) / (2.0 * radius) * 0.98,
             0.5 + (p[2] - 0.5) / (2.0 * radius) * 0.98] for p in points]


def _context(above, closed_bottom, layers=0, sides=0, crown=False):
    return (0x80000000 | above | (int(closed_bottom) << 3) |
            (layers << 4) | (sides << 8) | (int(crown) << 12))


def _snow_join(lower, upper, layers, sides, texture, ring):
    """A ground-supported annulus, cut to actual snow-bearing neighbour sides."""
    height = layers / 8.0
    # This tiny hidden overlap prevents cracks, while the octagonal hole follows
    # exactly the same linear taper as the trunk body at this snow height.
    width = _f32(upper if layers == 8 else
                 (lower + (upper - lower) * height) * 0.999)
    # Full-depth snow meets the horizontal wood end at y=1: share its exact edge,
    # since a hidden overlap there would instead be coplanar with the endgrain.
    inside, outside = ring(width, height), ring(0.5, height)
    quads = []
    inner_keys = {tuple(round(v, 8) for v in p) for p in inside}

    def face(points):
        quads.append({"p": deepcopy(points),
                      "uv": [[0.001 + p[0] * 0.998, 0.001 + p[2] * 0.998]
                             for p in points],
                      "texture": texture, "tint": 4294967295, "cutout": False})

    for i in range(8):
        j = (i + 1) & 7
        if i & 1:
            visible = bool(sides & (1 << (i // 2)))
        else:
            corner = i // 2
            visible = bool(sides & ((1 << corner) | (1 << ((corner + 3) & 3))))
        if not visible:
            continue
        face([outside[i], inside[i], inside[j], outside[j]])
        if not i & 1:
            corner = [0.0 if i in (0, 6) else 1.0, height,
                      0.0 if i < 4 else 1.0]
            face([corner, outside[i], outside[j], outside[j]])

    # Close only exposed edges. Shared edges and the hidden bark intersection
    # need no wall; snow-bearing neighbours already close their outer boundary.
    boundaries = {}
    for quad in quads:
        points = quad["p"]
        for i in range(4):
            a, b = points[i], points[(i + 1) & 3]
            ka, kb = tuple(round(v, 8) for v in a), tuple(round(v, 8) for v in b)
            if ka == kb:
                continue
            edge = tuple(sorted((ka, kb)))
            if edge in boundaries:
                boundaries[edge][2] += 1
            else:
                boundaries[edge] = [a, b, 1]
    for a, b, count in boundaries.values():
        if count != 1:
            continue
        ka, kb = tuple(round(v, 8) for v in a), tuple(round(v, 8) for v in b)
        if ka in inner_keys and kb in inner_keys:
            continue
        side = (0 if abs(a[2]) < 1e-7 and abs(b[2]) < 1e-7 else
                1 if abs(a[0] - 1) < 1e-7 and abs(b[0] - 1) < 1e-7 else
                2 if abs(a[2] - 1) < 1e-7 and abs(b[2] - 1) < 1e-7 else
                3 if abs(a[0]) < 1e-7 and abs(b[0]) < 1e-7 else None)
        if side is not None and sides & (1 << side):
            continue
        points = [b, a, [a[0], 0.0, a[2]], [b[0], 0.0, b[2]]]
        axis = 0 if abs(a[0] - b[0]) >= abs(a[2] - b[2]) else 2
        quads.append({"p": deepcopy(points),
                      "uv": [[0.001 + p[axis] * 0.998, 0.001 + (1 - p[1]) * 0.998]
                             for p in points],
                      "texture": texture, "tint": 4294967295, "cutout": False})
    return quads


def prepare_forest_shapes(shapes, materials, textures, endgrain_id):
    """Mutate existing cap UVs and append complete native trunk contexts.

    shape.nativeEndgrain stores texture+1 for descriptor word 4.
    shape.nativeGround stores 1 for an authored ground collar, 2 for a bare
    trunk, for descriptor word 5. nativeLogContext is descriptor word 6:
    marker|above|closedBottom<<3|snowLayers<<4|snowSides<<8|crown<<12.
    These are render metadata, never block/save states.
    """
    ground_names = {"minecraft:block/snow", "darkwinter:block/test_ground_snow"}
    ground_ids = {int(t["id"]) for t in textures if t.get("name") in ground_names}
    needle_ids = {int(t["id"]) for t in textures
                  if t.get("name") == "darkwinter:block/test_spruce_needles"}
    bark_ids = {int(t["id"]) for t in textures
                if t.get("name") == "darkwinter:block/test_spruce_bark"}
    if not isinstance(endgrain_id, int) or endgrain_id < 0 or endgrain_id >= len(textures):
        raise ValueError("Native spruce endgrain texture is missing")

    # Snapshot the captured shapes. Appending variants must never change source
    # overrides or turn a generated variant into another generation input.
    source = list(shapes)
    logs = {}
    for mid, (name, _flags) in enumerate(materials):
        if not name.startswith("darkwinter:test_spruce_log[") or "axis=y" not in name:
            continue
        radius = next((r for r in range(1, 6) if "radius=" + str(r) in name), None)
        if radius is None:
            continue
        candidates = [s for s in source if s["material"] == mid and s.get("quads")]
        if not candidates:
            raise ValueError("Native spruce has no captured body: " + name)

        bark = None
        style = None
        for shape in candidates:
            for quad in shape["quads"]:
                ys = [p[1] for p in quad["p"]]
                n = _normal(quad)
                if max(ys) - min(ys) > 0.5 and abs(n[1]) < 0.1:
                    if not bark_ids or quad["texture"] in bark_ids:
                        bark = quad["texture"]
                        style = quad
                        break
            if bark is not None:
                break
        if bark is None:
            raise ValueError("Native spruce vertical bark is missing: " + name)
        logs[mid] = (radius, bark, style, candidates)

    converted = 0
    for mid, (_radius, bark, _style, candidates) in logs.items():
        for shape in candidates:
            shape["nativeEndgrain"] = endgrain_id + 1
            shape["nativeGround"] = 1 if any(
                q["texture"] in ground_ids for q in shape["quads"]) else 2
            # Keep authored geometry and all old shape IDs. Only wood ends use
            # the new endgrain material; snow and foliage keep their own sprites.
            for quad in shape["quads"]:
                if _wood_end(quad, bark):
                    quad["texture"] = endgrain_id
                    quad["uv"] = _end_uv(quad["p"])
                    converted += 1

    cx = [-1.0, -0.52, 0.52, 1.0, 1.0, 0.52, -0.52, -1.0]
    cz = [-0.52, -1.0, -1.0, -0.52, 0.52, 1.0, 1.0, 0.52]

    def ring(width, y):
        return [[_f32(0.5 + _f32(_f32(cx[i]) * width)), float(y),
                 _f32(0.5 + _f32(_f32(cz[i]) * width))] for i in range(8)]

    snow_heights = set()
    for name, _flags in materials:
        if name == "minecraft:snow_block":
            snow_heights.add(8)
        elif name.startswith("minecraft:snow[") and "layers=" in name:
            value = int(name.split("layers=", 1)[1].split(",", 1)[0].split("]", 1)[0])
            if 1 <= value <= 8:
                snow_heights.add(value)
        elif (name.startswith("darkwinter:test_snowy_grass_slab[") and
              "type=bottom" in name and "snowy=true" in name and
              "waterlogged=true" not in name):
            snow_heights.add(5)
    ground_texture = next((int(tex["id"]) for tex in textures
                           if tex.get("name") == "darkwinter:block/test_ground_snow"),
                          min(ground_ids) if ground_ids else None)
    # The exact authored thin-leader crown is shared by render-only repairs of
    # deterministic natural tips in older generators. Cut stumps never request it.
    crown_choices = [[q for q in s["quads"] if q["texture"] in needle_ids]
                     for s in source if s["material"] in logs
                     if any(max(p[1] for p in q["p"]) > 1.0
                            for q in s["quads"] if q["texture"] in needle_ids)]
    common_crown = _natural_leader_profile(max(crown_choices, key=len) if crown_choices else [])
    added = joins = 0
    for mid, (radius, bark, style, candidates) in logs.items():
        lower = _f32(0.065) if radius == 1 else _f32(radius * _f32(0.06))
        for above in range(6):
            upper = (_f32(0.065) if above == 1 else _f32(above * _f32(0.06))) if above else (
                _f32(0.025) if radius == 1 else _f32(lower * _f32(0.84)))
            bottom, top = ring(lower, 0), ring(upper, 1)
            for closed_bottom in (False, True):
                quads = []
                for i in range(8):
                    j = (i + 1) & 7
                    face = {k: deepcopy(v) for k, v in style.items() if k not in ("p", "uv")}
                    face["p"] = deepcopy([bottom[i], top[i], top[j], bottom[j]])
                    face["uv"] = [[0.01, 0.99], [0.01, 0.01],
                                  [0.99, 0.01], [0.99, 0.99]]
                    face["texture"] = bark
                    quads.append(face)
                for i in range(8):
                    j = (i + 1) & 7
                    if closed_bottom:
                        points = [[0.5, 0.0, 0.5], bottom[i], bottom[j], bottom[j]]
                        quads.append({"p": deepcopy(points), "uv": _cap_uv(points, lower),
                                      "texture": endgrain_id, "tint": 4294967295,
                                      "cutout": False})
                    if above == 0:
                        points = [[0.5, 1.0, 0.5], top[j], top[i], top[i]]
                        quads.append({"p": deepcopy(points), "uv": _cap_uv(points, upper),
                                      "texture": endgrain_id, "tint": 4294967295,
                                      "cutout": False})
                shapes.append({"material": mid, "quads": quads,
                               "nativeEndgrain": endgrain_id + 1, "nativeGround": 2,
                               "nativeLogContext": _context(above, closed_bottom)})
                added += 1
                if above == 0 and common_crown:
                    shapes.append({"material": mid, "quads": deepcopy(quads) + deepcopy(common_crown),
                                   "nativeEndgrain": endgrain_id + 1, "nativeGround": 2,
                                   "nativeLogContext": _context(above, closed_bottom, crown=True)})
                    added += 1
                if closed_bottom and ground_texture is not None:
                    for layers in sorted(snow_heights):
                        for sides in range(1, 16):
                            shapes.append({"material": mid,
                                           "quads": deepcopy(quads) + _snow_join(
                                               lower, upper, layers, sides, ground_texture, ring),
                                           "nativeEndgrain": endgrain_id + 1, "nativeGround": 1,
                                           "nativeLogContext": _context(above, True, layers, sides)})
                            added += 1
                            joins += 1
    return {"nativeLogMaterials": len(logs), "nativeLogVariants": added,
            "nativeGroundJoins": joins, "nativeSnowHeights": sorted(snow_heights),
            "naturalLeaderQuads": len(common_crown),
            "naturalLeaderMinY": min((p[1] for q in common_crown for p in q["p"]), default=0.0),
            "endgrainQuads": converted, "endgrainTexture": endgrain_id}
