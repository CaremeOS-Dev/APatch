#!/system/bin/sh
#######################################################################################
# APatch Boot Image Patcher — AArch32 / BBK / XTC (MSM8937) build
#
# Why this differs from upstream APatch:
#   1. The kernel inside these boot images is a COMPRESSED zImage. kptools -p/--image only
#      parses a decompressed ARM Image, otherwise it fails with
#      "patch.c:335/parse_image_patch_info(); can't find linux banner" — which the UI
#      surfaces as a bogus "CONFIG_KALLSYMS is not enabled".
#   2. `kptools repack` SEGFAULTS (SIGSEGV, rc=139) on these images. magiskboot
#      unpack/repack round-trips them correctly (verified byte-identical kernel).
#   So magiskboot does every unpack/repack job and kptools only patches the Image.
#   3. BBK/XTC bootloaders stamp a verdict the kernel re-checks and machine_restart()s
#      ("boot_vfail") on mismatch — a ~1 second reboot loop. libbvfix locates that site by
#      FEATURES (string cluster + cmp #0x3a + conditional branch + movw/movt/bl scoring),
#      never by a hardcoded offset, so it transfers to other Z-series models, and it
#      refuses to patch when the margin is too small.
#
# All work happens inside a private scratch directory, so this script never depends on
# files a previous step (e.g. the app's parse pass) happened to leave behind.
#
# Usage: boot_patch.sh <superkey> <bootimage> [FLASH_TO_DEVICE] [ARGS_FOR_KPTOOLS]
#######################################################################################

# On 32-bit ARM the image-side CONFIG_* pre-checks are false negatives when the kernel
# ships without an embedded IKCONFIG (XTC/MSM8937). Runtime /proc/kallsyms is authoritative,
# so a failed check is downgraded to a warning. Set AP_FORCE_PATCH=0 for strict behaviour.
if [ -z "$AP_FORCE_PATCH" ]; then
  case "$(getprop ro.product.cpu.abi)" in armeabi-v7a|armeabi) AP_FORCE_PATCH=1 ;; *) AP_FORCE_PATCH=0 ;; esac
fi

ARCH=$(getprop ro.product.cpu.abi)

# Load utility functions
. ./util_functions.sh

echo "****************************"
echo " APatch Boot Image Patcher"
echo "****************************"

SUPERKEY="$1"
BOOTIMAGE="$2"
FLASH_TO_DEVICE="$3"
if [ $# -gt 3 ]; then shift 3; else shift $#; fi

[ -z "$SUPERKEY" ] && { >&2 echo "- SuperKey empty!"; exit 1; }
[ -e "$BOOTIMAGE" ] || { >&2 echo "- $BOOTIMAGE does not exist!"; exit 1; }

# Check for dependencies
command -v ./kptools >/dev/null 2>&1 || { >&2 echo "- Command kptools not found!"; exit 1; }

# magiskboot runs from a scratch directory, so it needs an absolute path. BOOTIMAGE may be
# a relative file (a user-chosen boot.img) or an absolute block device (/dev/block/...).
case "$BOOTIMAGE" in
  /*) AP_BOOTIMAGE="$BOOTIMAGE" ;;
  *)  AP_BOOTIMAGE="$(pwd)/$BOOTIMAGE" ;;
esac

# Native libs live next to the ./kptools symlink (/data/app/<pkg>/lib/arm/).
ap_libdir() {
  _k=$(./busybox readlink -f ./kptools 2>/dev/null)
  if [ -n "$_k" ]; then dirname "$_k"; else echo "./lib/$ARCH"; fi
}
AP_LIBDIR=$(ap_libdir)

ap_tool() {
  # ap_tool <name> : resolve ./<name>, else $AP_LIBDIR/lib<name>.so, else empty
  if [ -x "./$1" ]; then echo "$(pwd)/$1"; return 0; fi
  if [ -x "$AP_LIBDIR/lib$1.so" ]; then echo "$AP_LIBDIR/lib$1.so"; return 0; fi
  return 1
}

MAGISKBOOT=$(ap_tool magiskboot) || { >&2 echo "- magiskboot not found!"; exit 1; }
BVFIX=$(ap_tool bvfix)
BB=./busybox

#######################################################################################
# AArch32 entry-layout helpers
#
# On AArch32, stext begins with `bl __hyp_stub_install` — an instruction that MUST
# run.  The patcher cannot simply clobber it with its own B: the proven-booting
# reference image keeps stext's first instruction at word 0 and puts the B at
# word 1 (setup_preset.header_backup restores both words before jumping into the
# real entry).  Moving a relative branch from offset 0 to offset 4 shortens its
# displacement by exactly one instruction word, so the corrected opcode is w0 - 1.
# These helpers read/emit little-endian u32 without relying on the shell's
# (32-bit signed) arithmetic.
#######################################################################################
ap_w32() { "$BB" od -An -tu4 -j "$2" -N 4 "$1" 2>/dev/null | "$BB" tr -d ' \n'; }

# ap_emit4 <u32> : write the word as 4 raw little-endian bytes on stdout
ap_emit4() {
  "$BB" awk -v n="$1" 'BEGIN{
    printf "%c%c%c%c", n%256, int(n/256)%256, int(n/65536)%256, int(n/16777216)%256
  }'
}

# ap_is_branch <u32> : print 1 when the word is an A32 B (top byte 0xEA).
# 0xEB04352E (`bl __hyp_stub_install`) is deliberately NOT a branch here: the whole
# point is to keep that instruction and only relocate kptools' own B.
ap_is_branch() {
  "$BB" awk -v w="$1" 'BEGIN{ print (int(w/16777216) == 234) ? 1 : 0 }'
}

# ap_b_target <opcode> <offset> : A32 PC-relative B destination.
# The 24-bit immediate occupies the opcode's low bits and is already in units of 4.
ap_b_target() {
  "$BB" awk -v w="$1" -v o="$2" 'BEGIN{
    i = w % 16777216;                  # low 24 bits = branch immediate / 4
    if (i >= 8388608) i -= 16777216;   # sign-extend
    printf "%d", o + 8 + i * 4
  }'
}

# ---------------------------------------------------------------- 0. kpimg arch gate
# kptools only knows x86_64 vs "everything else", so it reports arch=arm64 for BOTH
# a 32-bit ARM blob and a real 64-bit one — its metadata cannot be trusted here.
# Injecting an A64 blob into an AArch32 kernel is an instant illegal-instruction hang.
# Ground truth is the blob's own code: kpimg-arm*.lds puts setup_entry at blob offset
# 0x1000, and it is an A32 `B setup` (0xEA......) in the ARM build and an A64 `add x9,
# sp, #0` (0x910003e9) in the arm64 build.  The A32-only "A32 relocate" string and
# the A64-only "ID_AA64AFR0_EL1" string are checked as cross-checks.
if [ -f kpimg ]; then
  _ent=$(ap_w32 kpimg 4096)                     # opcode at blob offset 0x1000
  _top=$("$BB" awk -v w="$_ent" 'BEGIN{ printf "%d", int(w/16777216) }')
  _a32mk=$("$BB" grep -c "A32 relocate" kpimg 2>/dev/null)
  _a64mk=$("$BB" grep -c "ID_AA64AFR0_EL1" kpimg 2>/dev/null)
  echo "- kpimg: setup_entry opcode=0x$(printf '%08x' "$_ent") A32mark=$_a32mk A64mark=$_a64mk"

  if [ "$_top" != "234" ] || [ "$_a32mk" -lt 1 ] || [ "$_a64mk" -gt 0 ]; then
    >&2 echo "- kpimg is NOT an AArch32 blob (this device is $ARCH)."
    >&2 echo "- Refusing to patch: an A64 blob in a 32-bit kernel hangs the device."
    exit 1
  fi
  echo "- kpimg: AArch32 (ok)"
fi

WORK=.apkpi
rm -rf "$WORK" || exit 1
mkdir -p "$WORK" || exit 1
trap 'rm -rf "$WORK"' EXIT HUP INT TERM

# ---------------------------------------------------------------- 1. unpack
echo "- Unpacking boot image (magiskboot)"
( cd "$WORK" && "$MAGISKBOOT" unpack "$AP_BOOTIMAGE" ) || {
  >&2 echo "- magiskboot unpack failed"; exit 1; }

# magiskboot always writes the DECOMPRESSED kernel to ./kernel (gzip/lz4/xz handled for us),
# which is exactly the Image kptools needs.
if [ ! -s "$WORK/kernel" ]; then
  >&2 echo "- no kernel found in the boot image"
  exit 1
fi
echo "- kernel: Image ($($BB stat -c %s "$WORK/kernel") bytes)"

RAW="$WORK/kernel"

# Preserve the untouched Image for the unpatch path before bvfix/kptools rewrite it.
cp -f "$RAW" "$WORK/Image.stock" || exit 1

# ---------------------------------------------------------------- 2. bvfix
if [ "$AP_BVFIX" != "0" ]; then
  if [ -n "$BVFIX" ] && [ -x "$BVFIX" ]; then
    "$BVFIX" "$RAW" || {
      echo "- WARNING: bvfix refused to patch (no site found, or margin too small)."
      echo "- WARNING: if this device checks boot verification, it will boot-loop."
    }
  else
    echo "- WARNING: bvfix not found; boot verification left in place"
  fi
fi

# ---------------------------------------------------------------- 3. patch
if [ "$AP_FORCE_PATCH" != "1" ] && { [ ! -r /proc/kallsyms ] || [ "$(wc -l < /proc/kallsyms 2>/dev/null)" -lt 100 ]; }; then
	echo "- Patcher has Aborted!"
	echo "- APatch requires CONFIG_KALLSYMS to be Enabled."
	echo "- But your kernel seems NOT enabled it."
	exit 0
fi

echo "- Patching kernel"

# Keep a stock copy of the boot image where the unpatch flow and OTA cleanup look for it.
if [ ! -f ori.img ]; then
  echo "- Backing up original boot image"
  cp "$BOOTIMAGE" ori.img >/dev/null 2>&1 || rm -f ori.img
fi

KPT_ARGS=""
[ "$SUPERKEY" != "su" ] && KPT_ARGS="-S $SUPERKEY"

cp -f "$RAW" "$WORK/Image.ori" || exit 1

set -x
./kptools -p -i "$WORK/Image.ori" $KPT_ARGS -k kpimg -o "$WORK/Image.patched" "$@"
patch_rc=$?
set +x

if [ $patch_rc -ne 0 ]; then
  >&2 echo "- Patch kernel error: $patch_rc"
  exit $patch_rc
fi

if [ "$AP_FORCE_PATCH" != "1" ] && [ "$(grep -cE " [DdBbRr] " /proc/kallsyms 2>/dev/null)" -lt 100 ]; then
	echo "- Detected CONFIG_KALLSYMS_ALL is not set!"
	echo "- APatch has patched but maybe your device won't boot."
	echo "- Make sure you have original boot image backup."
fi

# ------------------------------------------------ 3b. repair the AArch32 entry
# kptools puts its branch at offset 0, destroying stext's `bl __hyp_stub_install`.
# The real kernel entry must be entered *through* that bl, so word 0 has to be the
# pristine stock opcode and the injected branch has to live at word 1.
# Also drop a single instruction from the branch displacement (offset 0 -> 4).
if [ "$ARCH" = "armeabi-v7a" ] || [ "$ARCH" = "armeabi" ]; then
  _w0=$(ap_w32 "$WORK/Image.patched" 0)
  _w1=$(ap_w32 "$WORK/Image.patched" 4)
  # kptools' layout is deterministic: the kpimg header page lands at
  # align_ceil(len(input Image), 4096) and it branches SZ_4K past that, i.e.
  # text_offset == the blob's setup_entry.  Derive the expected target from the
  # very same input Image kptools was handed, then verify the blob is really there.
  _isz=$("$BB" stat -c %s "$WORK/Image.ori")
  _blob=$(( ( (_isz + 4095) / 4096 ) * 4096 ))
  _want=$(( _blob + 4096 ))
  _fixed=0
  _magic=$("$BB" dd if="$WORK/Image.patched" bs=1 skip=$_blob count=4 2>/dev/null)

  echo "- kpimg blob page=0x$(printf '%x' "$_blob") expected entry=0x$(printf '%x' "$_want")"

  if [ "$_magic" != "KP11" ]; then
    >&2 echo "- kpimg blob not found at 0x$(printf '%x' "$_blob") (got '$_magic'); refusing to patch"
    exit 1
  fi

  if [ "$(ap_is_branch "$_w0")" = "1" ]; then
    # kptools clobbered word 0: shorten the branch for its new home and move it
    # to word 1, then put stext's `bl __hyp_stub_install` back at word 0.
    _nb=$(( _w0 - 1 ))                       # 0xEA58FFFE -> 0xEA58FFFD
    ap_emit4 "$_nb" | "$BB" dd of="$WORK/Image.patched" bs=1 seek=4 conv=notrunc 2>/dev/null
    ap_emit4 3942921518 | "$BB" dd of="$WORK/Image.patched" bs=1 seek=0 conv=notrunc 2>/dev/null
    _w0=$(ap_w32 "$WORK/Image.patched" 0)
    _w1=$(ap_w32 "$WORK/Image.patched" 4)
    echo "- entry B relocated 0 -> 4 (0xea58fffe -> 0x$(printf '%x' "$_nb"))"
    echo "- entry word0: stext \`bl __hyp_stub_install\` restored (0xeb04352e)"
    _fixed=1
  elif [ "$_w0" = "3942921518" ] && [ "$(ap_is_branch "$_w1")" = "1" ]; then
    echo "- entry layout already correct (w0=0xeb04352e, w1=0x$(printf '%x' "$_w1"))"
    _fixed=1
  else
    >&2 echo "- unrecognised AArch32 entry layout (w0=0x$_w0 w1=0x$_w1); refusing to guess"
    exit 1
  fi

  if [ "$_fixed" = "1" ]; then
    _t=$(ap_b_target "$_w1" 4)
    echo "- entry self-check: B target=0x$(printf '%x' "$_t") expected=0x$(printf '%x' "$_want")"
    if [ "$_t" != "$_want" ]; then
      >&2 echo "- entry self-check FAILED: kptools' branch does not land on the blob"
      exit 1
    fi
    # Word 0 must be the stock stext opcode: entering the kernel without running
    # `bl __hyp_stub_install` hangs before any log appears.
    if [ "$_w0" != "3942921518" ]; then
      >&2 echo "- entry self-check FAILED: word0 is 0x$_w0, not stext's bl"
      exit 1
    fi
    echo "- entry self-check OK"
  fi
fi

mv -f "$WORK/Image.patched" "$WORK/kernel" || exit 1

# ---------------------------------------------------------------- 4. repack
# kptools repack segfaults here; magiskboot rebuilds the image from the scratch dir.
echo "- Repacking boot image (magiskboot)"
rm -f "$WORK/new-boot.img"
set -x
( cd "$WORK" && "$MAGISKBOOT" repack "$AP_BOOTIMAGE" )
patch_rc=$?
set +x

if [ $patch_rc -ne 0 ] || [ ! -s "$WORK/new-boot.img" ]; then
  >&2 echo "- Repack error: $patch_rc"
  exit $patch_rc
fi

# Keep the raw Image for the unpatch path, and publish the new image at the usual place.
cp -f "$WORK/Image.stock" kernel.ori 2>/dev/null
cp -f "$WORK/new-boot.img" new-boot.img || exit 1
echo "- new-boot.img: $($BB stat -c %s new-boot.img) bytes"

if [ "$FLASH_TO_DEVICE" = "true" ]; then
  if [ -b "$BOOTIMAGE" ] || [ -c "$BOOTIMAGE" ]; then
    echo "- Flashing new boot image"
    flash_image new-boot.img "$BOOTIMAGE"
    if [ $? -ne 0 ]; then
      >&2 echo "- Flash error: $?"
      exit $?
    fi
    echo "- Successfully Flashed!"
  else
    >&2 echo "- Not a block device, skipping flash: $BOOTIMAGE"
    echo "- Successfully Patched!"
  fi
else
  echo "- Successfully Patched!"
fi
