#!/bin/bash
# 手动构建 Android 服务端 APK
set -e

export JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2
export PATH=$JAVA_HOME/bin:$PATH
ANDROID_SDK=/workspace/build_tools/android-sdk
BUILD_TOOLS=$ANDROID_SDK/build-tools/33.0.2
PLATFORM_JAR=$ANDROID_SDK/platforms/android-33/android.jar
PROJECT=/workspace/AndroidLocationServer
BUILD=$PROJECT/app/build_manual
KEYSTORE=/root/.android/debug.keystore
DEPS=/workspace/build_tools/deps

echo "=== 1. 清理 ==="
rm -rf $BUILD
mkdir -p $BUILD/gen $BUILD/obj $BUILD/res_compiled $BUILD/dex

echo "=== 2. 编译资源 (aapt2 compile) ==="
$BUILD_TOOLS/aapt2 compile --dir $PROJECT/app/src/main/res -o $BUILD/res_compiled

echo "=== 3. 链接资源 (aapt2 link) ==="
cat > $BUILD/AndroidManifest_pkg.xml << 'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.locationserver">
XMLEOF
tail -n +3 $PROJECT/app/src/main/AndroidManifest.xml >> $BUILD/AndroidManifest_pkg.xml

# 合并 AAR 资源:不需要 appcompat(改用系统 Activity + 系统主题)
# 只取 osmdroid 的资源
AAR_RES=""
for name in osmdroid-android-6.1.14; do
    d="$DEPS/aar_extracted/$name/res"
    if [ -d "$d" ]; then
        flatdir=$(dirname "$d")/res_flat
        mkdir -p $flatdir
        $BUILD_TOOLS/aapt2 compile --dir "$d" -o $flatdir 2>/dev/null || true
        for f in $flatdir/*.flat; do
            [ -f "$f" ] && AAR_RES="$AAR_RES -R $f"
        done
    fi
done

$BUILD_TOOLS/aapt2 link \
  -I $PLATFORM_JAR \
  --manifest $BUILD/AndroidManifest_pkg.xml \
  -o $BUILD/resources.apk \
  --java $BUILD/gen \
  -R $BUILD/res_compiled/*.flat \
  $AAR_RES \
  --auto-add-overlay 2>&1 | tail -5 || true

echo "=== 4. 编译 Java (javac) ==="
find $PROJECT/app/src/main/java -name "*.java" > /tmp/srcs_server.txt
find $BUILD/gen -name "*.java" >> /tmp/srcs_server.txt

CP="$PLATFORM_JAR"
for j in $DEPS/*.jar; do
    [ -f "$j" ] && CP="$CP:$j"
done

javac -source 11 -target 11 \
  -classpath "$CP" \
  -d $BUILD/obj \
  @/tmp/srcs_server.txt 2>&1 | grep -v "warning:" || true

echo "=== 5. 转 dex (d8) ==="
DEX_JARS=""
for j in $DEPS/*-runtime.jar $DEPS/nanohttpd-*.jar $DEPS/annotation-experimental-*.jar; do
    [ -f "$j" ] && DEX_JARS="$DEX_JARS $j"
done

$BUILD_TOOLS/d8 \
  --release \
  --min-api 21 \
  --lib $PLATFORM_JAR \
  --output $BUILD/dex \
  $(find $BUILD/obj -name "*.class") \
  $DEX_JARS 2>&1 | grep -v "^Warning" || true

echo "=== 6. 打包未签名 APK ==="
cp $BUILD/resources.apk $BUILD/unsigned.apk
cd $BUILD/dex
zip -j $BUILD/unsigned.apk classes*.dex 2>/dev/null || zip -j $BUILD/unsigned.apk classes.dex
cd $PROJECT

echo "=== 7. zipalign ==="
$BUILD_TOOLS/zipalign -f 4 $BUILD/unsigned.apk $BUILD/aligned.apk

echo "=== 8. 签名 ==="
java -jar $BUILD_TOOLS/lib/apksigner.jar sign \
  --ks $KEYSTORE \
  --ks-pass pass:android \
  --ks-key-alias androiddebugkey \
  --key-pass pass:android \
  --out $BUILD/LocationServer-debug.apk \
  $BUILD/aligned.apk

echo "=== 9. 完成 ==="
ls -la $BUILD/LocationServer-debug.apk
cp $BUILD/LocationServer-debug.apk /workspace/LocationServer-debug.apk
ls -la /workspace/LocationServer-debug.apk
