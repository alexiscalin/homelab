# postmarketOS on the Samsung Galaxy A5 (2017) — samsung-a5y17lte

Notes from the session of 2026-10-11/12 that finally got postmarketOS booting on my A5 2017,
after several failed attempts. It covers what was wrong, how each problem was found, and how to
redo the whole install from scratch.

Patches referenced below are in [`postmarketos_a5y17lte/`](postmarketos_a5y17lte/).

## The phone and the port

| | |
|---|---|
| Model | SM-A520F, Exynos 7880 (not Qualcomm), hardware rev 8 |
| Bootloader | `A520FXXUGCTKA` (final Android 8 bootloader) |
| pmOS codename | `samsung-a5y17lte` |
| Kernel | LineageOS vendor kernel 3.18.140, built with gcc6 |
| Port status | `device/archived/` in pmaports since 2026-06 (no maintainer, nothing tests it) |
| Flash method | `heimdall-bootimg` on paper; in practice TWRP + recovery zip (see below) |
| Partitions | `BOOT` = `mmcblk0p10`, `RECOVERY` = `mmcblk0p11`, `SYSTEM` = `mmcblk0p19` |

pmOS installs into the `SYSTEM` partition as a disk image with its own partition table:
`p1` = `pmOS_boot` (ext2, starts at sector 2048), `p2` = `pmOS_root` (ext4, starts at sector
499712, grown to fill `SYSTEM`). The initramfs reaches them through a loop device.

`SYSTEM` is only 4.3 GB, so the 23 GB Android `USERDATA` partition (`mmcblk0p25`) was
reformatted as ext4 with label `pmOS_home` and mounted on `/home`
(`LABEL=pmOS_home /home ext4 defaults,noatime 0 2` in `/etc/fstab`; keep `/home` mode 755,
see #15). Android data on it was wiped on purpose.

### Boot slots (since 2026-10-13: mainline is the default)

| Partition | Holds | How S-boot picks it |
|---|---|---|
| `BOOT` (`mmcblk0p10`) | **mainline Linux 7.2.10** daily image (uniLoader + kernel + pmOS initramfs) | power-on, plain `reboot`, forced restart (Vol Down + Power) |
| `RECOVERY` (`mmcblk0p11`) | the downstream 3.18 boot.img (known-good fallback) | `sudo reboot recovery`, Vol Up + Home + Power, and automatically after a mainline crash/panic |

Both kernels share the same rootfs and `/home`. On mainline, Wi-Fi (ath10k), USB networking,
charging (also from USB-C PD laptops), eMMC HS200, buttons, touchscreen, RTC, battery level and
temperature, SoC temperature, flip-cover hall sensor, vibrator and camera flash/torch work; see
`~/Projects/a5y17lte-mainline/README.md` for the details and how to rebuild/flash
(`out/mainline-daily.img` → `dd` to `mmcblk0p10`). Mainline-only phone service:
`a5-usb-policy` (connects USB data only for a computer, so chargers charge), plus
`/etc/elogind/logind.conf.d/10-a5-lid.conf` (`10-a5-lid.conf` here: ignore the flip cover until
suspend is validated on mainline). Not on mainline yet: sound, CPU frequency scaling, display
driver (the framebuffer from the bootloader is used).
TWRP is no longer installed. To make 3.18 the default again:
`dd if=/dev/mmcblk0p11 of=/dev/mmcblk0p10 bs=1M count=32` (from either kernel).

## Result

- Boots to a console system (OpenRC), SSH over USB and over Wi-Fi.
- Wi-Fi works (qcacld driver + firmware from `firmware-samsung-universal7880`).
- Wi-Fi stays up (the qcacld TDLS deadlock that killed networking after ~10 min is fixed, #17).
- The screen works through the framebuffer (no text console: the kernel has no
  `CONFIG_FRAMEBUFFER_CONSOLE`). A status screen shows the postmarketOS logo and live system
  status in green on black (sleeps after 30 s, any button wakes it), and **SHUTTING DOWN** in red
  on shutdown.
- Boot time: kernel + initramfs env 4 s; the rest was 3 minutes of firmware timeouts (fix #9,
  confirmed: the three missing firmware files now fail at 6.7 s, 7.7 s and 9.0 s instead of
  costing 60 s each).

## What was wrong, in boot order

Each problem hid the next one. Numbers match the patches/fixes further down.

1. **Previous attempts never reached the phone.** Every old flash stopped at
   `Waiting for device...` / `wait-for-usb-sideload`. Causes: phone not fully in Download mode
   and a flaky USB connection. Also only `flash_rootfs` had ever been run, never `flash_kernel`.
   (lk2nd, tried back then, is for Qualcomm SoCs and irrelevant here.)
2. **Kernel doesn't compile.** A 2026-07 pmaports commit "sync kconfig" for this kernel turned on
   `CONFIG_SYN_COOKIES`, which this kernel's MPTCP code can't build with
   (`net/mptcp/mptcp_ipv4.c: too many arguments to function 'cookie_v4_init_sequence'`).
   That commit was never tested on hardware, so I restored the whole pre-July config.
3. **pmbootstrap picked systemd.** pmbootstrap ≥ 3.11 ignores the old `systemd = never` key and
   uses `service_manager`. systemd needs Linux ≥ 5.4 (`systemd_linux_min_version` in
   `pmaports.cfg`); this kernel is 3.18. Fix: `pmbootstrap config service_manager openrc`.
4. **No prebuilt firmware package.** `firmware-samsung-universal7880` isn't on the mirror for
   archived devices and pmbootstrap doesn't auto-build it. Fix: build it explicitly.
5. **pmbootstrap 3.12.0 crashes with a French locale.** Commit `ac782284` stopped creating
   `/etc/profile.d/10locale-pmos.sh` but the next line still appends `XKB_DEFAULT_LAYOUT=fr` to
   it. Fix: reverse that commit locally (`pmbootstrap-locale-fix.patch`).
6. **heimdall can't flash big partitions.** `flash_rootfs` died at 4 %
   (`ERROR: Failed to confirm end of file transfer sequence!`), which leaves the bootloader
   showing "An error has occurred while updating the device software" until a flash succeeds.
   Small flashes (`BOOT`, `RECOVERY`, ≤ ~32 MB) work fine. Fix: install with TWRP + recovery zip.
7. **zstd initramfs.** mkinitfs now compresses with zstd by default; the 3.18 kernel only has
   `CONFIG_RD_GZIP`, finds no `/init`, panics, and the phone bootloops silently.
   Fix: `deviceinfo_initfs_compression="gzip"`.
8. **Hard reset on the first write to the root filesystem.** The initramfs attaches `SYSTEM` with
   `losetup -Pf --direct-io=on`. util-linux then opens the backing device with `O_DIRECT`; this
   3.18 loop driver has no direct-I/O support and can't write through such a file handle. The
   phone reset ~1.5 s after `loop0: p1 p2`, inside `e2fsck` journal replay, every single boot.
   Fix: the device initfs hook runs `losetup -Pf /dev/mmcblk0p19` (no direct-io) first; the
   initramfs then finds `pmOS_root` already present and skips its own `losetup`.
9. **3-minute boot.** The Wi-Fi driver requests three files that don't exist
   (`bdwlan30.b00`, `qsetup30.bin`, `wlan/wlan_mac.bin`). Each falls back to the usermode helper
   and waits `/sys/class/firmware/timeout` = 60 s, blocking udev, so OpenRC's sysinit stalls
   180 s. Fix: the hook sets that timeout to 1 s (it survives `switch_root`).
10. **Wrong UUIDs.** The recovery-zip installer formats new filesystems on the phone (new random
    UUIDs) but `/etc/fstab` keeps the UUIDs from the PC build. On top of that the Samsung
    bootloader **replaces** the kernel command line, so `pmos_root_uuid=` never reaches the
    kernel; the initramfs only finds root by its label. OpenRC then can't mount anything from
    fstab. Fix: fstab by `LABEL=`.
11. **`/in-pmbootstrap` left on the rootfs.** Makes `mkinitfs` skip with
    "running in pmbootstrap", so kernel/hook updates never regenerate the boot image.
    Fix: delete it.
12. **Random resets ~2.5–3.5 min after boot.** No kernel panic recorded (hard lockup). Prime
    suspect: `msm-fb-refresher` (a Qualcomm display hack that keeps poking the framebuffer) in the
    `boot` runlevel; the phone also froze once right after something drew to the framebuffer.
    Fix: `rc-update del msm-fb-refresher boot`. Not proven, but no resets since. It isn't needed
    either: the panel keeps its image after a single pan (see "Display").
13. **No DHCP on USB in the booted system.** `unudhcpd.usb0` serves `usb0`, but with Samsung's
    `android_usb` gadget the interface is `rndis0`. Fix: run `unudhcpd.rndis0` instead.
14. **Wi-Fi handshake failing.** Driver trace showed `ST:ASSOC`, then the router's first WPA
    message repeated (`ST:R:EPL`) and `ST:DISASC`: 4-way handshake never completes.
    NetworkManager was randomising the MAC, which this old qcacld driver doesn't cope with.
    Fix: disable MAC randomisation.
15. **SSH key refused.** The recovery zip excludes `/home`, and `/home` ended up mode `0777`,
    so sshd's StrictModes ignored `authorized_keys`. Fix: `chmod 755 /home`.
16. **wpa_supplicant on the wrong interface.** `/etc/conf.d/wpa_supplicant` only sets
    `-Dnl80211,wext`; with no `-i`, the init script appends the first wireless interface it finds,
    which is `p2p0` (Wi-Fi Direct), not `wlan0`. Fix: `wpa_supplicant_if="wlan0"`. Correct, but it
    was **not** the cause of #17.
17. **Networking deadlocks ~2–10 min after boot.** NetworkManager and wpa_supplicant end up in
    `D` state; new connections, DHCP, `ip addr`, USB networking after a replug all hang, while
    already-open SSH sessions keep working for a while; overnight the phone became unreachable.
    The kernel stacks (`/proc/<pid>/stack`, captured by the status screen) show the chain:
    - the driver's main thread `VosMCThread` is blocked in `wlan_hdd_tdls_is_progress` ←
      `wlan_hdd_tdls_implicit_send_discovery_request` ← `hdd_RoamTdlsStatusUpdateHandler`,
      **holding the SME global lock**;
    - NetworkManager's RSSI poll (`nl80211_dump_station` → `wlan_hdd_get_rssi` → `sme_GetRssi`)
      waits for that lock **while holding `genl_lock`**;
    - wpa_supplicant then waits on `genl_lock`, and everything else piles up behind.

    TDLS (direct station-to-station links) with implicit discovery is enabled in the driver's
    `qcom_cfg.ini`. Fix: `gEnableTDLSSupport=0` and `gEnableTDLSImplicitTrigger=0` (the
    `firmware-samsung-universal7880` package now does this, pkgrel 1). Verified: 21 minutes with no
    `D` state, where it used to lock up within 2–10 minutes.

## Reproduce from scratch

### 0. Prerequisites

- Android settings → Developer options → **OEM unlocking** on (otherwise "Custom binary blocked by FRP").
- A data USB cable that sits firmly. The phone's USB port is flaky; reseat the plug if nothing shows up.
- pmbootstrap from git (`~/Projects/pmbootstrap`, 3.12.0 here), heimdall, adb.
- TWRP for this phone: official `twrp-3.4.0-0-a5y17lte.img` from `dl.twrp.me/a5y17lte/`
  (check its `.sha256`).

Key combos:

| Mode | Buttons |
|---|---|
| Download mode | power off, hold **Vol Down + Home + Power**, then **Vol Up** on the warning |
| TWRP | hold **Vol Up + Home + Power**; release only Power when the logo appears |
| Force restart | **Vol Down + Power** ~8 s |
| pmOS debug shell | **Vol Down held** while pmOS starts (telnet on 172.16.42.1:23) — avoid it by accident |

### 1. Put TWRP in RECOVERY

From Download mode:

```sh
heimdall flash --RECOVERY twrp-3.4.0-0-a5y17lte.img --no-reboot
```

Then force-restart straight into TWRP with the combo above.

### 2. Configure pmbootstrap and apply the patches

```sh
pmbootstrap init                     # device samsung-a5y17lte, ui console
pmbootstrap config service_manager openrc
pmbootstrap pull
git -C ~/.local/var/pmbootstrap/cache_git/pmaports apply \
    ~/Projects/homelab/documentation/postmarketos_a5y17lte/pmaports-a5y17lte.patch
git -C ~/Projects/pmbootstrap apply \
    ~/Projects/homelab/documentation/postmarketos_a5y17lte/pmbootstrap-locale-fix.patch
```

The pmaports patch contains: the pre-July kernel config + checksum, gzip compression, the
initfs hook (loop without direct-io + firmware timeout), and TDLS disabled in the Wi-Fi firmware
package's `qcom_cfg.ini`. If upstream pmaports changed since,
`git apply` may refuse; re-do the edits by hand from the patch. The pmbootstrap patch is only
needed for non-English locales and only while upstream still has the bug.

### 3. Build and create the recovery zip

```sh
pmbootstrap build firmware-samsung-universal7880
pmbootstrap install --android-recovery-zip
```

### 4. Sideload with TWRP

In TWRP: **Advanced → ADB Sideload**, swipe. Then:

```sh
adb kill-server          # a host adb server grabs the phone and pmbootstrap's adb waits forever
pmbootstrap flasher --method=adb sideload
```

The transfer may end around 47–49 % with `adb: failed to read command: Success`: that's normal.
TWRP's `/tmp/recovery.log` should end with `Installation done.` and `Updater process ended with RC=0`.

### 5. Fix the rootfs from TWRP before the first boot

Over `adb shell` in TWRP (offsets from `fdisk -lu /dev/block/mmcblk0p19`):

```sh
L=$(losetup -f)
losetup -o $((499712*512)) $L /dev/block/mmcblk0p19
mkdir -p /tmp/r && mount -t ext4 $L /tmp/r
sed -i 's|^UUID=[^ ]* / |LABEL=pmOS_root / |; s|^UUID=[^ ]* /boot |LABEL=pmOS_boot /boot |' /tmp/r/etc/fstab
rm -f /tmp/r/in-pmbootstrap
chmod 755 /tmp/r/home
cat /tmp/r/etc/fstab
umount /tmp/r && losetup -d $L
```

TWRP's own `e2fsck` (1.43) is too old for this filesystem (`unsupported feature FEATURE_C12` =
`orphan_file`); don't use it to repair pmOS_root.

### 6. First boot

`adb reboot`, don't touch any buttons. The screen stays on the logo or goes black: normal.
About 30 s later, **unplug and replug the USB cable** (the phone only enables USB data on a fresh
plug-in). Then:

```sh
ssh alexis@172.16.42.1
```

If the laptop gets no DHCP lease on the first boot, give it a static address once:
`nmcli device modify <iface> ipv4.method manual ipv4.addresses 172.16.42.2/24`.

### 7. On-phone setup (once)

```sh
# USB DHCP server on the right interface
sudo ln -sf unudhcpd /etc/init.d/unudhcpd.rndis0
sudo rc-update add unudhcpd.rndis0 boot
sudo rc-update del unudhcpd.usb0 boot

# suspected cause of the random resets; the panel doesn't need it
sudo rc-update del msm-fb-refresher boot

# Wi-Fi: no MAC randomisation for the qcacld driver, wpa_supplicant on wlan0 (not p2p0)
printf "[device]\nwifi.scan-rand-mac-address=no\n\n[connection]\nwifi.cloned-mac-address=permanent\n" \
  | sudo tee /etc/NetworkManager/conf.d/90-no-mac-randomization.conf
echo 'wpa_supplicant_if="wlan0"' | sudo tee -a /etc/conf.d/wpa_supplicant
sudo rc-service wpa_supplicant restart
sudo rc-service networkmanager restart

# TDLS off (#17) -- only needed if the firmware package is older than pkgrel 1
grep -E '^gEnableTDLS(Support|ImplicitTrigger)=' /usr/lib/firmware/postmarketos/wlan/qcom_cfg.ini
# both must be 0; the driver reads this file when it loads, so reboot after changing it
sudo nmcli --ask dev wifi connect "<SSID>" ifname wlan0   # use wlan0, not p2p0

# SSH key login (from the laptop)
ssh-copy-id alexis@172.16.42.1
```

Over SSH, NetworkManager calls need `sudo` (the session isn't "local" for polkit).

My `~/.ssh/config` has `Host samsung-a5` → `HostName samsung-a5y17lte`, `User alexis`; the
Livebox resolves the hostname, so `ssh samsung-a5` works over Wi-Fi without the cable.

### 8. Boot status screen

Copy [`boot-status-screen`](postmarketos_a5y17lte/boot-status-screen) and
[`boot-status-screen.initd`](postmarketos_a5y17lte/boot-status-screen.initd) to the phone's
`/tmp`, then:

```sh
sudo install -Dm755 /tmp/boot-status-screen /usr/local/bin/boot-status-screen
sudo install -Dm755 /tmp/boot-status-screen.initd /etc/init.d/boot-status-screen
sudo rc-update add boot-status-screen default
sudo rc-service boot-status-screen start
```

It runs after `sshd` and `networkmanager` as a small daemon (`boot-status-screen daemon`):

- black screen, postmarketOS logo (the small fastfetch variant, drawn in terminal-like 1:2 cells
  so the slopes look right) on the top left, then in green: device, OS, kernel, uptime, load,
  SoC and battery temperature, battery level/state, memory, Wi-Fi state (connected / no address /
  no gateway / down), SSH (daemon up + open sessions) and **NETWORK OK / STUCK**;
- "UPDATED hh:mm:ss" at the bottom, redrawn every 10 s while the screen is on, so a frozen
  screen is obvious;
- after **30 s without input** it powers the panel down (`echo 4 > /sys/class/graphics/fb0/blank`);
- any button (Power, Volume, Home, the two capacitive keys) powers it back up and redraws;
- **SHUTTING DOWN** in red when the service stops.

Everything is read from `/proc` and `/sys` only (`fib_trie`, `route`, `arp`, `tcp`, thermal and
power-supply sysfs), never through netlink or `ip`/`nmcli`, so the screen keeps working when
networking is wedged. **NETWORK: STUCK** (red) means NetworkManager or wpa_supplicant has been in
`D` state for 20 s; the daemon then writes `/var/log/net-stuck-<date>.txt` with the kernel stack
of every `D` process plus `dmesg`. That file is how #17 was found. `boot-status-screen once` /
`off` draw a single screen by hand (as root, or any user in the `video` group for `once`).

Touching the screen doesn't wake it: Samsung's `bt532_ts` driver powers the touch controller off
together with the panel (`bt532_power_ctrl: off` in `dmesg`), like stock Android. Touch works
while the screen is on and resets the timer. elogind has `HandlePowerKey=ignore`, so pressing
Power only wakes the screen. Only needs `python3`, which is already installed.

## Display

The display works without any kernel change; it just doesn't behave like a normal framebuffer.

- Panel: Samsung `s6e3fa3` AMOLED, 1080×1920, MIPI **command mode**, driven by Samsung's
  `decon_7880` fbdev driver. No DRM (`# CONFIG_DRM is not set`), so no modern graphical UI.
- The device hook's blank/unblank powers it on at ~4 s (`dsim_panel_displayon` in `dmesg`).
- **`write()` to `/dev/fb0` goes nowhere**: reading it back returns nothing. The real buffer
  (ION memory) is only reachable with **`mmap`**.
- After drawing, one **`FBIOPAN_DISPLAY`** ioctl (`0x4606`) makes `decon_pan_display` point the
  hardware at the buffer and push a frame. The panel then **keeps the image by itself**: no
  periodic refresh, so `msm-fb-refresher` isn't needed.
- Pixel format is **R, G, B, A** bytes (`DECON_PIXEL_FORMAT_ABGR_8888`), stride 4320.
- The user needs to be in the `video` group (default) to draw.

Minimal test (red top half, blue bottom half):

```python
import fcntl, os, mmap
fd = os.open("/dev/fb0", os.O_RDWR)
m = mmap.mmap(fd, 1080*1920*4, mmap.MAP_SHARED, mmap.PROT_READ | mmap.PROT_WRITE)
half = 1080 * 960 * 4
m[:half] = bytes([255, 0, 0, 255]) * (half // 4)
m[half:2*half] = bytes([0, 0, 255, 255]) * (half // 4)
var = bytearray(160)
fcntl.ioctl(fd, 0x4600, var)   # FBIOGET_VSCREENINFO
fcntl.ioctl(fd, 0x4606, var)   # FBIOPAN_DISPLAY
```

A text console on the screen would still need a kernel with `CONFIG_FRAMEBUFFER_CONSOLE`, and
`fbcon` uses the same pan path, so it may well work; untested.

## Updating the boot image later

The recovery zip only installs once. To ship a changed device package or kernel:

```sh
pmbootstrap build device-samsung-a5y17lte       # sideload does NOT build
pmbootstrap sideload --host 172.16.42.1 --user alexis --install-key device-samsung-a5y17lte
ssh -t alexis@172.16.42.1 'sudo mkinitfs && sudo dd if=/boot/boot.img of=/dev/mmcblk0p11 bs=1M conv=fsync \
  && sudo cmp -n $(stat -c %s /boot/boot.img) /boot/boot.img /dev/mmcblk0p11 && echo RECOVERY_OK'
```

3.18 now boots from `RECOVERY` (`mmcblk0p11`, see "Boot slots"); write `BOOT`
(`mmcblk0p10`) instead only if you went back to the plain layout. Writing the boot image is
never automatic on this phone. Check `zcat /boot/initramfs | cpio -t | grep hooks/`
before flashing if you changed the hook. If a boot image is bad: Download mode
(Vol Down + Home + Power, hold 10–15 s) and `heimdall flash --BOOT <img>` or
`--RECOVERY <img>` (small files work, big ones fail).

## How the problems were found (debugging toolbox)

The night went badly while I was guessing and reflashing; it went well once every step was
driven by a log. What worked:

- **Kernel log of the host.** `journalctl -k | grep 'usb 7-1'` shows exactly when the phone
  enumerates, as what (`04e8:685d` = Download mode, `18d1:d001` = TWRP adb *or* pmOS RNDIS), and
  when it disconnects (= reset/reboot).
- **`last_kmsg` is useless here**: it only holds the bootloader's log.
- **pstore `pmsg-ramoops-0`** survives warm resets. pmOS's initramfs tees its log into
  `/dev/pmsg0`, so after a crash, TWRP's `/sys/fs/pstore/pmsg-ramoops-0` shows how far the
  initramfs got. A forced restart (long Power) can wipe it. `dmesg-ramoops-0` stayed random
  garbage: the resets were hard lockups, not panics.
- **Debug wrapper as `/init`.** Appending a small extra cpio to the initramfs (the kernel unpacks
  concatenated archives, later files win) let me run a wrapper that dumped `dmesg` every 2 s
  before exec'ing the real `/init`. Lesson learned the hard way: **don't dump to raw offsets
  inside `SYSTEM`**: `pmOS_root` fills the whole partition and I wrote into it.
- **Hybrid boot images** (TWRP kernel + pmOS initramfs, etc.) to split "kernel problem" from
  "userspace problem". Repacking a v0 boot image is a few lines of Python (`struct`), page size 2048.
- **Mount the rootfs read-only from TWRP** (`losetup -r -o <offset>`, `mount -o ro,noload`) to see
  what the last boot did: superblock times (`dumpe2fs -h` on a 4 MB dump), SSH host keys existing
  or not, files written.
- **Telnet debug shell** (port 23 open = initramfs stopped). `cat /pmOS_init.log`,
  `pmos_continue_boot` to resume.
- **Process start times** (`/proc/<pid>/stat` field 22, in 1/100 s) rebuild the boot timeline
  when there's no boot log; that's how the 183 s gap after `udevd` showed up.
- **Kernel stacks of `D`-state processes** (`/proc/<pid>/stack`, root only) name the exact lock
  and driver function a hang is stuck on. Saved automatically by the status screen; that's how the
  TDLS deadlock (#17) was found after two guesses (#16, display-off hooks) turned out wrong.
- **`logread -b list` / `logread -b -1 -n 0`** read earlier boots from logbookd, but logbookd only
  flushes to disk periodically/on shutdown: a boot that hangs and gets force-restarted can vanish
  from the log entirely.

## Known limitations / open items

- Display: graphics through the framebuffer only (status screen); no text console, no GUI.
- The Wi-Fi driver doesn't report signal strength (`/proc/net/wireless` is all zeros).
- USB data needs a replug after every boot.
- `nftables` service doesn't start (old kernel); the firewall is effectively off.
- `pmbootstrap pull` / `git pull` in pmbootstrap can conflict with the local patches; keep the
  patch files here and re-apply.
- Worth reporting upstream: #5 (pmbootstrap locale regression), #8 (`--direct-io` on old kernels),
  #10/#11 (recovery-zip installer UUIDs and `/in-pmbootstrap`), #2 (untested kconfig sync).
