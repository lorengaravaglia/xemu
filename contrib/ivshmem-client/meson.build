executable('ivshmem-client', files('ivshmem-client.c', 'main.c'), genh,
           dependencies: glib,
           build_by_default: host_os == 'linux' and not cc.has_header('android/api-level.h'),
           install: false)
