#!/bin/bash
DEST=../fastlane/metadata/android/en-US/images/

if ! [ -d "$DEST" ]; then
	echo "this script must be launched from media/"
	exit 1
fi

cp -f "logo/logo.png"   "$DEST/icon.png"
cp -f "main.png"        "$DEST/phoneScreenshots/1.png"
cp -f "log.png"         "$DEST/phoneScreenshots/2.png"
cp -f "settings.png"    "$DEST/phoneScreenshots/3.png"
cp -f "side.png"        "$DEST/phoneScreenshots/4.png"

echo "Images updated"