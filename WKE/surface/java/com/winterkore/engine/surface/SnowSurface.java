// Independent layered snow mass, settled volume, local trails and historical weather. No host/game classes.
package com.winterkore.engine.surface;

import com.winterkore.engine.surface.geometry.SnowContactRelief;
import com.winterkore.engine.surface.geometry.SoleProfiles;
import java.util.ArrayList;
import java.util.List;

/** One support layer per chunk; the host may keep several sparse layers for roofs and ground. */
public final class SnowSurface {
    public static final int COLUMNS=256;
    public static final float MAX_DEPTH_MM=1500,MAX_MASS_MM=4000,MAX_CONTACT_DEPTH_MM=750,PRESENTATION_STEP_MM=6.25f;
    /** Historical v5 fade duration; retained only for migration and bounded age metadata. */
    public static final long TRACK_LIFETIME_TICKS=144000;
    public enum Material {SOIL,ROCK,WOOD,CONCRETE,METAL,FURNITURE}
    public record Support(Material material,float driftPotential,boolean driftTrap){
        public static final Support DEFAULT=new Support(Material.SOIL,.5f,false);
        public Support{
            if(material==null||!Float.isFinite(driftPotential)||driftPotential<0||driftPotential>1)
                throw new IllegalArgumentException("Invalid snow support");
        }
        public float capMillimeters(){return driftTrap?1000+500*driftPotential:200+300*driftPotential;}
        double settlingRate(){return (switch(material){case SOIL->65;case ROCK->70;case WOOD->55;case CONCRETE->60;case METAL,FURNITURE->50;})*(driftTrap?.35:1);}
        double erosionScale(){return (switch(material){case SOIL->6;case ROCK,WOOD,CONCRETE->8;case METAL->10;case FURNITURE->7;})*(driftTrap?1-.85*driftPotential:1);}
    }
    final int[] y=new int[COLUMNS];
    final float[] depth=new float[COLUMNS],baseDepth=new float[COLUMNS],compacted=new float[COLUMNS];
    final float[] settlementBudget=new float[COLUMNS],legacyPackingFraction=new float[COLUMNS],pathCompaction=new float[COLUMNS];
    final Support[] supports=new Support[COLUMNS];
    final byte[] projected=new byte[COLUMNS];
    final long[] sampledAt=new long[COLUMNS];
    final SnowWeatherTimeline.Mark[] weatherAt=new SnowWeatherTimeline.Mark[COLUMNS];
    final boolean[] rebaseWeather=new boolean[COLUMNS];
    final ArrayList<Contact> contacts=new ArrayList<>();
    final ArrayList<SnowWeatherTimeline.Mark> contactWeatherAt=new ArrayList<>();
    long contactsAt;
    private boolean dirty,contactIndexDirty=true;
    private static final double MAX_CONTACT_QUERY_RADIUS=SnowContactRelief.influenceRadiusMeters(2,4,MAX_CONTACT_DEPTH_MM);
    private final ContactBucket[] contactIndex=new ContactBucket[COLUMNS];
    private static final class ContactBucket{
        int[] values=new int[8];int size;
        void add(int index){if(size==values.length)values=java.util.Arrays.copyOf(values,size*2);values[size++]=index;}
    }

    public record Weather(float snowfall,float temperatureC,float windMetersPerSecond,float windFromDegrees){
        public Weather(float snowfall,float temperatureC,float windMetersPerSecond){this(snowfall,temperatureC,windMetersPerSecond,0);}
        public Weather{
            if(!Float.isFinite(snowfall)||!Float.isFinite(temperatureC)||!Float.isFinite(windMetersPerSecond)||!Float.isFinite(windFromDegrees))
                throw new IllegalArgumentException("Non-finite surface weather");
            snowfall=Math.clamp(snowfall,0,2);temperatureC=Math.clamp(temperatureC,-80,60);
            windMetersPerSecond=Math.clamp(windMetersPerSecond,0,60);windFromDegrees=(windFromDegrees%360+360)%360;
        }
        public float physicalSnowMmPerActiveHour(){return snowfall*150;}
    }
    /** Depth is loose-equivalent mass; subtract compacted for actual column height. Path density is local traffic metadata. */
    public record Sample(int snowY,float depthMillimeters,float compactedMillimeters,int layers,
                         float baseMillimeters,float pathCompactionFraction){
        public Sample(int snowY,float depthMillimeters,float compactedMillimeters,int layers){
            this(snowY,depthMillimeters,compactedMillimeters,layers,layers*125,0);
        }
    }
    /** OwnerY identifies a support layer. Legacy contacts lack that identity and keep the conservative height fallback. */
    public record Contact(float x,float y,float z,float yaw,float width,float length,float cover,boolean vehicle,
                          float depthMillimeters,float compaction,int soleProfile,int ownerY,long ageTicks){
        public Contact(float x,float y,float z,float yaw,float width,float length,float cover,boolean vehicle,
                float depthMillimeters,float compaction,int soleProfile,int ownerY){
            this(x,y,z,yaw,width,length,cover,vehicle,depthMillimeters,compaction,soleProfile,ownerY,0);
        }
        public Contact(float x,float y,float z,float yaw,float width,float length,float cover,boolean vehicle,
                float depthMillimeters,float compaction,int soleProfile){
            this(x,y,z,yaw,width,length,cover,vehicle,depthMillimeters,compaction,soleProfile,Integer.MIN_VALUE,0);
        }
        public Contact(float x,float y,float z,float yaw,float width,float length,float cover,boolean vehicle,float depthMillimeters,float compaction){
            this(x,y,z,yaw,width,length,cover,vehicle,depthMillimeters,compaction,0);
        }
        public Contact(float x,float y,float z,float yaw,float width,float length,float cover,boolean vehicle){
            this(x,y,z,yaw,width,length,cover,vehicle,vehicle?60:36,vehicle?.8f:.65f);
        }
        public Contact{
            if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(width)
                    ||!Float.isFinite(length)||!Float.isFinite(cover)||!Float.isFinite(depthMillimeters)||!Float.isFinite(compaction)
                    ||x<0||x>=16||z<0||z>=16||y< -2048||y>2048||width<.04f||width>2||length<.08f||length>4
                    ||cover<0||cover>MAX_DEPTH_MM||depthMillimeters<=0||depthMillimeters>MAX_CONTACT_DEPTH_MM
                    ||compaction<0||compaction>1||!SoleProfiles.valid(soleProfile)
                    ||ownerY!=Integer.MIN_VALUE&&(ownerY< -2048||ownerY>2048)||ageTicks<0||ageTicks>TRACK_LIFETIME_TICKS)
                throw new IllegalArgumentException("Invalid surface contact");
        }
        public float depressionMillimeters(){return Math.max(0,depthMillimeters-cover);}
        public float strength(){return Math.clamp(depressionMillimeters()/depthMillimeters,0,1);}
    }
    /** Match the durable chunk-local float encoding used by every persisted contact. */
    public static float contactChunkCoordinate(double worldCoordinate){
        return Math.clamp((float)(worldCoordinate-Math.floor(worldCoordinate/16)*16),0,Math.nextDown(16f));
    }
    public static double contactWorldCoordinate(int chunkCoordinate,float contactCoordinate){
        return chunkCoordinate*16.0+contactCoordinate;
    }
    public interface ContactExposure{float vertical(Contact contact);float[] directional(Contact contact);}
    public SnowSurface(long tick){
        java.util.Arrays.fill(y,Integer.MIN_VALUE);java.util.Arrays.fill(sampledAt,tick);
        java.util.Arrays.fill(supports,Support.DEFAULT);contactsAt=tick;
    }
    private static void index(int cell){if(cell<0||cell>=COLUMNS)throw new IllegalArgumentException("Surface column");}
    public boolean observed(int cell){index(cell);return y[cell]!=Integer.MIN_VALUE;}
    public Sample sample(int cell){index(cell);return new Sample(y[cell],depth[cell],compacted[cell],layers(cell),baseDepth[cell],pathCompaction[cell]);}
    public Support support(int cell){index(cell);return supports[cell];}
    public float physicalDepthMillimeters(int cell){index(cell);return Math.max(0,depth[cell]-compacted[cell]);}
    public float remainingLooseDepthMillimeters(int cell){return physicalDepthMillimeters(cell)*(1-.85f*pathCompaction[cell]);}
    public float fineDeltaMillimeters(int cell){index(cell);return physicalDepthMillimeters(cell)-Byte.toUnsignedInt(projected[cell])*125;}
    public float presentationDeltaMillimeters(int cell){return Math.round(fineDeltaMillimeters(cell)/PRESENTATION_STEP_MM)*PRESENTATION_STEP_MM;}
    public void observe(int cell,int snowY,int actualLayers,long tick){observe(cell,snowY,actualLayers,tick,Support.DEFAULT);}
    public void observe(int cell,int snowY,int actualLayers,long tick,Support support){
        index(cell);if(actualLayers<0||actualLayers>8||snowY< -2048||snowY>2048||support==null)throw new IllegalArgumentException("Surface host sample");
        if(!support.equals(supports[cell])){
            // A new shelter may slow future settling, but cannot expand snow that is already dense.
            if(depth[cell]>0)legacyPackingFraction[cell]=Math.max(legacyPackingFraction[cell],compacted[cell]/depth[cell]);
            supports[cell]=support;dirty=true;
        }
        if(y[cell]!=snowY||Byte.toUnsignedInt(projected[cell])!=actualLayers){
            boolean existed=observed(cell);
            y[cell]=snowY;depth[cell]=baseDepth[cell]=actualLayers*125;compacted[cell]=settlementBudget[cell]=legacyPackingFraction[cell]=pathCompaction[cell]=0;
            projected[cell]=(byte)actualLayers;sampledAt[cell]=tick;weatherAt[cell]=null;rebaseWeather[cell]=existed;dirty=true;
        }
    }
    /** A removed support has no field to advance or restore when a new object later occupies its coordinates. */
    public void forget(int cell){
        index(cell);int ownerY=y[cell];if(ownerY==Integer.MIN_VALUE)return;
        removeCellContacts(cell,ownerY);
        y[cell]=Integer.MIN_VALUE;depth[cell]=baseDepth[cell]=compacted[cell]=0;
        settlementBudget[cell]=legacyPackingFraction[cell]=pathCompaction[cell]=0;projected[cell]=0;
        sampledAt[cell]=0;weatherAt[cell]=null;rebaseWeather[cell]=false;supports[cell]=Support.DEFAULT;dirty=true;
    }
    /** A real replacement keeps its authored support, but inherits none of the previous object's weather snow or tracks. */
    public void resetAccumulationToBase(int cell){
        index(cell);if(!observed(cell))throw new IllegalStateException("Unobserved construction reset");
        removeCellContacts(cell,y[cell]);depth[cell]=baseDepth[cell];compacted[cell]=0;
        settlementBudget[cell]=legacyPackingFraction[cell]=pathCompaction[cell]=0;
        weatherAt[cell]=null;rebaseWeather[cell]=true;dirty=true;
    }
    private void removeCellContacts(int cell,int ownerY){
        for(int i=contacts.size()-1;i>=0;i--){
            Contact contact=contacts.get(i);int centreCell=(int)contact.x()|((int)contact.z()<<4);
            if(centreCell==cell&&(contact.ownerY()==ownerY||contact.ownerY()==Integer.MIN_VALUE)){
                contacts.remove(i);contactWeatherAt.remove(i);contactIndexDirty=true;
            }
        }
    }
    /** Real construction starts at this weather cursor; unseen old terrain must retain normal historical replay. */
    public void rebaseWeatherTo(int cell,SnowWeatherTimeline.Snapshot weather){
        index(cell);if(!observed(cell))throw new IllegalStateException("Unobserved snow rebase");
        if(weather==null)throw new IllegalArgumentException("Missing construction weather");
        weatherAt[cell]=weather.mark();sampledAt[cell]=weather.tick();rebaseWeather[cell]=false;dirty=true;
    }
    public int advance(int cell,SnowWeatherTimeline.Snapshot weather,float verticalExposure,float[] directionalExposure){
        index(cell);SnowWeatherTimeline.exposure(verticalExposure,directionalExposure);if(!observed(cell))return 0;
        if(rebaseWeather[cell]){
            rebaseWeather[cell]=false;weatherAt[cell]=weather.mark();sampledAt[cell]=weather.tick();dirty=true;return layers(cell);
        }
        Support support=supports[cell];
        var dynamics=new SnowWeatherTimeline.Dynamics(support.capMillimeters(),support.driftTrap()?1000:200,
                support.settlingRate(),support.erosionScale(),legacyPackingFraction[cell]);
        var result=weather.evolve(weatherAt[cell],depth[cell],compacted[cell],settlementBudget[cell],
                pathCompaction[cell],baseDepth[cell],dynamics,verticalExposure,directionalExposure);
        float next=(float)result.depthMillimeters(),packed=(float)result.compactedMillimeters();
        if(next!=depth[cell]||packed!=compacted[cell]||weatherAt[cell]!=weather.mark())dirty=true;
        depth[cell]=next;compacted[cell]=packed;settlementBudget[cell]=(float)result.settlementBudget();
        pathCompaction[cell]=(float)result.pathCompactionFraction();weatherAt[cell]=weather.mark();sampledAt[cell]=weather.tick();
        return layers(cell);
    }
    /** Wind transport preserves mass and never steals authored support mass or pressure-dense trail material. */
    public float looseAdditionalMillimeters(int cell){
        index(cell);return observed(cell)?Math.max(0,depth[cell]-baseDepth[cell]-compacted[cell])*(1-pathCompaction[cell]):0;
    }
    public float transferAdditionalTo(int sourceCell,SnowSurface target,int targetCell,float requestedMillimeters){
        index(sourceCell);if(target==null)throw new IllegalArgumentException("Missing snow drift receiver");index(targetCell);
        if(!Float.isFinite(requestedMillimeters)||requestedMillimeters<0)throw new IllegalArgumentException("Invalid snow drift amount");
        if(target==this&&targetCell==sourceCell||!observed(sourceCell)||!target.observed(targetCell))return 0;
        double capacity=Math.max(target.depth[targetCell],Math.min(MAX_MASS_MM,target.supports[targetCell].capMillimeters()+target.compacted[targetCell]));
        double candidate=Math.min(requestedMillimeters,Math.min(looseAdditionalMillimeters(sourceCell),capacity-target.depth[targetCell]));
        if(candidate<=0)return 0;
        float quantum=Math.max(Math.ulp(depth[sourceCell]),Math.ulp((float)(target.depth[targetCell]+candidate)));
        float moved=(float)(Math.floor(candidate/quantum)*quantum);if(moved<=0)return 0;
        float sourceBefore=depth[sourceCell],targetBefore=target.depth[targetCell];
        depth[sourceCell]-=moved;target.depth[targetCell]+=moved;
        // Transported mass leaves at the donor's density. Keep the remaining layer's attained density,
        // so a later dry tick cannot inflate a near-depleted donor back toward a fresh-snow target.
        float remainingRatio=depth[sourceCell]/sourceBefore;
        compacted[sourceCell]*=remainingRatio;
        settlementBudget[sourceCell]=Math.max(compacted[sourceCell],settlementBudget[sourceCell]*remainingRatio);
        if(depth[sourceCell]>0)legacyPackingFraction[sourceCell]=Math.max(legacyPackingFraction[sourceCell],compacted[sourceCell]/depth[sourceCell]);
        // Fresh drift lies above existing packed snow; it dilutes its density and has no old settling budget.
        float dilution=targetBefore/target.depth[targetCell];
        target.legacyPackingFraction[targetCell]*=dilution;target.pathCompaction[targetCell]*=dilution;
        target.settlementBudget[targetCell]=target.compacted[targetCell];
        dirty=true;target.dirty=true;return moved;
    }
    /** Compatibility-only active path. Historical adapters use the snapshot overload. */
    public int advance(int cell,long tick,Weather weather,float exposure){
        index(cell);if(!Float.isFinite(exposure))throw new IllegalArgumentException("Surface exposure");if(!observed(cell))return 0;
        double seconds=Math.clamp((tick-sampledAt[cell])/20.0,0,10);sampledAt[cell]=tick;
        float snow=weather.temperatureC()<=0?weather.physicalSnowMmPerActiveHour()/3600*Math.clamp(exposure,0,1):0;
        float melt=Math.max(0,weather.temperatureC())*12/3600;
        float capacity=Math.max(depth[cell],Math.min(MAX_MASS_MM,supports[cell].capMillimeters()+compacted[cell]));
        float next=(float)Math.clamp(depth[cell]+(snow-melt)*seconds,baseDepth[cell],capacity);
        if(next!=depth[cell]){depth[cell]=next;dirty=true;}return layers(cell);
    }
    public int layers(int cell){index(cell);return Math.clamp((int)Math.floor((physicalDepthMillimeters(cell)+.001f)/125),0,8);}
    public void projected(int cell,int layers){index(cell);if(layers<0||layers>8)throw new IllegalArgumentException("Surface layers");projected[cell]=(byte)layers;dirty=true;}
    public void contact(Contact contact){addContact(contact,null);}
    public void contact(Contact contact,SnowWeatherTimeline.Snapshot weather){addContact(contact,weather.mark());}
    private void addContact(Contact contact,SnowWeatherTimeline.Mark mark){
        int index=contacts.size();contacts.add(contact);contactWeatherAt.add(mark);
        if(!contactIndexDirty)indexContact(index,contact);dirty=true;
    }
    private void indexContact(int index,Contact contact){
        // Use the largest legal depth so weather or deeper pressure at the same immutable
        // placement cannot escape its bins. This is only a broad phase; the exact kernel follows.
        double radius=SnowContactRelief.influenceRadiusMeters(contact.width(),contact.length(),MAX_CONTACT_DEPTH_MM);
        int minX=Math.max(0,(int)Math.floor(contact.x()-radius)),maxX=Math.min(15,(int)Math.floor(contact.x()+radius));
        int minZ=Math.max(0,(int)Math.floor(contact.z()-radius)),maxZ=Math.min(15,(int)Math.floor(contact.z()+radius));
        for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++){
            int cell=x|(z<<4);ContactBucket bucket=contactIndex[cell];
            if(bucket==null)contactIndex[cell]=bucket=new ContactBucket();bucket.add(index);
        }
    }
    private ContactBucket contactsAt(double x,double z){
        if(contactIndexDirty){
            for(var bucket:contactIndex)if(bucket!=null)bucket.size=0;
            for(int i=0;i<contacts.size();i++)indexContact(i,contacts.get(i));
            contactIndexDirty=false;
        }
        return contactIndex[Math.clamp((int)Math.floor(x),0,15)|(Math.clamp((int)Math.floor(z),0,15)<<4)];
    }
    private static boolean samePlacement(Contact a,Contact b,int ownerY){
        return a.ownerY()==ownerY&&a.x()==b.x()&&a.y()==b.y()&&a.z()==b.z()&&a.yaw()==b.yaw()
                &&a.width()==b.width()&&a.length()==b.length()&&a.vehicle()==b.vehicle()&&a.soleProfile()==b.soleProfile();
    }
    /** Weather may move the skin, but cannot relocate the fixed stamp on the same support. */
    private static boolean sameFixedPlacement(Contact a,Contact b,int ownerY){
        return a.ownerY()==ownerY&&a.x()==b.x()&&a.z()==b.z()&&a.yaw()==b.yaw()
                &&a.width()==b.width()&&a.length()==b.length()&&a.vehicle()==b.vehicle()&&a.soleProfile()==b.soleProfile();
    }
    /** Maximum cavity depth contributes to the next pressure event, bounded by its live snow column. */
    public static float contactDepthWithCavity(float requestedDepth,double cavityDepth,float physicalDepth){
        return (float)Math.min(Math.min(MAX_CONTACT_DEPTH_MM,physicalDepth*.9f),Math.max(requestedDepth,cavityDepth));
    }
    /** A pressure event changes local trail density and the exact sole field; it never lowers the whole square cell. */
    public Contact pressContact(int cell,Contact placement,SnowWeatherTimeline.Snapshot weather){
        index(cell);if(!observed(cell))throw new IllegalStateException("Unobserved snow contact");
        if(placement.ownerY()!=Integer.MIN_VALUE&&placement.ownerY()!=y[cell])throw new IllegalArgumentException("Contact belongs to another support");
        float height=physicalDepthMillimeters(cell);if(height<=0)return null;
        float existing=0;int merge=-1;ContactBucket nearby=contactsAt(placement.x(),placement.z());
        if(nearby!=null)for(int n=0;n<nearby.size;n++){
            int i=nearby.values[n];Contact old=contacts.get(i);
            if(old.ownerY()!=Integer.MIN_VALUE?old.ownerY()!=y[cell]:Math.abs(old.y()-placement.y())>.25f)continue;
            existing=Math.max(existing,(float)SnowContactRelief.depressionMillimeters(placement.x(),placement.z(),old.x(),old.z(),old.yaw(),old.width(),old.length(),old.depressionMillimeters(),old.vehicle(),old.soleProfile()));
            // Moving a nearby mark advects its old cavity. Only this exact placement may be updated.
            if(samePlacement(old,placement,y[cell]))merge=i;
        }
        float limit=Math.min(MAX_CONTACT_DEPTH_MM,height*.9f);
        float requested=Math.min(limit,placement.depthMillimeters());
        float pressed=existing<=0?requested:Math.min(limit,Math.max(requested,existing+(limit-existing)*placement.compaction()*.28f));
        pressed=Math.max(.001f,pressed);
        if(merge>=0)pressed=Math.max(pressed,contacts.get(merge).depressionMillimeters());
        double area=Math.clamp(placement.width()*placement.length(),.005,1);
        pathCompaction[cell]=(float)Math.min(.95,pathCompaction[cell]+(1-pathCompaction[cell])*placement.compaction()*Math.sqrt(area)*.5);
        Contact made=new Contact(placement.x(),placement.y(),placement.z(),placement.yaw(),placement.width(),placement.length(),0,
                placement.vehicle(),pressed,placement.compaction(),placement.soleProfile(),y[cell],0);
        if(merge>=0){contacts.set(merge,made);contactWeatherAt.set(merge,weather.mark());dirty=true;}
        else addContact(made,weather.mark());
        return made;
    }
    /**
     * A fixed footprint keeps its selected depth and immutable placement. Replaying the same
     * placement must not deepen it, re-excavate weather cover, reset its cursor or compact twice.
     */
    public Contact pressFixedContact(int cell,Contact placement,SnowWeatherTimeline.Snapshot weather){
        index(cell);if(!observed(cell))throw new IllegalStateException("Unobserved snow contact");
        if(placement.ownerY()!=Integer.MIN_VALUE&&placement.ownerY()!=y[cell])throw new IllegalArgumentException("Contact belongs to another support");
        float height=physicalDepthMillimeters(cell);if(height<=0)return null;
        ContactBucket nearby=contactsAt(placement.x(),placement.z());
        if(nearby!=null)for(int n=0;n<nearby.size;n++){
            Contact old=contacts.get(nearby.values[n]);
            if(sameFixedPlacement(old,placement,y[cell]))return old;
        }
        float target=Math.min(Math.min(MAX_CONTACT_DEPTH_MM,height*.9f),placement.depthMillimeters());
        if(target<=0)return null;
        double area=Math.clamp(placement.width()*placement.length(),.005,1);
        pathCompaction[cell]=(float)Math.min(.95,pathCompaction[cell]+(1-pathCompaction[cell])*placement.compaction()*Math.sqrt(area)*.5);
        Contact made=new Contact(placement.x(),placement.y(),placement.z(),placement.yaw(),placement.width(),placement.length(),0,
                placement.vehicle(),target,placement.compaction(),placement.soleProfile(),y[cell],0);
        addContact(made,weather.mark());
        return made;
    }
    /**
     * Exact live pressure near a query, including the portion extending outside this chunk.
     * Edge bins remain conservative: clamping an exterior point towards the chunk cannot move
     * it outside any contact's indexed influence bounds. The owner filter belongs to the host.
     */
    public double depressionMillimeters(double x,double z,java.util.function.Predicate<Contact> ownerFilter){
        if(!Double.isFinite(x)||!Double.isFinite(z)||x< -MAX_CONTACT_QUERY_RADIUS||x>16+MAX_CONTACT_QUERY_RADIUS
                ||z< -MAX_CONTACT_QUERY_RADIUS||z>16+MAX_CONTACT_QUERY_RADIUS)return 0;
        ContactBucket nearby=contactsAt(x,z);if(nearby==null)return 0;double pressure=0;
        for(int n=0;n<nearby.size;n++){
            Contact c=contacts.get(nearby.values[n]);if(ownerFilter!=null&&!ownerFilter.test(c))continue;
            pressure=Math.max(pressure,SnowContactRelief.depressionMillimeters(x,z,c.x(),c.z(),c.yaw(),c.width(),c.length(),
                    c.depressionMillimeters(),c.vehicle(),c.soleProfile()));
        }
        return pressure;
    }
    /** Constant-time ledger size; fixed stamping either appends one contact or retains the existing record. */
    public int contactCount(){return contacts.size();}
    public List<Contact> contacts(){return List.copyOf(contacts);}
    public void ageContacts(SnowWeatherTimeline.Snapshot weather,float verticalExposure,float[] directionalExposure){
        SnowWeatherTimeline.exposure(verticalExposure,directionalExposure);
        ageContacts(weather,new ContactExposure(){public float vertical(Contact c){return verticalExposure;}public float[] directional(Contact c){return directionalExposure;}});
    }
    /** A resumable reverse sweep: one batch integrates at most limit contacts and compacts its range once. */
    public record ContactAging(int visited,int visiblyChanged,int removed,boolean completed) {}
    private int contactWeatherCursor;
    public ContactAging ageContactsBudget(SnowWeatherTimeline.Snapshot weather,ContactExposure exposure,int limit){
        if(weather==null||exposure==null||limit<1)throw new IllegalArgumentException("Invalid contact weather budget");
        int end=contactWeatherCursor;
        if(end<=0||end>contacts.size())end=contacts.size();
        int start=Math.max(0,end-limit),write=start,changed=0,removed=0;
        for(int read=start;read<end;read++){
            Contact c=contacts.get(read);SnowWeatherTimeline.Mark from=contactWeatherAt.get(read);
            Contact next=c;SnowWeatherTimeline.Mark cursor=from;
            if(from==null){cursor=weather.mark();dirty=true;}
            else if(weather.tick()>from.tick()||weather.tick()==from.tick()&&weather.phase()!=from.phase()){
                var change=weather.integrate(from,0,MAX_MASS_MM,exposure.vertical(c),exposure.directional(c));
                float cover=(float)Math.min(MAX_DEPTH_MM,c.cover()+change.snowfallMillimeters()+change.windFillMillimeters()+change.meltMillimeters());
                long elapsed=Math.max(0,weather.tick()-from.tick());
                long age=Math.min(TRACK_LIFETIME_TICKS,c.ageTicks()+Math.min(elapsed,TRACK_LIFETIME_TICKS));
                cursor=weather.mark();dirty=true;
                if(cover!=c.cover())changed++;
                if(cover>=c.depthMillimeters()){removed++;continue;}
                next=new Contact(c.x(),c.y(),c.z(),c.yaw(),c.width(),c.length(),cover,c.vehicle(),c.depthMillimeters(),c.compaction(),c.soleProfile(),c.ownerY(),age);
            }
            contacts.set(write,next);contactWeatherAt.set(write,cursor);write++;
        }
        // ArrayList.remove per buried mark is quadratic under a blizzard. Shift this bounded
        // processed range once, preserving every surviving placement and its own exact weather cursor.
        if(write<end){
            contacts.subList(write,end).clear();contactWeatherAt.subList(write,end).clear();contactIndexDirty=true;
        }
        contactWeatherCursor=start;contactsAt=weather.tick();
        return new ContactAging(end-start,changed,removed,start==0);
    }
    public void ageContacts(SnowWeatherTimeline.Snapshot weather,ContactExposure exposure){
        contactWeatherCursor=0;
        for(int i=contacts.size()-1;i>=0;i--){
            Contact c=contacts.get(i);var from=contactWeatherAt.get(i);
            if(from==null){contactWeatherAt.set(i,weather.mark());dirty=true;continue;}
            long elapsed=Math.max(0,weather.tick()-from.tick());
            if(elapsed==0&&from.phase()==weather.phase())continue;
            var change=weather.integrate(from,0,MAX_MASS_MM,exposure.vertical(c),exposure.directional(c));
            float cover=(float)Math.min(MAX_DEPTH_MM,c.cover()+change.snowfallMillimeters()+change.windFillMillimeters()+change.meltMillimeters());
            long age=Math.min(TRACK_LIFETIME_TICKS,c.ageTicks()+Math.min(elapsed,TRACK_LIFETIME_TICKS));
            if(cover>=c.depthMillimeters()){contacts.remove(i);contactWeatherAt.remove(i);contactIndexDirty=true;}
            else{
                contacts.set(i,new Contact(c.x(),c.y(),c.z(),c.yaw(),c.width(),c.length(),cover,c.vehicle(),c.depthMillimeters(),c.compaction(),c.soleProfile(),c.ownerY(),age));
                contactWeatherAt.set(i,weather.mark());
            }
            dirty=true;
        }
        contactsAt=weather.tick();
    }
    public void ageContacts(long tick,Weather weather,float exposure){
        if(!Float.isFinite(exposure))throw new IllegalArgumentException("Surface exposure");
        long elapsed=Math.max(0,Math.min(tick-contactsAt,200));double seconds=elapsed/20.0;contactsAt=tick;
        float fill=(weather.temperatureC()<=0?weather.physicalSnowMmPerActiveHour():0)+Math.max(0,weather.windMetersPerSecond()-3)*2;
        float erase=(fill*Math.clamp(exposure,0,1)+Math.max(0,weather.temperatureC())*12)/3600;
        for(int i=contacts.size()-1;i>=0;i--){
            Contact c=contacts.get(i);float cover=(float)Math.min(MAX_DEPTH_MM,c.cover()+erase*seconds);
            long age=Math.min(TRACK_LIFETIME_TICKS,c.ageTicks()+elapsed);
            if(cover>=c.depthMillimeters()){contacts.remove(i);contactWeatherAt.remove(i);contactIndexDirty=true;}
            else contacts.set(i,new Contact(c.x(),c.y(),c.z(),c.yaw(),c.width(),c.length(),cover,c.vehicle(),c.depthMillimeters(),c.compaction(),c.soleProfile(),c.ownerY(),age));
        }
        dirty=true;
    }
    public static double movementMultiplier(float depthMillimeters){
        if(!Float.isFinite(depthMillimeters))return 1;return Math.clamp(1-Math.max(0,depthMillimeters-80)/1000*.65,.45,1);
    }
    public boolean dirty(){return dirty;}public void clean(){dirty=false;}
    public void resetClock(long tick){java.util.Arrays.fill(sampledAt,tick);contactsAt=tick;}
}
