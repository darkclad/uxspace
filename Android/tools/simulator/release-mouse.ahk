#Requires AutoHotkey v2.0
#SingleInstance Force
;
; release-mouse.ahk -- UxSpace simulator helper (docs/SIMULATOR.md).
;
; With VirtioMouse on, clicking inside the Android emulator captures the host mouse and
; keyboard; the emulator's own release shortcut is Ctrl+R. This makes a lone Ctrl+Alt
; tap (press both, release, no other key in between) release it too, VM-style.
; Ctrl+Alt+<key> combos are left alone. Only the lone tap is turned into Ctrl+R.
; Only active while an emulator window is in front.
;

#HotIf WinActive("ahk_exe qemu-system-x86_64.exe")

~*LControl up::
~*RControl up::
~*LAlt up::
~*RAlt up::
{
    ; A_PriorKey is the last key pressed; if it is Ctrl or Alt and the other modifier is
    ; still held, nothing else was pressed during the combo.
    if !(A_PriorKey ~= "^[LR](Control|Alt)$")
        return
    other := InStr(A_ThisHotkey, "Control") ? "Alt" : "Ctrl"
    if !GetKeyState(other, "P")
        return
    KeyWait "Ctrl"
    KeyWait "Alt"
    Send "^r"
}

#HotIf
