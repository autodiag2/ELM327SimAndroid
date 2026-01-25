# Develop
## Build
### Retrieving the repo
```bash
git clone --depth=1 https://github.com/autodiag2/ELM327SimAndroid/ && \
 cd ELM327SimAndroid && \
 git submodule update --init --recursive --depth=1
```
Compile and install the lib
```bash
cd devscripts/
./install_libs.sh
cd ..
```
Compile the app
```bash
./gradlew assembleDebug
```