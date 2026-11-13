// Rejects stale native upload publications without retaining compilation snapshots or replacing live meshes.
package com.dark.winter.client.fixes.performance;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;

/** Tokens contain only weak owners/tasks, primitive identity and the native cancellation flag. */
public final class SectionMeshPublication {
    private SectionMeshPublication() {}
    public interface Owner {
        long darkwinter$meshGeneration();
        long darkwinter$meshNode();
        SectionMesh darkwinter$currentMesh();
        void darkwinter$lockMeshUploads();
        void darkwinter$unlockMeshUploads();
        void darkwinter$releaseNativeMesh(SectionMesh mesh);
    }
    public interface Task {
        void darkwinter$bindMeshTask(Owner owner,long node,long generation);
        Token darkwinter$meshTaskToken();
    }
    public interface Mesh {
        Token darkwinter$meshToken();
        void darkwinter$bindMeshToken(Token token);
    }
    public static final class Token {
        private final WeakReference<Owner> owner;
        private final WeakReference<Object> task;
        private final AtomicBoolean cancelled;
        private final long node,generation;
        private final AtomicBoolean retired=new AtomicBoolean();
        public Token(Owner owner,Object task,AtomicBoolean cancelled,long node,long generation) {
            this.owner=new WeakReference<>(owner);this.task=new WeakReference<>(task);
            this.cancelled=cancelled;this.node=node;this.generation=generation;
        }
        public Object task(){return task.get();}
    }
    public static Token token(SectionMesh mesh){return mesh instanceof Mesh tagged?tagged.darkwinter$meshToken():null;}
    public static void bind(CompiledSectionMesh mesh,Token token){if(token!=null)((Mesh)mesh).darkwinter$bindMeshToken(token);}
    /** Called under the task monitor for the final publication decision. */
    public static boolean current(Owner owner,Token token) {
        return !token.retired.get()&&!token.cancelled.get()&&token.owner.get()==owner
                &&token.node==owner.darkwinter$meshNode()&&token.generation==owner.darkwinter$meshGeneration();
    }
    /** Callback entry is also guarded before native setters can access maps cleared by close(). */
    public static boolean acceptCallback(Owner owner,CompiledSectionMesh mesh,boolean sortedIndex) {
        Token token=token(mesh);if(token==null)return true;
        if(token.retired.get())return false;
        // A rebuild must not invalidate a transparency resort of the still-visible old mesh.
        if(sortedIndex&&owner.darkwinter$currentMesh()==mesh)return true;
        Object task=token.task();
        if(task!=null)synchronized(task){if(current(owner,token))return true;}
        retireObsolete(owner,mesh,token);
        return false;
    }
    /** Never frees a still-visible old mesh, another owner or a replacement's allocation key. */
    public static void retireObsolete(Owner owner,SectionMesh mesh,Token token) {
        if(token.owner.get()!=owner||token.retired.get())return;
        owner.darkwinter$lockMeshUploads();
        try {if(owner.darkwinter$currentMesh()!=mesh)owner.darkwinter$releaseNativeMesh(mesh);}
        finally {owner.darkwinter$unlockMeshUploads();}
    }
    /** Both native cancellation and stale callbacks share the same exactly-once retirement claim. */
    public static boolean claimRelease(SectionMesh mesh) {
        Token token=token(mesh);return token==null||token.retired.compareAndSet(false,true);
    }
}
