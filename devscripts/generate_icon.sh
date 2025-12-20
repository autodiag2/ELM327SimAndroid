#!/bin/bash
SRC=./logo.png
RES=../app/src/main/res

if ! [ -f "$SRC" ] ; then
	echo "this script must be launched from devscripts/"
	exit 1
fi

declare -A sizes=(
  [mipmap-mdpi]=48
  [mipmap-hdpi]=72
  [mipmap-xhdpi]=96
  [mipmap-xxhdpi]=144
  [mipmap-xxxhdpi]=192
)

for dir in "${!sizes[@]}"; do
  px=${sizes[$dir]}
  mkdir -p "$RES/$dir"
  sips -z $px $px "$SRC" --out "$RES/$dir/ic_launcher.png"
  sips -z $px $px "$SRC" --out "$RES/$dir/ic_launcher_round.png"
done

mkdir -p "$RES/mipmap-anydpi-v26"
sips -z 1024 1024 "$SRC" --out "$RES/mipmap-anydpi-v26/ic_launcher_foreground.png"

