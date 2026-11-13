package com.winterkore.engine.audio;

import java.util.Map;

/** Implemented by the host's real OpenAL Channel; all methods run on its audio executor. */
public interface WkeAcousticChannel {
    void wke$acoustics(AcousticModel.Parameters parameters,boolean worldSource);
    void wke$bodyColour(float low,float high);
    Map<String,Object> wke$audioSnapshot();
}
