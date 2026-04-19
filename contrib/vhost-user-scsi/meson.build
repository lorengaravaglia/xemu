if libiscsi.found()
  executable('vhost-user-scsi', files('vhost-user-scsi.c'),
             dependencies: [qemuutil, libiscsi, vhost_user],
             build_by_default: host_os == 'linux' and not cc.has_header('android/api-level.h'),
             install: false)
endif
