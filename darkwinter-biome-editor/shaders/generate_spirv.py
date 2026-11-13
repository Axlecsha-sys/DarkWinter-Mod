#!/usr/bin/env python3
"""Regenerate the embedded Vulkan shaders; normal C++ builds use the committed header."""
from pathlib import Path
import argparse, hashlib, shutil, struct, subprocess, sys, tempfile

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--glslang",help="Path to glslangValidator, if not available on PATH")
    parser.add_argument("--compiler-script",type=Path,help="Optional local shaderc wrapper")
    args=parser.parse_args()
    root=Path(__file__).resolve().parent
    project=root.parent.parent
    glslang=args.glslang or shutil.which("glslangValidator")
    wrapper=args.compiler_script or project/".darkwinter-lab/biome-editor-toolchain/compile_glsl.py"
    if not glslang and not wrapper.is_file():
        parser.error("Install glslangValidator or supply --compiler-script; existing header remains usable.")
    pieces=["// Embeds SPIR-V compiled from the adjacent original GLSL sources.\n#pragma once\n#include <array>\n#include <cstdint>\nnamespace PreviewShaderCode {\n"]
    with tempfile.TemporaryDirectory(prefix="darkwinter-preview-shaders-") as temp:
        for stage,suffix,name in [("vertex","vert","Vertex"),("fragment","frag","Fragment")]:
            source=root/f"preview.{suffix}";target=Path(temp)/f"{suffix}.spv"
            command=[glslang,"-V","--target-env","vulkan1.2","-S",suffix,str(source),"-o",str(target)] if glslang else [sys.executable,str(wrapper),"--stage",stage,str(source),str(target)]
            subprocess.run(command,check=True)
            data=target.read_bytes()
            if len(data)%4 or len(data)<20:raise RuntimeError("Invalid SPIR-V size")
            words=struct.unpack("<"+"I"*(len(data)//4),data)
            if words[0]!=0x07230203:raise RuntimeError("Invalid SPIR-V magic")
            pieces.append(f"// {source.name} SHA-256 {hashlib.sha256(source.read_bytes()).hexdigest()}\n")
            pieces.append(f"inline constexpr std::array<std::uint32_t,{len(words)}> {name}={{\n")
            for i in range(0,len(words),8):pieces.append("    "+",".join(f"0x{x:08x}u" for x in words[i:i+8])+",\n")
            pieces.append("};\n")
    pieces.append("}\n")
    result=root/"PreviewShaderCode.hpp"
    result.write_text("".join(pieces),encoding="utf-8",newline="\n")
    print(result)
if __name__=="__main__":
    main()
