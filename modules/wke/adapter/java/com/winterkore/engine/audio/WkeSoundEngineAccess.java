package com.winterkore.engine.audio;

import java.util.Map;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;

/** Read only on the client thread, the same owner as Minecraft's active sound map. */
public interface WkeSoundEngineAccess {
    Map<SoundInstance,ChannelAccess.ChannelHandle> wke$channels();
}
