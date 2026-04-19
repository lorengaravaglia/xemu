executable('vhost-user-blk', files('vhost-user-blk.c'),
           dependencies: [qemuutil, vhost_user],
           build_by_default: host_os == 'linux' and not cc.has_header('android/api-level.h'),
           install: false)
