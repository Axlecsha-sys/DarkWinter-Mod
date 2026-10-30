// One profile runtime and one native-session coordinator for the life of this Minecraft client.
package com.dark.winter.client.online;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;

/** One profile runtime and one native-session coordinator for the life of this Minecraft client. */
public final class OnlineClientRuntime {
    private static OnlineFriendsRuntime friends;
    private static OnlineSessionClient session;
    private static KeyMapping friendsKey;
    private static boolean initialized,closed;
    private OnlineClientRuntime() {}

    public static synchronized void init(){
        if(initialized)return;initialized=true;
        Minecraft client=Minecraft.getInstance();
        session=new OnlineSessionClient(client);
        friends=new OnlineFriendsRuntime(client.gameDirectory.toPath(),client::execute,session);
        session.bind(friends);
        OnlineUiBridge.install(friends);
        OnlineProfilePreview.install();
        KeyMapping.Category category=KeyMapping.Category.register(Identifier.fromNamespaceAndPath("darkwinter","online"));
        // F6 is Minecraft 26.3 debug options; NumEnter has no native default mapping.
        friendsKey=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.darkwinter.friends",InputConstants.Type.KEYBOARD,InputConstants.KEY_NUMPADENTER,category));
        ClientLifecycleEvents.CLIENT_STOPPING.register(stopping->close());
    }
    public static OnlineFriendsRuntime friends(){return friends;}
    public static OnlineSessionClient session(){return session;}
    public static void tick(Minecraft client){
        if(!initialized||closed)return;
        session.tick();
        OnlineUiBridge.tick(client);
        while(friendsKey.consumeClick()){
            if(client.gui.screen()==null)FriendsScreen.openQuick(client);
            else if(client.level==null&&client.gui.screen() instanceof TitleScreen)FriendsScreen.open(client.gui.screen());
        }
    }
    public static synchronized void close(){
        if(closed)return;closed=true;
        try{if(session!=null)session.close();}
        finally{
            if(friends!=null)friends.close();
            OnlineUiBridge.install(null);
        }
    }
}
