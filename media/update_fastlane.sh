#!/bin/bash
ORIGIN=./
if [ -d "./media" ] ; then
	ORIGIN="./media/"
fi

DEST="${ORIGIN}/../fastlane/metadata/android/en-US/images/"

if ! [ -d "$DEST" ]; then
	echo "folder ${DEST} not found, launch this script from root or root/media"
	exit 1
fi

cp -f "${ORIGIN}/logo/logo.png"   "$DEST/icon.png"
cp -f "${ORIGIN}/main.png"        "$DEST/phoneScreenshots/1.png"
cp -f "${ORIGIN}/log.png"         "$DEST/phoneScreenshots/2.png"
cp -f "${ORIGIN}/settings.png"    "$DEST/phoneScreenshots/3.png"
cp -f "${ORIGIN}/side.png"        "$DEST/phoneScreenshots/4.png"
cp -f "${ORIGIN}/godot.png"       "$DEST/phoneScreenshots/5.png"

echo "Images updated"
