//! Kitty graphics placements and image pixels for the renderer.
const std = @import("std");
const vt = @import("ghostty-vt");
const Session = @import("Session.zig");

const graphics = vt.kitty.graphics;

/// One visible placement, flattened into `GhosttyVt.KITTY_PLACEMENT_LONGS`
/// longs in field order.
pub const Placement = extern struct {
    image_id: i64,
    image_generation: i64,
    viewport_col: i64,
    viewport_row: i64,
    offset_x: i64,
    offset_y: i64,
    dest_width: i64,
    dest_height: i64,
    source_x: i64,
    source_y: i64,
    source_width: i64,
    source_height: i64,
    z: i64,

    pub const longs = @sizeOf(Placement) / @sizeOf(i64);
};

/// Images larger than this are not handed to the renderer.
const max_image_pixels = 100 * 1000 * 1000;

/// Collects the placements visible in the viewport, ordered by z. Virtual
/// (unicode placeholder) placements and those relative to them come from
/// the render state, so call this after a snapshot.
pub fn placements(session: *Session, alloc: std.mem.Allocator) ![]Placement {
    const t = &session.terminal;
    const screen = t.screens.active;
    const storage = &screen.kitty_images;
    if (storage.generation == 0) return &.{};

    var list: std.ArrayList(Placement) = .empty;
    errdefer list.deinit(alloc);

    var it = storage.placements.iterator();
    while (it.next()) |entry| {
        const p = entry.value_ptr;
        if (p.location == .virtual) continue;
        const image = storage.images.getPtr(entry.key_ptr.image_id) orelse continue;
        const pos = viewportPos(p, image, t) orelse continue;
        const pixels = p.pixelSize(image.*, t);
        const source = p.sourceRect(image.*);
        if (pixels.width == 0 or pixels.height == 0 or
            source.width == 0 or source.height == 0) continue;
        try list.append(alloc, .{
            .image_id = image.id,
            .image_generation = @intCast(image.generation),
            .viewport_col = pos.col,
            .viewport_row = pos.row,
            .offset_x = p.x_offset,
            .offset_y = p.y_offset,
            .dest_width = pixels.width,
            .dest_height = pixels.height,
            .source_x = source.x,
            .source_y = source.y,
            .source_width = source.width,
            .source_height = source.height,
            .z = p.z,
        });
    }

    try appendVirtual(&list, alloc, &session.render);

    std.mem.sortUnstable(Placement, list.items, {}, struct {
        fn lessThan(_: void, a: Placement, b: Placement) bool {
            if (a.z != b.z) return a.z < b.z;
            return a.image_id < b.image_id;
        }
    }.lessThan);
    return list.toOwnedSlice(alloc);
}

/// Appends the placements the last render state update resolved from
/// placeholder cells: virtual placements and the relative placements
/// attached to them.
fn appendVirtual(
    list: *std.ArrayList(Placement),
    alloc: std.mem.Allocator,
    render: *const vt.RenderState,
) !void {
    var it = render.imagePlacementIterator();
    while (it.next()) |p| {
        try list.append(alloc, .{
            .image_id = p.image_id,
            .image_generation = @intCast(p.image_generation),
            .viewport_col = p.x,
            .viewport_row = p.y,
            .offset_x = p.offset_x,
            .offset_y = p.offset_y,
            .dest_width = p.width,
            .dest_height = p.height,
            .source_x = p.source_x,
            .source_y = p.source_y,
            .source_width = p.source_width,
            .source_height = p.source_height,
            .z = p.z,
        });
    }
}

/// The viewport cell a placement starts at, or null when no part of it is
/// in the viewport. Relative placements resolve through their parent
/// chain; chains rooted at a virtual placement come from the render state.
fn viewportPos(
    p: *const graphics.ImageStorage.Placement,
    image: *const graphics.Image,
    t: *vt.Terminal,
) ?struct { col: i32, row: i32 } {
    const Origin = struct { pin: *const vt.Pin, col_offset: i32 = 0, row_offset: i32 = 0 };
    const origin: Origin = switch (p.location) {
        .pin => |pin| .{ .pin = pin },
        .virtual => return null,
        .relative => |rel| origin: {
            const chain = t.screens.active.kitty_images.resolveChain(rel) orelse return null;
            switch (chain.root.location) {
                .pin => |root| break :origin .{
                    .pin = root,
                    .col_offset = chain.horizontal_offset,
                    .row_offset = chain.vertical_offset,
                },
                .virtual => return null,
                .relative => unreachable, // chain roots are never relative
            }
        },
    };
    if (origin.pin.garbage) return null;

    const pages = &t.screens.active.pages;
    const pin = (pages.pointFromPin(.screen, origin.pin.*) orelse return null).screen;
    const top = (pages.pointFromPin(.screen, pages.getTopLeft(.viewport)) orelse return null).screen;
    const row = (@as(i32, @intCast(pin.y)) - @as(i32, @intCast(top.y))) +| origin.row_offset;
    const col = @as(i32, @intCast(pin.x)) +| origin.col_offset;

    const grid = p.gridSize(image.*, t);
    const visible = @as(i64, row) + grid.rows > 0 and row < t.rows and
        @as(i64, col) + grid.cols > 0 and col < t.cols;
    return if (visible) .{ .col = col, .row = row } else null;
}

/// The current frame of an image as `[width, height, argb...]`.
pub fn imageArgb(session: *Session, alloc: std.mem.Allocator, image_id: u32) !?[]i32 {
    const storage = &session.terminal.screens.active.kitty_images;
    const image = storage.images.getPtr(image_id) orelse return null;
    const data = image.renderData().bytes() orelse return null;
    const bpp: usize = switch (image.format) {
        .gray => 1,
        .gray_alpha => 2,
        .rgb => 3,
        .rgba => 4,
        else => return null,
    };
    const pixel_count = @as(usize, image.width) * image.height;
    if (pixel_count == 0 or pixel_count > max_image_pixels or data.len < pixel_count * bpp) return null;

    const out = try alloc.alloc(i32, 2 + pixel_count);
    out[0] = @intCast(image.width);
    out[1] = @intCast(image.height);
    for (out[2..], 0..) |*dest, i| {
        const px = data[i * bpp ..][0..bpp];
        const r: u32, const g: u32, const b: u32, const a: u32 = switch (bpp) {
            1 => .{ px[0], px[0], px[0], 0xFF },
            2 => .{ px[0], px[0], px[0], px[1] },
            3 => .{ px[0], px[1], px[2], 0xFF },
            else => .{ px[0], px[1], px[2], px[3] },
        };
        dest.* = @bitCast(a << 24 | r << 16 | g << 8 | b);
    }
    return out;
}
