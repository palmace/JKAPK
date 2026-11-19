#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${1:-$HOME/download/CodeAssistProjects/JKAPK}"
cd "$PROJECT_ROOT"

OUT_DIR="$PROJECT_ROOT/build/manual-apk"
RES_COMPILED="$OUT_DIR/res-compiled"
GEN_SRC="$OUT_DIR/gen-src"
CLASSES_DIR="$OUT_DIR/classes"
DEX_DIR="$OUT_DIR/dex"
KEYSTORE_FILE="$OUT_DIR/jkapk-debug.keystore"
UNSIGNED_APK="$OUT_DIR/jkapk-app-unsigned.apk"
ALIGNED_APK="$OUT_DIR/jkapk-app-aligned.apk"
SIGNED_APK="$OUT_DIR/jkapk-app-debug.apk"

rm -rf "$RES_COMPILED" "$GEN_SRC" "$CLASSES_DIR" "$DEX_DIR" "$UNSIGNED_APK" "$ALIGNED_APK" "$SIGNED_APK"
mkdir -p "$RES_COMPILED" "$GEN_SRC" "$CLASSES_DIR" "$DEX_DIR"

ANDROID_JAR="/data/data/com.termux/files/home/Android/Sdk/platforms/android-35/android.jar"
if [[ -z "$ANDROID_JAR" ]]; then
  ANDROID_JAR="$(find "$HOME" /data/data/com.termux/files/usr -name "android.jar" 2>/dev/null | head -n 1 || true)"
fi
if [[ -z "$ANDROID_JAR" || ! -f "$ANDROID_JAR" ]]; then
  echo "ERROR: No se encontro android.jar. Exporta ANDROID_JAR=/ruta/a/android.jar" >&2
  exit 1
fi

AAPT2_BIN="${AAPT2_BIN:-$(command -v aapt2 || true)}"
if [[ -z "$AAPT2_BIN" ]]; then
  AAPT2_BIN="$(find "$HOME" /data/data/com.termux/files/usr -name "*aapt2*" -type f 2>/dev/null | head -n 1 || true)"
fi
if [[ -z "$AAPT2_BIN" || ! -x "$AAPT2_BIN" ]]; then
  echo "ERROR: No se encontro aapt2 ejecutable. Exporta AAPT2_BIN=/ruta/a/aapt2" >&2
  exit 1
fi

KOTLINC_BIN="${KOTLINC_BIN:-$(command -v kotlinc || true)}"
if [[ -z "$KOTLINC_BIN" ]]; then
  echo "ERROR: No se encontro kotlinc en PATH." >&2
  exit 1
fi

echo "[1/6] Compilando :engine..."
gradle :engine:jar --offline || gradle :engine:jar
ENGINE_JAR="$(find "$PROJECT_ROOT/engine/build/libs" -name "*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" | head -n 1)"
if [[ -z "$ENGINE_JAR" || ! -f "$ENGINE_JAR" ]]; then
  echo "ERROR: No se genero el JAR de :engine." >&2
  exit 1
fi

CACHE_JARS_FILE="$OUT_DIR/classpath.txt"
: > "$CACHE_JARS_FILE"
printf "%s\n" "$ENGINE_JAR" >> "$CACHE_JARS_FILE"
printf "%s\n" "$ANDROID_JAR" >> "$CACHE_JARS_FILE"

find "$HOME/.gradle/caches/modules-2/files-2.1" -name "*.jar" 2>/dev/null \
  | grep -vE "sources\.jar$|javadoc\.jar$|pom\.jar$" \
  | grep -E "kotlin-stdlib|kotlinx-coroutines|appcompat|material|core-ktx|core-|lifecycle|fragment|activity|annotation|collection|drawerlayout|viewpager|coordinatorlayout|r8|apksig|bcprov" \
  | grep -vE 'kotlinx-coroutines-core-jvm-1\.5\.0\.jar|kotlinx-coroutines-test-jvm-.*\.jar|/annotations-13\.0\.jar$' \
  | awk -F/ '{ if (!seen[$NF]++) print $0 }' >> "$CACHE_JARS_FILE"

CLASSPATH="$(paste -sd: "$CACHE_JARS_FILE")"

echo "[2/6] Compilando recursos con aapt2..."
while IFS= read -r -d '' res_file; do
  "$AAPT2_BIN" compile -o "$RES_COMPILED" "$res_file"
done < <(find "$PROJECT_ROOT/app/src/main/res" -type f -print0)

FLAT_RES_ARGS=()
while IFS= read -r -d '' flat_file; do
  FLAT_RES_ARGS+=("-R" "$flat_file")
done < <(find "$RES_COMPILED" -name "*.flat" -print0)

LINKED_APK="$OUT_DIR/linked.apk"
rm -f "$LINKED_APK"
"$AAPT2_BIN" link \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" \
  --java "$GEN_SRC" \
  --auto-add-overlay \
  --custom-package "com.palmace.jkapk.app" \
  -o "$LINKED_APK" \
  "${FLAT_RES_ARGS[@]}"

R_JAVA_FILES=()
while IFS= read -r -d '' rj; do
  R_JAVA_FILES+=("$rj")
done < <(find "$GEN_SRC" -name "*.java" -print0)

if [[ ${#R_JAVA_FILES[@]} -gt 0 ]]; then
  javac -source 17 -target 17 -cp "$CLASSPATH" -d "$CLASSES_DIR" "${R_JAVA_FILES[@]}"
fi

echo "[3/6] Compilando Kotlin de :app..."
KT_FILES=()
while IFS= read -r -d '' kt; do
  KT_FILES+=("$kt")
done < <(find "$PROJECT_ROOT/app/src/main/kotlin" -name "*.kt" -print0)

"$KOTLINC_BIN" \
  -jvm-target 17 \
  -no-stdlib \
  -cp "$CLASSPATH:$CLASSES_DIR" \
  -d "$CLASSES_DIR" \
  "${KT_FILES[@]}"

echo "[4/6] Generando classes.dex..."
D8_BIN="${D8_BIN:-$(command -v d8 || true)}"
R8_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1" -path "*/com.android.tools/r8/*/*.jar" | head -n 1 || true)"

APP_CLASSES_JAR="$OUT_DIR/app_classes.jar"
rm -f "$APP_CLASSES_JAR"
(
  cd "$CLASSES_DIR" || exit 1
  class_files=()
  while IFS= read -r -d '' cf; do
    class_files+=("${cf#./}")
  done < <(find . -name "*.class" -print0)
  if [[ ${#class_files[@]} -gt 0 ]]; then
    jar cf "$APP_CLASSES_JAR" "${class_files[@]}"
  else
    echo "ERROR: No se generaron archivos .class en $CLASSES_DIR" >&2
    exit 1
  fi
)

DEX_INPUTS=("$APP_CLASSES_JAR" "$ENGINE_JAR")
while IFS= read -r jar_line; do
  case "$jar_line" in
    "$ENGINE_JAR"|"$ANDROID_JAR") ;;
    *) DEX_INPUTS+=("$jar_line") ;;
  esac
done < "$CACHE_JARS_FILE"

if [[ -n "$D8_BIN" ]]; then
  "$D8_BIN" --min-api 24 --lib "$ANDROID_JAR" --output "$DEX_DIR" "${DEX_INPUTS[@]}"
elif [[ -n "$R8_JAR" ]]; then
  java -cp "$R8_JAR" com.android.tools.r8.D8 --min-api 24 --lib "$ANDROID_JAR" --output "$DEX_DIR" "${DEX_INPUTS[@]}"
else
  echo "ERROR: No se encontro ni d8 ni r8.jar para generar classes.dex." >&2
  exit 1
fi

echo "[5/6] Empaquetando APK..."
cp "$LINKED_APK" "$UNSIGNED_APK"

if [[ -d "$PROJECT_ROOT/app/src/main/jniLibs" ]] && find "$PROJECT_ROOT/app/src/main/jniLibs" -type f | grep -q .; then
  (cd "$PROJECT_ROOT/app/src/main" && zip -qr "$UNSIGNED_APK" lib)
fi

if [[ -d "$PROJECT_ROOT/app/src/main/assets" && -n "$(ls -A "$PROJECT_ROOT/app/src/main/assets" 2>/dev/null || true)" ]]; then
  (cd "$PROJECT_ROOT/app/src/main" && zip -qr "$UNSIGNED_APK" assets)
fi

(cd "$DEX_DIR" && zip -qj "$UNSIGNED_APK" classes*.dex)

ZIPALIGN_BIN="${ZIPALIGN_BIN:-$(command -v zipalign || true)}"
APK_TO_SIGN="$UNSIGNED_APK"
if [[ -n "$ZIPALIGN_BIN" ]]; then
  "$ZIPALIGN_BIN" -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"
  APK_TO_SIGN="$ALIGNED_APK"
fi

echo "[6/6] Firmando APK..."
if [[ ! -f "$KEYSTORE_FILE" ]]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE_FILE" \
    -storepass "jkapkdebug" \
    -keypass "jkapkdebug" \
    -alias "jkapkdebug" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=JKAPK, OU=Dev, O=Palmace, L=Maripa, S=Bolivar, C=VE"
fi

APKSIGNER_BIN="${APKSIGNER_BIN:-$(command -v apksigner || true)}"
APKSIG_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1" -path "*/com.android.tools.build/apksig/*/*.jar" | head -n 1 || true)"

if [[ -n "$APKSIGNER_BIN" ]]; then
  "$APKSIGNER_BIN" sign \
    --ks "$KEYSTORE_FILE" \
    --ks-key-alias "jkapkdebug" \
    --ks-pass "pass:jkapkdebug" \
    --key-pass "pass:jkapkdebug" \
    --out "$SIGNED_APK" \
    "$APK_TO_SIGN"
elif [[ -n "$APKSIG_JAR" ]]; then
  cp "$APK_TO_SIGN" "$SIGNED_APK"
  jarsigner -keystore "$KEYSTORE_FILE" -storepass "jkapkdebug" -keypass "jkapkdebug" "$SIGNED_APK" "jkapkdebug"
else
  echo "ERROR: No se encontro apksigner ni jarsigner para firmar el APK." >&2
  exit 1
fi

echo "APK listo en: $SIGNED_APK"
