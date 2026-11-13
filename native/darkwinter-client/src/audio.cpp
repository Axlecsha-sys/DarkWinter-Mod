#include "audio.hpp"
#include <cstring>
namespace dw::client {
StepAudio::StepAudio(const std::filesystem::path& path,bool audible){
 auto b=read_file(path,4*1024*1024);size_t at=32;
 auto need=[](bool ok){if(!ok)throw std::runtime_error("Footstep PCM package invalid");};
 auto word=[&](size_t p){need(p+4<=b.size());uint32_t v;std::memcpy(&v,b.data()+p,4);return v;};
 need(b.size()>=36&&std::memcmp(b.data(),"DWSOUND\0",8)==0&&word(8)==1&&word(12)==44100);
 uint64_t length=0,hash=0;std::memcpy(&length,b.data()+16,8);std::memcpy(&hash,b.data()+24,8);
 need(length==b.size()-32&&checksum(b.data()+32,b.size()-32)==hash&&word(at)==16);at+=4;size_t largest=0;
 for(auto& sample:samples){uint32_t count=word(at);at+=4;need(count>0&&count<=44100&&uint64_t(at)+count*2<=b.size());sample.resize(count);std::memcpy(sample.data(),b.data()+at,count*2);at+=count*2;largest=std::max(largest,size_t(count));}
 need(at==b.size());for(auto& voice:voices)voice.pcm.resize(largest*2);
 if(!audible)return;
 WAVEFORMATEX f{};f.wFormatTag=WAVE_FORMAT_PCM;f.nChannels=2;f.nSamplesPerSec=44100;f.wBitsPerSample=16;f.nBlockAlign=4;f.nAvgBytesPerSec=f.nSamplesPerSec*f.nBlockAlign;
 enabled=waveOutOpen(&output,WAVE_MAPPER,&f,0,0,CALLBACK_NULL)==MMSYSERR_NOERROR;
}
StepAudio::~StepAudio(){if(!output)return;waveOutReset(output);for(auto& voice:voices)if(voice.prepared)waveOutUnprepareHeader(output,&voice.header,sizeof(voice.header));waveOutClose(output);}
void StepAudio::step(const std::string& surface,bool left,float strength){
 if(!enabled||muted)return;strength*=volume;uint32_t group=(surface.find("snow")!=std::string::npos&&surface.find("snowy=false")==std::string::npos)?0:surface.find("gravel")!=std::string::npos||surface.find("dirt")!=std::string::npos||surface.find("grass")!=std::string::npos?2:surface.find("log")!=std::string::npos||surface.find("wood")!=std::string::npos?3:1;
 for(auto& voice:voices){
  if(voice.prepared&&!(voice.header.dwFlags&WHDR_DONE))continue;
  if(voice.prepared){if(waveOutUnprepareHeader(output,&voice.header,sizeof(voice.header))!=MMSYSERR_NOERROR)continue;voice.prepared=false;}
  const auto& sample=samples[group*4+(variant++%4)];
  float l=(left?.34f:.23f)*strength,r=(left?.23f:.34f)*strength;
  for(size_t i=0;i<sample.size();i++){voice.pcm[i*2]=int16_t(std::clamp(float(sample[i])*l,-32768.f,32767.f));voice.pcm[i*2+1]=int16_t(std::clamp(float(sample[i])*r,-32768.f,32767.f));}
  voice.header={};voice.header.lpData=reinterpret_cast<char*>(voice.pcm.data());voice.header.dwBufferLength=DWORD(sample.size()*4);
  if(waveOutPrepareHeader(output,&voice.header,sizeof(voice.header))!=MMSYSERR_NOERROR)return;voice.prepared=true;
  if(waveOutWrite(output,&voice.header,sizeof(voice.header))==MMSYSERR_NOERROR)++played;
  return;
 }
}
}
