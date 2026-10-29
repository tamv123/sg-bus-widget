# Source me: `source env.sh` — uses the shared local Android toolchain in ~/android-dev when present.
_dev="${ANDROID_TOOLCHAIN:-$HOME/android-dev}"
[ -d "$_dev/jdk17" ] && export JAVA_HOME="$_dev/jdk17"
[ -d "$_dev/sdk" ] && export ANDROID_HOME="$_dev/sdk"
export ANDROID_SDK_ROOT="${ANDROID_HOME:-}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${ANDROID_HOME:+$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:}$PATH"
unset _dev
