# ELM327 Emulator Android

[![CI](https://github.com/autodiag2/ELM327SimAndroid/actions/workflows/release.yml/badge.svg?event=push)](https://github.com/autodiag2/ELM327SimAndroid/actions)
[![License](https://img.shields.io/github/license/autodiag2/ELM327SimAndroid)](https://github.com/autodiag2/ELM327SimAndroid/blob/main/LICENSE)
[![Release](https://img.shields.io/github/v/release/autodiag2/ELM327SimAndroid)](https://github.com/autodiag2/ELM327SimAndroid/releases)
![Downloads](https://img.shields.io/github/downloads/autodiag2/ELM327SimAndroid/total)

Android app to simulate, emulate an ELM327 (Wifi, Bluetooth BLE, Bluetooth) connected to a car (0-n ECUs) for testing OBD-II, UDS applications.  
You can plug in or plug out ECUs of simulation, change default protocol, inspect logs, share your config with other devs.

Ever wanted to test your scantool software before going into production ?  
Just curious about how your scantool works ?  
ELM327 Emulator is here ! Turn your phone into a car   
You can emulate ELM327 wifi, bluetooth, bluetooth BLE then connect to it for example with [autodiag](https://github.com/autodiag2/autodiag/).  
To go more deeper a playable virtual car linked to virtual ELM327 is available ! Test fuel consumption, dyno tests and much more !

<table>
  <tr>
    <td><img src="https://raw.githubusercontent.com/autodiag2/ELM327SimAndroid/main/media/main.png"></td>
    <td><img src="https://raw.githubusercontent.com/autodiag2/ELM327SimAndroid/main/media/side.png"></td>
  </tr>
  <tr>
    <td><img src="https://raw.githubusercontent.com/autodiag2/ELM327SimAndroid/main/media/settings.png"></td>
    <td><img src="https://raw.githubusercontent.com/autodiag2/ELM327SimAndroid/main/media/log.png"></td>
  </tr>
  <tr>
    <td><img src="https://raw.githubusercontent.com/autodiag2/ELM327SimAndroid/main/media/godot.png"></td>
  </tr>
</table>

## Download
Available with [releases](https://github.com/autodiag2/ELM327SimAndroid/releases/) or from [fdroid](https://f-droid.org/fr/packages/com.github.autodiag2.elm327emu/).
<br />
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on-en.png" width="250">](https://f-droid.org/fr/packages/com.github.autodiag2.elm327emu/)

## Dev
See [this](/doc/DEV.md)

## Contributing
 - This app is open to contributions, also if you want to help the app to grow you can participate to internal tests for google play (send a mail at autodiag@netcourrier.com with your google account mail).

## Credits
 - [Godot-Advanced-Vehicle](https://github.com/Dechode/Godot-Advanced-Vehicle) for the car sim view
