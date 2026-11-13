#!/usr/bin/env python3
"""Compile the original retained-scene GLSL to the embedded Vulkan 1.2 header."""
from pathlib import Path
import argparse,hashlib,shutil,struct,subprocess,sys,tempfile
def main():
    p=argparse.ArgumentParser()
    p.add_argument("--glslang")
    p.add_argument("--compiler-script",type=Path)
    args=p.parse_args()
    root=Path(__file__).resolve().parent
    glslang=args.glslang or shutil.which("glslangValidator")
    wrapper=args.compiler_script or root.parent.parent/".darkwinter-lab/biome-editor-toolchain/compile_glsl.py"
    if not glslang and not wrapper.is_file():p.error("A Vulkan GLSL compiler is required")
    result=["// Embeds SPIR-V compiled from the adjacent original retained-scene GLSL.\n#pragma once\n#include <array>\n#include <cstdint>\nnamespace PreviewSceneShaderCode {\n"]
    with tempfile.TemporaryDirectory(prefix="darkwinter-scene-shaders-") as temp:
        for suffix,name in [("vert","Vertex"),("frag","Fragment"),("Overlay.frag","OverlayFragment")]:
            filename="PreviewScene."+suffix if not suffix.startswith("Overlay") else "PreviewSceneOverlay.frag"
            source=root/filename;target=Path(temp)/(name+".spv");stage="vertex" if suffix=="vert" else "fragment"
            command=[glslang,"-V","--target-env","vulkan1.2","-S","vert" if stage=="vertex" else "frag",str(source),"-o",str(target)] if glslang else [sys.executable,str(wrapper),"--stage",stage,str(source),str(target)]
            subprocess.run(command,check=True)
            data=target.read_bytes()
            if len(data)<20 or len(data)%4:raise RuntimeError("Invalid SPIR-V")
            words=struct.unpack("<"+"I"*(len(data)//4),data)
            if words[0]!=0x07230203:raise RuntimeError("Invalid SPIR-V magic")
            result.append(f"// {filename} SHA-256 {hashlib.sha256(source.read_bytes()).hexdigest()}\ninline constexpr std::array<std::uint32_t,{len(words)}> {name}={{\n")
            for i in range(0,len(words),8):result.append("    "+",".join(f"0x{x:08x}u" for x in words[i:i+8])+",\n")
            result.append("};\n")
    result.append("}\n")
    output=root/"PreviewSceneShaderCode.hpp"
    output.write_text("".join(result),encoding="utf-8",newline="\n")
    print(output)
if __name__=="__main__":main()
