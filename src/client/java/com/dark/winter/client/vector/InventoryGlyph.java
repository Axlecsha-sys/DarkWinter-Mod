package com.dark.winter.client.vector;

/** Original SVG symbols for the approved three-page inventory and physical-pixel reticle. */
public enum InventoryGlyph {
    PERSON("person"),SHIRT("shirt"),PACK("pack"),THERMOMETER("thermometer"),REST("rest"),
    DROP("drop"),FOOD("food"),HEART("heart"),WIND("wind"),SHIELD("shield"),SPRINT("sprint"),
    CHECK("check"),PLUS("plus"),MEDICAL("medical"),SUN("sun"),CLOUD("cloud"),WEIGHT("weight"),
    CONDITION("condition"),TOOL("tool"),MATERIAL("material"),TAG("tag"),EYE("eye"),SOCKS("socks"),
    CAP("cap"),PANTS("pants"),BOOTS("boots"),GLOVES("gloves"),DOT("reticle-dot"),TARGET("reticle-target"),STICK("stick");
    public final String resource;
    InventoryGlyph(String resource){this.resource=resource;}
}
