#!/bin/sh
# By SakuraKyuo

OUTFD=/proc/self/fd/$2

function ui_print() {
  echo -e "ui_print $1\nui_print" >> $OUTFD
}

function ui_printfile() {
  while IFS='' read -r line || $BB [[ -n "$line" ]]; do
    ui_print "$line";
  done < $1;
}

function kernelFlagsErr(){
	ui_print "- Installation has Aborted!"
	ui_print "- APatch requires CONFIG_KALLSYMS to be Enabled."
	ui_print "- But your kernel seems NOT enabled it."
	exit
}

function apatchNote(){
	ui_print "- APatch Patch Done"
	ui_print "- We do have saved Origin Boot image to /data"
	ui_print "- If you encounter bootloop, reboot into Recovery and flash it"
	exit
}

function failed(){
	ui_printfile /dev/tmp/install/log
	ui_print "- APatch Patch Failed."
	ui_print "- Please feedback to the developer with the screenshots."
	exit
}

# This device (XTC/MSM8937) has no /dev/block/by-name; the dd below needs it.
if [ ! -e /dev/block/by-name ] && [ -d /dev/block/bootdevice/by-name ]; then
	mkdir -p /dev/block/by-name 2>/dev/null
	for _bn in /dev/block/bootdevice/by-name/*; do
		[ -e "$_bn" ] && ln -sf "$_bn" /dev/block/by-name/ 2>/dev/null
	done
	unset _bn
fi

function boot_execute_ab(){
	./lib/$(getprop ro.product.cpu.abi)/libkptools.so unpack boot.img
	if [[ ! $(./lib/$(getprop ro.product.cpu.abi)/libkptools.so -i ./kernel -f | grep CONFIG_KALLSYMS=y) ]]; then
		kernelFlagsErr
	fi
	mv kernel kernel-origin
	./lib/$(getprop ro.product.cpu.abi)/libkptools.so -p --image kernel-origin --kpimg ./assets/kpimg --out ./kernel 2>&1 | tee /dev/tmp/install/log
	if [[ ! $(cat /dev/tmp/install/log | grep "patch done") ]]; then
		failed
	fi
	ui_printfile /dev/tmp/install/log
	./lib/$(getprop ro.product.cpu.abi)/libkptools.so repack boot.img
	dd if=/dev/tmp/install/new-boot.img of=/dev/block/by-name/boot$slot
	mv boot.img /data/boot.img
	apatchNote
}

function boot_execute(){
	./lib/$(getprop ro.product.cpu.abi)/libkptools.so unpack boot.img
	if [[ ! $(./lib/$(getprop ro.product.cpu.abi)/libkptools.so -i ./kernel -f | grep CONFIG_KALLSYMS=y) ]]; then
		kernelFlagsErr
	fi
	mv kernel kernel-origin
	./lib/$(getprop ro.product.cpu.abi)/libkptools.so -p --image kernel-origin --kpimg ./assets/kpimg --out ./kernel 2>&1 | tee /dev/tmp/install/log
	if [[ ! $(cat /dev/tmp/install/log | grep "patch done") ]]; then
		failed
	fi
	ui_printfile /dev/tmp/install/log
	./lib/$(getprop ro.product.cpu.abi)/libkptools.so repack boot.img
	dd if=/dev/tmp/install/new-boot.img of=/dev/block/by-name/boot$slot
	mv boot.img /data/boot.img
	apatchNote
}

function main(){

cd /dev/tmp/install

chmod a+x ./assets/kpimg
chmod a+x ./lib/$(getprop ro.product.cpu.abi)/libkptools.so

slot=$(getprop ro.boot.slot_suffix)

if [[ ! "$slot" == "" ]]; then

	ui_print ""
	ui_print "- You are using A/B device."

	# Script author
	ui_print "- Install Script by SakuraKyuo"

	# Get kernel
	ui_print ""
	dd if=/dev/block/by-name/boot$slot of=/dev/tmp/install/boot.img
	if [[ "$?" == 0 ]]; then
		ui_print "- Detected boot partition."
		boot_execute_ab
	fi

else

	ui_print "You are using A Only device."

	# Get kernel
	ui_print ""
	dd if=/dev/block/by-name/boot of=/dev/tmp/install/boot.img
	if [[ "$?" == 0 ]]; then
		ui_print "- Detected boot partition."
		boot_execute
	fi

fi

}

main
