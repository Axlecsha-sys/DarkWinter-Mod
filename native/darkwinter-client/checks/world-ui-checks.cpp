#include "../src/world_ui_store.hpp"
#include "../src/world_options.hpp"
#include <iostream>
#include <limits>
using namespace dw::client;
static void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}

static Settings requestFor(const Settings& applied){
 const auto value=nativeWorldOptions(applied);Settings request;
 for(const auto& [key,number]:std::map<std::string,double>{
     {"gameMode",double(value.gameMode)},{"personalGameMode",double(value.personalGameMode)},
     {"commands",double(value.commands)},{"difficulty",double(value.difficulty)},
     {"difficultyLocked",double(value.difficultyLocked)},{"lan",0},{"guestCommands",0},{"forceGameMode",1}})
  request.options["world.draft."+key]=number;
 return request;
}
static bool sameOptions(const Settings& a,const Settings& b){
 return a.options==b.options&&a.textOptions==b.textOptions;
}
template<class Action> static void rejects(Action action,const char* message){
 bool rejected=false;try{action();}catch(const std::exception&){rejected=true;}
 require(rejected,message);
}
static void nativeOptionsChecks(const std::filesystem::path& package,const std::filesystem::path& root){
 Settings legacy;legacy.options={{"create.gameMode",2},{"create.difficulty",1},{"create.commands",1}};
 const auto old=nativeWorldOptions(legacy);
 require(old.gameMode==1&&old.personalGameMode==1&&old.difficulty==1&&old.commands,
         "Old save falls back to creation settings; creation mode 2 means canonical creative 1");
 require(nativeDaySeconds(legacy,123.25)==123.25&&!nativeFlying(legacy,old),"Old save calendar and flying fallbacks");
 Settings empty;const auto defaults=nativeWorldOptions(empty);
 require(defaults.gameMode==0&&defaults.personalGameMode==0&&defaults.difficulty==2&&!defaults.commands,
         "Save without options has survival defaults");

 Settings stale=legacy;stale.options["world.gameMode"]=3;stale.options["world.personalGameMode"]=3;
 stale.options["world.daySeconds"]=999;stale.options["world.flying"]=1;stale.options["world.draft.commands"]=0;
 stale.textOptions["world.draft.port"]="private draft";
 auto creative=newNativeWorldOptions(stale);const auto fresh=nativeWorldOptions(creative);
 require(fresh.gameMode==1&&fresh.personalGameMode==1&&nativeDaySeconds(creative,-1)==0
         &&!nativeFlying(creative,fresh)&&!creative.options.contains("world.draft.commands")
         &&!creative.textOptions.contains("world.draft.port"),"Creation never inherits previous world state");

 auto base=newNativeWorldOptions(Settings{});
 const auto unchanged=base;
 auto rejectOptions=[&](Settings request,const char* message){
  rejects([&]{(void)applyNativeWorldOptions(base,request,false);},message);
  require(sameOptions(base,unchanged),"Rejected settings request mutated applied state");
 };
 for(const char* key:{"gameMode","personalGameMode","commands","difficulty","difficultyLocked","lan","guestCommands","forceGameMode"}){
  auto request=requestFor(base);request.options.erase(std::string("world.draft.")+key);
  rejectOptions(request,"Incomplete settings request must be rejected");
 }
 for(const auto& [key,value]:std::vector<std::pair<std::string,double>>{
     {"gameMode",-1},{"gameMode",4},{"personalGameMode",4},{"difficulty",4},{"difficulty",1.5},
     {"commands",2},{"commands",std::numeric_limits<double>::quiet_NaN()},
     {"difficulty",std::numeric_limits<double>::infinity()},{"lan",2}}){
  auto request=requestFor(base);request.options["world.draft."+key]=value;
  rejectOptions(request,"Out-of-range or non-integral world setting must be rejected");
 }
 for(const auto& [key,value]:std::map<std::string,double>{{"lan",1},{"guestCommands",1},{"forceGameMode",0}}){
  auto request=requestFor(base);request.options["world.draft."+key]=value;
  request.options["world.draft.difficulty"]=1;
  rejectOptions(request,"Unavailable LAN settings must reject the entire transaction");
 }
 for(const char* key:{"gameMode","personalGameMode"}){
  auto request=requestFor(base);request.options[std::string("world.draft.")+key]=1;
  rejectOptions(request,"Mode changes without command permission must be rejected");
 }
 auto difficultyOnly=requestFor(base);difficultyOnly.options["world.draft.difficulty"]=1;
 require(nativeWorldOptions(applyNativeWorldOptions(base,difficultyOnly,false)).difficulty==1,
         "Unlocked difficulty does not require command permission");

 auto allow=requestFor(base);allow.options["world.draft.commands"]=1;
 auto permitted=applyNativeWorldOptions(base,allow,false);
 require(nativeWorldOptions(permitted).commands&&sameOptions(base,unchanged),"Enable commands commits to a new settings value");
 auto defaultRequest=requestFor(permitted);defaultRequest.options["world.draft.gameMode"]=1;
 auto defaultCreative=applyNativeWorldOptions(permitted,defaultRequest,false);
 auto defaultValue=nativeWorldOptions(defaultCreative);
 require(defaultValue.gameMode==1&&defaultValue.personalGameMode==0&&!defaultValue.mayFly(),
         "Default mode never silently changes the current player's personal mode");
 auto personal=requestFor(defaultCreative);personal.options["world.draft.personalGameMode"]=3;
 auto spectator=applyNativeWorldOptions(defaultCreative,personal,false);
 const auto spectatorValue=nativeWorldOptions(spectator);
 require(spectatorValue.gameMode==1&&spectatorValue.personalGameMode==3&&spectatorValue.noClip()
         &&spectatorValue.mayFly()&&spectatorValue.invulnerable()&&!spectatorValue.mayBuild()
         &&nativeFlying(spectator,spectatorValue),"Spectator has flying, noclip and restricted building");
 for(int mode:{0,1,2}){
  auto request=requestFor(spectator);request.options["world.draft.personalGameMode"]=mode;
  const auto changed=applyNativeWorldOptions(spectator,request,true);const auto value=nativeWorldOptions(changed);
  require(value.personalGameMode==mode&&value.gameMode==1&&value.mayFly()==(mode==1)
          &&value.mayBuild()==(mode<2)&&!value.noClip()&&value.invulnerable()==(mode==1)
          &&nativeFlying(changed,value)==(mode==1),"Personal mode abilities and flying transitions");
 }

 auto lock=requestFor(base);lock.options["world.draft.difficulty"]=3;lock.options["world.draft.difficultyLocked"]=1;
 rejectOptions(lock,"Difficulty lock requires explicit confirmation");
 lock.options["world.draft.lockConfirmed"]=1;
 const auto locked=applyNativeWorldOptions(base,lock,false);
 require(nativeWorldOptions(locked).difficulty==3&&nativeWorldOptions(locked).difficultyLocked,"Confirmed difficulty lock commits");
 for(const auto& [key,value]:std::map<std::string,double>{{"difficulty",2},{"difficultyLocked",0}}){
  auto request=requestFor(locked);request.options["world.draft."+key]=value;
  rejects([&]{(void)applyNativeWorldOptions(locked,request,false);},"Locked difficulty cannot change or unlock");
 }
 require(sameOptions(locked,applyNativeWorldOptions(locked,requestFor(locked),false)),"Unchanged locked settings remain valid");

 Settings hardcoreSeed;hardcoreSeed.options["create.gameMode"]=1;
 const auto hardcore=newNativeWorldOptions(hardcoreSeed);const auto hard=nativeWorldOptions(hardcore);
 require(hard.hardcore&&hard.gameMode==0&&hard.personalGameMode==0&&hard.difficulty==3&&hard.difficultyLocked,
         "Hardcore starts in survival with locked hard difficulty");
 for(const auto& [key,value]:std::map<std::string,double>{{"commands",1},{"gameMode",1},{"personalGameMode",3},{"difficulty",2},{"difficultyLocked",0}}){
  auto request=requestFor(hardcore);request.options["world.draft."+key]=value;
  rejects([&]{(void)applyNativeWorldOptions(hardcore,request,false);},"Hardcore permissions are immutable");
 }
 Settings invalidClock;invalidClock.options["world.daySeconds"]=-1;
 rejects([&]{(void)nativeDaySeconds(invalidClock,0);},"Negative calendar clock rejected");
 invalidClock.options["world.daySeconds"]=std::numeric_limits<double>::infinity();
 rejects([&]{(void)nativeDaySeconds(invalidClock,0);},"Nonfinite calendar clock rejected");

 const NativeRuleCatalog catalogue(package/"ui-game-rules.tsv");
 require(catalogue.rules.contains("advance_time")&&catalogue.rules.contains("max_snow_accumulation_height"),
         "Actual packaged rule catalogue contains the audited rules");
 require(catalogue.rules.at("max_snow_accumulation_height").minimum==0
         &&catalogue.rules.at("max_snow_accumulation_height").maximum==8,"Packaged integer rule bounds");
 Settings timeRequest;timeRequest.options["world.ruleDraft.advance_time"]=0;
 timeRequest.options["world.draft.gameMode"]=2;
 const auto frozen=applyNativeGameRules(permitted,timeRequest,catalogue);
 require(!nativeWorldOptions(frozen).advanceTime&&nativeWorldOptions(frozen).gameMode==0,
         "Rules commit independently and never apply the outer world-option draft");
 require(!permitted.options.contains("rule.advance_time"),"Rule commit preserves its input settings");
 for(double invalid:{-1.,9.,1.5,std::numeric_limits<double>::infinity()}){
  auto request=timeRequest;request.options["world.ruleDraft.max_snow_accumulation_height"]=invalid;
  const auto before=permitted;
  rejects([&]{(void)applyNativeGameRules(permitted,request,catalogue);},"Invalid integer rule rejects the complete rule transaction");
  require(sameOptions(permitted,before),"Invalid rule must not partially commit advance_time");
 }
 auto unknown=timeRequest;unknown.options["world.ruleDraft.unknown_test_rule"]=1;
 rejects([&]{(void)applyNativeGameRules(permitted,unknown,catalogue);},"Unknown game rule rejected");
 auto invalidBoolean=timeRequest;invalidBoolean.options["world.ruleDraft.advance_time"]=2;
 rejects([&]{(void)applyNativeGameRules(permitted,invalidBoolean,catalogue);},"Boolean game rule range enforced");
 rejects([&]{(void)applyNativeGameRules(base,timeRequest,catalogue);},"Rules require applied command permission");
 rejects([&]{(void)applyNativeGameRules(hardcore,timeRequest,catalogue);},"Hardcore rule editing rejected");
 auto resume=timeRequest;resume.options["world.ruleDraft.advance_time"]=1;
 require(nativeWorldOptions(applyNativeGameRules(frozen,resume,catalogue)).advanceTime,"Time rule can resume after a committed freeze");

 Settings staged=spectator;staged.options["world.draft.commands"]=0;staged.options["world.ruleDraft.advance_time"]=0;
 staged.options["clouds"]=2;staged.textOptions["world.draft.port"]="12345";staged.textOptions["language"]="ru_ru";
 auto saved=worldDraft(staged);
 require(saved.options.contains("world.gameMode")&&saved.options.contains("world.personalGameMode")
         &&!saved.options.contains("world.draft.commands")&&!saved.options.contains("world.ruleDraft.advance_time")
         &&!saved.options.contains("clouds")&&!saved.textOptions.contains("world.draft.port")
         &&!saved.textOptions.contains("language"),"World persistence excludes staging and client preferences");
 clearWorldStaging(staged);
 require(!staged.options.contains("world.draft.commands")&&!staged.options.contains("world.ruleDraft.advance_time")
         &&staged.options.contains("world.personalGameMode"),"Clearing staging preserves canonical values");
 Settings client;client.options["clouds"]=2;client.options["rule.obsolete_fixture"]=1;
 client.textOptions["language"]="ru_ru";client.textOptions["create.biome"]="old fixture";
 applyWorldDraft(client,saved);
 require(client.options["clouds"]==2&&client.textOptions["language"]=="ru_ru"
         &&!client.options.contains("rule.obsolete_fixture")&&!client.textOptions.contains("create.biome")
         &&nativeWorldOptions(client).personalGameMode==3,"World replacement removes stale world keys and preserves client preferences");
 saved.options["world.daySeconds"]=456.125;saved.save(root/"native-options.options");
 Settings roundtrip;roundtrip.load(root/"native-options.options");
 require(sameOptions(saved,worldDraft(roundtrip))&&nativeDaySeconds(roundtrip,0)==456.125
         &&nativeFlying(roundtrip,nativeWorldOptions(roundtrip)),"Canonical options and abilities survive exact sidecar round trip");
}

int main(int argc,char** argv){
 try{
  require(argc>=3,"Expected package and isolated fixture path");
  auto root=std::filesystem::path(argv[2]);std::filesystem::create_directories(root);
  nativeOptionsChecks(std::filesystem::path(argv[1]),root);
  require(seedFromText(L" 19971008 \t")==19971008,"Numeric seed whitespace");
  require(seedFromText(L"-1")==UINT64_MAX,"Signed seed compatibility");
  require(seedFromText(L"abc")==96354,"Text seed uses source UTF-16 hash");
  require(seedFromText(L"\u51ac")==20908,"Unicode seed uses UTF-16");
  Settings settings;settings.setOption("create.difficulty",3);settings.setOption("rule.keep_inventory",1);
  settings.setTextOption("create.biome","minecraft:snowy_taiga");settings.setTextOption("language","ru_ru");
  settings.setOption("sound.players",.3);settings.setOption("clouds",2);settings.sensitivity=.7f;
  settings.save(root/"settings.cfg");Settings restored;restored.load(root/"settings.cfg");
  require(restored.option("clouds",0)==2&&restored.clouds,"Cloud quality survives persistence");
  require(std::abs(restored.sensitivity-.7f)<.0001f,"New sensitivity is not migrated twice");
  auto draft=worldDraft(settings);require(draft.options.size()==2&&draft.textOptions.size()==1,"World draft excludes client preferences");
  draft.save(root/"snapshot.options");Settings loaded;loaded.load(root/"snapshot.options");
  require(loaded.option("rule.keep_inventory",0)==1,"World rule survives persistence");
  auto make=[&](const char* stem,const char* id){
   auto file=root/(std::string(stem)+".dwv");std::ofstream(file)<<"owned fixture";
   std::ofstream(root/(std::string(stem)+".state"))<<"state fixture";
   std::ofstream(root/(std::string(stem)+".info"))<<std::quoted("Old")<<" 42 "<<std::quoted(id)<<'\n';
   return file;
  };
  auto older=make("old","stable-id"),latest=make("latest","stable-id"),other=make("other","other-id");
  WorldRow row{latest,L"Old",true,42,"stable-id"};
  auto original=worldSnapshotFiles(root,row);require(original.size()==6,"World snapshots use stable identity");
  auto backup=worldBackup(root,row);for(const auto& file:original)require(std::filesystem::is_regular_file(backup/file.filename()),"Backup contains all snapshot companions");
  renameWorld(root,row,L"Северная тайга");
  for(const char* stem:{"old","latest"}){std::ifstream meta(root/(std::string(stem)+".info"));std::string name,id;uint64_t seed;meta>>std::quoted(name)>>seed>>std::quoted(id);require(name!="Old"&&seed==42&&id=="stable-id","Rename preserves seed and identity");}
  bool rejected=false;try{renameWorld(root,row,L"");}catch(...){rejected=true;}require(rejected,"Empty names are rejected");
  auto removed=worldBackup(root,row,true);
  require(!std::filesystem::exists(latest)&&!std::filesystem::exists(older),"Deleted snapshots do not reappear in browser");
  require(std::filesystem::is_regular_file(other),"Another world is untouched");
  for(const auto& file:original)require(std::filesystem::is_regular_file(removed/file.filename()),"Deleted snapshot remains recoverable");
  std::cout<<"WORLD_UI_STORE_PASS\nWORLD_OPTIONS_CHECKS_PASS\n";return 0;
 }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
}
