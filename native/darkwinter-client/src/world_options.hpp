#pragma once
#include "settings.hpp"
#include <array>
#include <string_view>

namespace dw::client {
inline int worldInteger(const Settings& settings,const std::string& key,int fallback,int lo,int hi) {
    const double value=settings.option(key,fallback);
    if(!std::isfinite(value)||std::floor(value)!=value||value<lo||value>hi)
        throw std::runtime_error("Invalid world option: "+key);
    return int(value);
}
inline void putWorldOption(Settings& settings,const std::string& key,double value) {
    if(!settings.options.contains(key)&&settings.options.size()>=1024)
        throw std::runtime_error("World option capacity exceeded");
    settings.options.insert_or_assign(key,value);
}
struct NativeWorldOptions {
    int gameMode=0,personalGameMode=0,difficulty=2;
    bool hardcore=false,commands=false,difficultyLocked=false,advanceTime=true,spectatorsGenerateChunks=true;
    bool mayFly()const{return personalGameMode==1||personalGameMode==3;}
    bool mayBuild()const{return personalGameMode<2;}
    bool noClip()const{return personalGameMode==3;}
    bool invulnerable()const{return mayFly();}
};
inline NativeWorldOptions nativeWorldOptions(const Settings& source) {
    NativeWorldOptions value;
    const int creationMode=worldInteger(source,"create.gameMode",0,0,2);
    value.hardcore=creationMode==1;
    const int initialMode=creationMode==2?1:0;
    value.gameMode=worldInteger(source,"world.gameMode",initialMode,0,3);
    value.personalGameMode=worldInteger(source,"world.personalGameMode",value.gameMode,0,3);
    value.difficulty=worldInteger(source,"world.difficulty",
        value.hardcore?3:worldInteger(source,"create.difficulty",2,0,3),0,3);
    value.commands=worldInteger(source,"world.commands",
        value.hardcore?0:worldInteger(source,"create.commands",0,0,1),0,1)!=0;
    value.difficultyLocked=worldInteger(source,"world.difficultyLocked",value.hardcore?1:0,0,1)!=0;
    value.advanceTime=worldInteger(source,"rule.advance_time",1,0,1)!=0;
    value.spectatorsGenerateChunks=worldInteger(source,"rule.spectators_generate_chunks",1,0,1)!=0;
    if(value.hardcore&&(value.difficulty!=3||!value.difficultyLocked))
        throw std::runtime_error("Invalid hardcore world settings");
    return value;
}
inline void storeWorldOptions(Settings& target,const NativeWorldOptions& value) {
    putWorldOption(target,"world.gameMode",value.gameMode);
    putWorldOption(target,"world.personalGameMode",value.personalGameMode);
    putWorldOption(target,"world.commands",value.commands);
    putWorldOption(target,"world.difficulty",value.difficulty);
    putWorldOption(target,"world.difficultyLocked",value.difficultyLocked);
}
inline Settings newNativeWorldOptions(Settings source) {
    // A new world gets creation-screen values, never the previous world's abilities or clocks.
    std::erase_if(source.options,[](const auto& row){return row.first.starts_with("world.");});
    std::erase_if(source.textOptions,[](const auto& row){return row.first.starts_with("world.");});
    const auto value=nativeWorldOptions(source);
    storeWorldOptions(source,value);
    putWorldOption(source,"world.daySeconds",0);
    putWorldOption(source,"world.flying",0);
    return source;
}
inline bool nativeFlying(const Settings& source,const NativeWorldOptions& value) {
    if(value.noClip())return true;
    const bool stored=worldInteger(source,"world.flying",0,0,1)!=0;
    return value.mayFly()&&stored;
}
inline double nativeDaySeconds(const Settings& source,double legacySeconds) {
    const double result=source.option("world.daySeconds",legacySeconds);
    if(!std::isfinite(result)||result<0)throw std::runtime_error("Invalid saved calendar clock");
    return result;
}
inline Settings applyNativeWorldOptions(const Settings& applied,const Settings& staged,bool flying) {
    const auto before=nativeWorldOptions(applied);
    auto requested=[&](const char* name,int lo,int hi) {
        const std::string key=std::string("world.draft.")+name;
        if(!staged.options.contains(key))throw std::runtime_error("Incomplete world settings request: "+key);
        return worldInteger(staged,key,0,lo,hi);
    };
    NativeWorldOptions after=before;
    after.gameMode=requested("gameMode",0,3);
    after.personalGameMode=requested("personalGameMode",0,3);
    after.commands=requested("commands",0,1)!=0;
    after.difficulty=requested("difficulty",0,3);
    after.difficultyLocked=requested("difficultyLocked",0,1)!=0;
    const bool lan=requested("lan",0,1)!=0;
    const int guestCommands=requested("guestCommands",0,1),forceGameMode=requested("forceGameMode",0,1);
    if(lan)throw std::runtime_error("Сетевая игра ещё не подключена. Настройки мира не применены.");
    if(guestCommands!=0||forceGameMode!=1)
        throw std::runtime_error("Сетевые настройки недоступны в одиночной игре. Изменения не применены.");
    if(before.hardcore&&(after.gameMode!=before.gameMode||after.personalGameMode!=before.personalGameMode
        ||(!before.commands&&after.commands)||after.difficulty!=before.difficulty||after.difficultyLocked!=before.difficultyLocked))
        throw std::runtime_error("Эти параметры нельзя изменить в режиме «Хардкор».");
    if(before.difficultyLocked&&(!after.difficultyLocked||after.difficulty!=before.difficulty))
        throw std::runtime_error("Сложность этого мира уже зафиксирована.");
    if(!before.difficultyLocked&&after.difficultyLocked
        &&worldInteger(staged,"world.draft.lockConfirmed",0,0,1)!=1)
        throw std::runtime_error("Подтвердите фиксацию сложности.");
    if(!after.commands&&(after.gameMode!=before.gameMode||after.personalGameMode!=after.gameMode))
        throw std::runtime_error("Для изменения режима игры нужно разрешить команды.");
    Settings next=applied;
    storeWorldOptions(next,after);
    putWorldOption(next,"world.flying",after.noClip()||(after.mayFly()&&flying));
    return next;
}
struct NativeRuleCatalog {
    struct Rule {long long minimum=0,maximum=1;};
    std::map<std::string,Rule> rules;
    explicit NativeRuleCatalog(const std::filesystem::path& path) {
        std::ifstream input(path);if(!input)throw std::runtime_error("World rule catalogue unavailable");
        std::string line;
        while(std::getline(input,line)) {
            if(line.empty()||line.front()=='#')continue;
            if(line.size()>16384||rules.size()>=256)throw std::runtime_error("World rule catalogue exceeds bounds");
            std::array<std::string,8> cells;std::istringstream row(line);
            for(auto& cell:cells)if(!(row>>std::quoted(cell)))throw std::runtime_error("World rule catalogue malformed");
            if(cells[0].empty()||!Settings::validKey(cells[0])||(cells[2]!="boolean"&&cells[2]!="integer"))
                throw std::runtime_error("World rule identity invalid");
            size_t usedLo=0,usedHi=0;
            const long long lo=std::stoll(cells[4],&usedLo),hi=std::stoll(cells[5],&usedHi);
            if(usedLo!=cells[4].size()||usedHi!=cells[5].size()||lo>hi||lo<INT32_MIN||hi>INT32_MAX
                ||(cells[2]=="boolean"&&(lo!=0||hi!=1))||!rules.emplace(cells[0],Rule{lo,hi}).second)
                throw std::runtime_error("World rule bounds invalid");
        }
        if(rules.empty())throw std::runtime_error("World rule catalogue empty");
    }
};
inline Settings applyNativeGameRules(const Settings& applied,const Settings& staged,const NativeRuleCatalog& catalogue) {
    Settings next=applied;bool changed=false;
    const auto access=nativeWorldOptions(applied);
    for(const auto& [key,value]:staged.options) {
        constexpr std::string_view prefix="world.ruleDraft.";
        if(!key.starts_with(prefix))continue;
        if(!access.commands||access.hardcore)throw std::runtime_error("Нет права изменять игровые правила этого мира.");
        const auto name=key.substr(prefix.size());
        const auto found=catalogue.rules.find(name);
        if(found==catalogue.rules.end()||!std::isfinite(value)||std::floor(value)!=value
            ||value<double(found->second.minimum)||value>double(found->second.maximum))
            throw std::runtime_error("Invalid game rule: "+name);
        putWorldOption(next,"rule."+name,value);changed=true;
    }
    if(changed)(void)nativeWorldOptions(next);
    return next;
}
inline void clearWorldStaging(Settings& settings) {
    std::erase_if(settings.options,[](const auto& row) {
        return row.first.starts_with("world.draft.")||row.first.starts_with("world.ruleDraft.");
    });
}
}
