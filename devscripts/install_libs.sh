#!/bin/bash

if ! [ -d "../autodiag" ] ; then
  echo "this script must be launched from devscripts/"
  exit 1
fi

OPWD="$(pwd)"
cd "../autodiag"
ndk-build
cd "$OPWD"

DST="../app/src/main/jniLibs/"
SRC="../autodiag/libs/"

if ! [ -d "$SRC" ] ; then
  echo "this script must be launched from devscripts/ and $(PWD)/${SRC} must exists"
  exit 1
fi

mkdir -p "$DST"
cp -fr "$SRC"/* "$DST/"
echo "libs installed"
