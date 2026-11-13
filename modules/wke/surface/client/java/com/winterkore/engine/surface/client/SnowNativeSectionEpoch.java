// Compile ownership is carried to the actual native commit, including the shared empty mesh path.
package com.winterkore.engine.surface.client;

public final class SnowNativeSectionEpoch {
    public interface Task {
        void wke$bindSnowEpoch(long epoch);
        long wke$snowEpoch();
        boolean wke$snowTaskRunning();
        boolean wke$snowTaskCancelled();
    }
    public interface Owner {Task wke$lastSnowTask();}
    private static final ThreadLocal<Long> CURRENT=new ThreadLocal<>();
    private SnowNativeSectionEpoch(){}
    public static Long push(long epoch){Long prior=CURRENT.get();CURRENT.set(epoch);return prior;}
    public static void restore(Long prior){if(prior==null)CURRENT.remove();else CURRENT.set(prior);}
    public static long current(){Long epoch=CURRENT.get();return epoch==null?-1:epoch;}
}
