executable('ivshmem-server', files('ivshmem-server.c', 'main.c'), genh,
           dependencies: [qemuutil, rt],
           build_by_default: host_os == 'linux' and not cc.has_header('android/api-level.h'),
           install: false)
