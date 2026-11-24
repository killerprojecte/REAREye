fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

[ "$#" -eq 6 ] || fail 'Invalid wallpaper metadata arguments'
directory="/data/system/theme_magic/users/$1/rearScreen"
original="$directory/runtime.json"
staged="$directory/$2"
original_identity="$3:$4:1"
staged_identity="$5:$6:1"

identity() {
    /system/bin/toybox stat -c '%d:%i:%h' "$@"
}

fd_tool() {
    input_fd=$1
    shift
    # mksh closes the saved descriptors on exec. Explicitly inherit the selected inode on stdin.
    /system/bin/toybox "$@" /proc/self/fd/0 <&"$input_fd"
}

assert_identity() {
    [ -f "$1" ] && [ ! -L "$1" ] && [ "$(identity "$1")" = "$3" ] &&
        [ "$(fd_tool "$2" stat -L -c '%d:%i:%h')" = "$3" ] || fail 'Wallpaper file identity changed'
}

label() {
    line=$(fd_tool "$1" ls -ZdL)
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
assert_identity "$original" 3 "$original_identity"
assert_identity "$staged" 4 "$staged_identity"

owner=$(fd_tool 3 stat -L -c '%u:%g')
mode=$(fd_tool 3 stat -L -c '%a')
source_label=$(label 3)

# Operate on the opened inode even if its directory entry is changed during the command.
if [ "$(fd_tool 4 stat -L -c '%u:%g')" != "$owner" ]; then
    fd_tool 4 chown "$owner"
fi
fd_tool 4 chmod "$mode"
if [ "$(label 4)" != "$source_label" ]; then
    fd_tool 4 chcon "$source_label"
fi

assert_identity "$original" 3 "$original_identity"
assert_identity "$staged" 4 "$staged_identity"
[ "$(fd_tool 4 stat -L -c '%u:%g %a')" = "$owner $mode" ] &&
    [ "$(label 4)" = "$source_label" ] || fail 'Wallpaper file metadata verification failed'
