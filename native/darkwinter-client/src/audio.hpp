#pragma once
#include "scene.hpp"
#include <windows.h>
#include <mmsystem.h>
namespace dw::client {
class StepAudio {
 struct Voice {WAVEHDR header{};std::vector<int16_t> pcm;bool prepared=false;};
 std::array<std::vector<int16_t>,16> samples;std::array<Voice,6> voices;HWAVEOUT output=nullptr;uint32_t variant=0;
public:
 uint64_t played=0;bool enabled=false;float volume=1;bool muted=false;
 StepAudio(const std::filesystem::path& path,bool audible);
 ~StepAudio();
 void step(const std::string& surface,bool left,float strength=1);
};
}
