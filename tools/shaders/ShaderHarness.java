import java.nio.ByteBuffer;
import java.nio.file.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.*;
import org.lwjgl.util.spvc.*;

/** Mirrors renderpearl GlslCompiler (shaderc, Vulkan 1.2 env) + GlPipelineRecompiler (spvc -> GLSL 330). */
public class ShaderHarness {
    static Path assets;

    public static void main(String[] args) throws Exception {
        assets = Paths.get(args[0]);           // dir containing <ns>/shaders/...
        boolean ok = true;
        for (int i = 1; i < args.length; i++) ok &= compile(args[i]);
        System.exit(ok ? 0 : 1);
    }

    static ByteBuffer keep(String s) { return MemoryUtil.memUTF8(s, false); }

    static boolean compile(String id) throws Exception { // e.g. galactica:core/star_plasma.fsh
        String ns = id.substring(0, id.indexOf(':')), path = id.substring(id.indexOf(':') + 1);
        String src = Files.readString(assets.resolve(ns).resolve("shaders").resolve(path));
        int kind = path.endsWith(".fsh") ? Shaderc.shaderc_glsl_fragment_shader : Shaderc.shaderc_glsl_vertex_shader;
        long compiler = Shaderc.shaderc_compiler_initialize();
        long opts = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(opts, 0, 4202496);
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(opts, true);
        Shaderc.shaderc_compile_options_set_preserve_bindings(opts, false);
        Shaderc.shaderc_compile_options_set_generate_debug_info(opts);
        Shaderc.shaderc_compile_options_set_optimization_level(opts, 0);
        ShadercIncludeResolve resolve = ShadercIncludeResolve.create((ud, req, type, requesting, depth) -> {
            String r = MemoryUtil.memASCII(req);
            String rns = r.contains(":") ? r.substring(0, r.indexOf(':')) : "minecraft";
            String rp = r.substring(r.indexOf(':') + 1);
            ShadercIncludeResult res = ShadercIncludeResult.calloc();
            try {
                String content = Files.readString(assets.resolve(rns).resolve("shaders/include").resolve(rp));
                res.source_name(keep(r)).content(keep(content));
            } catch (Exception e) {
                res.source_name(keep("")).content(keep("not found"));
            }
            return res.address();
        });
        ShadercIncludeResultRelease release = ShadercIncludeResultRelease.create((ud, p) -> {});
        Shaderc.shaderc_compile_options_set_include_callbacks(opts, resolve, release, 0L);
        long result = Shaderc.shaderc_compile_into_spv(compiler, keep(src), kind, MemoryUtil.memUTF8(id), MemoryUtil.memUTF8("main"), opts);
        int status = Shaderc.shaderc_result_get_compilation_status(result);
        if (status != 0) {
            System.out.println("FAIL " + id + ": " + Shaderc.shaderc_result_get_error_message(result));
            return false;
        }
        ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
        System.out.println("SPIR-V OK " + id + " (" + spv.remaining() + " bytes, warnings: " + Shaderc.shaderc_result_get_num_warnings(result) + ")");
        // SPIRV-Cross back to GLSL 330 like the GL backend.
        try (MemoryStack st = MemoryStack.stackPush()) {
            PointerBuffer pb = st.mallocPointer(1);
            check(Spvc.spvc_context_create(pb)); long ctx = pb.get(0);
            check(Spvc.spvc_context_parse_spirv(ctx, spv.asIntBuffer(), spv.remaining() / 4, pb)); long ir = pb.get(0);
            check(Spvc.spvc_context_create_compiler(ctx, Spvc.SPVC_BACKEND_GLSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pb)); long comp = pb.get(0);
            Spvc.spvc_compiler_create_compiler_options(comp, pb); long co = pb.get(0);
            Spvc.spvc_compiler_options_set_uint(co, 33554440, 330);
            Spvc.spvc_compiler_options_set_bool(co, 33554439, false);
            Spvc.spvc_compiler_options_set_bool(co, 33554465, true);
            Spvc.spvc_compiler_install_compiler_options(comp, co);
            int rc = Spvc.spvc_compiler_compile(comp, pb);
            if (rc != 0) { System.out.println("SPVC FAIL " + id + ": " + Spvc.spvc_context_get_last_error_string(ctx)); return false; }
            String glsl = MemoryUtil.memUTF8(pb.get(0));
            System.out.println("GLSL330 OK " + id + " (" + glsl.lines().count() + " lines)");
            Spvc.spvc_context_destroy(ctx);
        }
        Shaderc.shaderc_result_release(result);
        Shaderc.shaderc_compile_options_release(opts);
        Shaderc.shaderc_compiler_release(compiler);
        return true;
    }

    static void check(int r) { if (r != 0) throw new IllegalStateException("spvc error " + r); }
}
