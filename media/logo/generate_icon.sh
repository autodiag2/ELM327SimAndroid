#!/bin/bash
SRC=./logo.png
RES=../../app/src/main/res
FASTLANE_LOGO="../../fastlane/metadata/android/en-US/images/icon.png"
if ! [ -f "$SRC" ] ; then
	echo "this script must be launched from media/logo"
	exit 1
fi

for item in \
  mipmap-mdpi:48 \
  mipmap-hdpi:72 \
  mipmap-xhdpi:96 \
  mipmap-xxhdpi:144 \
  mipmap-xxxhdpi:192
do
  dir=${item%%:*}
  px=${item##*:}
  mkdir -p "$RES/$dir"
  sips -z $px $px "$SRC" --out "$RES/$dir/ic_launcher.png"
  sips -z $px $px "$SRC" --out "$RES/$dir/ic_launcher_round.png"
done

mkdir -p "$RES/drawable/"
sips -z 1024 1024 "$SRC" --out "$RES/drawable/ic_launcher_foreground.png"
sips -z 1024 1024 "$SRC" --out "$FASTLANE_LOGO"
