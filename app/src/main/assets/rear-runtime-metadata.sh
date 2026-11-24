set -eu

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

directory="/data/system/theme_magic/users/$1/rearScreen"
original="$directory/runtime.json"
staged="$directory/$2"
original_identity="$3:$4:1"
staged_identity="$5:$6:1"

identity() {
    /system/bin/toybox stat -c '%d:%i:%h' "$@"
}

assert_identity() {
    [ -f "$1" ] && [ ! -L "$1" ] && [ "$(identity "$1")" = "$3" ] &&
        [ "$(identity -L "$2")" = "$3" ] || fail 'Wallpaper file identity changed'
}

label() {
    line=$(/system/bin/toybox ls -ZdL "$1")
    context=${line%% *}
    case "$context" in
        *:*:*:*) printf '%s\n' "$context" ;;
        *) fail 'Could not read the SELinux label' ;;
    esac
}

[ -f "$original" ] && [ ! -L "$original" ] && [ -f "$staged" ] && [ ! -L "$staged" ] &&
    [ "$(identity "$original")" = "$original_identity" ] &&
    [ "$(identity "$staged")" = "$staged_identity" ] || fail 'Wallpaper file identity changed'
exec 3<"$original" 4<"$staged"
source_fd=/proc/self/fd/3
staged_fd=/proc/self/fd/4
assert_identity "$original" "$source_fd" "$original_identity"
assert_identity "$staged" "$staged_fd" "$staged_identity"

owner=$(/system/bin/toybox stat -L -c '%u:%g' "$source_fd")
mode=$(/system/bin/toybox stat -L -c '%a' "$source_fd")
source_label=$(label "$source_fd")

# Operate on the opened inode even if its directory entry is changed during the command.
if [ "$(/system/bin/toybox stat -L -c '%u:%g' "$staged_fd")" != "$owner" ]; then
    /system/bin/toybox chown "$owner" "$staged_fd"
fi
/system/bin/toybox chmod "$mode" "$staged_fd"
if [ "$(label "$staged_fd")" != "$source_label" ]; then
    /system/bin/toybox chcon "$source_label" "$staged_fd"
fi

assert_identity "$original" "$source_fd" "$original_identity"
assert_identity "$staged" "$staged_fd" "$staged_identity"
[ "$(/system/bin/toybox stat -L -c '%u:%g %a' "$staged_fd")" = "$owner $mode" ] &&
    [ "$(label "$staged_fd")" = "$source_label" ] || fail 'Wallpaper file metadata verification failed'
