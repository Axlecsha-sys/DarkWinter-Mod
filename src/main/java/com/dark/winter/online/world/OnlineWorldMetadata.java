// Archive paths which bind one logical island to its owner, access policy, host authority and leases.
package com.dark.winter.online.world;

/** Archive paths which bind one logical island to its owner, access policy, host authority and leases. */
public final class OnlineWorldMetadata {
    private OnlineWorldMetadata(){}
    /**
     * Used only while publishing a new independent ownership graph. It never deletes or edits a save.
     * Ordinary snapshots, test copies, standby replicas, previews and restores keep these entries.
     * Historical operation actor IDs live under darkwinter-testing and are deliberately not authority metadata.
     */
    public static boolean isAuthorityPath(String canonicalArchivePath){
        if(canonicalArchivePath==null)return false;
        // ZIP manifests can spell the same Windows directory with case, ./ or trailing-dot aliases.
        // Classify without touching the filesystem; the archive's separate path validator still rejects escapes.
        int component=0;
        for(String part:canonicalArchivePath.replace('\\','/').split("/")){
            if(part.isEmpty()||part.equals("."))continue;
            int end=part.length();while(end>0&&(part.charAt(end-1)=='.'||part.charAt(end-1)==' '))end--;
            if(end==0)continue;String normalized=part.substring(0,end);
            if(component++==0){if(!normalized.equalsIgnoreCase("data"))return false;}
            else return normalized.equalsIgnoreCase("darkwinter-online");
        }
        return false;
    }
}
