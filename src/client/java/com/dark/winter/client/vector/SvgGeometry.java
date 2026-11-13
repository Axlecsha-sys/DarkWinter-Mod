package com.dark.winter.client.vector;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Bounded monochrome HUD SVG geometry. No bitmap API or graphics context is used. */
public final class SvgGeometry {
    public static final int MAX_SEGMENTS=1024, MAX_SHAPES=128;
    public static final double TOLERANCE=.018;
    private static final Pattern TOKEN=Pattern.compile("[MmLlHhVvCcQqAaZz]|[-+]?(?:[0-9]*\\.[0-9]+|[0-9]+\\.?[0-9]*)(?:[eE][-+]?[0-9]+)?");
    public record Segment(float ax,float ay,float bx,float by) {}
    // 0 nonzero fill, 1 evenodd fill, 2 round stroke, 3 butt stroke, 4 circle, 5 ring, 6 circular arc.
    public record Shape(int first,int count,int mode,float radius,int argb) {}
    public record Asset(float minX,float minY,float width,float height,List<Segment> segments,List<Shape> shapes) {
        public Asset {segments=List.copyOf(segments);shapes=List.copyOf(shapes);}
    }
    public record Atlas(List<Asset> assets,List<Segment> segments,List<Shape> shapes,List<Integer> firstShapes) {
        public Atlas {assets=List.copyOf(assets);segments=List.copyOf(segments);shapes=List.copyOf(shapes);firstShapes=List.copyOf(firstShapes);}
        public ByteBuffer segmentBlock(){
            var b=ByteBuffer.allocateDirect(MAX_SEGMENTS*16).order(ByteOrder.nativeOrder());
            for(var s:segments)b.putFloat(s.ax).putFloat(s.ay).putFloat(s.bx).putFloat(s.by);
            b.position(0);return b;
        }
        public ByteBuffer shapeBlock(){
            var b=ByteBuffer.allocateDirect(MAX_SHAPES*48).order(ByteOrder.nativeOrder());
            for(var s:shapes){
                b.putInt(s.first).putInt(s.count).putInt(s.mode).putInt(0);
                b.putFloat(s.radius).putFloat(0).putFloat(0).putFloat(0);
                b.putFloat(((s.argb>>>16)&255)/255f).putFloat(((s.argb>>>8)&255)/255f).putFloat((s.argb&255)/255f).putFloat((s.argb>>>24)/255f);
            }
            b.position(0);return b;
        }
    }
    private record Point(double x,double y) {}
    private record Style(String fill,String stroke,double width,String cap,String join,String rule,String current,double opacity,double fillOpacity,double strokeOpacity) {
        Style child(Element e){
            if(e.hasAttribute("style"))throw unsupported("inline CSS");
            return new Style(attr(e,"fill",fill),attr(e,"stroke",stroke),number(e,"stroke-width",width),attr(e,"stroke-linecap",cap),attr(e,"stroke-linejoin",join),attr(e,"fill-rule",rule),attr(e,"color",current),opacity*number(e,"opacity",1),number(e,"fill-opacity",fillOpacity),number(e,"stroke-opacity",strokeOpacity));
        }
    }
    private final List<Segment> segments=new ArrayList<>();
    private final List<Shape> shapes=new ArrayList<>();
    private SvgGeometry(){}
    public static Asset read(InputStream input)throws Exception{
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var bytes=input.readNBytes(262145);if(bytes.length>262144)throw unsupported("SVG file size");
        var root=factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        if(!root.getLocalName().equals("svg"))throw unsupported("root");
        var box=numbers(root.getAttribute("viewBox"));
        if(box.size()!=4||box.get(2)<=0||box.get(3)<=0)throw unsupported("viewBox");
        var reader=new SvgGeometry();reader.walk(root,new Style("#000000","none",1,"butt","miter","nonzero","#000000",1,1,1),0);
        return new Asset(box.get(0).floatValue(),box.get(1).floatValue(),box.get(2).floatValue(),box.get(3).floatValue(),reader.segments,reader.shapes);
    }
    public static Atlas pack(List<Asset> assets){
        var segments=new ArrayList<Segment>();var shapes=new ArrayList<Shape>();var first=new ArrayList<Integer>();
        for(var asset:assets){first.add(shapes.size());int offset=segments.size();segments.addAll(asset.segments);for(var s:asset.shapes)shapes.add(new Shape(s.first+offset,s.count,s.mode,s.radius,s.argb));}
        if(segments.size()>MAX_SEGMENTS||shapes.size()>MAX_SHAPES)throw unsupported("geometry capacity; split the batch");
        return new Atlas(assets,segments,shapes,first);
    }
    private void walk(Element e,Style parent,int depth){
        if(depth>32)throw unsupported("element depth");
        for(String attr:new String[]{"transform","filter","mask","clip-path","stroke-dasharray","vector-effect"})if(e.hasAttribute(attr))throw unsupported(attr);
        var s=parent.child(e);
        switch(e.getLocalName()){
            case "svg","g"->{
                // Group opacity requires an isolated compositing group; never silently approximate it.
                if(e.hasAttribute("opacity")&&number(e,"opacity",1)!=1)throw unsupported("group opacity");
                for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element child)walk(child,s,depth+1);
            }
            case "title","desc","metadata"->{}
            case "path"->{String data=e.getAttribute("d");if(!circularArc(data,s))emitPath(new Path(data).read(),s);}
            case "circle"->{
                float x=(float)number(e,"cx",0),y=(float)number(e,"cy",0),r=(float)number(e,"r",0);
                if(r<0)throw unsupported("negative radius");if(r==0)return;
                int index=segments.size();segments.add(new Segment(x,y,r,0));
                if(!s.fill.equals("none"))add(new Shape(index,1,4,0,color(s.fill,s,s.fillOpacity)));
                if(!s.stroke.equals("none")&&s.width>0)add(new Shape(index,1,5,(float)s.width/2,color(s.stroke,s,s.strokeOpacity)));
            }
            default->throw unsupported(e.getLocalName());
        }
    }
    private void emitPath(List<Contour> contours,Style s){
        if(!s.rule.equals("nonzero")&&!s.rule.equals("evenodd"))throw unsupported("fill rule");
        if(!s.fill.equals("none")){
            int first=segments.size();for(var c:contours){lines(c);if(c.points.size()>1&&!c.closed)segment(c.points.getLast(),c.points.getFirst());}
            if(segments.size()>first)add(new Shape(first,segments.size()-first,s.rule.equals("evenodd")?1:0,0,color(s.fill,s,s.fillOpacity)));
        }
        if(!s.stroke.equals("none")&&s.width>0){
            if(!s.cap.equals("round")&&!s.cap.equals("butt"))throw unsupported("stroke cap");
            // Our artwork uses round joins; straight single-segment compass ticks also allow miter.
            if(!s.join.equals("round")&&contours.stream().anyMatch(c->c.points.size()>2))throw unsupported("non-round path join");
            int first=segments.size();for(var c:contours)lines(c);
            if(segments.size()>first)add(new Shape(first,segments.size()-first,s.cap.equals("round")?2:3,(float)s.width/2,color(s.stroke,s,s.strokeOpacity)));
        }
    }
    /** Continuous HUD arcs are derived from an ordinary single circular SVG arc, not sampled frames. */
    private boolean circularArc(String data,Style s){
        if(!s.fill.equals("none")||s.stroke.equals("none")||s.width<=0||!s.cap.equals("round"))return false;
        var path=new Path(data);if(path.tokens.size()!=11||!path.tokens.get(0).equals("M")||!path.tokens.get(3).equals("A"))return false;
        double x=Double.parseDouble(path.tokens.get(1)),y=Double.parseDouble(path.tokens.get(2));
        double radius=Double.parseDouble(path.tokens.get(4)),ry=Double.parseDouble(path.tokens.get(5));
        double large=Double.parseDouble(path.tokens.get(7)),sweep=Double.parseDouble(path.tokens.get(8));
        if(radius<=0||Math.abs(radius-ry)>1e-7||(large!=0&&large!=1)||(sweep!=0&&sweep!=1))return false;
        double ex=Double.parseDouble(path.tokens.get(9)),ey=Double.parseDouble(path.tokens.get(10));
        double dx=(x-ex)/2,dy=(y-ey)/2,halfChordSquared=dx*dx+dy*dy;
        if(halfChordSquared<1e-16)return false;
        radius=Math.max(radius,Math.sqrt(halfChordSquared));
        double factor=(large==sweep?-1:1)*Math.sqrt(Math.max(0,(radius*radius-halfChordSquared)/halfChordSquared));
        double cx=(x+ex)/2+factor*dy,cy=(y+ey)/2-factor*dx;
        double start=Math.atan2(y-cy,x-cx),extent=Math.atan2(ey-cy,ex-cx)-start;
        if(sweep==1&&extent<0)extent+=Math.PI*2;if(sweep==0&&extent>0)extent-=Math.PI*2;
        int index=segments.size();segments.add(new Segment((float)cx,(float)cy,(float)radius,(float)start));
        segments.add(new Segment((float)extent,0,0,0));
        add(new Shape(index,2,6,(float)s.width/2,color(s.stroke,s,s.strokeOpacity)));return true;
    }
    private void add(Shape s){if(s.argb>>>24!=0)shapes.add(s);if(segments.size()>MAX_SEGMENTS||shapes.size()>MAX_SHAPES)throw unsupported("asset geometry capacity");}
    private void lines(Contour c){for(int i=1;i<c.points.size();i++)segment(c.points.get(i-1),c.points.get(i));}
    private void segment(Point a,Point b){if(Math.hypot(a.x-b.x,a.y-b.y)>1e-9)segments.add(new Segment((float)a.x,(float)a.y,(float)b.x,(float)b.y));}
    private static String attr(Element e,String name,String fallback){return e.hasAttribute(name)?e.getAttribute(name):fallback;}
    private static double number(Element e,String name,double fallback){return e.hasAttribute(name)?finite(Double.parseDouble(e.getAttribute(name))):fallback;}
    private static double finite(double n){if(!Double.isFinite(n)||Math.abs(n)>1e6)throw unsupported("numeric range");return n;}
    private static int color(String value,Style s,double alpha){
        if(value.equals("currentColor"))value=s.current;
        if(!value.matches("#[0-9a-fA-F]{6}"))throw unsupported("paint "+value);
        int opacity=(int)Math.round(Math.clamp(alpha*s.opacity,0,1)*255);
        return opacity<<24|Integer.parseInt(value.substring(1),16);
    }
    private static IllegalArgumentException unsupported(String item){return new IllegalArgumentException("Unsupported HUD SVG feature: "+item);}
    private static List<Double> numbers(String input){
        var result=new ArrayList<Double>();for(var n:input.trim().split("[\\s,]+"))if(!n.isEmpty())result.add(finite(Double.parseDouble(n)));return result;
    }
    private static final class Contour {final List<Point> points=new ArrayList<>();boolean closed;}
    private static final class Path {
        final List<String> tokens=new ArrayList<>();int pos;Point current=new Point(0,0),start=current;Contour contour;final List<Contour> result=new ArrayList<>();
        Path(String input){var m=TOKEN.matcher(input);int previous=0;while(m.find()){if(!input.substring(previous,m.start()).matches("[\\s,]*"))throw unsupported("path token");String token=m.group();if(!Character.isLetter(token.charAt(0)))finite(Double.parseDouble(token));tokens.add(token);previous=m.end();}if(!input.substring(previous).matches("[\\s,]*"))throw unsupported("path suffix");if(tokens.size()>10000)throw unsupported("path token limit");}
        boolean command(){return pos<tokens.size()&&Character.isLetter(tokens.get(pos).charAt(0));}
        double n(){if(pos>=tokens.size()||command())throw unsupported("path arity");return finite(Double.parseDouble(tokens.get(pos++)));}
        Point xy(boolean relative){double x=n(),y=n();return new Point(x+(relative?current.x:0),y+(relative?current.y:0));}
        void to(Point q){if(contour==null)throw unsupported("path must begin with M");contour.points.add(q);current=q;if(contour.points.size()>MAX_SEGMENTS)throw unsupported("contour limit");}
        List<Contour> read(){
            char op=0;
            while(pos<tokens.size()){
                if(command())op=tokens.get(pos++).charAt(0);else if(op==0)throw unsupported("missing path command");
                boolean relative=Character.isLowerCase(op);
                switch(Character.toUpperCase(op)){
                    case 'M'->{Point q=xy(relative);contour=new Contour();result.add(contour);current=start=q;contour.points.add(q);op=relative?'l':'L';}
                    case 'L'->to(xy(relative));
                    case 'H'->{double x=n();to(new Point(x+(relative?current.x:0),current.y));}
                    case 'V'->{double y=n();to(new Point(current.x,y+(relative?current.y:0)));}
                    case 'C'->{Point a=xy(relative),b=xy(relative),end=xy(relative);cubic(current,a,b,end,0);}
                    case 'Q'->{Point a=xy(relative),end=xy(relative);Point old=current;cubic(old,new Point(old.x+(a.x-old.x)*2/3,old.y+(a.y-old.y)*2/3),new Point(end.x+(a.x-end.x)*2/3,end.y+(a.y-end.y)*2/3),end,0);}
                    case 'A'->{double rx=n(),ry=n(),rotation=n(),large=n(),sweep=n();if((large!=0&&large!=1)||(sweep!=0&&sweep!=1))throw unsupported("arc flags");Point end=xy(relative);elliptical(current,Math.abs(rx),Math.abs(ry),rotation,large==1,sweep==1,end);}
                    case 'Z'->{if(contour==null)throw unsupported("close without move");to(start);contour.closed=true;op=0;}
                    default->throw unsupported("path command "+op);
                }
            }
            return result;
        }
        static Point mid(Point a,Point b){return new Point((a.x+b.x)/2,(a.y+b.y)/2);}
        static double distance(Point p,Point a,Point b){double dx=b.x-a.x,dy=b.y-a.y;double t=Math.clamp(((p.x-a.x)*dx+(p.y-a.y)*dy)/Math.max(1e-20,dx*dx+dy*dy),0,1);return Math.hypot(p.x-a.x-t*dx,p.y-a.y-t*dy);}
        void cubic(Point a,Point b,Point c,Point d,int depth){
            if(depth>=16||Math.max(distance(b,a,d),distance(c,a,d))<=TOLERANCE){to(d);return;}
            Point ab=mid(a,b),bc=mid(b,c),cd=mid(c,d),abc=mid(ab,bc),bcd=mid(bc,cd),center=mid(abc,bcd);
            cubic(a,ab,abc,center,depth+1);cubic(center,bcd,cd,d,depth+1);
        }
        void elliptical(Point a,double rx,double ry,double rotation,boolean large,boolean sweep,Point end){
            if(Math.hypot(end.x-a.x,end.y-a.y)<1e-10)return;if(rx==0||ry==0){to(end);return;}
            double phi=Math.toRadians(rotation%360),cp=Math.cos(phi),sp=Math.sin(phi),dx=(a.x-end.x)/2,dy=(a.y-end.y)/2;
            double xp=cp*dx+sp*dy,yp=-sp*dx+cp*dy,lambda=xp*xp/(rx*rx)+yp*yp/(ry*ry);
            if(lambda>1){double scale=Math.sqrt(lambda);rx*=scale;ry*=scale;}
            double q=Math.max(0,(rx*rx*ry*ry-rx*rx*yp*yp-ry*ry*xp*xp)/(rx*rx*yp*yp+ry*ry*xp*xp));
            double factor=(large==sweep?-1:1)*Math.sqrt(q),cxp=factor*rx*yp/ry,cyp=-factor*ry*xp/rx;
            double cx=cp*cxp-sp*cyp+(a.x+end.x)/2,cy=sp*cxp+cp*cyp+(a.y+end.y)/2;
            double begin=Math.atan2((yp-cyp)/ry,(xp-cxp)/rx),finish=Math.atan2((-yp-cyp)/ry,(-xp-cxp)/rx),extent=finish-begin;
            if(sweep&&extent<0)extent+=Math.PI*2;if(!sweep&&extent>0)extent-=Math.PI*2;
            double step=2*Math.acos(Math.clamp(1-TOLERANCE/Math.max(rx,ry),-1,1));
            int steps=Math.max(1,(int)Math.ceil(Math.abs(extent)/Math.max(step,.001)));if(steps>MAX_SEGMENTS)throw unsupported("arc segment limit");
            for(int i=1;i<=steps;i++){double angle=begin+extent*i/steps;to(i==steps?end:new Point(cx+cp*rx*Math.cos(angle)-sp*ry*Math.sin(angle),cy+sp*rx*Math.cos(angle)+cp*ry*Math.sin(angle)));}
        }
    }
}
