#!/bin/bash
# 手动构建 Android 客户端 APK
set -e

export JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2
export PATH=$JAVA_HOME/bin:$PATH
ANDROID_SDK=/workspace/build_tools/android-sdk
BUILD_TOOLS=$ANDROID_SDK/build-tools/33.0.2
PLATFORM_JAR=$ANDROID_SDK/platforms/android-33/android.jar
PROJECT=/workspace/AndroidLocationClient
BUILD=$PROJECT/app/build_manual
KEYSTORE=/root/.android/debug.keystore
DEPS=/workspace/build_tools/deps

echo "=== 1. 清理 ==="
rm -rf $BUILD /tmp/flat_short_client
mkdir -p $BUILD/gen $BUILD/obj $BUILD/res_compiled $BUILD/dex /tmp/flat_short_client

echo "=== 2. 编译资源 (aapt2 compile) ==="
$BUILD_TOOLS/aapt2 compile --dir $PROJECT/app/src/main/res -o $BUILD/res_compiled

echo "=== 3. 链接资源 (aapt2 link) ==="
cat > $BUILD/AndroidManifest_pkg.xml << 'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.locationclient"
    android:versionCode="13"
    android:versionName="1.3.0">
XMLEOF
tail -n +3 $PROJECT/app/src/main/AndroidManifest.xml >> $BUILD/AndroidManifest_pkg.xml

# 客户端不需要额外 AAR 资源,只编译项目自身的资源
# 复制 flat 文件到短路径避免命令行过长
PROJ_RES=""
idx=1
for f in $BUILD/res_compiled/*.flat; do
    if [ -f "$f" ]; then
        short=/tmp/flat_short_client/p$(printf "%03d" $idx).flat
        cp "$f" "$short"
        PROJ_RES="$PROJ_RES -R $short"
        idx=$((idx+1))
    fi
done

$BUILD_TOOLS/aapt2 link \
  -I $PLATFORM_JAR \
  --manifest $BUILD/AndroidManifest_pkg.xml \
  -o $BUILD/resources.apk \
  --java $BUILD/gen \
  --auto-add-overlay \
  $PROJ_RES 2>&1
echo "=== aapt2 link done ==="

echo "=== 4. 编译 Java (javac) ==="
find $PROJECT/app/src/main/java -name "*.java" > /tmp/srcs_client.txt
find $BUILD/gen -name "*.java" >> /tmp/srcs_client.txt
echo "源文件数: $(wc -l < /tmp/srcs_client.txt)"

CP="$PLATFORM_JAR"
for j in $DEPS/*.jar; do
    [ -f "$j" ] && CP="$CP:$j"
done

javac -source 11 -target 11 \
  -classpath "$CP" \
  -d $BUILD/obj \
  @/tmp/srcs_client.txt 2>&1 | grep -E "error:" || true
echo "=== javac done, class 文件数: $(find $BUILD/obj -name '*.class' | wc -l) ==="

echo "=== 5. 转 dex (d8) ==="
# 客户端已完全改用系统框架 API,不再打包任何第三方 jar(轻量化,APK 体积最小)
DEX_JARS=""

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
  --out $BUILD/LocationClient-debug.apk \
  $BUILD/aligned.apk

echo "=== 9. 完成 ==="
ls -la $BUILD/LocationClient-debug.apk
cp $BUILD/LocationClient-debug.apk /workspace/LocationClient-debug.apk
ls -la /workspace/LocationClient-debug.apk
