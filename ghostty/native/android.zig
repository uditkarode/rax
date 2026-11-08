//! Points a compile step at the NDK sysroot for bionic and liblog. Zig
//! does not ship bionic (ziglang/zig#23906). The NDK comes from
//! ANDROID_NDK_HOME, which the Gradle build sets to the pinned version.
const std = @import("std");
const builtin = @import("builtin");

pub fn addPaths(b: *std.Build, step: *std.Build.Step.Compile) !void {
    const target = step.rootModuleTarget();
    const ndk = b.graph.environ_map.get("ANDROID_NDK_HOME") orelse
        return error.AndroidNdkHomeNotSet;
    const triple = ndkTriple(target) orelse return error.AndroidNdkUnsupportedTarget;
    const host = switch (builtin.target.os.tag) {
        .linux => "linux-x86_64",
        .macos => "darwin-x86_64",
        .windows => "windows-x86_64",
        else => return error.AndroidNdkUnsupportedHost,
    };
    const sysroot = b.pathJoin(&.{ ndk, "toolchains", "llvm", "prebuilt", host, "sysroot" });
    const api_lib_dir = b.pathJoin(&.{
        sysroot,                                                "usr", "lib", triple,
        b.fmt("{d}", .{target.os.version_range.linux.android}),
    });

    // Zig 0.17 requires every key, and a non-empty cc_dir for Linux targets (Android included).
    const wf = b.addWriteFiles();
    step.setLibCFile(wf.add("libc.txt", b.fmt(
        \\include_dir={s}
        \\sys_include_dir={s}
        \\cc_dir={s}
        \\crt_dir={s}
        \\msvc_lib_dir=
        \\kernel32_lib_dir=
        \\darwin_sdk_dir=
        \\
    , .{
        b.pathJoin(&.{ sysroot, "usr", "include" }),
        b.pathJoin(&.{ sysroot, "usr", "include", triple }),
        api_lib_dir,
        api_lib_dir,
    })));
    step.root_module.addLibraryPath(.{ .cwd_relative = api_lib_dir });
}

fn ndkTriple(target: std.Target) ?[]const u8 {
    return switch (target.cpu.arch) {
        .aarch64 => "aarch64-linux-android",
        .arm => "arm-linux-androideabi",
        .x86 => "i686-linux-android",
        .x86_64 => "x86_64-linux-android",
        else => null,
    };
}
