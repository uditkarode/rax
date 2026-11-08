const std = @import("std");

pub fn build(b: *std.Build) !void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const lib = b.addLibrary(.{
        .name = "ghostty_jni",
        .linkage = .dynamic,
        .root_module = bridgeModule(b, target, optimize),
    });
    if (target.result.ofmt == .elf) lib.setVersionScript(b.path("jni.map"));
    if (target.result.abi.isAndroid()) {
        // Android 15+ devices may use 16 KB pages.
        lib.link_z_max_page_size = 16384;
        try @import("android.zig").addPaths(b, lib);
        lib.root_module.linkSystemLibrary("log", .{});
    }
    b.installArtifact(lib);

    // The Kotlin mirrors of ghostty's input types, generated on the host. Ghostty defaults a
    // Windows host to the MSVC ABI, whose SIMD C++ does not build; use GNU like the desktop JNI.
    const host = if (b.graph.host.result.os.tag == .windows)
        b.resolveTargetQuery(.{ .abi = .gnu })
    else
        b.graph.host;
    const gen = b.addExecutable(.{
        .name = "gen-kotlin",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/gen_kotlin.zig"),
            .target = host,
            .optimize = .debug,
        }),
    });
    gen.root_module.addImport("ghostty-vt", ghosttyDependency(b, host, .debug).module("ghostty-vt"));
    const run_gen = b.addRunArtifact(gen);
    const kotlin_dir = run_gen.addOutputDirectoryArg("kotlin");
    const gen_step = b.step("gen-kotlin", "Generate the Kotlin mirrors of ghostty's input types");
    gen_step.dependOn(&b.addInstallDirectory(.{
        .source_dir = kotlin_dir,
        .install_dir = .prefix,
        .install_subdir = "kotlin",
    }).step);

    const tests = b.addTest(.{ .root_module = bridgeModule(b, target, optimize) });
    b.step("test", "Run the bridge unit tests").dependOn(&b.addRunArtifact(tests).step);
}

fn ghosttyDependency(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.lang.Optimize,
) *std.Build.Dependency {
    return b.dependency("ghostty", .{ .target = target, .optimize = optimize });
}

fn bridgeModule(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.lang.Optimize,
) *std.Build.Module {
    const ghostty = ghosttyDependency(b, target, optimize);
    const mod = b.createModule(.{
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .strip = optimize != .debug,
    });
    const vt = ghostty.module("ghostty-vt");
    mod.addImport("ghostty-vt", vt);
    // The same PNG decoder Ghostty uses. Reuse the instance ghostty-vt imports for Kitty graphics:
    // ghostty adjusts the target (e.g. on a macOS host), and a second instance built for ours
    // would put the same files in two modules.
    if (vt.import_table.get("wuffs")) |wuffs| {
        mod.addImport("wuffs", wuffs);
    } else if (ghostty.builder.lazyDependency("wuffs", .{
        .target = target,
        .optimize = optimize,
    })) |wuffs| mod.addImport("wuffs", wuffs.module("wuffs"));
    return mod;
}
