dsp56300_emu_ffi = dependency('dsp56300-emu-ffi', fallback: ['dsp56300', 'dsp56300_dep'])

subdir('interp')

# The DSP JIT backend requires a pre-built Rust library not available for
# Android; on Android always fall back to the C interpreter.
if host_machine.system() == 'android'
  dsp_jit_sources = []
else
  dsp_jit_sources = files(['dsp_jit.c'])
endif

libdsp = static_library(
  'dsp',
  files(['dsp.c', 'dsp_c.c', 'dsp_dma.c']) + dsp_jit_sources + genh,
  dependencies: [dsp56300_emu_ffi],
)

dsp = declare_dependency(
  objects: [
    libdsp.extract_all_objects(recursive: false),
    libdsp_cpu_interp.extract_all_objects(recursive: false),
  ],
  dependencies: [dsp56300_emu_ffi],
)

mcpx_ss.add(dsp, files('gp_ep.c'))
