package com.dark.winter.client.inventory;

/** One approved 1920x1080 composition, fitted uniformly to the current GUI viewport. */
public record InventoryLayout(float scale,float left,float top) {
    public record Rect(int x,int y,int w,int h) {
        public boolean contains(double mx,double my){return mx>=x&&my>=y&&mx<x+w&&my<y+h;}
    }
    public static InventoryLayout fit(int width,int height){
        float s=Math.min(width/1920f,height/1080f);
        return new InventoryLayout(s,(width-1920*s)/2,(height-1080*s)/2);
    }
    public int x(float value){return Math.round(left+value*scale);}
    public int y(float value){return Math.round(top+value*scale);}
    public int size(float value){return Math.max(1,Math.round(value*scale));}
    public float localX(double value){return (float)((value-left)/scale);}
    public float localY(double value){return (float)((value-top)/scale);}
    public static Rect item(int index){return new Rect(570+(index%5)*132,269+(index/5)*157,120,145);}
    public static Rect tab(int index){return new Rect(631+index*218,42,202,68);}
    public static Rect category(int index){return new Rect(489,268+index*82,62,70);}
    public static Rect portrait(boolean backpack){return backpack?new Rect(46,174,405,730):new Rect(435,173,474,816);}
}
