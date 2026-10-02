#!/system/bin/sh
# Decompress the kernel inside an Android boot image, in place, so kptools can parse it.
#
# AArch32 / BBK / XTC (Z9, and likely Z8/Z11/Z7/Z8A) boot images wrap a COMPRESSED zImage.
# `kptools unpacknolog boot.img` therefore extracts a zImage, and `kptools -l -i kernel`
# fails with "patch.c:335/parse_image_patch_info(); can't find linux banner" because kptools
# only understands a decompressed ARM Image. magiskboot unpacks the image and decompresses
# every component on the fly, so use its output as `kernel`.
#
# Usage: boot_unwrap_kernel.sh <boot-image|block-device>
# Exit 0 when ./kernel is (now) a decompressed Image; non-zero keeps the original.
IMG="$1"
[ -n "$IMG" ] || { >&2 echo "- usage: boot_unwrap_kernel.sh <boot-image>"; exit 1; }
[ -e "$IMG" ] || { >&2 echo "- $IMG does not exist"; exit 1; }

BB=busybox
[ -x ./busybox ] && BB=./busybox

# Always re-extract. A ./kernel left over from an earlier run must never be reused, or the
# UI would report the previous image's banner.
rm -f kernel

# Locate magiskboot next to the app's other native libs (./kptools is a symlink to them).
# It MUST be an absolute path: magiskboot is invoked from inside a scratch directory.
_PWD=$(pwd)
MB=
[ -x ./magiskboot ] && MB="$_PWD/magiskboot"
if [ -z "$MB" ]; then
  _kt=$($BB readlink -f ./kptools 2>/dev/null)
  if [ -n "$_kt" ]; then
    _ld=$(dirname "$_kt")
    [ -x "$_ld/libmagiskboot.so" ] && MB="$_ld/libmagiskboot.so"
  fi
fi
if [ -z "$MB" ]; then
  >&2 echo "- magiskboot not found; leaving kernel as extracted"
  exit 1
fi

case "$IMG" in /*) _abs="$IMG" ;; *) _abs="$_PWD/$IMG" ;; esac

rm -rf .unwrap && mkdir -p .unwrap || exit 1
( cd .unwrap && "$MB" unpack "$_abs" ) >/dev/null 2>&1

# magiskboot names the decompressed kernel "<file>.<fmt>" (kernel.gzip, kernel.lz4, ...);
# the exact name differs per build, so accept whichever one carries the banner.
RAW=
for f in .unwrap/kernel .unwrap/kernel.gz .unwrap/kernel.gzip .unwrap/kernel.lz4 .unwrap/kernel.xz; do
  if [ -f "$f" ] && $BB grep -aq "Linux version" "$f"; then RAW="$f"; break; fi
done

if [ -z "$RAW" ]; then
  >&2 echo "- no decompressed kernel found in $IMG"
  rm -rf .unwrap
  exit 1
fi

cp -f "$RAW" kernel || { rm -rf .unwrap; exit 1; }
rm -rf .unwrap
>&2 echo "- kernel decompressed from $IMG ($RAW, $($BB stat -c %s kernel) bytes)"
exit 0
