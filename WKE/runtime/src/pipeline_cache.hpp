#pragma once
#include "vulkan_api.hpp"
#include <filesystem>
#include <fstream>
#include <vector>
#include <cstring>
#include <sstream>
#include <iomanip>

namespace dw::client::pipeline_disk {
inline constexpr size_t maximumBytes=64*1024*1024;
struct DiskHeader {char magic[8]={'D','W','V','K','C','0','2',0};uint64_t length=0,digest=0;};
static_assert(sizeof(DiskHeader)==24);
inline uint64_t digest(const std::vector<uint8_t>& bytes){
 uint64_t value=14695981039346656037ull;
 for(uint8_t byte:bytes){value^=byte;value*=1099511628211ull;}
 return value;
}
inline std::filesystem::path path(const VkPhysicalDeviceProperties& p){
 DWORD count=GetEnvironmentVariableW(L"LOCALAPPDATA",nullptr,0);
 if(!count||count>32768)return {};
 std::vector<wchar_t> root(count);
 DWORD length=GetEnvironmentVariableW(L"LOCALAPPDATA",root.data(),count);
 if(!length||length>=count)return {};
 std::ostringstream name;name<<"pipeline-v2-"<<std::hex<<p.vendorID<<'-'<<p.deviceID<<'-'<<p.driverVersion<<'-';
 for(uint8_t byte:p.pipelineCacheUUID)name<<std::setw(2)<<std::setfill('0')<<unsigned(byte);
 return std::filesystem::path(root.data())/"Winterkore"/"DarkWinter"/"cache"/"vulkan"/(name.str()+".bin");
}
inline std::vector<uint8_t> read(const std::filesystem::path& file,const VkPhysicalDeviceProperties& p)noexcept{
 try{
  if(file.empty())return {};
  std::ifstream input(file,std::ios::binary|std::ios::ate);if(!input)return {};
  auto length=input.tellg();
  if(length<std::streamoff(sizeof(DiskHeader)+sizeof(VkPipelineCacheHeaderVersionOne))||length>std::streamoff(maximumBytes+sizeof(DiskHeader)))return {};
  input.seekg(0);DiskHeader disk{},expected{};
  if(!input.read(reinterpret_cast<char*>(&disk),sizeof(disk))
    ||std::memcmp(disk.magic,expected.magic,sizeof(disk.magic))!=0
    ||disk.length!=uint64_t(length)-sizeof(DiskHeader))return {};
  std::vector<uint8_t> bytes(static_cast<size_t>(disk.length));
  if(!input.read(reinterpret_cast<char*>(bytes.data()),std::streamsize(bytes.size()))||digest(bytes)!=disk.digest)return {};
  VkPipelineCacheHeaderVersionOne header{};std::memcpy(&header,bytes.data(),sizeof(header));
  if(header.headerSize<sizeof(header)||header.headerSize>bytes.size()||header.headerVersion!=VK_PIPELINE_CACHE_HEADER_VERSION_ONE
    ||header.vendorID!=p.vendorID||header.deviceID!=p.deviceID
    ||std::memcmp(header.pipelineCacheUUID,p.pipelineCacheUUID,VK_UUID_SIZE)!=0)return {};
  return bytes;
 }catch(...){return {};}
}
inline void write(const std::filesystem::path& file,const std::vector<uint8_t>& bytes)noexcept{
 std::filesystem::path temporary;
 try{
  if(file.empty()||bytes.empty())return;
  std::filesystem::create_directories(file.parent_path());
  temporary=file;temporary+=L".tmp-"+std::to_wstring(GetCurrentProcessId());
  {
   std::ofstream output(temporary,std::ios::binary|std::ios::trunc);if(!output)return;
   DiskHeader disk{};disk.length=bytes.size();disk.digest=digest(bytes);
   output.write(reinterpret_cast<const char*>(&disk),sizeof(disk));
   output.write(reinterpret_cast<const char*>(bytes.data()),std::streamsize(bytes.size()));output.flush();
   if(!output){output.close();std::error_code error;std::filesystem::remove(temporary,error);return;}
  }
  if(!MoveFileExW(temporary.c_str(),file.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH)){
   std::error_code error;std::filesystem::remove(temporary,error);
  }
 }catch(...){if(!temporary.empty()){std::error_code error;std::filesystem::remove(temporary,error);}}
}
}
