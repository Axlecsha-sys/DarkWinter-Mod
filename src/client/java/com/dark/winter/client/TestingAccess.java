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
    public static boolean pipeline(boolean ordinary) {
        if(OnlineTestingView.active())return OnlineTestingView.enabled("visuals")||OnlineTestingView.enabled("weather")||OnlineTestingView.enabled("atmosphere");
        var server=Minecraft.getInstance().getSingleplayerServer();
        return TestingServer.isTesting(server)?(TestingServer.enabled(server,"visuals")||TestingServer.enabled(server,"weather")||TestingServer.enabled(server,"atmosphere")):ordinary;
    }
    public static boolean resolve(String module,boolean ordinary) {
        if(OnlineTestingView.active())return OnlineTestingView.enabled(module);
        var server=Minecraft.getInstance().getSingleplayerServer();
        return TestingServer.isTesting(server)?TestingServer.enabled(server,module):ordinary;
    }
    public static boolean enabled(String module) {
        if(OnlineTestingView.active())return OnlineTestingView.enabled(module);
        var server=Minecraft.getInstance().getSingleplayerServer();
        return server==null||TestingServer.enabled(server,module);
    }
}
