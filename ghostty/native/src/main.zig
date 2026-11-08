//! JNI bridge between io.github.xchacha20_poly1305.libghostty.GhosttyVt
//! and libghostty-vt.
const std = @import("std");
const builtin = @import("builtin");
const vt = @import("ghostty-vt");
const wuffs = @import("wuffs");
const jni = @import("jni.zig");
const Session = @import("Session.zig");
const snapshot = @import("snapshot.zig");
const kitty = @import("kitty.zig");

const package = "io/github/xchacha20_poly1305/libghostty/";
const vt_class = package ++ "GhosttyVt";
const callbacks_class = package ++ "GhosttyVtCallbacks";
const CallbacksRef = jni.Ref("L" ++ callbacks_class ++ ";");

const alloc = std.heap.c_allocator;

pub const std_options: std.Options = .{
    .logFn = log,
};

const android_log = struct {
    const Priority = enum(c_int) { debug = 3, info = 4, warn = 5, err = 6 };
    extern "log" fn __android_log_write(prio: Priority, tag: [*:0]const u8, text: [*:0]const u8) c_int;
};

fn log(
    comptime level: std.log.Level,
    comptime scope: @EnumLiteral(),
    comptime format: []const u8,
    args: anytype,
) void {
    if (comptime !builtin.abi.isAndroid()) return std.log.defaultLog(level, scope, format, args);
    var buf: [1024]u8 = undefined;
    const prefix = if (scope == .default) "" else "(" ++ @tagName(scope) ++ ") ";
    const text = std.mem.printSentinel(&buf, prefix ++ format, args, 0) catch blk: {
        buf[buf.len - 1] = 0;
        break :blk buf[0 .. buf.len - 1 :0];
    };
    _ = android_log.__android_log_write(switch (level) {
        .debug => .debug,
        .info => .info,
        .warn => .warn,
        .err => .err,
    }, "ghostty", text);
}

export fn JNI_OnLoad(vm: *jni.JavaVm, _: ?*anyopaque) jni.Int {
    const env = vm.getEnv() orelse return jni.err;

    const vt_cls = env.call("FindClass", .{vt_class}) orelse return jni.err;
    if (!env.registerNatives(vt_cls, natives)) return jni.err;

    const callbacks_cls = env.call("FindClass", .{callbacks_class}) orelse return jni.err;
    Session.callback_methods = .{
        .clipboard_has_text = env.call("GetMethodID", .{ callbacks_cls, "clipboardHasText", "()Z" }) orelse return jni.err,
        .read_clipboard_for_program = env.call("GetMethodID", .{ callbacks_cls, "readClipboardForProgram", "(Z)[B" }) orelse return jni.err,
    };

    vt.sys.decode_png = &decodePng;
    return jni.version_1_6;
}

/// Zig's DLL entry point skips the MinGW CRT, so on Windows run the C++ static constructors
/// (simdutf's implementation dispatch) that the ELF and Mach-O loaders run themselves.
pub const DllMain = if (builtin.os.tag == .windows) struct {
    const windows = std.os.windows;
    extern const __CTOR_LIST__: [1]usize;

    fn dllMain(_: windows.HINSTANCE, reason: windows.DWORD, _: windows.LPVOID) callconv(.winapi) windows.BOOL {
        if (reason == 1) { // DLL_PROCESS_ATTACH
            // A -1 (or count) header, the constructors, then 0; run in reverse like MinGW does.
            const list: [*]const usize = &__CTOR_LIST__;
            var n: usize = 0;
            while (list[n + 1] != 0) n += 1;
            while (n > 0) : (n -= 1) @as(*const fn () callconv(.c) void, @ptrFromInt(list[n]))();
        }
        return .TRUE;
    }
}.dllMain else {};

fn decodePng(a: std.mem.Allocator, data: []const u8) vt.sys.DecodeError!vt.sys.Image {
    const image = wuffs.png.decode(a, data) catch |err| return switch (err) {
        error.OutOfMemory => error.OutOfMemory,
        else => error.InvalidData,
    };
    return .{ .width = image.width, .height = image.height, .data = image.data };
}

fn session(handle: i64) *Session {
    return @ptrFromInt(@as(usize, @intCast(handle)));
}

fn boolean(value: bool) jni.Boolean {
    return @intFromBool(value);
}

/// Runs `encode` into a stack buffer, falling back to the heap for long
/// output, and returns the bytes as a Java array (null when empty).
fn encoded(env: *jni.Env, encode: anytype, args: anytype) jni.ByteArray {
    var stack_buf: [256]u8 = undefined;
    var stack: std.heap.BufferFirstAllocator = .init(&stack_buf, alloc);
    var out: std.Io.Writer.Allocating = .init(stack.allocator());
    defer out.deinit();
    @call(.auto, encode, .{&out.writer} ++ args) catch return null;
    if (out.written().len == 0) return null;
    return env.newByteArray(out.written());
}

fn optionalBytes(env: *jni.Env, bytes: ?[]const u8) jni.ByteArray {
    const b = bytes orelse return null;
    if (b.len == 0) return null;
    return env.newByteArray(b);
}

fn rgbFromArgb(value: i64) ?vt.color.RGB {
    if (value < 0) return null;
    return .{
        .r = @truncate(@as(u64, @intCast(value)) >> 16),
        .g = @truncate(@as(u64, @intCast(value)) >> 8),
        .b = @truncate(@as(u64, @intCast(value))),
    };
}

fn viewportPin(t: *vt.Terminal, col: i32, row: i32) ?vt.Pin {
    if (col < 0 or row < 0) return null;
    return t.screens.active.pages.pin(.{ .viewport = .{
        .x = std.math.cast(u16, col) orelse return null,
        .y = std.math.cast(u32, row) orelse return null,
    } });
}

/// Like `viewportPin`, but `row` may lie above or below the viewport.
fn viewportRowPin(t: *vt.Terminal, col: i32, row: i32) ?vt.Pin {
    if (row >= 0 and row < t.rows) return viewportPin(t, col, row);
    const top = viewportPin(t, col, 0) orelse return null;
    return if (row < 0) top.up(@abs(row)) else top.down(@intCast(row));
}

/// The row of `pin`, counted from the top of the scrollback.
fn screenRow(screen: *const vt.Screen, pin: vt.Pin) ?u32 {
    const pt = screen.pages.pointFromPin(.screen, pin) orelse return null;
    return pt.screen.y;
}

fn viewportRow(row: u32, top: u32) i32 {
    const offset = @as(i64, row) - @as(i64, top);
    return @intCast(std.math.clamp(offset, std.math.minInt(i32), std.math.maxInt(i32)));
}

fn installSelection(s: *Session, selection: ?vt.Selection) bool {
    const sel = selection orelse return false;
    s.terminal.screens.active.select(sel) catch return false;
    s.markDirty();
    return true;
}

/// The text of `selection`, or of the whole screen without one. With
/// `max_bytes`, longer text returns null, and formatting stops there.
fn formatSelection(env: *jni.Env, s: *Session, selection: ?vt.Selection, max_bytes: ?usize) jni.ByteArray {
    var formatter: vt.formatter.TerminalFormatter = .init(&s.terminal, .{
        .emit = .plain,
        .unwrap = true,
        .trim = true,
    });
    formatter.extra = .none;
    if (selection) |sel| formatter.content = .{ .selection = sel };
    if (max_bytes) |max| {
        const buf = alloc.alloc(u8, max) catch return null;
        defer alloc.free(buf);
        var out: std.Io.Writer = .fixed(buf);
        formatter.format(&out) catch return null;
        return env.newByteArray(out.buffered());
    }
    var out: std.Io.Writer.Allocating = .init(alloc);
    defer out.deinit();
    formatter.format(&out.writer) catch return null;
    return env.newByteArray(out.written());
}

/// Modifiers as `GhosttyMods` bits (ghostty's packed `input.KeyMods`).
fn mods(bits: i32) vt.input.KeyMods {
    return @bitCast(@as(u16, @truncate(@as(u32, @bitCast(bits)))));
}

/// The native methods of GhosttyVt. Names and parameter types define the
/// registered JNI methods.
const natives = struct {
    pub fn nativeCreate(
        env: *jni.Env,
        _: jni.Object,
        cols: jni.Int,
        rows: jni.Int,
        max_scrollback_lines: i64,
        xtversion: jni.String,
        terminfo_name: jni.String,
        cursor_style: jni.Int,
        cursor_blink: jni.Boolean,
        grapheme_cluster: jni.Boolean,
        callbacks: CallbacksRef,
    ) i64 {
        const version: ?[:0]u8 = if (xtversion != null) env.stringAlloc(alloc, xtversion) catch return 0 else null;
        defer if (version) |v| alloc.free(v);
        const terminfo = if (terminfo_name != null) env.stringAlloc(alloc, terminfo_name) catch return 0 else null;
        defer if (terminfo) |name| alloc.free(name);

        const callbacks_ref = if (callbacks != null) env.call("NewGlobalRef", .{callbacks}) else null;
        const s = Session.create(alloc, @ptrCast(callbacks_ref), .{
            .cols = std.math.cast(u16, cols) orelse 0,
            .rows = std.math.cast(u16, rows) orelse 0,
            .max_scrollback_lines = @intCast(@max(max_scrollback_lines, 0)),
            .xtversion = version orelse "",
            .terminfo_name = terminfo,
            .cursor_style = std.enums.fromInt(vt.CursorStyle, cursor_style) orelse .block,
            .cursor_blink = cursor_blink != 0,
            .grapheme_cluster = grapheme_cluster != 0,
        }) catch |err| {
            std.log.err("failed to create terminal session: {t}", .{err});
            if (callbacks_ref != null) env.call("DeleteGlobalRef", .{callbacks_ref});
            return 0;
        };
        return @intCast(@intFromPtr(s));
    }

    pub fn nativeRestore(
        env: *jni.Env,
        _: jni.Object,
        snapshot_bytes: jni.ByteArray,
        xtversion: jni.String,
        terminfo_name: jni.String,
        callbacks: CallbacksRef,
    ) i64 {
        if (snapshot_bytes == null) return 0;
        const bytes = env.byteArrayAlloc(alloc, snapshot_bytes) catch return 0;
        defer alloc.free(bytes);
        const version: ?[:0]u8 = if (xtversion != null) env.stringAlloc(alloc, xtversion) catch return 0 else null;
        defer if (version) |v| alloc.free(v);
        const terminfo = if (terminfo_name != null) env.stringAlloc(alloc, terminfo_name) catch return 0 else null;
        defer if (terminfo) |name| alloc.free(name);

        const callbacks_ref = if (callbacks != null) env.call("NewGlobalRef", .{callbacks}) else null;
        const s = Session.restore(alloc, @ptrCast(callbacks_ref), .{
            .xtversion = version orelse "",
            .terminfo_name = terminfo,
        }, bytes) catch |err| {
            std.log.warn("failed to restore terminal snapshot: {t}", .{err});
            if (callbacks_ref != null) env.call("DeleteGlobalRef", .{callbacks_ref});
            return 0;
        };
        return @intCast(@intFromPtr(s));
    }

    pub fn nativeFree(env: *jni.Env, _: jni.Object, handle: i64) void {
        const s = session(handle);
        if (s.callbacks != null) env.call("DeleteGlobalRef", .{s.callbacks});
        s.destroy();
    }

    pub fn nativeWrite(
        env: *jni.Env,
        _: jni.Object,
        handle: i64,
        data: jni.ByteArray,
        offset: jni.Int,
        length: jni.Int,
    ) jni.ByteArray {
        const s = session(handle);
        const array_len = env.call("GetArrayLength", .{data});
        if (offset < 0 or length < 0 or offset > array_len - length) return null;
        s.pty_out.clearRetainingCapacity();
        if (length > 0) {
            const bytes = alloc.alloc(u8, @intCast(length)) catch return null;
            defer alloc.free(bytes);
            env.call("GetByteArrayRegion", .{ data, offset, length, bytes.ptr });
            s.write(env, bytes);
        }
        return optionalBytes(env, s.pty_out.items);
    }

    /// Returns the in-band size report (mode 2048) for the new size, if the
    /// program asked for one.
    pub fn nativeResize(
        env: *jni.Env,
        _: jni.Object,
        handle: i64,
        cols: jni.Int,
        rows: jni.Int,
        cell_width_px: jni.Int,
        cell_height_px: jni.Int,
    ) jni.ByteArray {
        const s = session(handle);
        s.pty_out.clearRetainingCapacity();
        s.resize(
            std.math.cast(u16, cols) orelse return null,
            std.math.cast(u16, rows) orelse return null,
            std.math.cast(u32, cell_width_px) orelse return null,
            std.math.cast(u32, cell_height_px) orelse return null,
        ) catch |err| {
            std.log.warn("resize failed: {t}", .{err});
            return null;
        };
        return optionalBytes(env, s.pty_out.items);
    }

    pub fn nativeTakeEventFlags(_: *jni.Env, _: jni.Object, handle: i64) jni.Int {
        const s = session(handle);
        defer s.pending_events = 0;
        return s.pending_events;
    }

    pub fn nativeGetTitle(env: *jni.Env, _: jni.Object, handle: i64) jni.ByteArray {
        return optionalBytes(env, session(handle).terminal.getTitle());
    }

    pub fn nativeGetPwd(env: *jni.Env, _: jni.Object, handle: i64) jni.ByteArray {
        return optionalBytes(env, session(handle).terminal.getPwd());
    }

    pub fn nativeTakeClipboard(env: *jni.Env, _: jni.Object, handle: i64) jni.ByteArray {
        const s = session(handle);
        const text = s.clipboard_text orelse return null;
        const out = env.newByteArray(text);
        if (out == null) return null;
        alloc.free(text);
        s.clipboard_text = null;
        return out;
    }

    /// The oldest pending notification as a little-endian u32 title length,
    /// the title, then the body.
    pub fn nativeTakeNotification(env: *jni.Env, _: jni.Object, handle: i64) jni.ByteArray {
        const s = session(handle);
        if (s.notifications.items.len == 0) return null;
        const n = s.notifications.orderedRemove(0);
        defer Session.freeNotification(alloc, n);
        const packed_bytes = alloc.alloc(u8, 4 + n.title.len + n.body.len) catch return null;
        defer alloc.free(packed_bytes);
        std.mem.writeInt(u32, packed_bytes[0..4], @intCast(n.title.len), .little);
        @memcpy(packed_bytes[4..][0..n.title.len], n.title);
        @memcpy(packed_bytes[4 + n.title.len ..], n.body);
        return env.newByteArray(packed_bytes);
    }

    pub fn nativeGetProgress(env: *jni.Env, _: jni.Object, handle: i64) jni.IntArray {
        const s = session(handle);
        return env.newIntArray(&.{ s.progress_state, s.progress_percent });
    }

    /// Records the host's color scheme and returns the report to send when
    /// the program subscribed to changes (mode 2031).
    pub fn nativeSetColorScheme(env: *jni.Env, _: jni.Object, handle: i64, dark: jni.Boolean) jni.ByteArray {
        const s = session(handle);
        const is_dark = dark != 0;
        const changed = s.color_scheme_dark != is_dark;
        s.color_scheme_dark = is_dark;
        if (!changed or !s.terminal.modes.get(.report_color_scheme)) return null;
        const scheme: vt.device_status.ColorScheme = if (is_dark) .dark else .light;
        return encoded(env, vt.device_status.encodeColorSchemeReport, .{scheme});
    }

    pub fn nativeEncodeFocus(env: *jni.Env, _: jni.Object, handle: i64, gained: jni.Boolean) jni.ByteArray {
        if (!session(handle).terminal.modes.get(.focus_event)) return null;
        const event: vt.input.FocusEvent = if (gained != 0) .gained else .lost;
        return encoded(env, vt.input.encodeFocus, .{event});
    }

    pub fn nativeHyperlinkAt(env: *jni.Env, _: jni.Object, handle: i64, col: jni.Int, row: jni.Int) jni.ByteArray {
        const s = session(handle);
        const pin = viewportPin(&s.terminal, col, row) orelse return null;
        const page = pin.node.page();
        const cell = pin.rowAndCell().cell;
        if (!cell.hyperlink) return null;
        const id = page.lookupHyperlink(cell) orelse return null;
        const link = page.hyperlink_set.get(page.memory, id);
        return optionalBytes(env, link.uri.slice(page.memory));
    }

    /// Colors are ARGB with a negative value meaning "use the default".
    pub fn nativeSetTheme(
        env: *jni.Env,
        _: jni.Object,
        handle: i64,
        foreground: i64,
        background: i64,
        cursor: i64,
        palette: jni.LongArray,
    ) void {
        const s = session(handle);
        const colors = &s.terminal.colors;
        colors.foreground.default = rgbFromArgb(foreground);
        colors.background.default = rgbFromArgb(background);
        colors.cursor.default = rgbFromArgb(cursor);

        var table = vt.color.default;
        if (palette != null) {
            var entries: [256]i64 = undefined;
            const count: usize = @min(@as(usize, @intCast(env.call("GetArrayLength", .{palette}))), entries.len);
            env.longArrayRegion(palette, entries[0..count]);
            for (entries[0..count], table[0..count]) |entry, *slot| {
                if (rgbFromArgb(entry)) |rgb| slot.* = rgb;
            }
        }
        colors.palette.changeDefault(s.alloc, table) catch {
            std.log.warn("setting theme palette failed: out of memory", .{});
            colors.palette.resetDefault(s.alloc);
        };
        s.terminal.flags.dirty.palette = true;

        s.background = s.effectiveBackground();
        s.foreground = s.effectiveForeground();
        s.markDirty();
    }

    pub fn nativeParseColor(env: *jni.Env, _: jni.Object, color: jni.String) jni.Int {
        const text = env.stringAlloc(alloc, color) catch return 0;
        defer alloc.free(text);
        const rgb = vt.color.RGB.parse(text) catch {
            std.log.debug("ignoring invalid theme color: {s}", .{text});
            return 0;
        };
        return Session.argb(rgb);
    }

    pub fn nativeScroll(_: *jni.Env, _: jni.Object, handle: i64, delta_rows: jni.Int) void {
        session(handle).terminal.scrollViewport(.{ .delta = delta_rows });
    }

    pub fn nativeScrollToBottom(_: *jni.Env, _: jni.Object, handle: i64) void {
        session(handle).terminal.scrollViewport(.bottom);
    }

    /// `[total, offset, len]` of the scrollable area in rows.
    pub fn nativeScrollbar(env: *jni.Env, _: jni.Object, handle: i64) jni.LongArray {
        const scrollbar = session(handle).terminal.screens.active.pages.scrollbar();
        return env.newLongArray(&.{
            @intCast(scrollbar.total),
            @intCast(scrollbar.offset),
            @intCast(scrollbar.len),
        });
    }

    /// Bits mirrored by `GhosttyVt.VIEWPORT_*`.
    pub fn nativeViewportState(_: *jni.Env, _: jni.Object, handle: i64) jni.Int {
        const t = &session(handle).terminal;
        var state: jni.Int = 0;
        if (t.screens.active_key == .alternate) state |= 1;
        if (t.modes.get(.mouse_event_x10) or t.modes.get(.mouse_event_normal) or
            t.modes.get(.mouse_event_button) or t.modes.get(.mouse_event_any)) state |= 2;
        if (t.modes.get(.synchronized_output)) state |= 4;
        if (t.screens.active.selection != null) state |= 8;
        return state;
    }

    /// With `full`, every row is written, not only those changed since the last snapshot.
    pub fn nativeSnapshot(env: *jni.Env, _: jni.Object, handle: i64, buffer: jni.ByteBuffer, full: jni.Boolean) jni.Int {
        const base = env.call("GetDirectBufferAddress", .{buffer}) orelse return snapshot.failed;
        const cap = env.call("GetDirectBufferCapacity", .{buffer});
        if (cap < 0) return snapshot.failed;
        const s = session(handle);
        if (full != 0) s.markDirty();
        return snapshot.write(s, base[0..@intCast(cap)]);
    }

    pub fn nativeKittyPlacements(env: *jni.Env, _: jni.Object, handle: i64) jni.LongArray {
        const list = kitty.placements(session(handle), alloc) catch return null;
        defer alloc.free(list);
        if (list.len == 0) return null;
        return env.newLongArray(std.mem.bytesAsSlice(i64, std.mem.sliceAsBytes(list)));
    }

    pub fn nativeKittyImage(env: *jni.Env, _: jni.Object, handle: i64, image_id: jni.Int) jni.IntArray {
        const pixels = (kitty.imageArgb(session(handle), alloc, @bitCast(image_id)) catch return null) orelse return null;
        defer alloc.free(pixels);
        return env.newIntArray(pixels);
    }

    /// `key` is a `GhosttyKey` ordinal and `action` a ghostty
    /// `input.KeyAction` (release, press, repeat).
    pub fn nativeEncodeKey(
        env: *jni.Env,
        _: jni.Object,
        handle: i64,
        key: jni.Int,
        action: jni.Int,
        mod_bits: jni.Int,
        unshifted_codepoint: jni.Int,
        composing: jni.Boolean,
        utf8: jni.ByteArray,
    ) jni.ByteArray {
        const t = &session(handle).terminal;
        const text = if (utf8 != null) env.byteArrayAlloc(alloc, utf8) catch return null else &.{};
        defer alloc.free(text);
        const event: vt.input.KeyEvent = .{
            .action = std.enums.fromInt(vt.input.KeyAction, action) orelse .press,
            .key = std.enums.fromInt(vt.input.Key, key) orelse .unidentified,
            .mods = mods(mod_bits),
            .composing = composing != 0,
            .utf8 = text,
            .unshifted_codepoint = std.math.cast(u21, unshifted_codepoint) orelse 0,
        };
        return encoded(env, vt.input.encodeKey, .{ event, vt.input.KeyEncodeOptions.fromTerminal(t) });
    }

    /// `action` and `button` are ghostty `input.MouseAction` and
    /// `input.MouseButton` values; positions are cells.
    pub fn nativeEncodeMouse(
        env: *jni.Env,
        _: jni.Object,
        handle: i64,
        action: jni.Int,
        button: jni.Int,
        col: jni.Int,
        row: jni.Int,
        mod_bits: jni.Int,
    ) jni.ByteArray {
        const s = session(handle);
        const t = &s.terminal;
        const mouse_action = std.enums.fromInt(vt.input.MouseAction, action) orelse return null;
        const mouse_button: ?vt.input.MouseButton = switch (std.enums.fromInt(vt.input.MouseButton, button) orelse .unknown) {
            .unknown => null,
            else => |b| b,
        };
        if (mouse_button) |b| switch (b) {
            .left, .right, .middle => switch (mouse_action) {
                .press => s.mouse_button_down = true,
                .release => s.mouse_button_down = false,
                .motion => {},
            },
            else => {},
        };
        // Positions are already cells, so a 1x1 pixel cell grid makes the
        // encoder's pixel-to-cell conversion the identity.
        var opts: vt.input.MouseEncodeOptions = .fromTerminal(t, .{
            .screen = .{ .width = t.cols, .height = t.rows },
            .cell = .{ .width = 1, .height = 1 },
            .padding = .{},
        });
        opts.any_button_pressed = s.mouse_button_down;
        return encoded(env, vt.input.encodeMouse, .{ vt.input.MouseEncodeEvent{
            .action = mouse_action,
            .button = mouse_button,
            .mods = mods(mod_bits),
            .pos = .{ .x = @floatFromInt(col), .y = @floatFromInt(row) },
        }, opts });
    }

    pub fn nativeSelectWord(_: *jni.Env, _: jni.Object, handle: i64, col: jni.Int, row: jni.Int) jni.Boolean {
        const s = session(handle);
        const pin = viewportPin(&s.terminal, col, row) orelse return 0;
        return boolean(installSelection(s, s.terminal.screens.active.selectWord(
            pin,
            &vt.selection_codepoints.default_word_boundaries,
        )));
    }

    pub fn nativeSelectAll(_: *jni.Env, _: jni.Object, handle: i64) jni.Boolean {
        const s = session(handle);
        return boolean(installSelection(s, s.terminal.screens.active.selectAll()));
    }

    pub fn nativeSetSelection(
        _: *jni.Env,
        _: jni.Object,
        handle: i64,
        anchor_col: jni.Int,
        anchor_row: jni.Int,
        col: jni.Int,
        row: jni.Int,
    ) jni.Boolean {
        const s = session(handle);
        const start = viewportRowPin(&s.terminal, anchor_col, anchor_row) orelse return 0;
        const end = viewportPin(&s.terminal, col, row) orelse return 0;
        return boolean(installSelection(s, .init(start, end, false)));
    }

    /// `[first column, first row, last column, last row]` of the selection, in viewport cells. A row
    /// above the viewport is negative, and a row below it is the row count or more.
    pub fn nativeSelectionBounds(env: *jni.Env, _: jni.Object, handle: i64) jni.IntArray {
        const t = &session(handle).terminal;
        const screen = t.screens.active;
        const sel = screen.selection orelse return null;
        const top = viewportPin(t, 0, 0) orelse return null;
        const top_y = screenRow(screen, top) orelse return null;
        const first = sel.topLeft(screen);
        const last = sel.bottomRight(screen);
        const first_y = screenRow(screen, first) orelse return null;
        const last_y = screenRow(screen, last) orelse return null;
        return env.newIntArray(&.{
            first.x,
            viewportRow(first_y, top_y),
            last.x,
            viewportRow(last_y, top_y),
        });
    }

    pub fn nativeClearSelection(_: *jni.Env, _: jni.Object, handle: i64) void {
        const s = session(handle);
        s.terminal.screens.active.clearSelection();
        s.markDirty();
    }

    /// A negative `max_bytes` means no limit.
    pub fn nativeSelectionText(env: *jni.Env, _: jni.Object, handle: i64, max_bytes: jni.Int) jni.ByteArray {
        const s = session(handle);
        const selection = s.terminal.screens.active.selection orelse return null;
        return formatSelection(env, s, selection, std.math.cast(usize, max_bytes));
    }

    pub fn nativeViewportText(env: *jni.Env, _: jni.Object, handle: i64) jni.ByteArray {
        const s = session(handle);
        const t = &s.terminal;
        const start = viewportPin(t, 0, 0) orelse return null;
        const end = viewportPin(t, t.cols - 1, t.rows - 1) orelse return null;
        return formatSelection(env, s, .init(start, end, false), null);
    }

    pub fn nativeTranscriptText(env: *jni.Env, _: jni.Object, handle: i64) jni.ByteArray {
        return formatSelection(env, session(handle), null, null);
    }

    pub fn nativeGetBackgroundColor(_: *jni.Env, _: jni.Object, handle: i64) jni.Int {
        return session(handle).effectiveBackground();
    }

    pub fn nativeIsPasteSafe(env: *jni.Env, _: jni.Object, handle: i64, data: jni.ByteArray) jni.Boolean {
        const s = session(handle);
        const bytes = env.byteArrayAlloc(alloc, data) catch return 0;
        defer alloc.free(bytes);
        return boolean(vt.input.isSafePasteWith(bytes, .fromTerminal(&s.terminal)));
    }

    pub fn nativeEncodePaste(env: *jni.Env, _: jni.Object, handle: i64, data: jni.ByteArray) jni.ByteArray {
        const s = session(handle);
        const text = env.byteArrayAlloc(alloc, data) catch return null;
        defer alloc.free(text);
        s.pty_out.clearRetainingCapacity();
        const written = s.stream.handler.paste(.{
            .contents = .{ .memory = &.{.{ .mime = "text/plain", .data = text }} },
            .allow_unsafe = true,
        }) catch return null;
        if (!written) return null;
        return optionalBytes(env, s.pty_out.items);
    }
};
