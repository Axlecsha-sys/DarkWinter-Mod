package com.winterkore.engine.material;

/** Backend descriptions retain only their material classification; this owns no GPU resource. */
public interface WkeFoliagePipeline {
    int NONE=0,MAIN=1,SHADOW=2;
    int wke$materialKind();
    void wke$materialKind(int kind);
}
