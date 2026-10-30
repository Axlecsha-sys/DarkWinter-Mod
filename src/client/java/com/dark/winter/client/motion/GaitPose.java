// Defines the render-only pose sampled from measured gait state.
package com.dark.winter.client.motion;

/** Render-only snapshot. Climb phase doesn't move the model through a collision face. */
public record GaitPose(double phase,double amount,double running,double lean,double turn,double bodyYawLag,
                       boolean arms,double weight,double climbPhase,double climbWeight,boolean climbRight) {
    public static final GaitPose NONE=new GaitPose(0.5,0,0,0,0,0,false,0,0,0,false);
    public GaitPose(double phase,double amount,double running,double lean,double turn,double bodyYawLag,
                    boolean arms,double weight) {
        this(phase,amount,running,lean,turn,bodyYawLag,arms,weight,0,0,false);
    }
    public GaitPose withClimb(double phase,double amount,boolean right) {
        return new GaitPose(this.phase,this.amount,running,lean,turn,bodyYawLag,arms,weight,phase,amount,right);
    }
}
