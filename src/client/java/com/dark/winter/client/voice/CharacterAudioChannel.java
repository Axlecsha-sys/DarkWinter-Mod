package com.dark.winter.client.voice;

/** Implemented on native OpenAL channels; called only on their audio executor. */
public interface CharacterAudioChannel {
    void darkwinter$characterDepth(boolean firstPerson,boolean footstep,float range,CharacterAcoustics.Colour colour);
}
