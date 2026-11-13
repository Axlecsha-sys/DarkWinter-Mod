// Active-world weather integral. Host ticks once; loaded and unloaded surfaces replay the same phases.
package com.winterkore.engine.surface;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Thread-confined simulation with immutable marks. Closed history is paged, never silently discarded. */
public final class SnowWeatherTimeline {
    public static final int DIRECTIONS=8, PAGE_PHASES=64, CACHE_PAGES=4;
    interface Archive { void write(long first,List<Phase> phases)throws IOException; List<Phase> read(long first)throws IOException; }
    static final class Phase {
        final long id,startTick,endTick;
        final boolean melting;
        final double vertical,melt;
        final double[] snow,wind;
        Phase(long id,long startTick,long endTick,boolean melting,double vertical,double melt,double[] snow,double[] wind) {
            this.id=id;this.startTick=startTick;this.endTick=endTick;this.melting=melting;
            this.vertical=vertical;this.melt=melt;this.snow=snow;this.wind=wind;
        }
        Mark mark(long epoch){return new Mark(epoch,id,endTick,vertical,melt,snow,wind);}
    }
    /** A small immutable cursor; all columns sampled together can share it. */
    public static final class Mark {
        private final long epoch,phase,tick;
        private final double vertical,melt;
        private final double[] snow,wind;
        Mark(long epoch,long phase,long tick,double vertical,double melt,double[] snow,double[] wind) {
            if(epoch==0||phase<0||tick<0||!valid(vertical)||!valid(melt)||snow.length!=DIRECTIONS||wind.length!=DIRECTIONS)
                throw new IllegalArgumentException("Invalid snow weather cursor");
            this.epoch=epoch;this.phase=phase;this.tick=tick;this.vertical=vertical;this.melt=melt;
            this.snow=snow.clone();this.wind=wind.clone();
            for(int i=0;i<DIRECTIONS;i++)if(!valid(this.snow[i])||!valid(this.wind[i]))throw new IllegalArgumentException("Invalid snow weather integral");
        }
        public long epoch(){return epoch;} public long phase(){return phase;} public long tick(){return tick;}
        public double verticalSnowMillimeters(){return vertical;} public double meltMillimeters(){return melt;}
        public double snowMillimeters(int direction){return snow[sector(direction)];}
        public double windFillMillimeters(int direction){return wind[sector(direction)];}
    }
    public record Result(double additionalDepthMillimeters,double snowfallMillimeters,double meltMillimeters,double windFillMillimeters) {}
    /** Rates are per active real hour. Dry-phase losses remain additive and independent of visit frequency. */
    public record Dynamics(double capMillimeters,double restingFloorMillimeters,double settlingMmPerHour,
                           double windErosionScale,double legacyPackingFraction) {
        public Dynamics {
            if(!valid(capMillimeters)||capMillimeters>1500||!valid(restingFloorMillimeters)||!valid(settlingMmPerHour)
                    ||!valid(windErosionScale)||!Double.isFinite(legacyPackingFraction)||legacyPackingFraction<0||legacyPackingFraction>1)
                throw new IllegalArgumentException("Invalid snow dynamics");
        }
    }
    public record Evolution(double depthMillimeters,double compactedMillimeters,double settlementBudget,
                            double pathCompactionFraction,double snowfallMillimeters,double windLossMillimeters,long elapsedTicks) {}

    public static final class Snapshot {
        private final SnowWeatherTimeline owner;
        private final Mark mark;
        Snapshot(SnowWeatherTimeline owner,Mark mark){this.owner=owner;this.mark=mark;}
        public Mark mark(){return mark;}
        public long tick(){return mark.tick();}
        public long epoch(){return mark.epoch();}
        public long phase(){return mark.phase();}
        /** Keeps freeze/thaw order: a warm period cannot melt snow falling in a later period. */
        public Result integrate(Mark from,double initialAdditional,double capacity,float verticalExposure,float[] directionalExposure) {
            exposure(verticalExposure,directionalExposure);
            if(!Double.isFinite(initialAdditional)||!Double.isFinite(capacity)||initialAdditional<0||capacity<0)
                throw new IllegalArgumentException("Invalid snow capacity");
            if(from==null)from=owner.origin();
            if(from.epoch!=mark.epoch||from.phase>mark.phase||from.tick>mark.tick)
                throw new IllegalArgumentException("Snow cursor is from another timeline or the future");
            double value=Math.clamp(initialAdditional,0,capacity),snowTotal=0,meltTotal=0,windTotal=0;
            for(long id=from.phase;id<=mark.phase;id++) {
                Mark end=id==mark.phase?mark:owner.phase(id).mark(mark.epoch);
                Mark start=id==from.phase?from:null;
                double snow=Math.max(0,end.vertical-(start==null?0:start.vertical))*verticalExposure;
                double wind=0;
                for(int i=0;i<DIRECTIONS;i++) {
                    snow+=Math.max(0,end.snow[i]-(start==null?0:start.snow[i]))*directionalExposure[i];
                    wind+=Math.max(0,end.wind[i]-(start==null?0:start.wind[i]))*directionalExposure[i];
                }
                double melt=Math.max(0,end.melt-(start==null?0:start.melt));
                value=Math.clamp(value+snow-melt,0,capacity);
                snowTotal+=snow;meltTotal+=melt;windTotal+=wind;
                if(id==Long.MAX_VALUE)break;
            }
            return new Result(value,snowTotal,meltTotal,windTotal);
        }
        /** Mass and settling potential are separate: packing never destroys mass or doubles new snowfall. */
        public Evolution evolve(Mark from,double initialDepth,double initialCompacted,double settlementBudget,
                double pathCompaction,double authoredBase,Dynamics dynamics,float verticalExposure,float[] directionalExposure) {
            exposure(verticalExposure,directionalExposure);
            if(!Double.isFinite(initialDepth+initialCompacted+settlementBudget+pathCompaction+authoredBase)
                    ||initialDepth<0||initialCompacted<0||initialCompacted>initialDepth||settlementBudget<initialCompacted
                    ||pathCompaction<0||pathCompaction>1||authoredBase<0||authoredBase>initialDepth)
                throw new IllegalArgumentException("Invalid physical snow state");
            if(from==null)from=owner.origin();
            if(from.epoch!=mark.epoch||from.phase>mark.phase||from.tick>mark.tick)
                throw new IllegalArgumentException("Snow cursor is from another timeline or the future");
            double mass=initialDepth,packed=initialCompacted,budget=settlementBudget,path=pathCompaction,snowTotal=0,windLost=0;
            long elapsed=0;
            for(long id=from.phase;id<=mark.phase;id++){
                Phase phase=id==owner.active.id?owner.active:owner.phase(id);
                Mark end=id==mark.phase?mark:phase.mark(mark.epoch),start=id==from.phase?from:null;
                long ticks=Math.max(0,end.tick-Math.max(phase.startTick,start==null?phase.startTick:start.tick));
                double hours=ticks/72000.0,snow=Math.max(0,end.vertical-(start==null?0:start.vertical))*verticalExposure,wind=0;
                for(int i=0;i<DIRECTIONS;i++){
                    snow+=Math.max(0,end.snow[i]-(start==null?0:start.snow[i]))*directionalExposure[i];
                    wind+=Math.max(0,end.wind[i]-(start==null?0:start.wind[i]))*directionalExposure[i];
                }
                double melt=Math.max(0,end.melt-(start==null?0:start.melt)),before=mass;
                if(ticks==0&&snow==0&&wind==0&&melt==0)continue;
                boolean snowing=phase.vertical>0;
                for(double amount:phase.snow)snowing|=amount>0;
                if(snowing){
                    // A new blanket lies above the already settled snow; no hidden settling budget compacts it instantly.
                    budget=packed;
                    double capacity=Math.max(mass,Math.min(SnowSurface.MAX_MASS_MM,dynamics.capMillimeters+packed));
                    mass=Math.min(capacity,mass+snow);
                    if(mass>before&&mass>0)path*=before/mass;
                }else{
                    double windRequested=wind*dynamics.windErosionScale,totalLoss=windRequested+melt;
                    double applied=Math.min(Math.max(0,mass-authoredBase),totalLoss);
                    // Melt and erosion act over the same interval, including when they exhaust the loose blanket.
                    mass=Math.max(authoredBase,mass-applied);
                    if(totalLoss>0)windLost+=applied*(windRequested/totalLoss);
                    budget=Math.min(SnowSurface.MAX_MASS_MM,budget+dynamics.settlingMmPerHour*hours);
                    double target=Math.max(mass*.15,mass-dynamics.restingFloorMillimeters);
                    double maximum=Math.max(.60,dynamics.legacyPackingFraction)*mass;
                    target=Math.min(maximum,Math.max(target,dynamics.legacyPackingFraction*mass));
                    packed=Math.min(budget,Math.max(0,target));
                }
                path*=Math.exp(-hours/12.0);snowTotal+=snow;elapsed+=ticks;
                if(id==Long.MAX_VALUE)break;
            }
            return new Evolution(mass,Math.min(mass,packed),Math.max(budget,packed),Math.clamp(path,0,1),snowTotal,windLost,elapsed);
        }
    }
    final long epoch;
    long tick,archivedCount;
    private long hostTick;
    Phase active;
    final ArrayList<Phase> hotClosed=new ArrayList<>(PAGE_PHASES);
    private Archive archive;
    private Snapshot snapshot;
    private final LinkedHashMap<Long,List<Phase>> pageCache=new LinkedHashMap<>(CACHE_PAGES,.75f,true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long,List<Phase>> entry){return size()>CACHE_PAGES;}
    };
    public SnowWeatherTimeline(long tick) {
        if(tick<0)throw new IllegalArgumentException("Negative active tick");
        epoch=ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE);this.tick=tick;this.hostTick=tick;
        active=new Phase(0,tick,tick,false,0,0,new double[DIRECTIONS],new double[DIRECTIONS]);
    }
    SnowWeatherTimeline(long epoch,long tick,long archivedCount,List<Phase> closed,Phase active) {
        this.epoch=epoch;this.tick=tick;this.hostTick=tick;this.archivedCount=archivedCount;this.hotClosed.addAll(closed);this.active=active;
    }
    void attach(Archive archive){this.archive=archive;}
    public long epoch(){return epoch;} public long tick(){return tick;}
    public int residentClosedPhases(){return hotClosed.size();}
    public int cachedArchivePages(){return pageCache.size();}
    public long archivedPhases(){return archivedCount;}
    Mark origin(){return new Mark(epoch,0,0,0,0,new double[DIRECTIONS],new double[DIRECTIONS]);}
    /** Rebase only the legacy host clock on open. Offline wall time never advances weather. */
    public void resumeAt(long currentTick) {
        if(currentTick<0)throw new IllegalArgumentException("Negative active tick");
        hostTick=currentTick; // Logical weather time and saved cursors remain monotonic across host-clock rebases.
    }
    public void advance(long currentTick,SnowSurface.Weather weather) {
        if(currentTick<hostTick)throw new IllegalArgumentException("Active weather host clock moved backwards");
        if(currentTick==hostTick)return;
        long elapsed=currentTick-hostTick,nextTick=Math.addExact(tick,elapsed);
        double hours=elapsed/72000.0;
        boolean melting=weather.temperatureC()>0;
        boolean snowing=!melting&&weather.snowfall()>0,activeSnowing=active.vertical>0;
        for(double amount:active.snow)activeSnowing|=amount>0;
        // Dry and precipitating intervals cannot be collapsed: only dry intervals settle/erode snow.
        if(melting!=active.melting||snowing!=activeSnowing) {
            if(hotClosed.size()==PAGE_PHASES)flushPage();
            hotClosed.add(active);
            active=new Phase(active.id+1,tick,tick,melting,0,0,new double[DIRECTIONS],new double[DIRECTIONS]);
        }
        double[] snow=active.snow.clone(),wind=active.wind.clone();
        double precipitation=melting?0:weather.physicalSnowMmPerActiveHour()*hours;
        double windFraction=Math.clamp(weather.windMetersPerSecond()/18.0,0,.85);
        int direction=direction(weather.windFromDegrees());
        snow[direction]+=precipitation*windFraction;
        wind[direction]+=Math.max(0,weather.windMetersPerSecond()-3)*2*hours;
        active=new Phase(active.id,active.startTick,nextTick,melting,
                active.vertical+precipitation*(1-windFraction),
                active.melt+(melting?weather.temperatureC()*12*hours:0),snow,wind);
        tick=nextTick;hostTick=currentTick;snapshot=null;
    }
    private void flushPage() {
        if(archive==null)throw new IllegalStateException("Weather history needs a persistent archive before 64 freeze/thaw transitions");
        List<Phase> page=List.copyOf(hotClosed);
        try{archive.write(archivedCount,page);}catch(IOException ex){throw new UncheckedIOException("Cannot preserve weather phase history",ex);}
        pageCache.put(archivedCount,page);archivedCount+=PAGE_PHASES;hotClosed.clear();
    }
    public Snapshot snapshot() {
        if(snapshot==null)snapshot=new Snapshot(this,active.mark(epoch));
        return snapshot;
    }
    private Phase phase(long id) {
        if(id<0||id>active.id)throw new IllegalArgumentException("Unknown snow weather phase");
        if(id==active.id)return active;
        if(id>=archivedCount)return hotClosed.get(Math.toIntExact(id-archivedCount));
        long first=id/PAGE_PHASES*PAGE_PHASES;
        List<Phase> page=pageCache.get(first);
        if(page==null) {
            if(archive==null)throw new IllegalStateException("Missing weather archive");
            try{page=archive.read(first);}catch(IOException ex){throw new UncheckedIOException("Cannot replay weather phase history",ex);}
            pageCache.put(first,page);
        }
        return page.get((int)(id-first));
    }
    private static boolean valid(double value){return Double.isFinite(value)&&value>=0&&value<1e12;}
    private static int sector(int direction){if(direction<0||direction>=DIRECTIONS)throw new IllegalArgumentException("Wind direction");return direction;}
    public static int direction(float fromDegrees) {
        if(!Float.isFinite(fromDegrees))throw new IllegalArgumentException("Invalid wind bearing");
        return Math.floorMod((int)Math.floor((fromDegrees+22.5)/45),DIRECTIONS);
    }
    static void exposure(float vertical,float[] directional) {
        if(!Float.isFinite(vertical)||vertical<0||vertical>1||directional==null||directional.length!=DIRECTIONS)
            throw new IllegalArgumentException("Invalid snow exposure");
        for(float value:directional)if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Invalid directional snow exposure");
    }
}
