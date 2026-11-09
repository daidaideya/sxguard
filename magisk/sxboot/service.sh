#!/system/bin/sh
MODDIR=${0%/*}
(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  sleep 20
  while true; do
    if ! pidof com.lcs.sx >/dev/null 2>&1; then
      am start-foreground-service -n com.lcs.sx/.GuardService -a com.lcs.sx.START >/dev/null 2>&1
    fi
    sleep 60
  done
) &
