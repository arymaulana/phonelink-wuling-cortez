@echo off
rem Builds phonelink.apk with Android SDK tools only. Output in app\build.
setlocal
set "JB=C:\Program Files\Android\Android Studio\jbr\bin"
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "BT=%SDK%\build-tools\36.1.0"
set "JAR=%SDK%\platforms\android-36\android.jar"
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\classes build\checkclasses build\res

rem Protocol self-check on the PC
"%JB%\javac.exe" -nowarn -d build\checkclasses src\com\phonelink\phonelinkserver\Protocol.java check\ProtocolCheck.java || exit /b 1
"%JB%\java.exe" -ea -cp build\checkclasses com.phonelink.phonelinkserver.ProtocolCheck || exit /b 1

"%BT%\aapt2.exe" compile --dir res -o build\res\res.zip || exit /b 1
"%BT%\aapt2.exe" link -o build\base.apk -I "%JAR%" --manifest AndroidManifest.xml build\res\res.zip || exit /b 1
"%JB%\javac.exe" -nowarn --release 11 -cp "%JAR%" -d build\classes src\com\phonelink\phonelinkserver\*.java || exit /b 1
call "%BT%\d8.bat" --release --min-api 21 --lib "%JAR%" --output build build\classes\com\phonelink\phonelinkserver\*.class || exit /b 1

copy /y build\base.apk build\unsigned.apk >nul
pushd build
"%JB%\jar.exe" uf unsigned.apk classes.dex || exit /b 1
popd
"%BT%\zipalign.exe" -f 4 build\unsigned.apk build\aligned.apk || exit /b 1

rem Local test key, not a release key
if not exist test.keystore "%JB%\keytool.exe" -genkeypair -keystore test.keystore -storepass android -keypass android -alias test -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=PhoneLink Test" || exit /b 1
call "%BT%\apksigner.bat" sign --ks test.keystore --ks-pass pass:android --out build\phonelink.apk build\aligned.apk || exit /b 1
call "%BT%\apksigner.bat" verify --print-certs build\phonelink.apk | findstr /b "Signer #1 certificate DN"
echo BUILD OK build\phonelink.apk
