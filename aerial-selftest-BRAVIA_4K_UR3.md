### Aerial self-test

BRAVIA 4K UR3, Android 10, Aerial 1.73, 2026-10-30

```
guard              PASS Aerial is the default home (app.orchard.tv/.MainActivity)
home_on_aerial     PASS Aerial in front
home_from_app      PASS from com.android.tv.settings, Aerial in front after 0.1 s
standby            SKIP STANDBY=1 to try
reboot             SKIP REBOOT=1 to try (ask the owner first)
inputs             SKIP 7 listed; INPUTS=1 to open each
```

**Inputs as Aerial lists them**
```json
[{"id":"com.sony.dtv.tvinput.external\/.ExternalTvInputService\/HW2","label":"HDMI 1","kind":"Hdmi","shown":true,"hw":2,"firmwareLabel":"HDMI 1"},{"id":"com.sony.dtv.tvinput.external\/.ExternalTvInputService\/HW3","label":"HDMI 2","kind":"Hdmi","shown":true,"hw":3,"firmwareLabel":"HDMI 2"},{"id":"com.sony.dtv.tvinput.external\/.ExternalTvInputService\/HW4","label":"HDMI 3 (eARC\/ARC)","kind":"Hdmi","shown":true,"hw":4,"firmwareLabel":"HDMI 3 (eARC\/ARC)"},{"id":"com.sony.dtv.tvinput.external\/.ExternalTvInputService\/HW5","label":"HDMI 4","kind":"Hdmi","shown":true,"hw":5,"firmwareLabel":"HDMI 4"},{"id":"com.sony.dtv.tvinput.tuner\/.DvbTerrCableInputService\/HW0","label":"数字","kind":"Tuner","shown":true,"hw":0,"firmwareLabel":"数字"},{"id":"com.sony.dtv.tvinput.tuner\/.DvbRegionAnalogInputService\/HW1","label":"模拟","kind":"Tuner","shown":true,"hw":1,"firmwareLabel":"模拟"},{"id":"com.sony.dtv.tvinput.external\/.ExternalTvInputService\/HW6","label":"视频信号","kind":"Av","shown":true,"hw":6,"firmwareLabel":"视频信号"}]
```

**Self-test result**
```json
{"model":"BRAVIA 4K UR3","aerial":"1.73","checks":{"guard":{"result":"pass","detail":"Aerial is the default home (app.orchard.tv/.MainActivity)"},"home_on_aerial":{"result":"pass","detail":"Aerial in front"},"home_from_app":{"result":"pass","detail":"from com.android.tv.settings, Aerial in front after 0.1 s"},"standby":{"result":"skip","detail":"STANDBY=1 to try"},"reboot":{"result":"skip","detail":"REBOOT=1 to try (ask the owner first)"},"inputs":{"result":"skip","detail":"7 listed; INPUTS=1 to open each"}}}
```
