#pragma once
// Native world metadata and bounded file operations used by the world UI.
#include "ui.hpp"
#include <chrono>
#include <random>
#include <set>

namespace dw::client {
inline bool worldOptionKey(const std::string& key) {
    return key.rfind("create.",0)==0 || key.rfind("test.",0)==0
        || key.rfind("rule.",0)==0 || key.rfind("gamerule.",0)==0
        || key.rfind("experiment.",0)==0
        || key=="world.gameMode" || key=="world.personalGameMode" || key=="world.commands"
        || key=="world.difficulty" || key=="world.difficultyLocked"
        || key=="world.daySeconds" || key=="world.flying";
}
inline Settings worldDraft(const Settings& source) {
    Settings result;
    for(const auto& [key,value]:source.options) if(worldOptionKey(key)) result.options[key]=value;
    for(const auto& [key,value]:source.textOptions) if(worldOptionKey(key)) result.textOptions[key]=value;
    return result;
}
inline void applyWorldDraft(Settings& target,const Settings& draft) {
    std::erase_if(target.options,[](const auto& row){return worldOptionKey(row.first);});
    std::erase_if(target.textOptions,[](const auto& row){return worldOptionKey(row.first);});
    for(const auto& [key,value]:draft.options) if(worldOptionKey(key)) target.options[key]=value;
    for(const auto& [key,value]:draft.textOptions) if(worldOptionKey(key)) target.textOptions[key]=value;
}
inline std::filesystem::path worldSidecar(std::filesystem::path file,const char* extension) {
    file.replace_extension(extension);return file;
}
inline uint64_t seedFromText(const std::wstring& text) {
    auto first=text.find_first_not_of(L" \t\r\n"),last=text.find_last_not_of(L" \t\r\n");
    if(first==std::wstring::npos) {
        std::random_device random;
        return (uint64_t(random())<<32)^uint64_t(random())^uint64_t(GetTickCount64());
    }
    auto value=text.substr(first,last-first+1);size_t used=0;
    try {auto number=std::stoll(value,&used);if(used==value.size())return uint64_t(number);}catch(const std::exception&){}
    // Java String.hashCode uses UTF-16 code units; wchar_t is UTF-16 in this Windows client.
    uint32_t hash=0;for(wchar_t c:value)hash=hash*31u+uint16_t(c);
    return uint64_t(int64_t(int32_t(hash)));
}
inline std::vector<std::filesystem::path> worldSnapshotFiles(const std::filesystem::path& saves,const WorldRow& row) {
    std::set<std::filesystem::path> stems;stems.insert(row.file.stem());
    if(!row.id.empty()) for(const auto& entry:std::filesystem::directory_iterator(saves)) {
        if(!entry.is_regular_file()||entry.is_symlink()||entry.path().extension()!=".info")continue;
        std::ifstream stream(entry.path());std::string name,id;uint64_t seed=0;
        if(stream>>std::quoted(name)>>seed>>std::quoted(id);stream&&id==row.id)stems.insert(entry.path().stem());
    }
    std::vector<std::filesystem::path> files;
    for(const auto& stem:stems)for(const char* extension:{".dwv",".dwg",".state",".info",".options",".png"}) {
        auto file=saves/stem;file+=extension;
        if(std::filesystem::is_regular_file(file)&&!std::filesystem::is_symlink(file))files.push_back(file);
    }
    return files;
}
inline std::filesystem::path worldBackup(const std::filesystem::path& saves,const WorldRow& row,bool removeFromList=false) {
    const auto files=worldSnapshotFiles(saves,row);
    if(files.empty())throw std::runtime_error("World files are unavailable");
    auto stamp=std::to_string(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());
    auto target=saves/"backups"/((removeFromList?"deleted-":"world-")+stamp);
    for(unsigned suffix=1;std::filesystem::exists(target);++suffix)target=saves/"backups"/((removeFromList?"deleted-":"world-")+stamp+"-"+std::to_string(suffix));
    std::filesystem::create_directories(target);
    if(!removeFromList) {
        for(const auto& file:files)std::filesystem::copy_file(file,target/file.filename());
        return target;
    }
    std::vector<std::filesystem::path> moved;
    try {for(const auto& file:files){std::filesystem::rename(file,target/file.filename());moved.push_back(file);}}
    catch(...) {for(auto it=moved.rbegin();it!=moved.rend();++it){std::error_code error;std::filesystem::rename(target/it->filename(),*it,error);}throw;}
    return target;
}
inline void renameWorld(const std::filesystem::path& saves,const WorldRow& row,const std::wstring& name) {
    if(name.empty()||name.size()>128)throw std::runtime_error("World name must contain 1 to 128 characters");
    int size=WideCharToMultiByte(CP_UTF8,0,name.data(),int(name.size()),nullptr,0,nullptr,nullptr);
    std::string encoded(size,0);WideCharToMultiByte(CP_UTF8,0,name.data(),int(name.size()),encoded.data(),size,nullptr,nullptr);
    auto writeName=[&](const std::filesystem::path& file,uint64_t seed,const std::string& id){
        auto temporary=file;temporary+=".rename.tmp";
        {std::ofstream out(temporary);out<<std::quoted(encoded)<<' '<<seed<<' '<<std::quoted(id)<<'\n';if(!out)throw std::runtime_error("World name could not be written");}
        if(!MoveFileExW(temporary.c_str(),file.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("World name could not be replaced");
    };
    bool found=false;
    for(const auto& file:worldSnapshotFiles(saves,row))if(file.extension()==".info") {
        std::ifstream old(file);std::string previous,id;uint64_t seed=row.seed;
        if(!(old>>std::quoted(previous)>>seed))throw std::runtime_error("World metadata is invalid");
        if(!(old>>std::quoted(id)))id=row.id;old.close();writeName(file,seed,id);found=true;
    }
    if(!found)writeName(worldSidecar(row.file,".info"),row.seed,row.id);
}
}
