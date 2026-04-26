/*
 * Geforce NV2A PGRAPH OpenGL Renderer
 *
 * Copyright (c) 2015 espes
 * Copyright (c) 2015 Jannik Vogel
 * Copyright (c) 2020-2025 Matt Borgerson
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see <http://www.gnu.org/licenses/>.
 */

#include "qemu/osdep.h"
#include "qemu/fast-hash.h"
#include "qemu/mstring.h"

#include "xemu-version.h"
#include "ui/xemu-settings.h"
#include "hw/xbox/nv2a/pgraph/util.h"
#include "debug.h"
#include "renderer.h"

#if defined(__ANDROID__) || defined(ANDROID)
#include <android/log.h>
#define ALOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, "xemu-pgraph", __VA_ARGS__))

static int g_has_geometry_shaders = -1;
static int g_gles_minor = -1; /* cached GLES minor version: 0 = 3.0, 1 = 3.1, 2 = 3.2 */

static bool check_geometry_shader_support(void)
{
    if (g_has_geometry_shaders == -1) {
        GLuint test = glCreateShader(GL_GEOMETRY_SHADER);
        if (test == 0) {
            glGetError(); /* clear GL_INVALID_ENUM */
            g_has_geometry_shaders = 0;
            ALOGI("Geometry shaders not supported (GLES < 3.2), skipping");
        } else {
            glDeleteShader(test);
            g_has_geometry_shaders = 1;
            ALOGI("Geometry shaders supported");
        }
    }
    return g_has_geometry_shaders == 1;
}

static int get_gles_minor(void)
{
    if (g_gles_minor == -1) {
        GLint minor = 0;
        glGetIntegerv(GL_MINOR_VERSION, &minor);
        g_gles_minor = minor;
        ALOGI("GLES 3.%d detected", minor);
    }
    return g_gles_minor;
}
#endif

static void replace_all_gstring(GString *s, const char *from, const char *to) {
    g_string_replace(s, from, to, 0);
}

static char *patch_shader_source(const char *src, GLenum type) {
    if (!src) return NULL;

    const char *p = src;
    while (*p && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) p++;

#if defined(__ANDROID__) || defined(ANDROID)
    int gles_minor = get_gles_minor();
    bool gles31 = (gles_minor >= 1);
    bool gles32 = (gles_minor >= 2);
    bool is_geom = (type == GL_GEOMETRY_SHADER);
#endif

    GString *patched = NULL;
    if (strstr(p, "#version") == p) {
#if defined(__ANDROID__) || defined(ANDROID)
        /* Use the highest version that matches the device capability so that
         * 'precise' and 'fma' are core features (added in GLSL ES 3.20).
         * On GLES 3.1 they require GL_EXT_gpu_shader5 which many devices
         * do not expose, so upgrading to 320 es is the reliable fix. */
        const char *ver_str;
        if (gles32) {
            ver_str = "#version 320 es\n";
        } else if (gles31) {
            ver_str = "#version 310 es\n";
        } else {
            ver_str = "#version 300 es\n";
        }
        patched = g_string_new(ver_str);

        /* Geometry shaders on GLES 3.1 need extension enables for geometry
         * stage, 'precise', and fma() (all core in GLES 3.2). */
        if (is_geom && !gles32) {
            g_string_append(patched,
                "#extension GL_EXT_geometry_shader : enable\n"
                "#extension GL_EXT_gpu_shader5 : enable\n");
        }

        g_string_append(patched,
            "precision highp float;\n"
            "precision highp int;\n"
            "precision highp sampler2D;\n"
            "precision highp sampler3D;\n"
            "precision highp samplerCube;\n"
            "precision highp sampler2DArray;\n"
            "precision highp sampler2DShadow;\n"
            "precision highp sampler2DArrayShadow;\n");
        if (!gles31) {
            /* bitfieldExtract polyfill: not a built-in until GLSL ES 3.10 */
            g_string_append(patched,
                "int bitfieldExtract(int value, int offset, int bits) {\n"
                "    int mask = (1 << bits) - 1;\n"
                "    int raw = (value >> offset) & mask;\n"
                "    int signBit = 1 << (bits - 1);\n"
                "    return (raw ^ signBit) - signBit;\n"
                "}\n");
            /* fma polyfill: not a built-in until GLSL ES 3.10 */
            g_string_append(patched,
                "float fma(float a, float b, float c) { return a * b + c; }\n"
                "vec2  fma(vec2  a, vec2  b, vec2  c) { return a * b + c; }\n"
                "vec3  fma(vec3  a, vec3  b, vec3  c) { return a * b + c; }\n"
                "vec4  fma(vec4  a, vec4  b, vec4  c) { return a * b + c; }\n");
        }
        if (type == GL_FRAGMENT_SHADER) {
            /* psh.c shaders declare and use fragColor directly.
             * display.c/surface.c shaders use out_Color — remap via #define. */
            if (strstr(p, "fragColor") != NULL) {
                g_string_append(patched, "#define out_Color fragColor\n");
            } else {
                g_string_append(patched,
                    "#define out_Color fragColor\n"
                    "layout(location = 0) out vec4 fragColor;\n");
            }
        }
#else
        patched = g_string_new("#version 300 es\nprecision highp float;\n");
        if (type == GL_FRAGMENT_SHADER) {
            g_string_append(patched, "#define out_Color fragColor\nlayout(location = 0) out vec4 fragColor;\n");
        }
#endif
        const char *nl = strchr(p, '\n');
        if (nl) {
            g_string_append(patched, nl + 1);
        } else {
            g_string_append(patched, p);
        }
    } else {
        patched = g_string_new(src);
    }

    if (type == GL_FRAGMENT_SHADER) {
        replace_all_gstring(patched, "layout(location = 0) out vec4 out_Color;", "");
        replace_all_gstring(patched, "out vec4 out_Color;", "");
        replace_all_gstring(patched, "textureSize(tex, 0)", "vec2(textureSize(tex, 0))");
        replace_all_gstring(patched, "textureSize(pvideo_tex, 0)", "vec2(textureSize(pvideo_tex, 0))");
        replace_all_gstring(patched, "1.0f", "1.0");
    }

#if defined(__ANDROID__) || defined(ANDROID)
    /* Fix uint/int type mismatches — GLSL ES strict about these.
     * uintBitsToFloat() requires genUType; hex literals without 'u' are int. */
    replace_all_gstring(patched, "floatBitsToUint(t) == 0",  "floatBitsToUint(t) == 0u");
    replace_all_gstring(patched, "uintBitsToFloat(0x1F800000)", "uintBitsToFloat(0x1F800000u)");
    replace_all_gstring(patched, "uintBitsToFloat(0x5F800000)", "uintBitsToFloat(0x5F800000u)");
    replace_all_gstring(patched, "uintBitsToFloat(0xDF800000)", "uintBitsToFloat(0xDF800000u)");
    replace_all_gstring(patched, "uintBitsToFloat(0x9F800000)", "uintBitsToFloat(0x9F800000u)");
    /* textureSize() returns ivec2/ivec3; cast to vec2 for float arithmetic */
    replace_all_gstring(patched, "textureSize(texSamp0, 0)", "vec2(textureSize(texSamp0, 0))");
    replace_all_gstring(patched, "textureSize(texSamp1, 0)", "vec2(textureSize(texSamp1, 0))");
    replace_all_gstring(patched, "textureSize(texSamp2, 0)", "vec2(textureSize(texSamp2, 0))");
    replace_all_gstring(patched, "textureSize(texSamp3, 0)", "vec2(textureSize(texSamp3, 0))");
    if (!gles32) {
        /* 'precise' qualifier is core only in GLSL ES 3.20; requires
         * GL_EXT_gpu_shader5 on 3.10 and is unavailable on 3.00.
         * Strip it unconditionally on < 3.2 — it is only a precision hint. */
        replace_all_gstring(patched, "precise ", "");
    }
    if (is_geom) {
        /* GL_OES_geometry_point_size is not universally supported on GLES 3.2
         * devices. Reading gl_in[].gl_PointSize without it is a compile error.
         * Remove the passthrough assignment — point size is irrelevant for
         * triangle geometry shaders and writing gl_PointSize output (without
         * reading gl_in) is still valid in GLES 3.2 core. */
        replace_all_gstring(patched,
            "  gl_PointSize = gl_in[index].gl_PointSize;\n", "");
    }
#endif

    return g_string_free(patched, FALSE);
}

static GLenum get_gl_primitive_mode(enum ShaderPolygonMode polygon_mode, enum ShaderPrimitiveMode primitive_mode)
{
    switch (primitive_mode) {
    case PRIM_TYPE_POINTS: return GL_POINTS;
    case PRIM_TYPE_LINES: return GL_LINES;
    case PRIM_TYPE_LINE_LOOP: return GL_LINE_LOOP;
    case PRIM_TYPE_LINE_STRIP: return GL_LINE_STRIP;
    case PRIM_TYPE_TRIANGLES: return GL_TRIANGLES;
    case PRIM_TYPE_TRIANGLE_STRIP: return GL_TRIANGLE_STRIP;
    case PRIM_TYPE_TRIANGLE_FAN: return GL_TRIANGLE_FAN;
    case PRIM_TYPE_QUADS: return GL_LINES_ADJACENCY;
    case PRIM_TYPE_QUAD_STRIP: return GL_LINE_STRIP_ADJACENCY;
    case PRIM_TYPE_POLYGON:
        if (polygon_mode == POLY_MODE_LINE) {
            return GL_LINE_LOOP;
        } else if (polygon_mode == POLY_MODE_FILL) {
            return GL_TRIANGLE_FAN;
        }

        assert(!"PRIM_TYPE_POLYGON with invalid polygon_mode");
        return 0;
    default:
        assert(!"Invalid primitive_mode");
        return 0;
    }
}

static GLuint create_gl_shader(GLenum gl_shader_type,
                               const char *code,
                               const char *name)
{
    GLint compiled = 0;

    NV2A_GL_DGROUP_BEGIN("Creating new %s", name);

    NV2A_DPRINTF("compile new %s, code:\n%s\n", name, code);

#if defined(__ANDROID__) || defined(ANDROID)
    char *patched_code = patch_shader_source(code, gl_shader_type);
    const char *final_code = patched_code;
#else
    const char *final_code = code;
#endif

    GLuint shader = glCreateShader(gl_shader_type);
    glShaderSource(shader, 1, &final_code, 0);
    glCompileShader(shader);

    /* Check it compiled */
    compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLchar* log;
        GLint log_length;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &log_length);
        log = g_malloc(log_length * sizeof(GLchar));
        glGetShaderInfoLog(shader, log_length, NULL, log);
        fprintf(stderr, "%s\n\n" "nv2a: %s compilation failed: %s\n", final_code, name, log);
#if defined(__ANDROID__) || defined(ANDROID)
        __android_log_print(ANDROID_LOG_FATAL, "xemu-pgraph",
                            "nv2a: %s compilation failed: %s\nSHADER:\n%.2000s", name, log, final_code);
#endif
        g_free(log);

#if defined(__ANDROID__) || defined(ANDROID)
        g_free(patched_code);
#endif
        NV2A_GL_DGROUP_END();
        abort();
    }

#if defined(__ANDROID__) || defined(ANDROID)
    /* Log texture-sampling fragment shaders: emit the first texture() call
     * site and everything after (the combiner logic), up to 2000 chars.
     * Also emit the last 500 chars (final output assignment). */
    if (gl_shader_type == GL_FRAGMENT_SHADER) {
        /* Match any texture sampling: texture(), textureProj(), or texSamp */
        const char *tex_site = strstr(final_code, "texSamp");
        if (tex_site) {
            static int frag_tex_cnt = 0;
            if (frag_tex_cnt < 80) {
                int n = frag_tex_cnt++;
                /* Find first actual sampling call for anchor */
                const char *call = strstr(final_code, "texture(");
                const char *proj = strstr(final_code, "textureProj(");
                if (!call || (proj && proj < call)) call = proj;
                if (!call) call = tex_site;
                const char *start = (call > final_code + 100)
                                    ? call - 100 : final_code;
                __android_log_print(ANDROID_LOG_INFO, "xemu-psh",
                    "FRAG-TEX[%d]A: %.2000s", n, start);
                /* Also log the tail (final output assignment) */
                size_t len = strlen(final_code);
                const char *tail = (len > 800) ? final_code + len - 800 : final_code;
                __android_log_print(ANDROID_LOG_INFO, "xemu-psh",
                    "FRAG-TEX[%d]B(tail): %.800s", n, tail);
            }
        }
    }
    g_free(patched_code);
#endif
    NV2A_GL_DGROUP_END();

    return shader;
}

static void set_texture_sampler_uniforms(ShaderBinding *binding)
{
    for (int i = 0; i < NV2A_MAX_TEXTURES; i++) {
        char samplerName[16];
        snprintf(samplerName, sizeof(samplerName), "texSamp%d", i);
        GLint texSampLoc =
            glGetUniformLocation(binding->gl_program, samplerName);
        if (texSampLoc >= 0) {
            glUniform1i(texSampLoc, i);
        }
    }
}

static void update_shader_uniform_locs(ShaderBinding *binding)
{
    char tmp[64];

    for (int i = 0; i < ARRAY_SIZE(binding->uniform_locs.vsh); i++) {
        const char *name = VshUniformInfo[i].name;
        if (VshUniformInfo[i].count > 1) {
            snprintf(tmp, sizeof(tmp), "%s[0]", name);
            name = tmp;
        }
        binding->uniform_locs.vsh[i] = glGetUniformLocation(binding->gl_program, name);
    }

    for (int i = 0; i < ARRAY_SIZE(binding->uniform_locs.psh); i++) {
        const char *name = PshUniformInfo[i].name;
        if (PshUniformInfo[i].count > 1) {
            snprintf(tmp, sizeof(tmp), "%s[0]", name);
            name = tmp;
        }
        binding->uniform_locs.psh[i] = glGetUniformLocation(binding->gl_program, name);
    }
}

static void shader_module_cache_entry_init(Lru *lru, LruNode *node,
                                           const void *key)
{
    ShaderModuleCacheEntry *module =
        container_of(node, ShaderModuleCacheEntry, node);
    memcpy(&module->key, key, sizeof(ShaderModuleCacheKey));

    const char *kind_str;
    MString *code;

    switch (module->key.kind) {
    case GL_VERTEX_SHADER:
        kind_str = "vertex shader";
        code = pgraph_glsl_gen_vsh(&module->key.vsh.state,
                                   module->key.vsh.glsl_opts);
        break;
    case GL_GEOMETRY_SHADER:
        kind_str = "geometry shader";
        code = pgraph_glsl_gen_geom(&module->key.geom.state,
                                    module->key.geom.glsl_opts);
        break;
    case GL_FRAGMENT_SHADER:
        kind_str = "fragment shader";
        code = pgraph_glsl_gen_psh(&module->key.psh.state,
                                   module->key.psh.glsl_opts);
        break;
    default:
        assert(!"Invalid shader module kind");
        kind_str = "unknown";
        code = NULL;
    }

    module->gl_shader =
        create_gl_shader(module->key.kind, mstring_get_str(code), kind_str);
    mstring_unref(code);
}

static void shader_module_cache_entry_post_evict(Lru *lru, LruNode *node)
{
    ShaderModuleCacheEntry *module =
        container_of(node, ShaderModuleCacheEntry, node);
    glDeleteShader(module->gl_shader);
}

static bool shader_module_cache_entry_compare(Lru *lru, LruNode *node,
                                              const void *key)
{
    ShaderModuleCacheEntry *module =
        container_of(node, ShaderModuleCacheEntry, node);
    return memcmp(&module->key, key, sizeof(ShaderModuleCacheKey));
}

static GLuint get_shader_module_for_key(PGRAPHGLState *r,
                                        const ShaderModuleCacheKey *key)
{
    uint64_t hash = fast_hash((void *)key, sizeof(ShaderModuleCacheKey));
    LruNode *node = lru_lookup(&r->shader_module_cache, hash, key);
    ShaderModuleCacheEntry *module =
        container_of(node, ShaderModuleCacheEntry, node);
    return module->gl_shader;
}

static void generate_shaders(PGRAPHGLState *r, ShaderBinding *binding)
{
    GLuint program = glCreateProgram();

    ShaderState *state = &binding->state;
    ShaderModuleCacheKey key;

    bool need_geometry_shader = pgraph_glsl_need_geom(&state->geom);
#if defined(__ANDROID__) || defined(ANDROID)
    if (need_geometry_shader && !check_geometry_shader_support()) {
        need_geometry_shader = false;
    }
#endif
    if (need_geometry_shader) {
        memset(&key, 0, sizeof(key));
        key.kind = GL_GEOMETRY_SHADER;
        key.geom.state = state->geom;
        glAttachShader(program, get_shader_module_for_key(r, &key));
    }

    /* create the vertex shader */
    memset(&key, 0, sizeof(key));
    key.kind = GL_VERTEX_SHADER;
    key.vsh.state = state->vsh;
    key.vsh.glsl_opts.prefix_outputs = need_geometry_shader;
    glAttachShader(program, get_shader_module_for_key(r, &key));

    /* generate a fragment shader from register combiners */
    memset(&key, 0, sizeof(key));
    key.kind = GL_FRAGMENT_SHADER;
    key.psh.state = state->psh;
    glAttachShader(program, get_shader_module_for_key(r, &key));

    /* link the program */
    glLinkProgram(program);
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if(!linked) {
        GLchar log[2048];
        glGetProgramInfoLog(program, 2048, NULL, log);
        fprintf(stderr, "nv2a: shader linking failed: %s\n", log);
#if defined(__ANDROID__) || defined(ANDROID)
        __android_log_print(ANDROID_LOG_FATAL, "xemu-pgraph",
                            "nv2a: shader linking failed: %s", log);
#endif
        abort();
    }

    glUseProgram(program);

    binding->gl_program = program;
    binding->gl_primitive_mode = get_gl_primitive_mode(
        state->geom.polygon_front_mode, state->geom.primitive_mode);
    binding->initialized = true;

    set_texture_sampler_uniforms(binding);

    /* validate the program */
    GLint valid = 0;
    glValidateProgram(program);
    glGetProgramiv(program, GL_VALIDATE_STATUS, &valid);
    if (!valid) {
        GLchar log[1024];
        glGetProgramInfoLog(program, 1024, NULL, log);
        fprintf(stderr, "nv2a: shader validation failed: %s\n", log);
#if defined(__ANDROID__) || defined(ANDROID)
        __android_log_print(ANDROID_LOG_FATAL, "xemu-pgraph",
                            "nv2a: shader validation failed: %s", log);
#endif
        abort();
    }

    update_shader_uniform_locs(binding);
}

static const char *shader_gl_vendor = NULL;

static void shader_create_cache_folder(void)
{
    char *shader_path = g_strdup_printf("%sshaders", xemu_settings_get_base_path());
    qemu_mkdir(shader_path);
    g_free(shader_path);
}

static char *shader_get_lru_cache_path(void)
{
    return g_strdup_printf("%s/shader_cache_list", xemu_settings_get_base_path());
}

static void shader_write_lru_list_entry_to_disk(Lru *lru, LruNode *node, void *opaque)
{
    FILE *lru_list_file = (FILE*) opaque;
    size_t written = fwrite(&node->hash, sizeof(uint64_t), 1, lru_list_file);
    if (written != 1) {
        fprintf(stderr, "nv2a: Failed to write shader list entry %llx to disk\n",
                (unsigned long long) node->hash);
    }
}

void pgraph_gl_shader_write_cache_reload_list(PGRAPHState *pg)
{
    PGRAPHGLState *r = pg->gl_renderer_state;

    if (!g_config.perf.cache_shaders) {
        qatomic_set(&r->shader_cache_writeback_pending, false);
        qemu_event_set(&r->shader_cache_writeback_complete);
        return;
    }

    char *shader_lru_path = shader_get_lru_cache_path();
    qemu_thread_join(&r->shader_disk_thread);

    FILE *lru_list = qemu_fopen(shader_lru_path, "wb");
    g_free(shader_lru_path);
    if (!lru_list) {
        fprintf(stderr, "nv2a: Failed to open shader LRU cache for writing\n");
        return;
    }

    lru_visit_active(&r->shader_cache, shader_write_lru_list_entry_to_disk, lru_list);
    fclose(lru_list);

    lru_flush(&r->shader_cache);

    qatomic_set(&r->shader_cache_writeback_pending, false);
    qemu_event_set(&r->shader_cache_writeback_complete);
}

bool pgraph_gl_shader_load_from_memory(ShaderBinding *binding)
{
#if defined(__ANDROID__) || defined(ANDROID)
    glGetError(); /* clear any pending GL error before shader load */
#else
    assert(glGetError() == GL_NO_ERROR);
#endif

    if (!binding->program) {
        return false;
    }

    GLuint gl_program = glCreateProgram();
    glProgramBinary(gl_program, binding->program_format, binding->program,
                    binding->program_size);
    GLint gl_error = glGetError();
    if (gl_error != GL_NO_ERROR) {
        NV2A_DPRINTF(
            "failed to load shader binary from disk: GL error code %d\n",
            gl_error);
        glDeleteProgram(gl_program);
        return false;
    }

    GLint link_status = GL_FALSE;
    glGetProgramiv(gl_program, GL_LINK_STATUS, &link_status);
    if (!link_status) {
        NV2A_DPRINTF(
            "failed to load shader binary from disk: link status is FALSE\n");
        glDeleteProgram(gl_program);
        return false;
    }

    glUseProgram(gl_program);

    g_free(binding->program);

    binding->program = NULL;
    binding->gl_program = gl_program;
    binding->gl_primitive_mode =
        get_gl_primitive_mode(binding->state.geom.polygon_front_mode,
                              binding->state.geom.primitive_mode);
    binding->initialized = true;

    set_texture_sampler_uniforms(binding);

    glValidateProgram(gl_program);
    GLint valid = 0;
    glGetProgramiv(gl_program, GL_VALIDATE_STATUS, &valid);
    if (!valid) {
        GLchar log[1024];
        glGetProgramInfoLog(gl_program, 1024, NULL, log);
        NV2A_DPRINTF("failed to load shader binary from disk: %s\n", log);
        glDeleteProgram(gl_program);
        return false;
    }

    update_shader_uniform_locs(binding);

    return true;
}

static char *shader_get_bin_directory(uint64_t hash)
{
    const char *cfg_dir = xemu_settings_get_base_path();
    char *shader_bin_dir =
        g_strdup_printf("%s/shaders/%04x", cfg_dir, (uint32_t)(hash >> 48));
    return shader_bin_dir;
}

static char *shader_get_binary_path(const char *shader_bin_dir, uint64_t hash)
{
    uint64_t bin_mask = (uint64_t)0xffff << 48;
    return g_strdup_printf("%s/%012" PRIx64, shader_bin_dir, hash & ~bin_mask);
}

static void shader_load_from_disk(PGRAPHState *pg, uint64_t hash)
{
    PGRAPHGLState *r = pg->gl_renderer_state;

    char *shader_bin_dir = shader_get_bin_directory(hash);
    char *shader_path = shader_get_binary_path(shader_bin_dir, hash);
    char *cached_xemu_version = NULL;
    char *cached_gl_vendor = NULL;
    void *program_buffer = NULL;

    uint64_t cached_xemu_version_len;
    uint64_t gl_vendor_len;
    GLenum program_binary_format;
    ShaderState state;
    size_t shader_size;

    g_free(shader_bin_dir);

    qemu_mutex_lock(&r->shader_cache_lock);
    if (lru_contains_hash(&r->shader_cache, hash)) {
        qemu_mutex_unlock(&r->shader_cache_lock);
        return;
    }
    qemu_mutex_unlock(&r->shader_cache_lock);

    FILE *shader_file = qemu_fopen(shader_path, "rb");
    if (!shader_file) {
        goto error;
    }

    size_t nread;
    #define READ_OR_ERR(data, data_len) \
        do { \
            nread = fread(data, data_len, 1, shader_file); \
            if (nread != 1) { \
                fclose(shader_file); \
                goto error; \
            } \
        } while (0)

    READ_OR_ERR(&cached_xemu_version_len, sizeof(cached_xemu_version_len));

    cached_xemu_version = g_malloc(cached_xemu_version_len +1);
    READ_OR_ERR(cached_xemu_version, cached_xemu_version_len);
    if (strcmp(cached_xemu_version, xemu_version) != 0) {
        fclose(shader_file);
        goto error;
    }

    READ_OR_ERR(&gl_vendor_len, sizeof(gl_vendor_len));

    cached_gl_vendor = g_malloc(gl_vendor_len);
    READ_OR_ERR(cached_gl_vendor, gl_vendor_len);
    if (strcmp(cached_gl_vendor, shader_gl_vendor) != 0) {
        fclose(shader_file);
        goto error;
    }

    READ_OR_ERR(&program_binary_format, sizeof(program_binary_format));
    READ_OR_ERR(&state, sizeof(state));
    READ_OR_ERR(&shader_size, sizeof(shader_size));

    program_buffer = g_malloc(shader_size);
    READ_OR_ERR(program_buffer, shader_size);

    #undef READ_OR_ERR

    fclose(shader_file);
    g_free(shader_path);
    g_free(cached_xemu_version);
    g_free(cached_gl_vendor);

    qemu_mutex_lock(&r->shader_cache_lock);
    LruNode *node = lru_lookup(&r->shader_cache, hash, &state);
    ShaderBinding *binding = container_of(node, ShaderBinding, node);

    /* If we happened to regenerate this shader already, then we may as well use the new one */
    if (binding->initialized) {
        qemu_mutex_unlock(&r->shader_cache_lock);
        return;
    }

    binding->program_format = program_binary_format;
    binding->program_size = shader_size;
    binding->program = program_buffer;
    binding->cached = true;
    qemu_mutex_unlock(&r->shader_cache_lock);
    return;

error:
    /* Delete the shader so it won't be loaded again */
    qemu_unlink(shader_path);
    g_free(shader_path);
    g_free(program_buffer);
    g_free(cached_xemu_version);
    g_free(cached_gl_vendor);
}

#if defined(__ANDROID__) || defined(ANDROID)
/* On Android, xemu_android_request_exit() calls _exit(0) which bypasses the
 * normal QEMU shutdown sequence.  This means pgraph_gl_shader_write_cache_reload_list()
 * (which writes shader_cache_list) never runs.  The individual shader binary files
 * ARE written incrementally as each shader compiles, so we recover them by
 * scanning the shaders/ directory directly on next launch. */
#include <dirent.h>

static void shader_android_scan_and_load(PGRAPHState *pg)
{
    const char *base = xemu_settings_get_base_path();
    char *shaders_dir = g_strdup_printf("%sshaders", base);

    DIR *top = opendir(shaders_dir);
    if (!top) {
        fprintf(stderr, "shader_android_scan: no shaders dir at %s\n", shaders_dir);
        g_free(shaders_dir);
        return;
    }

    int loaded = 0;
    struct dirent *de;
    while ((de = readdir(top)) != NULL) {
        if (de->d_name[0] == '.') continue;

        unsigned int dir_prefix = 0;
        if (sscanf(de->d_name, "%04x", &dir_prefix) != 1) continue;

        char *subdir = g_strdup_printf("%s/%s", shaders_dir, de->d_name);
        DIR *sub = opendir(subdir);
        if (!sub) {
            g_free(subdir);
            continue;
        }

        struct dirent *fe;
        while ((fe = readdir(sub)) != NULL) {
            if (fe->d_name[0] == '.') continue;

            uint64_t file_bits = 0;
            if (sscanf(fe->d_name, "%" SCNx64, &file_bits) != 1) continue;

            uint64_t hash = ((uint64_t)dir_prefix << 48) | (file_bits & 0x0000ffffffffffffULL);
            shader_load_from_disk(pg, hash);
            loaded++;
        }

        closedir(sub);
        g_free(subdir);
    }

    closedir(top);
    g_free(shaders_dir);
    fprintf(stderr, "shader_android_scan: loaded %d shader(s) from disk\n", loaded);
}
#endif /* __ANDROID__ */

static void *shader_reload_lru_from_disk(void *arg)
{
    if (!g_config.perf.cache_shaders) {
        return NULL;
    }

    PGRAPHState *pg = (PGRAPHState*) arg;
    char *shader_lru_path = shader_get_lru_cache_path();

    FILE *lru_shaders_list = qemu_fopen(shader_lru_path, "rb");
    g_free(shader_lru_path);
    if (!lru_shaders_list) {
#if defined(__ANDROID__) || defined(ANDROID)
        /* LRU list file never written on Android — fall back to dir scan */
        shader_android_scan_and_load(pg);
#endif
        return NULL;
    }

    uint64_t hash;
    while (fread(&hash, sizeof(uint64_t), 1, lru_shaders_list) == 1) {
        shader_load_from_disk(pg, hash);
    }

    fclose(lru_shaders_list);
    return NULL;
}

static void shader_cache_entry_init(Lru *lru, LruNode *node, const void *state)
{
    ShaderBinding *binding = container_of(node, ShaderBinding, node);
    memcpy(&binding->state, state, sizeof(ShaderState));
    binding->initialized = false;
    binding->cached = false;
    binding->program = NULL;
    binding->save_thread = NULL;
}

static void shader_cache_entry_post_evict(Lru *lru, LruNode *node)
{
    ShaderBinding *binding = container_of(node, ShaderBinding, node);

    if (binding->save_thread) {
        qemu_thread_join(binding->save_thread);
        g_free(binding->save_thread);
    }

    glDeleteProgram(binding->gl_program);
    if (binding->program) {
        g_free(binding->program);
    }

    binding->cached = false;
    binding->save_thread = NULL;
    binding->program = NULL;
    memset(&binding->state, 0, sizeof(ShaderState));
}

static bool shader_cache_entry_compare(Lru *lru, LruNode *node, const void *key)
{
    ShaderBinding *binding = container_of(node, ShaderBinding, node);
    return memcmp(&binding->state, key, sizeof(ShaderState));
}

void pgraph_gl_init_shaders(PGRAPHState *pg)
{
    PGRAPHGLState *r = pg->gl_renderer_state;

    qemu_mutex_init(&r->shader_cache_lock);
    qemu_event_init(&r->shader_cache_writeback_complete, false);

    if (!shader_gl_vendor) {
        shader_gl_vendor = (const char *) glGetString(GL_VENDOR);
    }

    shader_create_cache_folder();

    fprintf(stderr, "pgraph_gl_init_shaders: cache_shaders=%d base_path=%s\n",
            (int)g_config.perf.cache_shaders, xemu_settings_get_base_path());

    /* FIXME: Make this configurable */
    const size_t shader_cache_size = 50*1024;
    lru_init(&r->shader_cache);
    r->shader_cache_entries = malloc(shader_cache_size * sizeof(ShaderBinding));
    assert(r->shader_cache_entries != NULL);
    for (int i = 0; i < shader_cache_size; i++) {
        lru_add_free(&r->shader_cache, &r->shader_cache_entries[i].node);
    }

    r->shader_cache.init_node = shader_cache_entry_init;
    r->shader_cache.compare_nodes = shader_cache_entry_compare;
    r->shader_cache.post_node_evict = shader_cache_entry_post_evict;

    qemu_thread_create(&r->shader_disk_thread, "pgraph.renderer_state->shader_cache",
                       shader_reload_lru_from_disk, pg, QEMU_THREAD_JOINABLE);

    /* FIXME: Make this configurable */
    const size_t shader_module_cache_size = 50*1024;
    lru_init(&r->shader_module_cache);
    r->shader_module_cache_entries =
        g_malloc_n(shader_module_cache_size, sizeof(ShaderModuleCacheEntry));
    assert(r->shader_module_cache_entries != NULL);
    for (int i = 0; i < shader_module_cache_size; i++) {
        lru_add_free(&r->shader_module_cache, &r->shader_module_cache_entries[i].node);
    }

    r->shader_module_cache.init_node = shader_module_cache_entry_init;
    r->shader_module_cache.compare_nodes = shader_module_cache_entry_compare;
    r->shader_module_cache.post_node_evict = shader_module_cache_entry_post_evict;
}

void pgraph_gl_finalize_shaders(PGRAPHState *pg)
{
    PGRAPHGLState *r = pg->gl_renderer_state;

    // Clear out shader cache
    pgraph_gl_shader_write_cache_reload_list(pg); // FIXME: also flushes, rename for clarity
    free(r->shader_cache_entries);
    r->shader_cache_entries = NULL;

    lru_flush(&r->shader_module_cache);
    g_free(r->shader_module_cache_entries);
    r->shader_module_cache_entries = NULL;

    qemu_mutex_destroy(&r->shader_cache_lock);
}

static void *shader_write_to_disk(void *arg)
{
    ShaderBinding *binding = (ShaderBinding*) arg;

    char *shader_bin = shader_get_bin_directory(binding->node.hash);
    char *shader_path = shader_get_binary_path(shader_bin, binding->node.hash);

    static uint64_t gl_vendor_len;
    if (gl_vendor_len == 0) {
        gl_vendor_len = (uint64_t) (strlen(shader_gl_vendor) + 1);
    }

    static uint64_t xemu_version_len = 0;
    if (xemu_version_len == 0) {
        xemu_version_len = (uint64_t) (strlen(xemu_version) + 1);
    }

    qemu_mkdir(shader_bin);
    g_free(shader_bin);

    FILE *shader_file = qemu_fopen(shader_path, "wb");
    if (!shader_file) {
        goto error;
    }

    size_t written;
    #define WRITE_OR_ERR(data, data_size) \
        do { \
            written = fwrite(data, data_size, 1, shader_file); \
            if (written != 1) { \
                fclose(shader_file); \
                goto error; \
            } \
        } while (0)

    WRITE_OR_ERR(&xemu_version_len, sizeof(xemu_version_len));
    WRITE_OR_ERR(xemu_version, xemu_version_len);

    WRITE_OR_ERR(&gl_vendor_len, sizeof(gl_vendor_len));
    WRITE_OR_ERR(shader_gl_vendor, gl_vendor_len);

    WRITE_OR_ERR(&binding->program_format, sizeof(binding->program_format));
    WRITE_OR_ERR(&binding->state, sizeof(binding->state));

    WRITE_OR_ERR(&binding->program_size, sizeof(binding->program_size));
    WRITE_OR_ERR(binding->program, binding->program_size);

    #undef WRITE_OR_ERR

    fclose(shader_file);

    g_free(shader_path);
    g_free(binding->program);
    binding->program = NULL;

    return NULL;

error:
    fprintf(stderr, "nv2a: Failed to write shader binary file to %s\n", shader_path);
    qemu_unlink(shader_path);
    g_free(shader_path);
    g_free(binding->program);
    binding->program = NULL;
    return NULL;
}

void pgraph_gl_shader_cache_to_disk(ShaderBinding *binding)
{
    if (binding->cached) {
        return;
    }

    GLint program_size;
    glGetProgramiv(binding->gl_program, GL_PROGRAM_BINARY_LENGTH, &program_size);

    if (binding->program) {
        g_free(binding->program);
        binding->program = NULL;
    }

    /* program_size might be zero on some systems, if no binary formats are supported */
    if (program_size == 0) {
        return;
    }

    binding->program = g_malloc(program_size);
    GLsizei program_size_copied;
    /* Drain any accumulated GL errors before calling glGetProgramBinary so the
     * post-call check only catches errors from this specific call. */
#if defined(__ANDROID__) || defined(ANDROID)
    while (glGetError() != GL_NO_ERROR) {}
#endif
    glGetProgramBinary(binding->gl_program, program_size, &program_size_copied,
                       &binding->program_format, binding->program);
#if defined(__ANDROID__) || defined(ANDROID)
    {
        GLenum _err = glGetError();
        if (_err != GL_NO_ERROR) {
            ALOGI("pgraph_gl_shader_cache_to_disk: glGetProgramBinary error 0x%x, skipping cache", (unsigned)_err);
            g_free(binding->program);
            binding->program = NULL;
            return;
        }
    }
#else
    assert(glGetError() == GL_NO_ERROR);
#endif

    binding->program_size = program_size_copied;
    binding->cached = true;

    char name[24];
    snprintf(name, sizeof(name), "scache-%llx", (unsigned long long) binding->node.hash);
    binding->save_thread = g_malloc0(sizeof(QemuThread));
    qemu_thread_create(binding->save_thread, name, shader_write_to_disk, binding, QEMU_THREAD_JOINABLE);
}

static void apply_uniform_updates(const UniformInfo *info, int *locs,
                                  void *values, size_t count)
{
    for (int i = 0; i < count; i++) {
        if (locs[i] == -1) {
            continue;
        }

        void *value = (char*)values + info[i].val_offs;

#if defined(__ANDROID__) || defined(ANDROID)
        /* GLES drivers may reject bulk array uploads when only a subset of
         * elements are "active" (used by the shader). The spec guarantees
         * location[n] = location[0] + n for arrays, so upload element-by-
         * element: active elements succeed, inactive ones produce
         * GL_INVALID_OPERATION which we drain silently. */
        for (size_t j = 0; j < info[i].count; j++) {
            void *elem = (char*)value + j * info[i].size;
            int loc = locs[i] + (int)j;
            switch (info[i].type) {
            case UniformElementType_uint:
                glUniform1uiv(loc, 1, elem);
                break;
            case UniformElementType_int:
                glUniform1iv(loc, 1, elem);
                break;
            case UniformElementType_ivec2:
                glUniform2iv(loc, 1, elem);
                break;
            case UniformElementType_ivec4:
                glUniform4iv(loc, 1, elem);
                break;
            case UniformElementType_float:
                glUniform1fv(loc, 1, elem);
                break;
            case UniformElementType_vec2:
                glUniform2fv(loc, 1, elem);
                break;
            case UniformElementType_vec3:
                glUniform3fv(loc, 1, elem);
                break;
            case UniformElementType_vec4:
                glUniform4fv(loc, 1, elem);
                break;
            case UniformElementType_mat2:
                glUniformMatrix2fv(loc, 1, GL_FALSE, elem);
                break;
            default:
                g_assert_not_reached();
            }
            GLenum _err = glGetError();
            if (_err != GL_NO_ERROR && _err != GL_INVALID_OPERATION) {
                ALOGI("uniform error: idx %d[%zu] name %s err 0x%x",
                      i, j, info[i].name, (unsigned)_err);
            }
        }
#else
        switch (info[i].type) {
        case UniformElementType_uint:
            glUniform1uiv(locs[i], info[i].count, value);
            break;
        case UniformElementType_int:
            glUniform1iv(locs[i], info[i].count, value);
            break;
        case UniformElementType_ivec2:
            glUniform2iv(locs[i], info[i].count, value);
            break;
        case UniformElementType_ivec4:
            glUniform4iv(locs[i], info[i].count, value);
            break;
        case UniformElementType_float:
            glUniform1fv(locs[i], info[i].count, value);
            break;
        case UniformElementType_vec2:
            glUniform2fv(locs[i], info[i].count, value);
            break;
        case UniformElementType_vec3:
            glUniform3fv(locs[i], info[i].count, value);
            break;
        case UniformElementType_vec4:
            glUniform4fv(locs[i], info[i].count, value);
            break;
        case UniformElementType_mat2:
            glUniformMatrix2fv(locs[i], info[i].count, GL_FALSE, value);
            break;
        default:
            g_assert_not_reached();
        }
#endif
    }

    assert(glGetError() == GL_NO_ERROR);
}

// FIXME: Dirty tracking
// FIXME: Consider UBO to align with VK renderer
static void update_shader_uniforms(PGRAPHState *pg, ShaderBinding *binding)
{
    PGRAPHGLState *r = pg->gl_renderer_state;

    VshUniformValues vsh_values;
    pgraph_glsl_set_vsh_uniform_values(pg, &binding->state.vsh,
                                  binding->uniform_locs.vsh, &vsh_values);
    apply_uniform_updates(VshUniformInfo, binding->uniform_locs.vsh,
                          &vsh_values, VshUniform__COUNT);

    PshUniformValues psh_values;
    pgraph_glsl_set_psh_uniform_values(pg, binding->uniform_locs.psh, &psh_values);

    for (int i = 0; i < 4; i++) {
        if (r->texture_binding[i] != NULL) {
            float scale = r->texture_binding[i]->scale;
            psh_values.texScale[i] = scale;
        }
    }
    apply_uniform_updates(PshUniformInfo, binding->uniform_locs.psh,
                          &psh_values, PshUniform__COUNT);
}

void pgraph_gl_bind_shaders(PGRAPHState *pg)
{
    PGRAPHGLState *r = pg->gl_renderer_state;

    bool binding_changed = false;
    if (r->shader_binding &&
        !pgraph_glsl_check_shader_state_dirty(pg, &r->shader_binding->state)) {
        nv2a_profile_inc_counter(NV2A_PROF_SHADER_BIND_NOTDIRTY);
        goto update_uniforms;
    }

    ShaderBinding *old_binding = r->shader_binding;
    ShaderState state = pgraph_glsl_get_shader_state(pg);

    NV2A_GL_DGROUP_BEGIN("%s (%s)", __func__,
                         state.vsh.is_fixed_function ? "FF" : "PROG");

    qemu_mutex_lock(&r->shader_cache_lock);

    uint64_t shader_state_hash =
        fast_hash((uint8_t *)&state, sizeof(ShaderState));

    LruNode *node = lru_lookup(&r->shader_cache, shader_state_hash, &state);
    ShaderBinding *binding = container_of(node, ShaderBinding, node);

    if (!binding->initialized && !pgraph_gl_shader_load_from_memory(binding)) {
        nv2a_profile_inc_counter(NV2A_PROF_SHADER_GEN);
        generate_shaders(r, binding);
        if (g_config.perf.cache_shaders) {
            pgraph_gl_shader_cache_to_disk(binding);
        }
    }
    assert(binding->initialized);
    r->shader_binding = binding;
    pg->program_data_dirty = false;

    qemu_mutex_unlock(&r->shader_cache_lock);

    binding_changed = (r->shader_binding != old_binding);
    if (binding_changed) {
        nv2a_profile_inc_counter(NV2A_PROF_SHADER_BIND);
        glUseProgram(r->shader_binding->gl_program);
    }

    NV2A_GL_DGROUP_END();

update_uniforms:
    assert(r->shader_binding);
    assert(r->shader_binding->initialized);
#if defined(__ANDROID__) || defined(ANDROID)
    /* On Android all GL "contexts" share the same EGL context. The render
     * thread's blit in xemu_hud_render() calls glUseProgram(s_blit_prog)
     * which persists when PGRAPH runs if the shader binding hasn't changed
     * (the glUseProgram above is skipped). Always re-bind the NV2A shader
     * before updating uniforms to avoid targeting the wrong program. */
    glUseProgram(r->shader_binding->gl_program);
#endif
    update_shader_uniforms(pg, r->shader_binding);
}

GLuint pgraph_gl_compile_shader(const char *vs_src, const char *fs_src)
{
    GLint status;
    char err_buf[512];

#if defined(__ANDROID__) || defined(ANDROID)
    char *patched_vs = patch_shader_source(vs_src, GL_VERTEX_SHADER);
    char *patched_fs = patch_shader_source(fs_src, GL_FRAGMENT_SHADER);
    const char *final_vs = patched_vs;
    const char *final_fs = patched_fs;
#else
    const char *final_vs = vs_src;
    const char *final_fs = fs_src;
#endif

    // Compile vertex shader
    GLuint vs = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vs, 1, &final_vs, NULL);
    glCompileShader(vs);
    glGetShaderiv(vs, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        glGetShaderInfoLog(vs, sizeof(err_buf), NULL, err_buf);
        err_buf[sizeof(err_buf)-1] = '\0';
        fprintf(stderr, "%s\n\n" "Vertex shader compilation failed: %s\n", final_vs, err_buf);
#if defined(__ANDROID__) || defined(ANDROID)
        g_free(patched_vs);
        g_free(patched_fs);
#endif
        exit(1);
    }

    // Compile fragment shader
    GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fs, 1, &final_fs, NULL);
    glCompileShader(fs);
    glGetShaderiv(fs, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        glGetShaderInfoLog(fs, sizeof(err_buf), NULL, err_buf);
        err_buf[sizeof(err_buf)-1] = '\0';
        fprintf(stderr, "%s\n\n" "Fragment shader compilation failed: %s\n", final_fs, err_buf);
#if defined(__ANDROID__) || defined(ANDROID)
        g_free(patched_vs);
        g_free(patched_fs);
#endif
        exit(1);
    }

    // Link vertex and fragment shaders
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glGetProgramiv(prog, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) {
        glGetProgramInfoLog(prog, sizeof(err_buf), NULL, err_buf);
        err_buf[sizeof(err_buf)-1] = '\0';
        fprintf(stderr, "nv2a: program link failed: %s\n", err_buf);
#if defined(__ANDROID__) || defined(ANDROID)
        g_free(patched_vs);
        g_free(patched_fs);
#endif
        exit(1);
    }
    glUseProgram(prog);

    // Flag shaders for deletion (will still be retained for lifetime of prog)
    glDeleteShader(vs);
    glDeleteShader(fs);

#if defined(__ANDROID__) || defined(ANDROID)
    g_free(patched_vs);
    g_free(patched_fs);
#endif

    return prog;
}
