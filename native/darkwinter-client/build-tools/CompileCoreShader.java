import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Build-only compiler; the standalone client ships only verified SPIR-V. */
public final class CompileCoreShader {
    public static void main(String[] args) throws Exception {
        if(args.length==0 || (args.length&1)!=0)throw new IllegalArgumentException("Expected input/output pairs");
        long compiler=shaderc_compiler_initialize(),options=shaderc_compile_options_initialize();
        if(compiler==0||options==0)throw new IllegalStateException("shaderc initialization");
        try {
            shaderc_compile_options_set_target_env(options,shaderc_target_env_vulkan,shaderc_env_version_vulkan_1_1);
            shaderc_compile_options_set_optimization_level(options,shaderc_optimization_level_performance);
            for(int i=0;i<args.length;i+=2) {
                String input=args[i];
                int kind=input.endsWith(".vert")?shaderc_glsl_vertex_shader:
                    input.endsWith(".frag")?shaderc_glsl_fragment_shader:
                    input.endsWith(".comp")?shaderc_glsl_compute_shader:-1;
                if(kind<0)throw new IllegalArgumentException("Unknown shader stage: "+input);
                long result=shaderc_compile_into_spv(compiler,Files.readString(Path.of(input)),kind,input,"main",options);
                try {
                    if(result==0||shaderc_result_get_compilation_status(result)!=shaderc_compilation_status_success)
                        throw new IllegalStateException(result==0?"No shader result":shaderc_result_get_error_message(result));
                    ByteBuffer bytes=shaderc_result_get_bytes(result);
                    if(bytes==null)throw new IllegalStateException("No SPIR-V");
                    byte[] data=new byte[bytes.remaining()];bytes.get(data);
                    Path target=Path.of(args[i+1]);Files.createDirectories(target.getParent());Files.write(target,data);
                    System.out.println("CORE_SHADER_OK "+target.getFileName()+" "+data.length+" bytes");
                } finally {if(result!=0)shaderc_result_release(result);}
            }
        } finally {shaderc_compile_options_release(options);shaderc_compiler_release(compiler);}
    }
}
