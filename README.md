<h1 align="left">
  <img src="media/logo/logo.png" alt="ELM327 Emulator Android" width="150" valign="middle">
  ELM327 Emulator Android
</h1>

[![CI](https://github.com/autodiag2/ELM327SimAndroid/actions/workflows/release.yml/badge.svg?event=push)](https://github.com/autodiag2/ELM327SimAndroid/actions)
[![License](https://img.shields.io/github/license/autodiag2/ELM327SimAndroid)](https://github.com/autodiag2/ELM327SimAndroid/blob/main/LICENSE)
[![Release](https://img.shields.io/github/v/release/autodiag2/ELM327SimAndroid)](https://github.com/autodiag2/ELM327SimAndroid/releases)
![Downloads](https://img.shields.io/github/downloads/autodiag2/ELM327SimAndroid/total)
![com.github.autodiag2.elm327emu-light.apk](https://img.shields.io/badge/Android-5.1%2B%20(API%2022%2B)-3DDC84?logo=android&logoColor=white)
![com.github.autodiag2.elm327emu.apk](https://img.shields.io/badge/Android%20%2B%20Godot-7.0%2B%20(API%2024%2B)-478CBF?logo=godot-engine&logoColor=white)  

Android app to simulate, emulate an ELM327 (Wifi, Bluetooth BLE, Bluetooth) connected to a car (0-n ECUs) for testing OBD-II, UDS applications.  
You can plug in or plug out ECUs of simulation, change default protocol, inspect logs, share your config with other devs.  
Turn your phone into a virtual car with ELM327 connected.  

<table>
  <tr>
    <td width="50%"><img src="https://raw.githubusercontent.com/autodiag2/ELM327SimAndroid/main/media/main.png"></td>
    <td width="50%"><img src="https://raw.githubusercontent.com/autodiag2/ELM327SimAndroid/main/media/side.png"></td>
  </tr>
  <tr>
    <td width="50%"><img src="https://raw.githubusercontent.com/autodiag2/ELM327SimAndroid/main/media/settings.png"></td>
    <td width="50%"><img src="https://raw.githubusercontent.com/autodiag2/ELM327SimAndroid/main/media/log.png"></td>
  </tr>
  <tr>
    <td width="50%"><img src="https://raw.githubusercontent.com/autodiag2/ELM327SimAndroid/main/media/godot.png"></td>
  </tr>
</table>

## Use cases
Test your scantool software before going into production ? for example with [autodiag](https://github.com/autodiag2/autodiag/).  
Curious about how your scantool works ?    
Go more deeper with a playable virtual car linked to the virtual ELM327, Test fuel consumption, evaluate dyno test and much more !

## Download
Available with [releases](https://github.com/autodiag2/ELM327SimAndroid/releases/) or from [fdroid](https://f-droid.org/fr/packages/com.github.autodiag2.elm327emu/).
<br />
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on-en.png" width="250">](https://f-droid.org/fr/packages/com.github.autodiag2.elm327emu/)

**Compatibility**: The application supports Android 5.1 (API 22) and newer. However, the embedded Godot engine requires Android 7.0 (API 24) or later, so Godot-based features are available only on devices running Android 7.0+. For older devices prefer `com.github.autodiag2.elm327emu-light.apk`, even with this BLE mode may not work on all devices (see log).

## Dev
See [this](/doc/DEV.md)

## Contributing
 - This app is open to contributions, also if you want to help the app to grow you can participate to internal tests for google play (send a mail at autodiag@netcourrier.com with your google account mail).

## Credits
 - [Godot-Advanced-Vehicle](https://github.com/Dechode/Godot-Advanced-Vehicle) for the car sim view
