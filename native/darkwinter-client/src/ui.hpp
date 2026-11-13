#pragma once
#include "renderer.hpp"
#include "settings.hpp"
#include "ui_catalog.hpp"
#include <map>
#include <string>
#include <vector>
#include <array>

namespace dw::client {
struct UiButton {
 int id,x,y,w,h; std::wstring text; bool enabled=true; float amount=-1;
 int kind=0,targetPage=-1; std::wstring tooltip; std::string optionKey;
 bool hasValue=false; double value=0;
};
struct WorldRow {std::filesystem::path file;std::wstring name;bool native=false;uint64_t seed=0;std::string id;std::filesystem::file_time_type modified{};};
struct UiLanguage {std::string code;std::wstring name,region;};
struct UiKeyBinding {std::string key;std::wstring name,category;unsigned defaultKey;unsigned initialKey=0;std::string categoryKey;int categoryOrder=0,order=0;std::string inputName;};
struct UiWorldPages;
class Ui {
 friend struct UiWorldPages;
 struct Art{uint32_t w,h;std::vector<uint32_t> pixels;};std::vector<Art> art;
 // Loading-only caches retain raw artwork and the fixed reading shadow across progress frames.
 Art loadingAurora{},loadingReadingShadow{};std::array<int,4> loadingCanvas{};int loadingShadowX=0,loadingShadowY=0;
 std::map<std::tuple<int,int,int,bool,uint32_t>,Art> scaledArt;
 std::filesystem::path fontPath,symbolFontPath;bool symbolFontLoaded=false;
 HDC dc=nullptr;HBITMAP bitmap=nullptr,oldBitmap=nullptr;uint32_t* mask=nullptr;
 std::map<int,HFONT> fonts;std::map<int,int> fontTop;int rasterW=1280,rasterH=720;float pixelX=1,pixelY=1;
 std::array<int,4> clip{0,0,1280,720};std::vector<std::array<int,4>> clipStack;
 std::map<int,float> lights;float priorAnimation=0,hoverSince=0;int lastHover=-1;
 int scrollX=0,scrollY=0,scrollWidth=0,scrollHeight=0,scrollMaximum=0;
 std::map<int,int> scrollPositions,fieldOffsets;std::vector<int> history;
 std::map<std::string,std::map<std::string,std::wstring>> translations;
 void rotated(int id,int cx,int cy,int size,float angle,uint32_t tint);HFONT font(int size);
 int measure(const std::wstring& value,float size);std::wstring fitted(const std::wstring& value,float size,int width);
 void surface(int x,int y,int w,int h,int kind,float light,uint32_t tint);
 void pixel(int x,int y,uint32_t c);void rect(int x,int y,int w,int h,uint32_t c);
 void gradient(int x,int y,int w,int h,uint32_t top,uint32_t bottom);
 void picture(int id,int x,int y,int w,int h,bool cover=false,uint32_t tint=0xffffffffu);
 void icon(int index,int x,int y,int size);
 void text(float x,float y,float size,const std::wstring& value,uint32_t c);
 void centered(int y,float size,const std::wstring& value,uint32_t c);
 void ring(int x,int y,int size,float fraction,uint32_t color);void analog();void backdrop(int id);
 void registerButton(int id,int x,int y,int w,int h,const std::wstring& text,bool enabled=true,int targetPage=-1);
 void add(int id,int x,int y,int w,int h,const std::wstring& text,bool enabled=true);
 void addPage(int id,int x,int y,int w,int h,const std::wstring& text,int targetPage,bool enabled=true);
 void addOption(int id,int x,int y,int w,int h);
 void editField(int id,int x,int y,int w,int h,const std::wstring& placeholder=L"");
 void setClip(int x,int y,int w,int h);void resetClip();
 void setScrollRegion(int x,int y,int w,int h,int contentHeight);void scrollBar(int x=-1);void tooltip();
 void wrapped(int x,int y,int maxWidth,float size,const std::wstring& value,uint32_t c,int maxLines=8);
 int settingsSection(int p)const;int settingsFrame(const std::wstring& title);
 void settingsFooter(const std::wstring& label=L"");void renderSettings();void renderLanguages();void renderBindings();
 void renderConnections();void renderSimplePage();
 std::wstring optionLabel(const UiOption& option)const;float optionAmount(const UiOption& option)const;
 void readCatalogues(const std::filesystem::path& folder);
public:
 enum Page {
  TITLE=0,GAME=1,PAUSE=2,MATERIALS=3,SETTINGS=4,WORLDS=5,CREATE=6,LOADING=7,INTERFACE=8,CONTROLS=9,VIDEO=10,AUDIO=11,ABOUT=12,FAULT=13,STATS=14,WORLD_OPTIONS=15,ACCESSIBILITY=16,LANGUAGE=17,
  MOUSE=18,KEYBINDS=19,ONLINE=20,SKIN=21,CHAT=22,RESOURCES=23,TELEMETRY=24,CREDITS=25,ATTRIBUTIONS=26,LICENSES=27,FONT_OPTIONS=28,CONNECTIONS=29,CONNECTION_CREATE=30,CONNECTION_JOIN=31,RESOURCE_CONFIRM=32,CREDITS_SCROLL=33,GUIDE=34,XBOX_PRIVACY=35,FRIENDS_CONFIRM=36,
  WORLD_MODE=40,STORY_SETUP=41,TEST_SETUP=42,CREATE_WORLD_TAB=43,CREATE_MORE_TAB=44,WORLD_EDIT=45,WORLD_DELETE_CONFIRM=46,STATS_ITEMS=47,STATS_MOBS=48,WORLD_GAME_RULES=49,WORLD_EXPERIMENTS=50,WORLD_DATA_PACKS=51,WORLD_FLAT_CUSTOMIZE=52,WORLD_BIOME_CUSTOMIZE=53,WORLD_BACKUP=54,WORLD_OPTIMIZE=55,WORLD_FLAT_PRESETS=56,WORLD_RESTRICTIONS=57,WORLD_DIFFICULTY_LOCK_CONFIRM=58,WORLD_RULES_DISCARD_CONFIRM=59
 };
 std::vector<uint32_t> pixels;std::vector<UiButton> buttons;std::vector<WorldRow> worlds;
 std::vector<UiLanguage> languages;std::vector<UiKeyBinding> keyBindings;std::map<int,std::wstring> textFields;
 std::vector<std::wstring> audioDevices;std::string selectedLanguage;int bindingCapture=-1;
 float menuTime=0;uint64_t animationFrame=0;int w=1280,h=720,page=TITLE,hover=-1,focus=-1,selectedWorld=0,worldScroll=0;uint32_t selected=1;
 uint64_t minute=UINT64_MAX,weatherTick=UINT64_MAX;int weatherOverride=-1;double worldDaySeconds=-1;
 std::wstring status,worldName=L"DarkWinter",seedText=L"19971008",loadingStage=L"Открытие мира",errorText;
 uint32_t loadingCompleted=0,loadingTotal=0;uint64_t loadingElapsedMillis=0;bool loadingKnown=false;
 int field=0,optionScroll=0,scrollStep=25,focusedField=-1;size_t caret=0,anchor=0;bool fieldSelected=false;
 bool activeWorld=false;double sessionDistance=0;uint64_t sessionSteps=0,sessionJumps=0,worldSeed=0;
 bool dirty=true,walking=true,third=true;float stamina=150,progress=0;bool exhausted=false;Needs needs;Settings settings;
 Ui(const std::filesystem::path& folder);~Ui();Ui(const Ui&)=delete;Ui& operator=(const Ui&)=delete;
 void animate(float time);void canvas(int width,int height,int actualW=0,int actualH=0);void resizeFor(int width,int height);
 int button(int x,int y)const;void selectPage(int p);void openPage(int p,bool remember=true);bool backPage();bool backToPage(int p);
 bool activateNavigation(int id);bool activateOption(int id);bool setOptionSlider(int id,float value);
 const UiOption* optionFor(int id)const;bool scroll(int steps,int x=-1,int y=-1);
 bool handleKey(unsigned key,bool shift=false,bool ctrl=false);bool inputText(wchar_t c);
 bool focusField(int id,int pointerX=-1);bool editing()const{return focusedField>=400&&focusedField<500;}
 std::wstring& fieldValue(int id);void applyPendingSettings();
 std::wstring tr(const std::string& key,const std::wstring& ru,const std::wstring& en=L"")const;
 void playerState(float energy,bool tired,bool actor,bool shoulder,const Needs& n);
 void render(const Scene* scene,double seconds);void render(const Scene& s,double seconds){render(&s,seconds);}
 void present(HWND window)const;void scanWorlds(const std::filesystem::path& directory);
};
}
