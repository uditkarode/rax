//! Serializes the render state into the direct ByteBuffer that the Kotlin
//! renderer reads. Every record is little-endian, matching the buffer's
//! ByteOrder.LITTLE_ENDIAN.
//!
//! Layout: a `Header`, then `Header.row_count` rows. Each row is a
//! `RowHeader`, `cols` `Cell` records, `RowHeader.text_units` UTF-16 code
//! units of cell text, and padding to a 4-byte boundary. Only dirty rows
//! are written unless `Header.dirty` is full.
const std = @import("std");
const builtin = @import("builtin");
const vt = @import("ghostty-vt");
const Session = @import("Session.zig");

comptime {
    // Records are copied in native byte order.
    std.debug.assert(builtin.cpu.arch.endian() == .little);
}

pub const version: i32 = 2;

/// Returned instead of a row count when the snapshot could not be taken.
pub const failed: i32 = -1;
pub const kitty_placements_dirty_bit: i32 = 1 << 8;

pub const Header = extern struct {
    version: i32 = version,
    dirty: i32,
    cols: i32,
    rows: i32,
    background: i32,
    foreground: i32,
    cursor_x: i32,
    cursor_y: i32,
    cursor_style: i32,
    cursor_visible: i32,
    row_count: i32 = 0,
    cursor_blinking: i32,
    cursor_color: i32,
    truncated: i32 = 0,
    password_input: i32,
};

pub const RowHeader = extern struct {
    index: i32,
    selection_start: i32,
    selection_end: i32,
    text_units: i32,
};

pub const Cell = extern struct {
    fg: i32 = 0,
    bg: i32 = 0,
    text_start: u16 = 0,
    text_len: u16 = 0,
    flags: u16 = Flags.fg_default | Flags.bg_none,
    underline: u16 = 0,
};

comptime {
    std.debug.assert(@sizeOf(Header) == 60);
    std.debug.assert(@sizeOf(RowHeader) == 16);
    std.debug.assert(@sizeOf(Cell) == 16);
}

pub const Flags = struct {
    pub const bold: u16 = 1 << 0;
    pub const italic: u16 = 1 << 1;
    pub const faint: u16 = 1 << 2;
    pub const underline: u16 = 1 << 3;
    pub const strikethrough: u16 = 1 << 4;
    pub const inverse: u16 = 1 << 5;
    pub const invisible: u16 = 1 << 6;
    pub const wide: u16 = 1 << 7;
    pub const spacer: u16 = 1 << 8;
    pub const fg_default: u16 = 1 << 9;
    pub const bg_none: u16 = 1 << 10;
};

/// Text beyond this many UTF-16 units per cell is dropped.
const max_cell_text_units = 32;

/// The codepoint Kitty unicode placeholders use; their images are drawn
/// from the placement list instead.
const kitty_placeholder: u21 = 0x10EEEE;

/// Writes the snapshot into `buf` and returns the number of rows written,
/// or `failed`. Deferring frames during synchronized output is up to the
/// caller.
pub fn write(session: *Session, buf: []u8) i32 {
    if (buf.len < @sizeOf(Header)) return failed;
    const state = &session.render;
    state.update(session.alloc, &session.terminal) catch return failed;

    var header: Header = .{
        .dirty = @intFromEnum(state.dirty) | if (session.terminal.screens.active.kitty_images.dirty)
            kitty_placements_dirty_bit
        else
            0,
        .cols = state.cols,
        .rows = state.rows,
        .background = Session.argb(state.colors.background),
        .foreground = Session.argb(state.colors.foreground),
        .cursor_x = if (state.cursor.viewport) |vp| vp.x else -1,
        .cursor_y = if (state.cursor.viewport) |vp| vp.y else -1,
        .cursor_style = @intFromEnum(state.cursor.visual_style),
        .cursor_visible = @intFromBool(state.cursor.visible),
        .cursor_blinking = @intFromBool(state.cursor.blinking),
        .cursor_color = if (state.colors.cursor) |c| Session.argb(c) else 0,
        .password_input = @intFromBool(state.cursor.password_input),
    };
    defer @memcpy(buf[0..@sizeOf(Header)], std.mem.asBytes(&header));
    session.terminal.screens.active.kitty_images.dirty = false;
    if (state.dirty == .false) return 0;

    var writer: RowWriter = .{ .buf = buf, .offset = @sizeOf(Header), .cols = state.cols };
    const rows = state.row_data.slice();
    for (
        rows.items(.dirty),
        rows.items(.selection),
        rows.items(.cells),
        0..,
    ) |dirty, selection, cells, y| {
        if (state.dirty != .full and !dirty) continue;
        writer.row(@intCast(y), selection, cells.slice(), &state.colors.palette) catch {
            header.truncated = 1;
            break;
        };
        header.row_count += 1;
    }

    // A truncated frame leaves the state dirty so the retry with a larger
    // buffer redraws every row it missed.
    if (header.truncated == 0) state.clean();
    return header.row_count;
}

const RowWriter = struct {
    buf: []u8,
    offset: usize,
    cols: u16,

    const Error = error{OutOfSpace};

    fn row(
        self: *RowWriter,
        index: i32,
        selection: ?[2]u16,
        cells: std.MultiArrayList(vt.RenderState.Cell).Slice,
        palette: *const vt.color.Palette,
    ) Error!void {
        const row_offset = self.offset;
        const cells_offset = row_offset + @sizeOf(RowHeader);
        const text_offset = cells_offset + @as(usize, self.cols) * @sizeOf(Cell);
        if (text_offset > self.buf.len) return error.OutOfSpace;

        var text: TextWriter = .{ .buf = self.buf[text_offset..] };
        const raws = cells.items(.raw);
        const graphemes = cells.items(.grapheme);
        const styles = cells.items(.style);
        for (0..self.cols) |x| {
            const record: Cell = if (x < raws.len)
                cell(raws[x], graphemes[x], styles[x], palette, &text)
            else
                .{};
            const dest = cells_offset + x * @sizeOf(Cell);
            @memcpy(self.buf[dest..][0..@sizeOf(Cell)], std.mem.asBytes(&record));
        }

        var end = text_offset + text.units * 2;
        if (end % 4 != 0) {
            if (end + 2 > self.buf.len) return error.OutOfSpace;
            @memset(self.buf[end..][0..2], 0);
            end += 2;
        }

        const header: RowHeader = .{
            .index = index,
            .selection_start = if (selection) |s| s[0] else -1,
            .selection_end = if (selection) |s| s[1] else -1,
            .text_units = @intCast(text.units),
        };
        @memcpy(self.buf[row_offset..][0..@sizeOf(RowHeader)], std.mem.asBytes(&header));
        self.offset = end;
    }
};

const TextWriter = struct {
    buf: []u8,
    units: usize = 0,

    /// Appends `cp` as UTF-16 if it fits both the buffer and `limit`
    /// units from `start`.
    fn append(self: *TextWriter, cp: u21, start: usize, limit: usize) bool {
        var pair: [2]u16 = undefined;
        const len: usize = if (cp < 0x10000) len: {
            pair[0] = @intCast(cp);
            break :len 1;
        } else len: {
            const v = cp - 0x10000;
            pair[0] = @intCast(0xD800 + (v >> 10));
            pair[1] = @intCast(0xDC00 + (v & 0x3FF));
            break :len 2;
        };
        if (self.units + len - start > limit) return false;
        if ((self.units + len) * 2 > self.buf.len) return false;
        @memcpy(self.buf[self.units * 2 ..][0 .. len * 2], std.mem.sliceAsBytes(pair[0..len]));
        self.units += len;
        return true;
    }
};

fn cell(
    raw: vt.Cell,
    grapheme: []const u21,
    style: vt.Style,
    palette: *const vt.color.Palette,
    text: *TextWriter,
) Cell {
    var record: Cell = .{ .flags = 0, .text_start = @intCast(text.units) };
    switch (raw.wide) {
        .narrow => {},
        .wide => record.flags |= Flags.wide,
        .spacer_tail, .spacer_head => record.flags |= Flags.spacer,
    }

    var fg_color: vt.Style.Color = .none;
    var bg_color: vt.Style.Color = .none;
    if (raw.hasStyling()) {
        const f = style.flags;
        if (f.bold or f.blink) record.flags |= Flags.bold;
        if (f.italic) record.flags |= Flags.italic;
        if (f.faint) record.flags |= Flags.faint;
        if (f.underline != .none) {
            record.flags |= Flags.underline;
            record.underline = @intFromEnum(f.underline);
        }
        if (f.strikethrough) record.flags |= Flags.strikethrough;
        if (f.inverse) record.flags |= Flags.inverse;
        if (f.invisible) record.flags |= Flags.invisible;
        fg_color = style.fg_color;
        bg_color = style.bg_color;
        // Bold text in one of the 8 base colors uses its bright variant.
        if (f.bold) switch (fg_color) {
            .palette => |i| if (i < 8) {
                fg_color = .{ .palette = i + 8 };
            },
            else => {},
        };
    }
    // Background-only cells carry their color in the cell content.
    switch (raw.content_tag) {
        .bg_color_palette => bg_color = .{ .palette = raw.content.color_palette.data },
        .bg_color_rgb => {
            const rgb = raw.content.color_rgb;
            bg_color = .{ .rgb = .{ .r = rgb.r, .g = rgb.g, .b = rgb.b } };
        },
        .codepoint, .codepoint_grapheme => {},
    }

    if (resolve(fg_color, palette)) |c| record.fg = c else record.flags |= Flags.fg_default;
    if (resolve(bg_color, palette)) |c| record.bg = c else record.flags |= Flags.bg_none;

    if (record.flags & Flags.spacer == 0 and raw.hasText()) text: {
        const first = raw.codepoint();
        if (first == kitty_placeholder) break :text;
        const start = text.units;
        if (!text.append(first, start, max_cell_text_units)) break :text;
        if (raw.hasGrapheme()) for (grapheme) |cp| {
            if (!text.append(cp, start, max_cell_text_units)) break;
        };
    }
    record.text_len = @intCast(text.units - record.text_start);
    return record;
}

fn resolve(color: vt.Style.Color, palette: *const vt.color.Palette) ?i32 {
    return switch (color) {
        .none => null,
        .palette => |i| Session.argb(palette[i]),
        .rgb => |rgb| Session.argb(rgb),
    };
}
