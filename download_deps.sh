#!/bin/bash
# 从阿里云 Maven 下载所有依赖 JAR/AAR 并提取 classes.jar
# 阿里云 Maven 仓库地址
MAVEN="https://maven.aliyun.com/repository/public"
GOOGLE="https://maven.aliyun.com/repository/google"

DEPS_DIR="/workspace/build_tools/deps"
mkdir -p "$DEPS_DIR/aar_extracted"
cd "$DEPS_DIR"

download_jar() {
    # $1 = groupId (colon-separated), $2 = artifactId, $3 = version
    local group=$(echo "$1" | tr '.' '/')
    local url="$GOOGLE/$group/$2/$3/$2-$3.jar"
    # 也试 public
    local url2="$MAVEN/$group/$2/$3/$2-$3.jar"
    echo "GET $url"
    curl -sSL --fail -o "$DEPS_DIR/$2-$3.jar" "$url" 2>/dev/null || \
    curl -sSL --fail -o "$DEPS_DIR/$2-$3.jar" "$url2" 2>/dev/null || \
    echo "  MISSING JAR $2-$3"
}

download_aar() {
    local group=$(echo "$1" | tr '.' '/')
    local url="$GOOGLE/$group/$2/$3/$2-$3.aar"
    local url2="$MAVEN/$group/$2/$3/$2-$3.aar"
    echo "GET AAR $2-$3"
    local tmp="$DEPS_DIR/$2-$3.aar"
    curl -sSL --fail -o "$tmp" "$url" 2>/dev/null || \
    curl -sSL --fail -o "$tmp" "$url2" 2>/dev/null || { echo "  MISSING AAR $2-$3"; return 1; }
    # 解压出 classes.jar → 重命名为 $2-$3-runtime.jar
    local outdir="$DEPS_DIR/aar_extracted/$2-$3"
    mkdir -p "$outdir"
    unzip -q -o "$tmp" -d "$outdir" classes.jar R.txt AndroidManifest.xml "res/*" 2>/dev/null || true
    if [ -f "$outdir/classes.jar" ]; then
        cp "$outdir/classes.jar" "$DEPS_DIR/$2-$3-runtime.jar"
    fi
    rm -f "$tmp"
}

echo "===== 下载 JAR ====="
download_jar androidx.annotation annotation 1.6.0
download_jar androidx.annotation annotation-experimental 1.3.0
download_jar androidx.collection collection 1.2.0
download_jar androidx.concurrent concurrent-futures 1.1.0
download_jar androidx.lifecycle lifecycle-common 2.5.1
download_jar androidx.arch.core core-common 2.1.0
download_jar androidx.cursoradapter cursoradapter 1.0.0
download_jar androidx.interpolator interpolator 1.0.0
download_jar androidx.documentfile documentfile 1.0.0
download_jar androidx.loader loader 1.0.0
download_jar androidx.localbroadcastmanager localbroadcastmanager 1.0.0
download_jar androidx.print print 1.0.0
download_jar androidx.tracing tracing 1.0.0
download_jar org.nanohttpd nanohttpd 2.3.1
download_jar androidx.core core-ktx 1.9.0 2>/dev/null  # 可选, 没有也没关系

echo "===== 下载 AAR ====="
download_aar androidx.core core 1.9.0
download_aar androidx.lifecycle lifecycle-runtime 2.5.1
download_aar androidx.lifecycle lifecycle-viewmodel 2.5.1
download_aar androidx.lifecycle lifecycle-livedata-core 2.5.1
download_aar androidx.lifecycle lifecycle-viewmodel-savedstate 2.5.1
download_aar androidx.savedstate savedstate 1.2.0
download_aar androidx.activity activity 1.6.0
download_aar androidx.fragment fragment 1.3.6
download_aar androidx.appcompat appcompat 1.6.1
download_aar androidx.appcompat appcompat-resources 1.6.1
download_aar androidx.drawerlayout drawerlayout 1.1.1
download_aar androidx.viewpager viewpager 1.0.0
download_aar androidx.customview customview 1.1.0
download_aar androidx.versionedparcelable versionedparcelable 1.1.1
download_aar androidx.legacy legacy-support-core-utils 1.0.0
download_aar androidx.vectordrawable vectordrawable 1.1.0
download_aar androidx.vectordrawable vectordrawable-animated 1.1.0
download_aar androidx.transition transition 1.4.1
download_aar androidx.arch.core core-runtime 2.1.0
download_aar com.google.android.material material 1.8.0
download_aar org.osmdroid osmdroid-android 6.1.14
# 图片加载 (osmdroid 可能需要, 可选, 不强制)
download_jar com.github.bumptech.glide glide 4.14.2 2>/dev/null || true
download_aar androidx.constraintlayout constraintlayout 2.1.4 2>/dev/null || true

echo "===== 列目录 ====="
ls -la "$DEPS_DIR/"*.jar 2>/dev/null | awk '{print $5, $9}'
echo "===== 完成 ====="
