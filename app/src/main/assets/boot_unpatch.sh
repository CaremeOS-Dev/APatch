#!/system/bin/sh
#######################################################################################
# APatch Boot Image Unpatcher — AArch32 / BBK / XTC (MSM8937) build
#
# Mirrors boot_patch.sh: magiskboot handles unpack/repack (kptools repack segfaults on
# these images), kptools only unwraps the KernelPatch payload from the Image itself.
# Runs in a private scratch directory so it never relies on files left behind by an
# earlier step.
#
# Usage: boot_unpatch.sh <bootimage> [ARGS_FOR_KPTOOLS]
#######################################################################################

ARCH=$(getprop ro.product.cpu.abi)

# Load utility functions
. ./util_functions.sh

echo "****************************"
echo " APatch Boot Image Unpatcher"
echo "****************************"

BOOTIMAGE="$1"

[ -e "$BOOTIMAGE" ] || { echo "- $BOOTIMAGE does not exist!"; exit 1; }

echo "- Target image: $BOOTIMAGE"

# The app copies the stock backup to ./new-boot.img before calling us. When it is there,
# restoring it IS the unpatch: no need to reverse-engineer the payload out of the Image.
if [ -s new-boot.img ]; then
  echo "- found backup boot.img, restoring it"
  if [ -b "$BOOTIMAGE" ] || [ -c "$BOOTIMAGE" ]; then
    flash_image new-boot.img "$BOOTIMAGE"
    if [ $? -ne 0 ]; then
      >&2 echo "- Flash error: $?"
      exit $?
    fi
    echo "- Flash successful"
  else
    echo "- not a block device; backup left at ./new-boot.img"
  fi
  exit 0
fi

# Check for dependencies
command -v ./kptools >/dev/null 2>&1 || { echo "- Command kptools not found!"; exit 1; }

case "$BOOTIMAGE" in
  /*) AP_BOOTIMAGE="$BOOTIMAGE" ;;
  *)  AP_BOOTIMAGE="$(pwd)/$BOOTIMAGE" ;;
esac

ap_libdir() {
  _k=$(./busybox readlink -f ./kptools 2>/dev/null)
  if [ -n "$_k" ]; then dirname "$_k"; else echo "./lib/$ARCH"; fi
}
AP_LIBDIR=$(ap_libdir)

ap_tool() {
  if [ -x "./$1" ]; then echo "$(pwd)/$1"; return 0; fi
  if [ -x "$AP_LIBDIR/lib$1.so" ]; then echo "$AP_LIBDIR/lib$1.so"; return 0; fi
  return 1
}

MAGISKBOOT=$(ap_tool magiskboot) || { >&2 echo "- magiskboot not found!"; exit 1; }
BB=./busybox

WORK=.apkpu
rm -rf "$WORK" || exit 1
mkdir -p "$WORK" || exit 1
trap 'rm -rf "$WORK"' EXIT HUP INT TERM

echo "- Unpacking boot image (magiskboot)"
( cd "$WORK" && "$MAGISKBOOT" unpack "$AP_BOOTIMAGE" ) || {
  >&2 echo "- magiskboot unpack failed"; exit 1; }
[ -s "$WORK/kernel" ] || { >&2 echo "- no kernel found in the boot image"; exit 1; }

RAW="$WORK/kernel"

if ./kptools -l -i "$RAW" 2>/dev/null | grep -q "patched=false"; then
  echo "- no need unpatch"
  exit 0
fi

echo "- kernel has been patched"

cp -f "$RAW" "$WORK/Image.ori" || exit 1

echo "- Unpatching kernel"
./kptools -u --image "$WORK/Image.ori" --out "$WORK/Image.unp" "$@"
if [ $? -ne 0 ]; then
  >&2 echo "- Unpatch error: $?"
  exit 1
fi

mv -f "$WORK/Image.unp" "$WORK/kernel" || exit 1

echo "- Repacking boot image (magiskboot)"
rm -f "$WORK/new-boot.img"
set -x
( cd "$WORK" && "$MAGISKBOOT" repack "$AP_BOOTIMAGE" )
patch_rc=$?
set +x

if [ $patch_rc -ne 0 ] || [ ! -s "$WORK/new-boot.img" ]; then
  >&2 echo "- Repack error: $patch_rc"
  exit 1
fi

cp -f "$WORK/new-boot.img" new-boot.img || exit 1
echo "- new-boot.img: $($BB stat -c %s new-boot.img) bytes"

if [ -b "$BOOTIMAGE" ] || [ -c "$BOOTIMAGE" ]; then
  echo "- Flashing boot image"
  flash_image new-boot.img "$BOOTIMAGE"
  if [ $? -ne 0 ]; then
    >&2 echo "- Flash error: $?"
    exit $?
  fi
fi

echo "- Flash successful"

# Reset any error code
true
