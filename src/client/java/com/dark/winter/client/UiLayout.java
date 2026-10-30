// Calculates GUI-space panel and control geometry.
package com.dark.winter.client;

/** Pure GUI-space layout. No screen, player, settings or world mutation. */
public final class UiLayout {
    public record Rect(int x,int y,int w,int h) {
        public int right(){return x+w;}
        public int bottom(){return y+h;}
        public boolean contains(int px,int py){return px>=x&&px<right()&&py>=y&&py<bottom();}
    }
    public record Cards(Rect left,Rect right,int titleY,int backY) {}
    public record Story(Rect panel,int imageHeight,int difficultyY,int configureY,int continueY,int backY) {}
    public record Hub(int left,int navWidth,int right,int contentWidth,int top,int step,int bottom) {}
    private UiLayout() {}
    public static Cards cards(int width,int height) {
        int margin=Math.max(12,Math.min(24,width/24)),gap=14;
        int half=Math.max(76,Math.min(220,(width-2*margin-gap)/2));
        int h=Math.max(88,Math.min(Math.round(half*1.10f),height-92));
        int x=(width-(half*2+gap))/2,y=Math.max(38,(height-h)/2-1);
        return new Cards(new Rect(x,y,half,h),new Rect(x+half+gap,y,half,h),Math.max(3,y-35),height-27);
    }
    public static int selectorHeight(int height){return height<260?18:22;}
    public static int configureHeight(int height){return height<260?25:32;}
    public static int continueHeight(int height){return height<260?22:28;}
    public static Story story(int width,int height) {
        int w=Math.max(180,Math.min(384,width-40));
        int image=Math.max(20,Math.min(76,height-180));
        int h=image+8+selectorHeight(height)+8+configureHeight(height)+12+continueHeight(height);
        int y=Math.max(36,(height-h)/2-4);
        int difficulty=y+image+8,configure=difficulty+selectorHeight(height)+8;
        return new Story(new Rect((width-w)/2,y,w,h),image,difficulty,configure,configure+configureHeight(height)+12,height-27);
    }
    public static Hub hub(int width,int height) {
        int left=Math.max(12,Math.min(34,width/22)),nav=Math.min(178,Math.max(102,(width-70)/3));
        int right=left+nav+20,top=58,step=Math.max(12,Math.min(25,(height-94)/8));
        return new Hub(left,nav,right,Math.max(90,width-right-left),top,step,height-34);
    }
    public static int hudWidth(int width){return Math.max(108,Math.min(192,Math.round(width*.43f)));}
    public static int pauseTop(int height){return Math.max(52,Math.min(Math.round(height*.225f),height-184));}
    public static int menuLeft(int width){return Math.max(20,Math.min(60,Math.round(width*.075f)));}
    public static int menuWidth(int width){return Math.min(214,Math.max(140,Math.round(width*.32f)));}
    public static int menuStep(int height){return height<280?25:31;}
    public static int menuTop(int height){return Math.max(65,Math.min(Math.round(height*.345f),height-4*menuStep(height)-53));}
}
