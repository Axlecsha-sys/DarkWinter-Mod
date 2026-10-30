// Exposes native configuration tasks for authenticated session admission.
package com.dark.winter.mixin.online;
import java.util.Queue;
import net.minecraft.server.network.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ServerConfigurationPacketListenerImpl.class)
public interface OnlineConfigurationAccess {
    @Accessor("configurationTasks") Queue<ConfigurationTask> darkwinter$tasks();
}
