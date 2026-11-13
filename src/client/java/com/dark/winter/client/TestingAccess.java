// Reads current world-specific or authenticated online laboratory overrides.
package com.dark.winter.client;

import com.dark.winter.client.online.OnlineTestingView;
import com.dark.winter.testing.TestingServer;
import com.dark.winter.testing.TestingSettings;
import net.minecraft.client.Minecraft;

/** Current Lab overrides are local-world data or authenticated native Online data. */
public final class TestingAccess {
    private TestingAccess() {}
    public static TestingSettings settings(){
        if(OnlineTestingView.active())return OnlineTestingView.settings();
        var server=Minecraft.getInstance().getSingleplayerServer();
        return TestingServer.isTesting(server)?TestingServer.settings(server):null;
    }
    public static com.dark.winter.testing.TestingPresentation presentation(){
        var online=OnlineTestingView.presentation();
        if(online!=null)return online;
        var server=Minecraft.getInstance().getSingleplayerServer();
        return TestingServer.isTesting(server)?TestingServer.presentation(server):null;
    }
    public static boolean pipeline(boolean ordinary) {
        var value=presentation();
        return value==null?ordinary:value.enabled("visuals")||value.enabled("weather")||value.enabled("atmosphere");
    }
    public static boolean resolve(String module,boolean ordinary) {
        var value=presentation();return value==null?ordinary:value.enabled(module);
    }
    public static boolean enabled(String module) {
        var value=presentation();return value==null||value.enabled(module);
    }
}
