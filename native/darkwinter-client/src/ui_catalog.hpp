#pragma once
#include <string>
#include <vector>
#include <algorithm>

namespace dw::client {
enum class UiOptionKind { Toggle, Cycle, Slider };
struct UiOption {
 int id;std::string key,labelKey;std::wstring label,english;
 UiOptionKind kind;double defaultValue,min,max,step;
 std::vector<double> values;std::vector<std::wstring> labels;
 std::wstring tooltip;std::string effect,format;bool nativeSupported;
};
inline UiOption uiSlider(int id,const char* key,const wchar_t* label,double min,double max,double value,double step,const char* format,const char* effect,bool supported,const char* translation="",const wchar_t* english=L"",const wchar_t* tooltip=L"") {
 return {id,key,translation,label,english,UiOptionKind::Slider,value,min,max,step,{},{},tooltip,effect,format,supported};
}
inline UiOption uiToggle(int id,const char* key,const wchar_t* label,bool value,const char* effect,bool supported,const char* translation="",const wchar_t* english=L"",const wchar_t* tooltip=L"") {
 return {id,key,translation,label,english,UiOptionKind::Toggle,value?1.:0.,0,1,1,{0,1},{L"Выкл",L"Вкл"},tooltip,effect,"toggle",supported};
}
inline UiOption uiCycle(int id,const char* key,const wchar_t* label,double value,std::vector<std::wstring> labels,std::vector<double> values,const char* effect,bool supported,const char* translation="",const wchar_t* english=L"",const wchar_t* tooltip=L"") {
 if(values.empty())for(size_t i=0;i<labels.size();++i)values.push_back(double(i));
 auto bounds=std::minmax_element(values.begin(),values.end());double minimum=*bounds.first,maximum=*bounds.second;
 return {id,key,translation,label,english,UiOptionKind::Cycle,value,minimum,maximum,1,std::move(values),std::move(labels),tooltip,effect,"cycle",supported};
}
// IDs are stable across layout changes. Metadata is also used by input handling and tests.
// nativeSupported is an explicit capability flag, not a claim that a stored option affects rendering.
inline const std::vector<UiOption>& uiOptionCatalogue() {
 static const std::vector<UiOption> options{
  uiSlider(107,"fov",L"Поле зрения",30,110,70,1,"fov","camera",true,"options.fov",L"Field of View"),
  uiSlider(20000,"fullscreenResolution",L"Режим прямого вывода",-1,0,-1,1,"resolution","display",false,"options.fullscreen.exclusive.mode",L"Exclusive Fullscreen Resolution"),
  uiSlider(105,"fps",L"Частота кадров",10,260,0,10,"fps","display",true,"options.framerateLimit",L"Max Framerate"),
  uiToggle(102,"vsync",L"V-Sync",false,"display",true,"options.vsync",L"V-Sync"),
  uiCycle(109,"backgroundLimit",L"Ограничение FPS",1,{L"В фоне",L"При бездействии"},{1,0},"display",true,"options.inactivityFpsLimit",L"Inactivity FPS Limit"),
  uiCycle(104,"guiScale",L"Интерфейс",3,{L"Авто",L"1",L"2",L"3",L"4",L"5",L"6",L"7",L"8"},{},"ui",true,"options.guiScale",L"GUI Scale"),
  uiToggle(101,"fullscreen",L"Полноэкранный режим",true,"display",true,"options.fullscreen",L"Fullscreen"),
  uiToggle(20001,"exclusiveFullscreen",L"Прямой вывод",false,"display",false,"options.exclusiveFullscreen",L"Exclusive Fullscreen"),
  uiSlider(106,"brightness",L"Яркость",.5,1.5,1,.01,"brightness","renderer",true,"options.gamma",L"Brightness"),
  uiCycle(150,"preferredGraphicsBackend",L"Графический API",2,{L"По умолчанию",L"OpenGL",L"Vulkan (эксперименты)"},{},"renderer",false,"options.graphicsApi",L"Graphics API"),
  uiSlider(20002,"graphicsPreset",L"Профиль",0,3,3,1,"preset","renderer",false,"options.graphics.preset",L"Preset"),
  uiSlider(20003,"biomeBlendRadius",L"Граница биомов",0,7,7,1,"biome","renderer",false,"options.biomeBlendRadius",L"Biome Blend"),
  uiSlider(108,"viewDistance",L"Прорисовка",32,512,240,16,"blockChunks","renderer",false,"options.renderDistance",L"Render Distance"),
  uiCycle(20004,"prioritizeChunkUpdates",L"Сборка чанков",1,{L"Постоянно",L"По событию",L"Рядом"},{},"streaming",false,"options.prioritizeChunkUpdates",L"Chunk Builder"),
  uiSlider(20005,"simulationDistance",L"Симуляция",5,32,17,1,"chunks","simulation",false,"options.simulationDistance",L"Simulation Distance"),
  uiToggle(115,"ambient",L"Мягкое освещение",true,"renderer",true,"options.ao",L"Smooth Lighting"),
  uiCycle(114,"clouds",L"Облака",0,{L"Выкл",L"Быстро",L"Детально"},{},"renderer",true,"options.renderClouds",L"Clouds"),
  uiCycle(20006,"particles",L"Частицы",0,{L"Все",L"Уменьшено",L"Минимум"},{},"renderer",false,"options.particles",L"Particles"),
  uiSlider(20007,"mipmapLevels",L"Уровни детализации",0,4,4,1,"integer","renderer",false,"options.mipmapLevels",L"Mipmap Levels"),
  uiToggle(110,"shadows",L"Тени сущностей",true,"renderer",true,"options.entityShadows",L"Entity Shadows"),
  uiSlider(20008,"entityDistanceScaling",L"До сущности",.5,5,1,.01,"percent","renderer",false,"options.entityDistanceScaling",L"Entity Distance"),
  uiSlider(20009,"menuBackgroundBlurriness",L"Размытие фона",0,10,10,1,"integer","ui",false,"options.accessibility.menu_background_blurriness",L"Menu Background Blur"),
  uiSlider(20010,"cloudRange",L"Дальность облаков",2,128,2,1,"chunks","renderer",false,"options.renderCloudsDistance",L"Cloud Range"),
  uiToggle(20011,"cutoutLeaves",L"Просветы в листьях",true,"renderer",false,"options.cutoutLeaves",L"Cutout Leaves"),
  uiToggle(20012,"improvedTransparency",L"Улучшенная прозрачность",true,"renderer",false,"options.improvedTransparency",L"Improved Transparency"),
  uiCycle(20013,"textureFiltering",L"Фильтрация текстур",2,{L"Ближайшая",L"Билинейная",L"Анизотропная"},{},"renderer",false,"options.textureFiltering",L"Texture Filtering"),
  uiSlider(20014,"maxAnisotropyBit",L"Анизотропная фильтрация",0,4,3,1,"power2","renderer",false,"options.maxAnisotropy",L"Anisotropic Filtering"),
  uiSlider(20015,"weatherRadius",L"Эффекты погоды",3,15,10,1,"blocks","renderer",false,"options.weatherRadius",L"Weather Radius"),
  uiCycle(20016,"transparencyQuality",L"Прозрачность",0,{L"Сбалансированная",L"Точная"},{},"renderer",false,"options.darkwinter.transparency",L"Transparency"),
  uiToggle(20017,"showAutosaveIndicator",L"Индикатор сохранения",true,"ui",false,"options.autosaveIndicator",L"Autosave Indicator"),
  uiToggle(20018,"vignette",L"Виньетирование",true,"renderer",false,"options.vignette",L"Vignette"),
  uiCycle(20019,"attackIndicator",L"Индикатор атаки",2,{L"Выкл",L"Прицел",L"Инвентарь"},{},"hud",false,"options.attackIndicator",L"Attack Indicator"),
  uiSlider(20020,"chunkSectionFadeInTime",L"Проявление чанков",0,1,.25,.01,"seconds","renderer",false,"options.chunkFade",L"Chunk Fade"),
  uiSlider(122,"volume",L"Общая громкость",0,1,1,.01,"volume","audio",true,"soundCategory.master",L"Master Volume"),
  uiSlider(20030,"sound.music",L"Музыка",0,1,0,.01,"volume","audio",false,"soundCategory.music",L"Music"),
  uiSlider(20031,"sound.records",L"Музыкальные блоки",0,1,1,.01,"volume","audio",false,"soundCategory.record",L"Jukebox/Note Blocks"),
  uiSlider(20032,"sound.weather",L"Погода",0,1,1,.01,"volume","audio",false,"soundCategory.weather",L"Weather"),
  uiSlider(20033,"sound.blocks",L"Блоки",0,1,1,.01,"volume","audio",false,"soundCategory.block",L"Blocks"),
  uiSlider(20034,"sound.hostile",L"Враждебные мобы",0,1,1,.01,"volume","audio",false,"soundCategory.hostile",L"Hostile Creatures"),
  uiSlider(20035,"sound.neutral",L"Мирные мобы",0,1,0,.01,"volume","audio",false,"soundCategory.neutral",L"Friendly Creatures"),
  uiSlider(20036,"sound.players",L"Игроки",0,1,1,.01,"volume","audio",true,"soundCategory.player",L"Players"),
  uiSlider(20037,"sound.ambient",L"Окружение",0,1,1,.01,"volume","audio",false,"soundCategory.ambient",L"Ambient/Environment"),
  uiSlider(20038,"sound.voice",L"Диктор/Голос",0,1,1,.01,"volume","audio",false,"soundCategory.voice",L"Voice/Speech"),
  uiSlider(20039,"sound.ui",L"Интерфейс",0,1,1,.01,"volume","audio",false,"soundCategory.ui",L"UI"),
  uiCycle(20040,"soundDevice",L"Устройство",0,{L"По умолчанию"},{},"audio",false,"options.audioDevice",L"Device"),
  uiToggle(20041,"showSubtitles",L"Субтитры",false,"audio",false,"options.showSubtitles",L"Show Subtitles"),
  uiToggle(20042,"directionalAudio",L"Позиционируемый звук",true,"audio",false,"options.directionalAudio",L"Directional Audio"),
  uiCycle(20043,"musicFrequency",L"Периодичность музыки",0,{L"По умолчанию",L"Часто",L"Постоянно"},{},"audio",false,"options.music_frequency",L"Music Frequency"),
  uiCycle(20044,"musicToast",L"Оповещение о музыке",0,{L"Никогда",L"В меню паузы",L"В меню паузы и уведомлением"},{},"audio",false,"options.musicToast",L"Music Toast"),
  uiCycle(20050,"toggleCrouch",L"Красться",0,{L"Удерживание",L"Переключение"},{},"input",false,"key.sneak",L"Sneak"),
  uiCycle(20051,"toggleSprint",L"Бег",0,{L"Удерживание",L"Переключение"},{},"input",true,"key.sprint",L"Sprint"),
  uiCycle(20052,"toggleAttack",L"Атаковать/Разрушить",0,{L"Удерживание",L"Переключение"},{},"input",true,"key.attack",L"Attack/Destroy"),
  uiCycle(20053,"toggleUse",L"Использовать/Установить",0,{L"Удерживание",L"Переключение"},{},"input",true,"key.use",L"Use Item/Place Block"),
  uiToggle(20054,"autojump",L"Автопрыжок",false,"input",true,"options.autoJump",L"Auto-Jump"),
  uiSlider(20055,"sprintWindow",L"Интервал разбега",0,10,0,1,"offInteger","input",false,"options.sprintWindow",L"Sprint Window"),
  uiToggle(20056,"quitShortcuts",L"Выход по нажатию",true,"input",true,"options.quitShortcuts",L"Quit Shortcuts"),
  uiToggle(20057,"operatorItemsTab",L"Вкладка оператора",false,"inventory",false,"options.operatorItemsTab",L"Operator Items Tab"),
  uiSlider(111,"sensitivity",L"Чувствительность",0,1,.5,.005,"sensitivity","input",true,"options.sensitivity",L"Sensitivity"),
  uiSlider(20060,"wheelSensitivity",L"Скорость прокрутки",.01,10,1,0,"logarithmic","input",true,"options.mouseWheelSensitivity",L"Scroll Sensitivity"),
  uiToggle(20061,"discreteScroll",L"Дискретная прокрутка",false,"input",true,"options.discrete_mouse_scroll",L"Discrete Scrolling"),
  uiToggle(20062,"invertMouseX",L"Инверсия оси мыши X",false,"input",true,"options.invertMouseX",L"Invert Mouse X"),
  uiToggle(20063,"invertMouseY",L"Инверсия оси мыши Y",false,"input",true,"options.invertMouseY",L"Invert Mouse Y"),
  uiToggle(20064,"allowCursorChanges",L"Изменение курсора",true,"input",true,"options.allowCursorChanges",L"Allow Cursor Changes"),
  uiCycle(20070,"narrator",L"Диктор",0,{L"Выкл",L"Всё",L"Чат",L"Система"},{},"accessibility",false,"options.narrator",L"Narrator"),
  uiToggle(20071,"highContrast",L"Высокая контрастность",false,"ui",false,"options.accessibility.high_contrast",L"High Contrast"),
  uiSlider(20072,"textBackgroundOpacity",L"Непрозрачность фона текста",0,1,.5,.01,"percent","ui",false,"options.accessibility.text_background_opacity",L"Text Background Opacity"),
  uiCycle(20073,"backgroundForChatOnly",L"Фон текста",1,{L"Везде",L"Только чат"},{},"ui",false,"options.accessibility.text_background",L"Text Background"),
  uiSlider(20074,"chatOpacity",L"Непрозрачность чата",0,1,1,.01,"chatOpacity","chat",false,"options.chat.opacity",L"Chat Opacity"),
  uiSlider(20075,"chatLineSpacing",L"Межстрочный интервал",0,1,0,.01,"percent","chat",false,"options.chat.line_spacing",L"Line Spacing"),
  uiSlider(20076,"chatDelay",L"Задержка чата",0,6,0,.1,"seconds","chat",false,"options.chat.delay_instant",L"Chat Delay"),
  uiSlider(20077,"notificationDisplayTime",L"Длительность уведомлений",.5,10,1,.1,"multiplier","ui",false,"options.notifications.display_time",L"Notification Display Time"),
  uiToggle(20078,"bobView",L"Покачивание камеры",true,"camera",false,"options.viewBobbing",L"View Bobbing"),
  uiSlider(20079,"screenEffectScale",L"Эффекты искажения",0,1,1,.01,"percent","renderer",false,"options.screenEffectScale",L"Distortion Effects"),
  uiSlider(20080,"fovEffectScale",L"Изменение поля зрения",0,1,1,.01,"percent","camera",false,"options.fovEffectScale",L"FOV Effects"),
  uiSlider(20081,"darknessEffectScale",L"Пульсация тьмы",0,1,1,.01,"percent","renderer",false,"options.darknessEffectScale",L"Darkness Pulsing"),
  uiSlider(20082,"damageTiltStrength",L"Наклон при уроне",0,1,1,.01,"percent","camera",false,"options.damageTiltStrength",L"Damage Tilt"),
  uiSlider(20083,"glintSpeed",L"Скорость блеска",0,1,.5,.01,"percent","renderer",false,"options.glintSpeed",L"Glint Speed"),
  uiSlider(20084,"glintStrength",L"Интенсивность блеска",0,1,.75,.01,"percent","renderer",false,"options.glintStrength",L"Glint Strength"),
  uiToggle(20085,"hideLightningFlash",L"Скрывать вспышки молний",false,"renderer",false,"options.hideLightningFlashes",L"Hide Lightning Flashes"),
  uiToggle(20086,"darkLogoBackground",L"Монохромный логотип",false,"ui",false,"options.darkMojangStudiosBackgroundColor",L"Monochrome Logo"),
  uiSlider(20087,"panoramaSpeed",L"Скорость панорамы",0,1,1,.01,"percent","ui",false,"options.accessibility.panorama_speed",L"Panorama Speed"),
  uiToggle(20088,"hideSplashTexts",L"Скрывать сплэши",false,"ui",false,"options.hideSplashTexts",L"Hide Splash Texts"),
  uiToggle(20089,"narratorHotkey",L"Клавиши диктора",true,"input",false,"options.accessibility.narrator_hotkey",L"Narrator Hotkey"),
  uiToggle(20090,"rotateWithMinecart",L"Поворот с вагонеткой",false,"camera",false,"options.rotateWithMinecart",L"Rotate with Minecart"),
  uiToggle(20091,"highContrastBlockOutline",L"Контрастная рамка блока",false,"renderer",false,"options.accessibility.high_contrast_block_outline",L"High Contrast Block Outline"),
  uiCycle(133,"motion",L"Эффекты",0,{L"полные",L"снижены",L"выкл."},{},"ui",true,"menu.darkwinter.motion",L"Analog effects"),
  uiCycle(135,"fxStrength",L"Фактура",1,{L"50%",L"75%",L"100%",L"125%"},{.5,.75,1,1.25},"ui",true,"menu.darkwinter.fx.strength",L"Optical texture strength"),
  uiToggle(136,"menuMist",L"Дымка в меню",true,"ui",true,"menu.darkwinter.fx.mist",L"Menu mist"),
  uiCycle(134,"pauseArt",L"Фон паузы",0,{L"мир",L"убежище"},{},"ui",true,"menu.darkwinter.pause",L"Pause background"),
  uiToggle(20100,"forceUnicodeFont",L"Шрифт Unicode",false,"ui",false,"options.forceUnicodeFont",L"Force Unicode Font"),
  uiToggle(20101,"japaneseGlyphVariants",L"Японский стандарт иероглифов",false,"ui",false,"options.japaneseGlyphVariants",L"Japanese Glyph Variants"),
  uiToggle(20110,"skin.cape",L"Плащ",true,"avatar",false,"options.modelPart.cape",L"Cape"),
  uiToggle(20111,"skin.jacket",L"Куртка",true,"avatar",false,"options.modelPart.jacket",L"Jacket"),
  uiToggle(20112,"skin.leftSleeve",L"Левый рукав",true,"avatar",false,"options.modelPart.left_sleeve",L"Left Sleeve"),
  uiToggle(20113,"skin.rightSleeve",L"Правый рукав",true,"avatar",false,"options.modelPart.right_sleeve",L"Right Sleeve"),
  uiToggle(20114,"skin.leftPants",L"Левая штанина",true,"avatar",false,"options.modelPart.left_pants_leg",L"Left Pants Leg"),
  uiToggle(20115,"skin.rightPants",L"Правая штанина",true,"avatar",false,"options.modelPart.right_pants_leg",L"Right Pants Leg"),
  uiToggle(20116,"skin.hat",L"Головной убор",true,"avatar",false,"options.modelPart.hat",L"Hat"),
  uiCycle(20117,"mainHand",L"Основная рука",1,{L"Левая",L"Правая"},{},"avatar",false,"options.mainHand",L"Main Hand"),
  uiCycle(20120,"chatVisibility",L"Чат",0,{L"Виден",L"Только команды",L"Скрыт"},{},"chat",false,"options.chat.visibility",L"Chat"),
  uiToggle(20121,"chatColors",L"Цвета",true,"chat",false,"options.chat.color",L"Colors"),
  uiToggle(20122,"chatLinks",L"Веб-ссылки",true,"chat",false,"options.chat.links",L"Web Links"),
  uiToggle(20123,"chatLinksPrompt",L"Подтверждать ссылки",true,"chat",false,"options.chat.links.prompt",L"Prompt on Links"),
  uiSlider(20124,"chatScale",L"Масштаб чата",0,1,1,.01,"percent","chat",false,"options.chat.scale",L"Chat Scale"),
  uiSlider(20125,"chatWidth",L"Ширина чата",0,1,1,.01,"chatWidth","chat",false,"options.chat.width",L"Chat Width"),
  uiSlider(20126,"chatHeightFocused",L"Высота открытого чата",0,1,1,.01,"chatHeight","chat",false,"options.chat.height.focused",L"Focused Height"),
  uiSlider(20127,"chatHeightUnfocused",L"Высота закрытого чата",0,1,.44366196,.01,"chatHeight","chat",false,"options.chat.height.unfocused",L"Unfocused Height"),
  uiToggle(20128,"chatSuggestions",L"Подсказки команд",true,"chat",false,"options.autoSuggestCommands",L"Command Suggestions"),
  uiToggle(20129,"chatReducedDebugInfo",L"Сокращённая отладка",false,"hud",false,"options.reducedDebugInfo",L"Reduced Debug Info"),
  uiToggle(20130,"onlyShowSecureChat",L"Только проверенный чат",false,"chat",false,"options.onlyShowSecureChat",L"Only Show Secure Chat"),
  uiToggle(20131,"hideMatchedNames",L"Скрывать заблокированные имена",true,"chat",false,"options.hideMatchedNames",L"Hide Matched Names"),
  uiToggle(20132,"saveChatDrafts",L"Сохранять черновики",false,"chat",false,"options.chat.drafts",L"Save Chat Drafts"),
  uiToggle(20142,"friendsList",L"Список друзей",false,"online",false,"options.friendsList",L"Friends List"),
  uiToggle(20143,"allowFriendRequests",L"Получение запросов",false,"online",false,"options.allowFriendRequests",L"Allow Requests"),
  uiToggle(20144,"inGameNotification",L"Уведомления в игре",false,"online",false,"options.inGameNotification",L"In-Game Notification"),
  uiCycle(20145,"sharePresence",L"Видимость",2,{L"Скрытая",L"Ограниченная",L"Полная"},{},"online",false,"options.sharePresence",L"Visibility"),
  uiToggle(20140,"allowServerListing",L"Показывать в списке игроков",true,"online",false,"options.allowServerListing",L"Allow Server Listings"),
  uiToggle(20141,"realmsNotifications",L"Уведомления Realms",true,"online",false,"options.realmsNotifications.button",L"Realms Notifications")
 };
 return options;
}
}
