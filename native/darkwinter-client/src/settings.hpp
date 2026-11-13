#pragma once
#include "scene.hpp"
#include <windows.h>
#include <iomanip>
#include <map>
#include <sstream>

namespace dw::client {
struct Settings {
    bool backgroundLimit=true, shadows=true, clouds=true, ambient=true;
    int fps=0;
    float brightness=1, fov=70, viewDistance=80, fog=1;
    bool fullscreen=true, vsync=true, hud=true, hints=false, animated=true;
    // Temporary visual review mode: noon on the initial date, without changing saves.
    bool daylightPreview=true;
    double visualDaySeconds(double savedDaySeconds) const {return daylightPreview?1200.:savedDaySeconds;}
    bool pauseArt=false, sound=true, third=true, menuMist=true, rightShoulder=true;
    int motion=0;
    float fxStrength=1, sensitivity=.5f, volume=1, scale=1;
    int width=1280, height=720;
    std::map<std::string,double> options;
    std::map<std::string,std::string> textOptions;

    static bool validKey(const std::string& key) {
        if (key.empty() || key.size()>96) return false;
        return std::all_of(key.begin(),key.end(),[](unsigned char c) {
            return (c>='a'&&c<='z') || (c>='A'&&c<='Z') ||
                   (c>='0'&&c<='9') || c=='.' || c=='_' || c=='-';
        });
    }
    double option(const std::string& key, double fallback) const {
        if (key == "fps") return fps;
        if (key == "backgroundLimit") return backgroundLimit;
        if (key == "shadows") return shadows;
        if (key == "clouds") {auto it=options.find(key);return it==options.end()?double(clouds):it->second;}
        if (key == "ambient") return ambient;
        if (key == "brightness") return brightness;
        if (key == "fov") return fov;
        if (key == "viewDistance") {auto it=options.find(key);return it==options.end()?double(viewDistance):it->second;}
        if (key == "fog") return fog;
        if (key == "rightShoulder") return rightShoulder;
        if (key == "motion") return motion;
        if (key == "fxStrength") return fxStrength;
        if (key == "menuMist") return menuMist;
        if (key == "daylightPreview") return daylightPreview;
        if (key == "fullscreen") return fullscreen;
        if (key == "vsync") return vsync;
        if (key == "hud") return hud;
        if (key == "hints") return hints;
        if (key == "animated") return animated;
        if (key == "pauseArt") return pauseArt;
        if (key == "sound") return sound;
        if (key == "third") return third;
        if (key == "sensitivity") return sensitivity;
        if (key == "volume") return volume;
        if (key == "scale") return scale;
        if (key == "width") return width;
        if (key == "height") return height;
        const auto it=options.find(key);
        return it==options.end()?fallback:it->second;
    }
    void setOption(const std::string& key, double value) {
        if (!validKey(key) || !std::isfinite(value)) return;
        if (key == "fps") fps = int(std::clamp(value, 0.00, 360.00));
        else if (key == "backgroundLimit") backgroundLimit = value != 0;
        else if (key == "shadows") shadows = value != 0;
        else if (key == "clouds") {value=std::clamp(std::round(value),0.,2.);clouds=value!=0;options[key]=value;}
        else if (key == "ambient") ambient = value != 0;
        else if (key == "brightness") brightness = float(std::clamp(value, 0.50, 1.50));
        else if (key == "fov") fov = float(std::clamp(value, 30.00, 110.00));
        else if (key == "viewDistance") {value=std::clamp(value,32.,512.);options[key]=value;viewDistance=float(std::clamp(value,32.,88.));}
        else if (key == "fog") fog = float(std::clamp(value, 0.50, 2.00));
        else if (key == "rightShoulder") rightShoulder = value != 0;
        else if (key == "motion") motion = int(std::clamp(value, 0.00, 2.00));
        else if (key == "fxStrength") fxStrength = float(std::clamp(value, 0.00, 1.50));
        else if (key == "menuMist") menuMist = value != 0;
        else if (key == "daylightPreview") daylightPreview = value != 0;
        else if (key == "fullscreen") fullscreen = value != 0;
        else if (key == "vsync") vsync = value != 0;
        else if (key == "hud") hud = value != 0;
        else if (key == "hints") hints = value != 0;
        else if (key == "animated") animated = value != 0;
        else if (key == "pauseArt") pauseArt = value != 0;
        else if (key == "sound") sound = value != 0;
        else if (key == "third") third = value != 0;
        else if (key == "sensitivity") sensitivity = float(std::clamp(value, 0.00, 1.00));
        else if (key == "volume") volume = float(std::clamp(value, 0.00, 1.00));
        else if (key == "scale") scale = float(std::clamp(value, 0.75, 1.50));
        else if (key == "width") width = int(std::clamp(value, 640.00, 3840.00));
        else if (key == "height") height = int(std::clamp(value, 360.00, 2160.00));
        else if (options.size()<1024 || options.contains(key)) options[key]=value;
    }
    std::string textOption(const std::string& key, const std::string& fallback) const {
        const auto it=textOptions.find(key);
        return it==textOptions.end()?fallback:it->second;
    }
    void setTextOption(const std::string& key, const std::string& value) {
        if (!validKey(key) || value.size()>4096 || (textOptions.size()>=512&&!textOptions.contains(key))) return;
        std::string clean=value;
        for (char& c:clean) if (static_cast<unsigned char>(c)<32) c=' ';
        textOptions[key]=std::move(clean);
    }
    void load(const std::filesystem::path& file) {
        std::ifstream in(file);
        std::string line;
        int schema=1;
        double legacySensitivity=1;
        bool sawSensitivity=false;
        while (std::getline(in,line)) {
            if (line.size()>8192) continue;
            std::istringstream row(line);
            std::string kind,key,valueText;
            double value=0;
            if (!(row>>kind)) continue;
            if (kind=="schema") {int version; if(row>>version) schema=version; continue;}
            if (kind=="text") {
                if (row>>std::quoted(key)>>std::quoted(valueText)) setTextOption(key,valueText);
                continue;
            }
            if (kind=="option") {if(!(row>>std::quoted(key)>>value)) continue;}
            else {key=kind; if(!(row>>value)) continue;}
            if (!std::isfinite(value)) continue;
            if (key=="sensitivity") {legacySensitivity=value; sawSensitivity=true;}
            setOption(key,value);
        }
        // v1 stored sensitivity as a multiplier; the source UI stores 0..1 (0..200%).
        if (schema<2 && sawSensitivity) sensitivity=float(std::clamp(legacySensitivity*.5,0.,1.));
    }
    void save(const std::filesystem::path& file) const {
        auto temp=file; temp+=".tmp";
        {
            std::ofstream out(temp);
            out << std::setprecision(17) << "schema 2\n";
            out << "fps " << fps << '\n';
            out << "backgroundLimit " << backgroundLimit << '\n';
            out << "shadows " << shadows << '\n';
            out << "clouds " << clouds << '\n';
            out << "ambient " << ambient << '\n';
            out << "brightness " << brightness << '\n';
            out << "fov " << fov << '\n';
            out << "viewDistance " << viewDistance << '\n';
            out << "fog " << fog << '\n';
            out << "rightShoulder " << rightShoulder << '\n';
            out << "motion " << motion << '\n';
            out << "fxStrength " << fxStrength << '\n';
            out << "menuMist " << menuMist << '\n';
            out << "daylightPreview " << daylightPreview << '\n';
            out << "fullscreen " << fullscreen << '\n';
            out << "vsync " << vsync << '\n';
            out << "hud " << hud << '\n';
            out << "hints " << hints << '\n';
            out << "animated " << animated << '\n';
            out << "pauseArt " << pauseArt << '\n';
            out << "sound " << sound << '\n';
            out << "third " << third << '\n';
            out << "sensitivity " << sensitivity << '\n';
            out << "volume " << volume << '\n';
            out << "scale " << scale << '\n';
            out << "width " << width << '\n';
            out << "height " << height << '\n';
            for (const auto& [key,value]:options)
                out << "option " << std::quoted(key) << ' ' << value << '\n';
            for (const auto& [key,value]:textOptions)
                out << "text " << std::quoted(key) << ' ' << std::quoted(value) << '\n';
            out.flush();
            if (!out) throw std::runtime_error("Settings write failed");
        }
        if (!MoveFileExW(temp.c_str(),file.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))
            throw std::runtime_error("Settings replace failed");
    }
};
struct Needs {float health=100,satiety=100,temperature=75,thirst=100,fatigue=10;void write(std::ostream& f)const{f<<health<<' '<<satiety<<' '<<temperature<<' '<<thirst<<' '<<fatigue<<'\n';}void read(std::istream& f){if(!(f>>health>>satiety>>temperature>>thirst>>fatigue))throw std::runtime_error("HUD state incomplete");for(float x:{health,satiety,temperature,thirst,fatigue})if(!std::isfinite(x)||x<0||x>100)throw std::runtime_error("HUD state invalid");}};
}
