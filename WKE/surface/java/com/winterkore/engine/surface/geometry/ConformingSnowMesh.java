package com.winterkore.engine.surface.geometry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.DoubleConsumer;

/**
 * Fresh adaptive cap topology. Boundary partitions do not depend on interior refinement.
 * Callers supply immutable authored triangles and one coherent physical sampling field.
 * Compatible owners must supply the same boundaryHeight and the same halo boundary context.
 */
public final class ConformingSnowMesh {
    public static final double BOUNDARY_STEP=1.0/64,MINIMUM_EDGE=1.0/512,BOUNDARY_ERROR=.0015;
    private static final double EPS=1e-10;
    // Keep split children representable even in chunk-relative float vertices.
    private static final double MINIMUM_ALTITUDE=MINIMUM_EDGE/256;
    private ConformingSnowMesh(){}
    public record Point(double x,double y,double z){
        public Point {if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Nonfinite authored point");}
    }
    /** boundaryMask bits zero, one, two identify AB, BC, CA physical perimeter edges. */
    public record Root(int faceId,Point a,Point b,Point c,int boundaryMask){
        public Root {
            Objects.requireNonNull(a);Objects.requireNonNull(b);Objects.requireNonNull(c);
            if((boundaryMask&~7)!=0||Math.abs(area(a,b,c))<1e-15)throw new IllegalArgumentException("Invalid authored cap triangle");
        }
    }
    /** y is the sampled physical height; rawY retains the immutable authored plane. */
    public record Vertex(double x,double y,double z,double rawY){}
    public record Triangle(int faceId,Vertex a,Vertex b,Vertex c,int level){}
    /** edge is AB=0, BC=1, CA=2; direction follows that edge of the original root. */
    public record BoundarySegment(int rootIndex,int edge,int faceId,Vertex a,Vertex b){}
    public record Result(List<Triangle> triangles,List<BoundarySegment> boundarySegments,Map<String,Long> diagnostics){
        /** Exact maximum of the reported leaf probes, or NaN when measurement was unavailable. */
        public double maximumLeafError(){
            return Double.longBitsToDouble(diagnostics.getOrDefault("maximumLeafErrorBits",Double.doubleToRawLongBits(Double.NaN)));
        }
    }
    @FunctionalInterface public interface Sampler {
        double height(int faceId,double x,double rawY,double z);
        default double boundaryHeight(int faceId,double x,double rawY,double z){return height(faceId,x,rawY,z);}
        default double midpointHeight(int faceId,double x,double rawY,double z){return height(faceId,x,rawY,z);}
    }
    /** Exact AB/BC/CA midpoint heights, available only during the current refinement callback. */
    @FunctionalInterface public interface MidpointSamples {double height(int edge);}
    @FunctionalInterface public interface ProbedRefinement {
        boolean refine(int faceId,Point a,Point b,Point c,double ya,double yb,double yc,int level,MidpointSamples probes);
    }
    @FunctionalInterface public interface ProbedHeightError {
        double error(int faceId,Point a,Point b,Point c,double ya,double yb,double yc,int level,double stopAbove,MidpointSamples probes);
    }
    /** level describes absolute world edge spacing (0..9), not history or binary recursion depth. */
    @FunctionalInterface public interface Refinement {
        boolean refine(int faceId,Point a,Point b,Point c,double sampledYa,double sampledYb,double sampledYc,int level);
    }
    /**
     * Returns the exact maximum measured error in metres unless a probe already exceeds stopAbove.
     * When a probe exceeds stopAbove, the callback may stop and return that measured error.
     */
    @FunctionalInterface public interface HeightError {
        double error(int faceId,Point a,Point b,Point c,double sampledYa,double sampledYb,double sampledYc,int level,double stopAbove);
    }
    @FunctionalInterface public interface HeightTolerance {
        double tolerance(int faceId,Point a,Point b,Point c,double sampledYa,double sampledYb,double sampledYc,int level);
    }
    /** The requested topology cannot be completed within the configured work bound. */
    public static final class WorkLimitExceeded extends IllegalStateException {
        private final transient Object owner;
        private WorkLimitExceeded(Object owner,String message){super(message);this.owner=owner;}
    }
    /** Stream final triangles without constructing a second object graph. The sink must not reenter this build. */
    @FunctionalInterface public interface TriangleSink {
        void accept(int faceId,Vertex a,Vertex b,Vertex c,int level);
    }
    public static final class Builder {
        private final Sampler sampler;private final ProbedRefinement refinement;
        private final ProbedHeightError heightError;private final HeightTolerance heightTolerance;
        private final List<Root> roots=new ArrayList<>(),context=new ArrayList<>();
        private final List<Point> cuts=new ArrayList<>();
        private int maximumWork=2_000_000;
        private boolean approximateOnWorkLimit,insetSeedGrid,cacheBoundarySamples;
        private Runnable cancellation=()->{};
        private DoubleConsumer terminalError;
        private TriangleSink triangleSink;
        public Builder(Sampler sampler,Refinement refinement){this(sampler,adapt(refinement));}
        public Builder(Sampler sampler,ProbedRefinement refinement){
            this.sampler=Objects.requireNonNull(sampler);this.refinement=Objects.requireNonNull(refinement);
            heightError=null;heightTolerance=null;
        }
        public Builder(Sampler sampler,HeightError error,HeightTolerance tolerance){this(sampler,adapt(error),tolerance);}
        public Builder(Sampler sampler,ProbedHeightError error,HeightTolerance tolerance){
            this.sampler=Objects.requireNonNull(sampler);refinement=null;
            heightError=Objects.requireNonNull(error);heightTolerance=Objects.requireNonNull(tolerance);
        }
        private static ProbedRefinement adapt(Refinement refinement){
            Objects.requireNonNull(refinement);
            return (face,a,b,c,ya,yb,yc,level,probes)->refinement.refine(face,a,b,c,ya,yb,yc,level);
        }
        private static ProbedHeightError adapt(HeightError error){
            Objects.requireNonNull(error);
            return (face,a,b,c,ya,yb,yc,level,stop,probes)->error.error(face,a,b,c,ya,yb,yc,level,stop);
        }
        /** Called for every final leaf after successful, non-budget-limited emission. */
        public Builder onTerminalError(DoubleConsumer report){
            if(heightError==null)throw new IllegalStateException("Terminal errors require a height error measure");
            terminalError=Objects.requireNonNull(report);return this;
        }
        public Builder addRoot(Root root){roots.add(Objects.requireNonNull(root));return this;}
        public Builder addRoot(int faceId,Point a,Point b,Point c,int boundaryMask){return addRoot(new Root(faceId,a,b,c,boundaryMask));}
        /** Halo roots affect only common perimeter cuts. They never emit a cap or fill a hole. */
        public Builder addBoundaryContext(Root root){context.add(Objects.requireNonNull(root));return this;}
        public Builder addBoundaryCut(Point point){cuts.add(Objects.requireNonNull(point));return this;}
        /** Called before work and at most every 256 counted operations; it may throw cancellation. */
        public Builder check(Runnable check){cancellation=Objects.requireNonNull(check);return this;}
        public Builder maxWork(int maximum){if(maximum<1)throw new IllegalArgumentException("Positive work guard required");maximumWork=maximum;return this;}
        /** Allow adaptive exhaustion to finish a complete, possibly coarser cap with the same boundary. */
        public Builder approximateOnWorkLimit(){approximateOnWorkLimit=true;return this;}
        /** Seed well-shaped roots with many perimeter cuts without moving or adding boundary vertices. */
        public Builder insetSeedGrid(){insetSeedGrid=true;return this;}
        /** Reuse exact calls to a deterministic boundary sampler within one build. */
        public Builder cacheBoundarySamples(){cacheBoundarySamples=true;return this;}
        /** With a sink, Result.triangles is empty; diagnostics and boundary segments remain complete. */
        public Builder emitTriangles(TriangleSink sink){triangleSink=Objects.requireNonNull(sink);return this;}
        public Result build(){return new Work(this).build();}
    }
    private static double area(Point a,Point b,Point c){return (b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x);}
    private static double zero(double v){return v==0?0:v;}
    /** First-seen identity with the last authored occurrence retained for exact edge ties. */
    private static final class AuthoredPoint {
        final Point point;long last;
        AuthoredPoint(Point point,long last){this.point=point;this.last=last;}
    }
    private static final class Node {
        final int id;final Point raw;boolean boundary;
        int firstFace;Vertex first;Map<Integer,Vertex> otherFaces;
        Node(int id,Point raw){this.id=id;this.raw=raw;}
        boolean sampled(int face){return first!=null&&(firstFace==face||otherFaces!=null&&otherFaces.containsKey(face));}
    }
    private static final class Edge {
        final Node a,b;
        final double lengthSquared;
        final long id;
        double midpointHeight=Double.NaN;int midpointFace;
        boolean locked;
        int incidents,blockedIncidents;
        Leaf first,second;
        List<Leaf> additional;
        Edge(Node a,Node b,long id){this.a=a;this.b=b;this.id=id;lengthSquared=Work.squared(a,b);}
        void attach(Leaf leaf,int side){
            incidents++;blockedIncidents+=(leaf.blockedSides>>side)&1;
            if(first==null)first=leaf;
            else if(second==null)second=leaf;
            else{
                if(additional==null)additional=new ArrayList<>();
                additional.add(leaf);
            }
        }
        void detach(Leaf leaf,int side){
            incidents--;blockedIncidents-=(leaf.blockedSides>>side)&1;
            if(first==leaf)first=null;
            else if(second==leaf)second=null;
            else if(additional!=null)additional.remove(leaf);
        }
    }
    /** Per-build identities without boxed keys or per-lookup coordinate records. No table iteration escapes. */
    private static long tableHash(long value){
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;return value^(value>>>31);
    }
    private static long pointBits(double value){return Double.doubleToLongBits(zero(value));}
    private static final int MAX_TABLE_CAPACITY=1<<29;
    /** Exact authored keys retain signed zero; unlike mesh nodes, authored edge ties cannot merge it. */
    private static final class AuthoredTable {
        long[] xs=new long[16],ys=new long[16],zs=new long[16];
        AuthoredPoint[] values=new AuthoredPoint[16];
        final int maximumEntries;int size;
        AuthoredTable(long maximumEntries){this.maximumEntries=(int)Math.min(maximumEntries,MAX_TABLE_CAPACITY*2L/3);}
        private static int first(long x,long y,long z,int mask){
            return (int)tableHash(x^Long.rotateLeft(y,21)^Long.rotateLeft(z,42))&mask;
        }
        private int slot(long x,long y,long z){
            int mask=values.length-1,index=first(x,y,z,mask);
            while(values[index]!=null&&(xs[index]!=x||ys[index]!=y||zs[index]!=z))index=(index+1)&mask;
            return index;
        }
        /** Existing entries keep their original point and sequence; the ordered caller records later occurrences. */
        AuthoredPoint intern(Point point,long sequence){
            long x=Double.doubleToRawLongBits(point.x),y=Double.doubleToRawLongBits(point.y),z=Double.doubleToRawLongBits(point.z);
            int slot=slot(x,y,z);if(values[slot]!=null)return values[slot];
            if(size>=maximumEntries)return null;
            if(size+1>values.length*2L/3){grow();slot=slot(x,y,z);}
            var value=new AuthoredPoint(point,sequence);size++;
            xs[slot]=x;ys[slot]=y;zs[slot]=z;values[slot]=value;return value;
        }
        private void grow(){
            var oldX=xs;var oldY=ys;var oldZ=zs;var oldValues=values;
            int capacity=values.length<<1;xs=new long[capacity];ys=new long[capacity];zs=new long[capacity];
            values=new AuthoredPoint[capacity];
            for(int i=0;i<oldValues.length;i++)if(oldValues[i]!=null){
                int slot=slot(oldX[i],oldY[i],oldZ[i]);xs[slot]=oldX[i];ys[slot]=oldY[i];zs[slot]=oldZ[i];values[slot]=oldValues[i];
            }
        }
    }
    private static final class NodeTable {
        long[] xs=new long[16],ys=new long[16],zs=new long[16];Node[] values=new Node[16];
        final int maximumEntries;int size;
        NodeTable(long maximumEntries){this.maximumEntries=(int)Math.min(maximumEntries,MAX_TABLE_CAPACITY*2L/3);}
        private static int first(long x,long y,long z,int mask){
            return (int)tableHash(x^Long.rotateLeft(y,21)^Long.rotateLeft(z,42))&mask;
        }
        private int slot(long x,long y,long z){
            int mask=values.length-1,index=first(x,y,z,mask);
            while(values[index]!=null&&(xs[index]!=x||ys[index]!=y||zs[index]!=z))index=(index+1)&mask;
            return index;
        }
        Node intern(Point point){
            long x=pointBits(point.x),y=pointBits(point.y),z=pointBits(point.z);int slot=slot(x,y,z);
            if(values[slot]!=null)return values[slot];
            if(size>=maximumEntries)return null;
            if(size+1>values.length*2L/3){grow();slot=slot(x,y,z);}
            var node=new Node(size++,point);xs[slot]=x;ys[slot]=y;zs[slot]=z;values[slot]=node;return node;
        }
        private void grow(){
            var oldX=xs;var oldY=ys;var oldZ=zs;var oldValues=values;
            int capacity=values.length<<1;xs=new long[capacity];ys=new long[capacity];zs=new long[capacity];values=new Node[capacity];
            for(int i=0;i<oldValues.length;i++)if(oldValues[i]!=null){
                int slot=slot(oldX[i],oldY[i],oldZ[i]);xs[slot]=oldX[i];ys[slot]=oldY[i];zs[slot]=oldZ[i];values[slot]=oldValues[i];
            }
        }
    }
    private static final class EdgeTable {
        long[] keys=new long[16];Edge[] values=new Edge[16];final int maximumEntries;int size;
        EdgeTable(long maximumEntries){this.maximumEntries=(int)Math.min(maximumEntries,MAX_TABLE_CAPACITY*2L/3);}
        private int slot(long key){
            int mask=values.length-1,index=(int)tableHash(key)&mask;
            while(values[index]!=null&&keys[index]!=key)index=(index+1)&mask;
            return index;
        }
        Edge intern(Node a,Node b){
            int low=Math.min(a.id,b.id),high=Math.max(a.id,b.id);
            long id=((long)low<<32)|(high&0xffffffffL);int slot=slot(id);
            if(values[slot]!=null)return values[slot];
            if(size>=maximumEntries)return null;
            if(size+1>values.length*2L/3){grow();slot=slot(id);}
            var edge=new Edge(a,b,id);size++;keys[slot]=id;values[slot]=edge;return edge;
        }
        private void grow(){
            var oldKeys=keys;var oldValues=values;int capacity=values.length<<1;
            keys=new long[capacity];values=new Edge[capacity];
            for(int i=0;i<oldValues.length;i++)if(oldValues[i]!=null){
                int slot=slot(oldKeys[i]);keys[slot]=oldKeys[i];values[slot]=oldValues[i];
            }
        }
    }
    private static final class Leaf {
        final int face,depth;
        final Node a,b,c;
        final double childAreaBound;
        Edge ab,bc,ca;
        int liveIndex=-1,blockedSides;
        boolean measured,partialError;
        double heightError;
        Leaf(int face,Node a,Node b,Node c,int depth){
            this.face=face;this.a=a;this.b=b;this.c=c;this.depth=depth;
            double width=Math.abs(area(a.raw,b.raw,c.raw))*.5/MINIMUM_ALTITUDE;
            childAreaBound=width*width;
        }
        Edge edge(int side){return switch(side){case 0 -> ab;case 1 -> bc;default -> ca;};}
        Node opposite(Edge edge){return ab==edge?c:bc==edge?a:b;}
        boolean sampled(){return a.sampled(face)&&b.sampled(face)&&c.sampled(face);}
    }
    private record LineKey(boolean alongX,long fixed){}
    private record Segment(int face,Point a,Point b,boolean alongX,int sampleKey){
        double first(){return alongX?a.x:a.z;}
        double last(){return alongX?b.x:b.z;}
        double min(){return Math.min(first(),last());}
        double max(){return Math.max(first(),last());}
        Point at(double t){
            double fraction=(t-first())/(last()-first());
            return new Point(alongX?t:a.x,a.y+(b.y-a.y)*fraction,alongX?a.z:t);
        }
    }
    private static final class Line {
        final List<Segment> segments=new ArrayList<>();
        final TreeSet<Double> cuts=new TreeSet<>(),mandatory=new TreeSet<>();
        int ownedSegments;
        void mandatoryCut(double at){cuts.add(at);mandatory.add(at);}
    }
    /** Per-build exact keys over reusable thread-local scratch. Collisions only cause misses. */
    private static final class BoundaryMemo {
        private static final int SIZE=2048;
        final int[] segments=new int[SIZE];
        final long[] points=new long[SIZE];
        final double[] heights=new double[SIZE];
        boolean leased;
        void begin(){leased=true;Arrays.fill(segments,0);}
        int slot(int segment,long point){
            long mixed=point^((long)segment*0x9e3779b97f4a7c15L);
            mixed=(mixed^(mixed>>>30))*0xbf58476d1ce4e5b9L;
            mixed=(mixed^(mixed>>>27))*0x94d049bb133111ebL;
            return (int)(mixed^(mixed>>>31))&(SIZE-1);
        }
    }
    private static final ThreadLocal<BoundaryMemo> BOUNDARY_SCRATCH=ThreadLocal.withInitial(BoundaryMemo::new);
    private static final class Work implements MidpointSamples {
        final Builder source;
        final NodeTable points;
        final EdgeTable edges;
        final Map<LineKey,Line> lines=new LinkedHashMap<>();
        final List<AuthoredPoint> authored=new ArrayList<>();
        final AuthoredTable authoredIndex=new AuthoredTable(MAX_TABLE_CAPACITY*2L/3);
        double authoredMinX,authoredMaxX,authoredMinZ,authoredMaxZ;
        long authoredSequence,authoredDuplicates,authoredContextOutside;
        final List<Leaf> initial=new ArrayList<>(),live=new ArrayList<>();
        final ArrayDeque<Leaf> pending=new ArrayDeque<>();
        final ArrayDeque<Edge> promotion=new ArrayDeque<>();
        final List<Triangle> triangles=new ArrayList<>();
        final List<BoundarySegment> boundaries=new ArrayList<>();
        long work,samples,adaptiveSplits,closureSplits,propagationSplits,minimumStops,boundaryLimited;
        int maximumLevel,unmeasuredLive,insetSeedRoots,boundarySequence;
        long boundarySampleHits,midpointSamples,midpointSampleHits;
        private BoundaryMemo boundaryMemo;
        private Leaf probing;
        final Object budgetOwner=new Object();
        boolean reserveOutput;
        Work(Builder source){
            this.source=source;
            // The original work guard still owns topology limits. These per-build tables
            // add only the integer-array capacity guard, never an earlier refinement budget.
            points=new NodeTable(MAX_TABLE_CAPACITY*2L/3);
            edges=new EdgeTable(MAX_TABLE_CAPACITY*2L/3);
        }
        WorkLimitExceeded limit(String message){return new WorkLimitExceeded(budgetOwner,message);}
        static long outputReserve(long triangles,long unmeasured){return triangles+3*unmeasured;}
        void step(){
            // Every live triangle needs one emission. An unmeasured leaf may still need three samples.
            long reserve=reserveOutput?outputReserve(live.size(),unmeasuredLive):0;
            if(work+reserve>=source.maximumWork)throw limit("Conforming snow topology exceeded its explicit work guard");
            if((++work&255)==0)source.cancellation.run();
        }
        Node node(Point point){
            var node=points.intern(point);
            if(node==null)throw limit("Conforming snow node identities exceeded integer-array capacity");
            return node;
        }
        Edge edge(Node a,Node b){
            var edge=edges.intern(a,b);
            if(edge==null)throw limit("Conforming snow edge identities exceeded integer-array capacity");
            return edge;
        }
        Vertex sample(int face,Node point){
            if(point.first!=null&&point.firstFace==face)return point.first;
            if(point.otherFaces!=null){var known=point.otherFaces.get(face);if(known!=null)return known;}
            Point raw=point.raw;step();samples++;
            double y=point.boundary?source.sampler.boundaryHeight(face,raw.x,raw.y,raw.z):source.sampler.height(face,raw.x,raw.y,raw.z);
            if(!Double.isFinite(y))throw new IllegalArgumentException("Physical cap sampler returned a nonfinite height");
            var value=new Vertex(raw.x,y,raw.z,raw.y);
            if(point.first==null){point.firstFace=face;point.first=value;}
            else{if(point.otherFaces==null)point.otherFaces=new HashMap<>();point.otherFaces.put(face,value);}
            return value;
        }
        double boundarySample(Segment segment,double at){
            step();samples++;
            BoundaryMemo memo=boundaryMemo;
            if(memo==null)return measureBoundary(segment,at);
            long point=Double.doubleToRawLongBits(at);
            int slot=memo.slot(segment.sampleKey,point);
            if(memo.segments[slot]==segment.sampleKey&&memo.points[slot]==point){
                boundarySampleHits++;return memo.heights[slot];
            }
            double height=measureBoundary(segment,at);
            memo.segments[slot]=segment.sampleKey;memo.points[slot]=point;memo.heights[slot]=height;
            return height;
        }
        double measureBoundary(Segment segment,double at){
            double fraction=(at-segment.first())/(segment.last()-segment.first());
            double rawY=segment.a.y+(segment.b.y-segment.a.y)*fraction;
            double y=source.sampler.boundaryHeight(segment.face,segment.alongX?at:segment.a.x,rawY,segment.alongX?segment.a.z:at);
            if(!Double.isFinite(y))throw new IllegalArgumentException("Boundary sampler returned a nonfinite height");
            return y;
        }
        @Override public double height(int side){
            Leaf leaf=probing;
            if(leaf==null||side<0||side>2)throw new IllegalStateException("Midpoint probe outside an active refinement edge");
            Edge edge=leaf.edge(side);
            if(edge.midpointFace==leaf.face&&!Double.isNaN(edge.midpointHeight)){
                midpointSampleHits++;return edge.midpointHeight;
            }
            Point a=edge.a.raw,b=edge.b.raw;
            double height=source.sampler.midpointHeight(leaf.face,(a.x+b.x)*.5,(a.y+b.y)*.5,(a.z+b.z)*.5);
            if(!Double.isFinite(height))throw new IllegalArgumentException("Midpoint sampler returned a nonfinite height");
            midpointSamples++;
            // A shared edge between different physical faces may have different heights.
            if(Double.isNaN(edge.midpointHeight)){edge.midpointFace=leaf.face;edge.midpointHeight=height;}
            return height;
        }
        static Point[] vertices(Root root){return new Point[]{root.a,root.b,root.c};}
        static LineKey lineKey(Point a,Point b){
            if(a.z==b.z&&a.x!=b.x)return new LineKey(true,Double.doubleToLongBits(zero(a.z)));
            if(a.x==b.x&&a.z!=b.z)return new LineKey(false,Double.doubleToLongBits(zero(a.x)));
            throw new IllegalArgumentException("A physical owner perimeter must be axis aligned");
        }
        void prepareAuthoredBounds(){
            double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
            double shortest=Double.POSITIVE_INFINITY;boolean unlocked=false;
            for(var root:source.roots){
                step();var p=vertices(root);
                for(int side=0;side<3;side++)if((root.boundaryMask&(1<<side))==0){
                    Point a=p[side],b=p[(side+1)%3];double length=squared(a,b);
                    if(length<1e-24)continue; // parameter() rejects this edge as well.
                    unlocked=true;shortest=Math.min(shortest,length);
                    minX=Math.min(minX,Math.min(a.x,b.x));maxX=Math.max(maxX,Math.max(a.x,b.x));
                    minZ=Math.min(minZ,Math.min(a.z,b.z));maxZ=Math.max(maxZ,Math.max(a.z,b.z));
                }
            }
            if(unlocked){
                // parameter() admits a perpendicular distance EPS / edge length.
                // Outward rounding also preserves near-edge candidates at large world coordinates.
                double scale=Math.max(Math.max(Math.abs(minX),Math.abs(maxX)),Math.max(Math.abs(minZ),Math.abs(maxZ)));
                double padding=Double.isFinite(shortest)?EPS/Math.sqrt(shortest)+32*Math.ulp(scale):Double.POSITIVE_INFINITY;
                minX=Math.nextDown(minX-padding);maxX=Math.nextUp(maxX+padding);
                minZ=Math.nextDown(minZ-padding);maxZ=Math.nextUp(maxZ+padding);
            }
            authoredMinX=minX;authoredMaxX=maxX;authoredMinZ=minZ;authoredMaxZ=maxZ;
        }
        void registerAuthored(Point point,boolean owned){
            long sequence=++authoredSequence;
            if(!owned&&(point.x<authoredMinX||point.x>authoredMaxX||point.z<authoredMinZ||point.z>authoredMaxZ)){
                authoredContextOutside++;return;
            }
            // Only exact duplicates are removed; signed zero and nearby authored planes remain distinct.
            AuthoredPoint known=authoredIndex.intern(point,sequence);
            if(known==null)throw limit("Conforming snow authored identities exceeded integer-array capacity");
            if(known.last!=sequence){known.last=sequence;authoredDuplicates++;return;}
            authored.add(known);
        }
        void register(Root root,boolean owned){
            step();var p=vertices(root);
            // Filter only interior partition candidates, never the halo boundary segments below.
            for(var point:p)registerAuthored(point,owned);
            for(int side=0;side<3;side++)if((root.boundaryMask&(1<<side))!=0){
                Point a=p[side],b=p[(side+1)%3];var key=lineKey(a,b);
                var segment=new Segment(root.faceId,a,b,key.alongX,++boundarySequence);
                if(owned)registerOwned(lines.computeIfAbsent(key,ignored->new Line()),segment);
                else{
                    var line=lines.get(key);
                    if(line!=null)registerContext(line,segment);
                }
            }
        }
        void registerOwned(Line line,Segment segment){
            line.segments.add(segment);line.ownedSegments++;
            line.mandatoryCut(segment.min());line.mandatoryCut(segment.max());
            // Integer multiples are exact binary world coordinates, including negative cells.
            double start=Math.ceil(segment.min()/BOUNDARY_STEP)*BOUNDARY_STEP;
            long count=(long)Math.ceil((segment.max()-start)/BOUNDARY_STEP);
            if(count>source.maximumWork)throw limit("Conforming snow boundary exceeded its explicit work guard");
            for(long i=0;i<=count;i++){step();double at=start+i*BOUNDARY_STEP;if(at>segment.min()&&at<segment.max())line.cuts.add(at);}
        }
        void registerContext(Line line,Segment segment){
            double min=segment.min(),max=segment.max();boolean overlaps=false;
            for(int i=0;i<line.ownedSegments;i++){
                step();var owner=line.segments.get(i);double low=owner.min(),high=owner.max();
                if(min>=high||max<=low)continue;
                overlaps=true;
                // The owner already provides every regular grid cut on the overlap.
                // The halo only adds authored endpoints and its sampling field.
                if(min>low)line.mandatoryCut(min);
                if(max<high)line.mandatoryCut(max);
            }
            if(overlaps)line.segments.add(segment);
        }
        boolean ownedInterval(Line line,double a,double b){
            for(int i=0;i<line.ownedSegments;i++){
                step();var owner=line.segments.get(i);
                if(a>=owner.min()&&b<=owner.max())return true;
            }
            return false;
        }
        void refineBoundary(Line line,double a,double b,int depth){
            if(b-a<=EPS)return;step();boolean refine=false;
            for(var segment:line.segments){
                step();double mid=a+(b-a)*.5;if(mid<segment.min()-EPS||mid>segment.max()+EPS)continue;
                double ya=boundarySample(segment,a),yb=boundarySample(segment,b);
                for(int probe=1;probe<=3;probe++){
                    double t=probe*.25,actual=boundarySample(segment,a+(b-a)*t);
                    if(Math.abs(actual-(ya+(yb-ya)*t))>BOUNDARY_ERROR){refine=true;break;}
                }
                if(refine)break;
            }
            if(!refine)return;
            if(b-a<=MINIMUM_EDGE+EPS||depth>=12){boundaryLimited++;return;}
            double middle=a+(b-a)*.5;line.cuts.add(middle);
            refineBoundary(line,a,middle,depth+1);refineBoundary(line,middle,b,depth+1);
        }
        boolean collinear(Line line,double a,double b,double c){
            for(var segment:line.segments){
                step();if(b<segment.min()-EPS||b>segment.max()+EPS)continue;
                double ya=boundarySample(segment,a),yb=boundarySample(segment,b),yc=boundarySample(segment,c);
                if(Math.abs(yb-(ya+(yc-ya)*((b-a)/(c-a))))>EPS)return false;
            }
            return true;
        }
        void reduceStraightSegments(Line line){
            // Removing a collinear point does not alter the sampled boundary polyline.
            // Never remove authored/generic endpoints, even on a completely flat cap.
            var reduced=new ArrayList<Double>();
            for(double at:line.cuts){
                reduced.add(at);
                while(reduced.size()>=3){
                    int last=reduced.size()-1;double b=reduced.get(last-1);
                    if(line.mandatory.contains(b)||!collinear(line,reduced.get(last-2),b,reduced.get(last)))break;
                    reduced.remove(last-1);
                }
            }
            line.cuts.clear();line.cuts.addAll(reduced);
        }
        void prepareLines(){
            prepareAuthoredBounds();
            for(var root:source.roots)register(root,true);
            for(var root:source.context)register(root,false);
            for(var entry:lines.entrySet()){
                var key=entry.getKey();var line=entry.getValue();double fixed=Double.longBitsToDouble(key.fixed);
                for(var point:source.cuts){
                    step();double cross=key.alongX?point.z:point.x,at=key.alongX?point.x:point.z;
                    if(Math.abs(cross-fixed)<=EPS)for(int i=0;i<line.ownedSegments;i++){
                        step();var segment=line.segments.get(i);
                        if(at>segment.min()&&at<segment.max()){line.mandatoryCut(at);break;}
                    }
                }
                var coarse=new ArrayList<>(line.cuts);
                for(int i=1;i<coarse.size();i++){
                    double a=coarse.get(i-1),b=coarse.get(i);
                    if(ownedInterval(line,a,b))refineBoundary(line,a,b,0);
                }
                reduceStraightSegments(line);
            }
        }
        static double parameter(Point a,Point b,Point point){
            double dx=b.x-a.x,dz=b.z-a.z,length=dx*dx+dz*dz;
            if(length<1e-24)return Double.NaN;
            double t=((point.x-a.x)*dx+(point.z-a.z)*dz)/length;
            if(t<=EPS||t>=1-EPS||Math.abs((point.x-a.x)*dz-(point.z-a.z)*dx)>EPS)return Double.NaN;
            return Math.abs(point.y-(a.y+(b.y-a.y)*t))<=EPS?t:Double.NaN;
        }
        List<Node> path(Root root,int side){
            var p=vertices(root);Point a=p[side],b=p[(side+1)%3];boolean locked=(root.boundaryMask&(1<<side))!=0;
            var sorted=new java.util.TreeMap<Double,Point>();sorted.put(0.0,a);sorted.put(1.0,b);
            if(locked){
                var key=lineKey(a,b);var segment=new Segment(root.faceId,a,b,key.alongX,0);var line=lines.get(key);
                for(double at:line.cuts.subSet(segment.min(),false,segment.max(),false)){
                    if(at<=segment.min()||at>=segment.max())continue;
                    step();double t=(at-segment.first())/(segment.last()-segment.first());sorted.put(t,segment.at(at));
                }
            }else{
                Map<Double,AuthoredPoint> winners=null;
                for(var candidate:authored){
                    step();double t=parameter(a,b,candidate.point);
                    if(!Double.isFinite(t))continue;
                    if(winners==null)winners=new HashMap<>();
                    AuthoredPoint previous=winners.get(t);
                    // Distinct EPS-close points can share t. Match the original last occurrence
                    // without restoring duplicate scans or changing first-seen point identity.
                    if(previous==null||candidate.last>previous.last){
                        winners.put(t,candidate);sorted.put(t,candidate.point);
                    }
                }
            }
            var result=new ArrayList<Node>(sorted.size());for(var point:sorted.values()){var n=node(point);if(locked)n.boundary=true;result.add(n);}
            for(int i=1;i<result.size();i++)if(locked)edge(result.get(i-1),result.get(i)).locked=true;
            return result;
        }
        private record SeedPerimeter(Root root,List<Node> perimeter,List<List<Node>> sides) {}
        void prepareRoots(){
            List<SeedPerimeter> seeds=source.insetSeedGrid?new ArrayList<>():null;
            for(int index=0;index<source.roots.size();index++){
                step();var root=source.roots.get(index);var perimeter=new ArrayList<Node>();
                List<List<Node>> sides=seeds==null?null:new ArrayList<>(3);
                for(int side=0;side<3;side++){
                    var path=path(root,side);for(int i=0;i<path.size()-1;i++)perimeter.add(path.get(i));
                    if(sides!=null)sides.add(path);
                    if((root.boundaryMask&(1<<side))!=0)for(int i=1;i<path.size();i++)
                        boundaries.add(new BoundarySegment(index,side,root.faceId,sample(root.faceId,path.get(i-1)),sample(root.faceId,path.get(i))));
                }
                if(seeds==null)addFan(root,perimeter);
                else seeds.add(new SeedPerimeter(root,perimeter,sides));
            }
            if(seeds==null)return;
            // Keep room for the baseline cap, including one visit, one emission and
            // up to three samples per leaf. The optional seed spends only extra room.
            long headroom=source.maximumWork-work;
            for(var seed:seeds)headroom-=5L*fanLeaves(seed.perimeter.size());
            for(var seed:seeds){
                int size=seed.perimeter.size(),divisions=seedDivisions(seed.root,size);
                if(divisions==0){addFan(seed.root,seed.perimeter);continue;}
                int addedLeaves=divisions*(divisions+3);
                int gridNodes=(divisions+1)*(divisions+2)/2;
                long seedCost=gridNodes+size+6L*addedLeaves;
                if(seedCost>=headroom){addFan(seed.root,seed.perimeter);continue;}
                headroom-=seedCost;
                addInsetGrid(seed,divisions);
            }
        }
        private static int fanLeaves(int perimeter){return perimeter==3?1:perimeter;}
        private static Point centre(Root root){
            return new Point((root.a.x+root.b.x+root.c.x)/3,(root.a.y+root.b.y+root.c.y)/3,(root.a.z+root.b.z+root.c.z)/3);
        }
        private void addFan(Root root,List<Node> perimeter){
            if(perimeter.size()==3){
                initial.add(new Leaf(root.faceId,perimeter.get(0),perimeter.get(1),perimeter.get(2),0));
                return;
            }
            var middle=node(centre(root));
            for(int i=0;i<perimeter.size();i++)
                initial.add(new Leaf(root.faceId,middle,perimeter.get(i),perimeter.get((i+1)%perimeter.size()),0));
        }
        private static int seedDivisions(Root root,int perimeterSize){
            if(perimeterSize<12)return 0;
            double longest=Math.max(squared(root.a,root.b),Math.max(squared(root.b,root.c),squared(root.c,root.a)));
            if(!Double.isFinite(longest)||longest<.25||Math.abs(area(root.a,root.b,root.c))<longest*.125)return 0;
            // The inner triangle spans three quarters of the original.
            return longest<=4.0/9?2:4;
        }
        private static Point inset(Point vertex,Point centre){
            return new Point(vertex.x+(centre.x-vertex.x)*.25,vertex.y+(centre.y-vertex.y)*.25,vertex.z+(centre.z-vertex.z)*.25);
        }
        private void addInsetGrid(SeedPerimeter seed,int n){
            var root=seed.root;var middle=centre(root);
            var a=inset(root.a,middle);var b=inset(root.b,middle);var c=inset(root.c,middle);
            var grid=new Node[n+1][];
            for(int i=0;i<=n;i++){
                grid[i]=new Node[n+1-i];
                for(int j=0;j<=n-i;j++){
                    step();double u=(double)i/n,v=(double)j/n;
                    grid[i][j]=node(new Point(a.x+(b.x-a.x)*u+(c.x-a.x)*v,
                            a.y+(b.y-a.y)*u+(c.y-a.y)*v,a.z+(b.z-a.z)*u+(c.z-a.z)*v));
                }
            }
            for(int i=0;i<n;i++)for(int j=0;j<n-i;j++){
                addSeedLeaf(root.faceId,grid[i][j],grid[i+1][j],grid[i][j+1]);
                if(i+j<n-1)addSeedLeaf(root.faceId,grid[i+1][j],grid[i+1][j+1],grid[i][j+1]);
            }
            for(int side=0;side<3;side++){
                var outside=seed.sides.get(side);int outer=0,inner=0;
                while(outer<outside.size()-1||inner<n){
                    Node fromOutside=outside.get(outer),fromInside=seedEdge(grid,side,inner,n);
                    // Both chains keep their authored winding. Take the shorter next
                    // diagonal while preserving every original perimeter segment.
                    if(inner==n||outer<outside.size()-1&&squared(outside.get(outer+1),fromInside)
                            <=squared(fromOutside,seedEdge(grid,side,inner+1,n))){
                        addSeedLeaf(root.faceId,fromOutside,outside.get(++outer),fromInside);
                    }else{
                        addSeedLeaf(root.faceId,fromOutside,seedEdge(grid,side,++inner,n),fromInside);
                    }
                }
            }
            insetSeedRoots++;
        }
        private void addSeedLeaf(int face,Node a,Node b,Node c){
            step();initial.add(new Leaf(face,a,b,c,0));
        }
        private static Node seedEdge(Node[][] grid,int side,int index,int n){
            return switch(side){case 0->grid[index][0];case 1->grid[n-index][index];default->grid[0][n-index];};
        }
        static double squared(Point a,Point b){double x=a.x-b.x,z=a.z-b.z;return x*x+z*z;}
        static double squared(Node a,Node b){double x=a.raw.x-b.raw.x,z=a.raw.z-b.raw.z;return x*x+z*z;}
        static int level(Node a,Node b,Node c){
            double maximum=Math.sqrt(Math.max(squared(a,b),Math.max(squared(b,c),squared(c,a))));
            return Math.clamp(-Math.getExponent(maximum),0,9);
        }
        void activate(Leaf t){activate(t,edge(t.a,t.b),edge(t.b,t.c),edge(t.c,t.a));}
        void activate(Leaf t,Edge ab,Edge bc,Edge ca){
            t.ab=ab;t.bc=bc;t.ca=ca;
            for(int side=0;side<3;side++){
                var edge=t.edge(side);
                if(!edge.locked&&edge.lengthSquared>MINIMUM_EDGE*MINIMUM_EDGE+1e-20&&!childAltitude(t,edge))
                    t.blockedSides|=1<<side;
                edge.attach(t,side);
            }
            if(reserveOutput){
                t.measured=t.sampled();
                if(!t.measured)unmeasuredLive++;
            }
            t.liveIndex=live.size();live.add(t);pending.addLast(t);
        }
        void deactivate(Leaf t,Edge split){
            if(reserveOutput&&!t.measured)unmeasuredLive--;
            if(t.ab!=split)t.ab.detach(t,0);
            if(t.bc!=split)t.bc.detach(t,1);
            if(t.ca!=split)t.ca.detach(t,2);
            var last=live.removeLast();
            if(last!=t){live.set(t.liveIndex,last);last.liveIndex=t.liveIndex;}
            t.liveIndex=-1;
        }
        static boolean childAltitude(Leaf t,Edge edge){
            if(t.depth>=40)return false;
            Node opposite=t.opposite(edge);
            double dx=opposite.raw.x-(edge.a.raw.x+edge.b.raw.x)*.5;
            double dz=opposite.raw.z-(edge.a.raw.z+edge.b.raw.z)*.5;
            double maximum=Math.max(Math.max(squared(opposite,edge.a),squared(opposite,edge.b)),
                    Math.max(edge.lengthSquared*.25,dx*dx+dz*dz));
            return maximum<=t.childAreaBound;
        }
        static boolean canSplit(Edge edge){
            return !edge.locked&&edge.lengthSquared>MINIMUM_EDGE*MINIMUM_EDGE+1e-20
                    &&edge.incidents>0&&edge.blockedIncidents==0;
        }
        static boolean higher(Edge candidate,Edge best){
            return best==null||candidate.lengthSquared>best.lengthSquared
                    ||candidate.lengthSquared==best.lengthSquared&&candidate.id>best.id;
        }
        static Edge longest(Leaf leaf,Edge best){
            if(leaf==null)return best;
            for(int i=0;i<3;i++){
                var candidate=leaf.edge(i);
                if(higher(candidate,best)&&canSplit(candidate))best=candidate;
            }
            return best;
        }
        void splitLeaf(Leaf t,Edge split,Node middle,Edge firstHalf,Edge secondHalf){
            int depth=t.depth+1;
            deactivate(t,split);
            // The two shared halves and the untouched sides already have exact edge identities.
            if(t.ab==split){
                Edge am=split.a==t.a?firstHalf:secondHalf,bm=split.a==t.a?secondHalf:firstHalf,mc=edge(middle,t.c);
                activate(new Leaf(t.face,t.a,middle,t.c,depth),am,mc,t.ca);
                activate(new Leaf(t.face,middle,t.b,t.c,depth),bm,t.bc,mc);
            }else if(t.bc==split){
                Edge bm=split.a==t.b?firstHalf:secondHalf,cm=split.a==t.b?secondHalf:firstHalf,ma=edge(middle,t.a);
                activate(new Leaf(t.face,t.a,t.b,middle,depth),t.ab,bm,ma);
                activate(new Leaf(t.face,t.a,middle,t.c,depth),ma,cm,t.ca);
            }else{
                Edge cm=split.a==t.c?firstHalf:secondHalf,am=split.a==t.c?secondHalf:firstHalf,mb=edge(middle,t.b);
                activate(new Leaf(t.face,t.a,t.b,middle,depth),t.ab,mb,am);
                activate(new Leaf(t.face,middle,t.b,t.c,depth),mb,t.bc,cm);
            }
        }
        static int unmeasuredGrowth(Leaf parent,Edge split,Node middle){
            if(parent==null)return 0;
            int face=parent.face;
            int children=2;
            if(middle.sampled(face)&&parent.opposite(split).sampled(face))
                children=(split.a.sampled(face)?0:1)+(split.b.sampled(face)?0:1);
            return children-(parent.measured?0:1);
        }
        void splitEdge(Edge split){
            var first=split.first;var second=split.second;var additional=split.additional;
            var middle=node(new Point((split.a.raw.x+split.b.raw.x)*.5,(split.a.raw.y+split.b.raw.y)*.5,
                    (split.a.raw.z+split.b.raw.z)*.5));
            if(reserveOutput){
                long unmeasured=(long)unmeasuredLive+unmeasuredGrowth(first,split,middle)+unmeasuredGrowth(second,split,middle);
                if(additional!=null)for(var leaf:additional)unmeasured+=unmeasuredGrowth(leaf,split,middle);
                if(work+outputReserve((long)live.size()+split.incidents,unmeasured)>source.maximumWork)
                    throw limit("Conforming snow topology exceeded its explicit work guard");
            }
            split.first=split.second=null;split.additional=null;
            split.incidents=split.blockedIncidents=0;
            var firstHalf=edge(split.a,middle);var secondHalf=edge(middle,split.b);
            // Every incident leaf is replaced in one operation. Until it is complete,
            // no sampling, cancellation, or emission observes an unmatched edge.
            if(first!=null)splitLeaf(first,split,middle,firstHalf,secondHalf);
            if(second!=null){splitLeaf(second,split,middle,firstHalf,secondHalf);if(first!=null)closureSplits++;}
            if(additional!=null)for(var leaf:additional){splitLeaf(leaf,split,middle,firstHalf,secondHalf);closureSplits++;}
        }
        boolean splitConforming(Edge target){
            promotion.push(target);boolean targetSplit=false;
            while(!promotion.isEmpty()){
                step();var edge=promotion.peek();
                if(!canSplit(edge)){promotion.pop();continue;}
                Edge higher=longest(edge.first,edge);
                higher=longest(edge.second,higher);
                if(edge.additional!=null)for(var leaf:edge.additional)higher=longest(leaf,higher);
                if(higher!=edge){
                    // Each promotion strictly increases (length, ID). Equal lengths
                    // therefore cannot form a cycle, and the counted work guard bounds the path.
                    promotion.push(higher);continue;
                }
                splitEdge(edge);promotion.pop();
                if(edge==target)targetSplit=true;else propagationSplits++;
            }
            return targetSplit;
        }
        double heightError(Leaf t,Vertex a,Vertex b,Vertex c,int level,double stopAbove){
            double error;
            probing=t;
            try{error=source.heightError.error(t.face,t.a.raw,t.b.raw,t.c.raw,a.y,b.y,c.y,level,stopAbove,this);}
            finally{probing=null;}
            if(!Double.isFinite(error)||error<0)throw new IllegalArgumentException("Nonfinite or negative snow height error");
            return error;
        }
        void refine(Leaf t){
            var a=sample(t.face,t.a);var b=sample(t.face,t.b);var c=sample(t.face,t.c);
            if(reserveOutput&&!t.measured){t.measured=true;unmeasuredLive--;}
            int level=level(t.a,t.b,t.c);
            if(source.heightError==null){
                boolean refine;
                probing=t;
                try{refine=source.refinement.refine(t.face,t.a.raw,t.b.raw,t.c.raw,a.y,b.y,c.y,level,this);}
                finally{probing=null;}
                if(!refine)return;
            }else{
                double tolerance=source.heightTolerance.tolerance(t.face,t.a.raw,t.b.raw,t.c.raw,a.y,b.y,c.y,level);
                if(!Double.isFinite(tolerance)||tolerance<0)throw new IllegalArgumentException("Nonfinite or negative snow height tolerance");
                t.heightError=heightError(t,a,b,c,level,tolerance);
                t.partialError=t.heightError>tolerance;
                if(!t.partialError)return;
            }
            var edge=longest(t,null);
            if(edge!=null&&splitConforming(edge))adaptiveSplits++;else minimumStops++;
        }
        Result build(){
            source.cancellation.run();
            if(source.cacheBoundarySamples){
                BoundaryMemo memo=BOUNDARY_SCRATCH.get();
                // A sampler can invoke another builder on the same thread.
                if(memo.leased)memo=new BoundaryMemo();
                memo.begin();boundaryMemo=memo;
                try{prepareLines();}
                finally{boundaryMemo=null;memo.leased=false;}
            }else prepareLines();
            prepareRoots();
            reserveOutput=source.approximateOnWorkLimit;
            for(var triangle:initial)activate(triangle);
            initial.clear();
            boolean budgetLimited=false;
            if(reserveOutput&&work+outputReserve(live.size(),unmeasuredLive)>source.maximumWork)
                throw limit("Conforming snow authored cap exceeded its explicit work guard");
            try{
                while(!pending.isEmpty()){
                    step();var triangle=pending.removeFirst();
                    if(triangle.liveIndex>=0)refine(triangle);
                }
            }catch(WorkLimitExceeded exhausted){
                if(!reserveOutput||exhausted.owner!=budgetOwner)throw exhausted;
                budgetLimited=true;source.cancellation.run();
                pending.clear();promotion.clear();
            }finally{reserveOutput=false;}
            boolean reportErrors=!budgetLimited&&source.heightError!=null;
            double maximumError=reportErrors?0:Double.NaN;
            for(var t:live){
                step();
                int level=level(t.a,t.b,t.c);maximumLevel=Math.max(maximumLevel,level);
                var a=sample(t.face,t.a);var b=sample(t.face,t.b);var c=sample(t.face,t.c);
                if(reportErrors){
                    // Rejected candidates may have stopped at the first failed probe.
                    // Only surviving, topology-limited leaves need a complete measurement.
                    if(t.partialError)t.heightError=heightError(t,a,b,c,level,Double.POSITIVE_INFINITY);
                    maximumError=Math.max(maximumError,t.heightError);
                }
                if(source.triangleSink==null)triangles.add(new Triangle(t.face,a,b,c,level));
                else source.triangleSink.accept(t.face,a,b,c,level);
            }
            if(reportErrors&&source.terminalError!=null)
                for(var t:live)source.terminalError.accept(t.heightError);
            return new Result(List.copyOf(triangles),List.copyOf(boundaries),Map.ofEntries(
                    Map.entry("roots",(long)source.roots.size()),Map.entry("triangles",(long)live.size()),
                    Map.entry("authoredVertices",(long)authored.size()),Map.entry("authoredDuplicates",authoredDuplicates),
                    Map.entry("authoredContextOutside",authoredContextOutside),
                    Map.entry("boundarySegments",(long)boundaries.size()),Map.entry("insetSeedRoots",(long)insetSeedRoots),
                    Map.entry("adaptiveSplits",adaptiveSplits),
                    Map.entry("closureSplits",closureSplits),Map.entry("propagationSplits",propagationSplits),
                    Map.entry("minimumSpacingStops",minimumStops),Map.entry("boundaryLimited",boundaryLimited),
                    Map.entry("maximumLevel",(long)maximumLevel),Map.entry("samples",samples),Map.entry("work",work),
                    Map.entry("boundarySampleHits",boundarySampleHits),Map.entry("midpointSamples",midpointSamples),
                    Map.entry("midpointSampleHits",midpointSampleHits),
                    Map.entry("budgetLimited",budgetLimited?1L:0L),
                    Map.entry("maximumLeafErrorBits",Double.doubleToRawLongBits(maximumError))));
        }
    }
}
