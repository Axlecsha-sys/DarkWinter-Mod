// DarkWinter standalone voxel core ABI v1. No Minecraft, JVM or graphics dependencies.
#pragma once
#include <stdint.h>
#if defined(_WIN32)
#define DW_API __declspec(dllexport)
#else
#define DW_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif
enum dw_result {
    DW_OK=0, DW_PENDING=1, DW_INVALID=-1, DW_BUSY=-2, DW_NOT_FOUND=-3,
    DW_CANCELLED=-4, DW_STALE=-5, DW_DEPENDENCY=-6, DW_IO=-7,
    DW_CORRUPT=-8, DW_LIMIT=-9, DW_INTERNAL=-10
};
enum { DW_SIDE=16, DW_CELLS=4096, DW_PREPARED_BYTES=586, DW_MATERIAL_AIR=1 };
enum dw_stat {
    DW_WORKERS, DW_QUEUED, DW_RUNNING, DW_HANDLES, DW_PEAK_RUNNING,
    DW_SECTIONS, DW_MATERIALS, DW_SNAPSHOT_BYTES, DW_PREPARED_PAYLOAD_BYTES,
    DW_PUBLISHED, DW_STALE_RESULTS
};
DW_API uint32_t dw_core_abi_version(void);
/* Handle functions must not race with destroy. Destroy cancels and joins its workers. */
DW_API int32_t dw_core_create(uint32_t workers,uint32_t capacity,uint32_t max_sections,void** out);
DW_API int32_t dw_core_destroy(void* core);
DW_API int32_t dw_core_define_material(void* core,uint32_t id,uint32_t flags,const uint8_t* name,uint32_t bytes);
/* Input is copied synchronously. Revision must increase strictly for an existing section. */
DW_API int32_t dw_core_put(void* core,int32_t x,int32_t y,int32_t z,uint64_t revision,const uint32_t* cells,uint32_t count);
DW_API int32_t dw_core_prepare(void* core,int32_t x,int32_t y,int32_t z,uint64_t revision,uint64_t dependency,uint64_t* ticket);
DW_API int32_t dw_core_poll(void* core,uint64_t ticket);
DW_API int32_t dw_core_cancel(void* core,uint64_t ticket);
DW_API int32_t dw_core_release(void* core,uint64_t ticket);
DW_API int32_t dw_core_copy_section(void* core,int32_t x,int32_t y,int32_t z,uint32_t* out,uint32_t count,uint64_t* revision);
/* Occupancy bitsets: 16^3,8^3,4^3,2^3,root; x changes fastest, then z, then y. */
DW_API int32_t dw_core_copy_prepared(void* core,int32_t x,int32_t y,int32_t z,uint64_t revision,uint8_t* out,uint32_t bytes);
DW_API int32_t dw_core_copy_index(void* core,int32_t* xyz,uint64_t* revisions,uint32_t capacity);
/* UTF-8 paths. Save creates a new file exclusively. Load requires an empty world. */
DW_API int32_t dw_core_save(void* core,const uint8_t* path,uint32_t bytes);
DW_API int32_t dw_core_load(void* core,const uint8_t* path,uint32_t bytes);
DW_API uint64_t dw_core_stat(void* core,uint32_t field);
#ifdef __cplusplus
}
#endif
