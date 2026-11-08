//! The subset of JNI the bridge uses. JNI's function tables are frozen, so
//! `NativeInterface` mirrors jni.h from the Android NDK (the same on every
//! JVM); slots the bridge never calls stay untyped.
const std = @import("std");

pub const Boolean = u8;
pub const Int = i32;
pub const Size = Int;

/// A reference to a Java object whose type has the JNI signature
/// `signature`. Distinct signatures are distinct Zig types, which lets
/// `registerNatives` derive method signatures from function types.
pub fn Ref(comptime signature: []const u8) type {
    return ?*opaque {
        pub const jni_signature = signature;
    };
}

pub const Object = Ref("Ljava/lang/Object;");
pub const Class = Ref("Ljava/lang/Class;");
pub const String = Ref("Ljava/lang/String;");
pub const ByteBuffer = Ref("Ljava/nio/ByteBuffer;");
pub const ByteArray = Ref("[B");
pub const IntArray = Ref("[I");
pub const LongArray = Ref("[J");

pub const MethodId = ?*opaque {};

/// Any reference, as the function table takes and returns them.
const Ptr = ?*anyopaque;

pub const Value = extern union {
    z: Boolean,
    b: i8,
    c: u16,
    s: i16,
    i: Int,
    j: i64,
    f: f32,
    d: f64,
    l: Ptr,
};

pub const NativeMethod = extern struct {
    name: [*:0]const u8,
    signature: [*:0]const u8,
    fn_ptr: *const anyopaque,
};

pub const version_1_6: Int = 0x00010006;
pub const ok: Int = 0;
pub const err: Int = -1;

pub const Env = extern struct {
    functions: *const NativeInterface,

    fn Return(comptime name: []const u8) type {
        return @typeInfo(@typeInfo(@FieldType(NativeInterface, name)).pointer.child).@"fn".return_type.?;
    }

    pub inline fn call(self: *Env, comptime name: []const u8, args: anytype) Return(name) {
        return @call(.auto, @field(self.functions, name), .{self} ++ args);
    }

    /// Clears a pending exception, reporting whether there was one.
    pub fn clearException(self: *Env) bool {
        if (self.call("ExceptionCheck", .{}) == 0) return false;
        self.call("ExceptionClear", .{});
        return true;
    }

    pub fn newByteArray(self: *Env, bytes: []const u8) ByteArray {
        const len: Size = @intCast(bytes.len);
        const array = self.call("NewByteArray", .{len}) orelse return null;
        self.call("SetByteArrayRegion", .{ array, 0, len, bytes.ptr });
        return @ptrCast(array);
    }

    pub fn newIntArray(self: *Env, values: []const i32) IntArray {
        const len: Size = @intCast(values.len);
        const array = self.call("NewIntArray", .{len}) orelse return null;
        self.call("SetIntArrayRegion", .{ array, 0, len, values.ptr });
        return @ptrCast(array);
    }

    pub fn newLongArray(self: *Env, values: []const i64) LongArray {
        const len: Size = @intCast(values.len);
        const array = self.call("NewLongArray", .{len}) orelse return null;
        self.call("SetLongArrayRegion", .{ array, 0, len, values.ptr });
        return @ptrCast(array);
    }

    /// Copies a Java byte array into memory owned by `alloc`.
    pub fn byteArrayAlloc(self: *Env, alloc: std.mem.Allocator, array: anytype) ![]u8 {
        const len = self.call("GetArrayLength", .{array});
        const bytes = try alloc.alloc(u8, @intCast(len));
        self.call("GetByteArrayRegion", .{ array, 0, len, bytes.ptr });
        return bytes;
    }

    /// Copies part of a Java long array into `out`.
    pub fn longArrayRegion(self: *Env, array: LongArray, out: []i64) void {
        self.call("GetLongArrayRegion", .{ array, 0, @as(Size, @intCast(out.len)), out.ptr });
    }

    /// Copies a Java string as modified UTF-8 into memory owned by `alloc`.
    pub fn stringAlloc(self: *Env, alloc: std.mem.Allocator, string: String) ![]u8 {
        const bytes = try alloc.alloc(u8, @intCast(self.call("GetStringUTFLength", .{string})));
        self.call("GetStringUTFRegion", .{ string, 0, self.call("GetStringLength", .{string}), bytes.ptr });
        return bytes;
    }

    /// Registers every public function of `natives` as the native method of
    /// the same name on `class`. Each function takes the JNIEnv and the
    /// receiver first; the JNI signature comes from the remaining
    /// parameter types and the return type.
    pub fn registerNatives(self: *Env, class: Ptr, comptime natives: type) bool {
        const names = @typeInfo(natives).@"struct".decl_names;
        const methods = comptime methods: {
            var methods: [names.len]NativeMethod = undefined;
            for (names, &methods) |name, *method| {
                const func = @field(natives, name);
                method.* = .{
                    .name = name,
                    .signature = signatureOf(@TypeOf(func)),
                    .fn_ptr = &func,
                };
            }
            break :methods methods;
        };
        return self.call("RegisterNatives", .{ class, &methods, @as(Int, methods.len) }) == ok;
    }
};

fn signatureOf(comptime Fn: type) [:0]const u8 {
    const info = @typeInfo(Fn).@"fn";
    comptime var sig: [:0]const u8 = "(";
    inline for (info.param_types[2..]) |param| sig = sig ++ typeSignature(param.?);
    return sig ++ ")" ++ typeSignature(info.return_type.?);
}

fn typeSignature(comptime T: type) [:0]const u8 {
    return switch (T) {
        void => "V",
        Boolean => "Z",
        Int => "I",
        i64 => "J",
        f32 => "F",
        f64 => "D",
        else => @typeInfo(@typeInfo(T).optional.child).pointer.child.jni_signature ++ "",
    };
}

pub const JavaVm = extern struct {
    functions: *const InvokeInterface,

    pub fn getEnv(self: *JavaVm) ?*Env {
        var env: ?*Env = null;
        if (self.functions.GetEnv(self, &env, version_1_6) != ok) return null;
        return env;
    }
};

pub const InvokeInterface = extern struct {
    reserved0: Ptr,
    reserved1: Ptr,
    reserved2: Ptr,
    DestroyJavaVM: *const anyopaque,
    AttachCurrentThread: *const anyopaque,
    DetachCurrentThread: *const anyopaque,
    GetEnv: *const fn (*JavaVm, *?*Env, Int) callconv(.c) Int,
    AttachCurrentThreadAsDaemon: *const anyopaque,
};

pub const NativeInterface = extern struct {
    reserved0: Ptr,
    reserved1: Ptr,
    reserved2: Ptr,
    reserved3: Ptr,
    GetVersion: *const anyopaque,
    DefineClass: *const anyopaque,
    FindClass: *const fn (*Env, [*:0]const u8) callconv(.c) Ptr,
    FromReflectedMethod: *const anyopaque,
    FromReflectedField: *const anyopaque,
    ToReflectedMethod: *const anyopaque,
    GetSuperclass: *const anyopaque,
    IsAssignableFrom: *const anyopaque,
    ToReflectedField: *const anyopaque,
    Throw: *const anyopaque,
    ThrowNew: *const anyopaque,
    ExceptionOccurred: *const anyopaque,
    ExceptionDescribe: *const anyopaque,
    ExceptionClear: *const fn (*Env) callconv(.c) void,
    FatalError: *const anyopaque,
    PushLocalFrame: *const anyopaque,
    PopLocalFrame: *const anyopaque,
    NewGlobalRef: *const fn (*Env, Ptr) callconv(.c) Ptr,
    DeleteGlobalRef: *const fn (*Env, Ptr) callconv(.c) void,
    DeleteLocalRef: *const fn (*Env, Ptr) callconv(.c) void,
    IsSameObject: *const anyopaque,
    NewLocalRef: *const anyopaque,
    EnsureLocalCapacity: *const anyopaque,
    AllocObject: *const anyopaque,
    NewObject: *const anyopaque,
    NewObjectV: *const anyopaque,
    NewObjectA: *const anyopaque,
    GetObjectClass: *const anyopaque,
    IsInstanceOf: *const anyopaque,
    GetMethodID: *const fn (*Env, Ptr, [*:0]const u8, [*:0]const u8) callconv(.c) MethodId,
    CallObjectMethod: *const anyopaque,
    CallObjectMethodV: *const anyopaque,
    CallObjectMethodA: *const fn (*Env, Ptr, MethodId, ?[*]const Value) callconv(.c) Ptr,
    CallBooleanMethod: *const anyopaque,
    CallBooleanMethodV: *const anyopaque,
    CallBooleanMethodA: *const fn (*Env, Ptr, MethodId, ?[*]const Value) callconv(.c) Boolean,
    CallByteMethod: *const anyopaque,
    CallByteMethodV: *const anyopaque,
    CallByteMethodA: *const anyopaque,
    CallCharMethod: *const anyopaque,
    CallCharMethodV: *const anyopaque,
    CallCharMethodA: *const anyopaque,
    CallShortMethod: *const anyopaque,
    CallShortMethodV: *const anyopaque,
    CallShortMethodA: *const anyopaque,
    CallIntMethod: *const anyopaque,
    CallIntMethodV: *const anyopaque,
    CallIntMethodA: *const anyopaque,
    CallLongMethod: *const anyopaque,
    CallLongMethodV: *const anyopaque,
    CallLongMethodA: *const anyopaque,
    CallFloatMethod: *const anyopaque,
    CallFloatMethodV: *const anyopaque,
    CallFloatMethodA: *const anyopaque,
    CallDoubleMethod: *const anyopaque,
    CallDoubleMethodV: *const anyopaque,
    CallDoubleMethodA: *const anyopaque,
    CallVoidMethod: *const anyopaque,
    CallVoidMethodV: *const anyopaque,
    CallVoidMethodA: *const anyopaque,
    CallNonvirtualObjectMethod: *const anyopaque,
    CallNonvirtualObjectMethodV: *const anyopaque,
    CallNonvirtualObjectMethodA: *const anyopaque,
    CallNonvirtualBooleanMethod: *const anyopaque,
    CallNonvirtualBooleanMethodV: *const anyopaque,
    CallNonvirtualBooleanMethodA: *const anyopaque,
    CallNonvirtualByteMethod: *const anyopaque,
    CallNonvirtualByteMethodV: *const anyopaque,
    CallNonvirtualByteMethodA: *const anyopaque,
    CallNonvirtualCharMethod: *const anyopaque,
    CallNonvirtualCharMethodV: *const anyopaque,
    CallNonvirtualCharMethodA: *const anyopaque,
    CallNonvirtualShortMethod: *const anyopaque,
    CallNonvirtualShortMethodV: *const anyopaque,
    CallNonvirtualShortMethodA: *const anyopaque,
    CallNonvirtualIntMethod: *const anyopaque,
    CallNonvirtualIntMethodV: *const anyopaque,
    CallNonvirtualIntMethodA: *const anyopaque,
    CallNonvirtualLongMethod: *const anyopaque,
    CallNonvirtualLongMethodV: *const anyopaque,
    CallNonvirtualLongMethodA: *const anyopaque,
    CallNonvirtualFloatMethod: *const anyopaque,
    CallNonvirtualFloatMethodV: *const anyopaque,
    CallNonvirtualFloatMethodA: *const anyopaque,
    CallNonvirtualDoubleMethod: *const anyopaque,
    CallNonvirtualDoubleMethodV: *const anyopaque,
    CallNonvirtualDoubleMethodA: *const anyopaque,
    CallNonvirtualVoidMethod: *const anyopaque,
    CallNonvirtualVoidMethodV: *const anyopaque,
    CallNonvirtualVoidMethodA: *const anyopaque,
    GetFieldID: *const anyopaque,
    GetObjectField: *const anyopaque,
    GetBooleanField: *const anyopaque,
    GetByteField: *const anyopaque,
    GetCharField: *const anyopaque,
    GetShortField: *const anyopaque,
    GetIntField: *const anyopaque,
    GetLongField: *const anyopaque,
    GetFloatField: *const anyopaque,
    GetDoubleField: *const anyopaque,
    SetObjectField: *const anyopaque,
    SetBooleanField: *const anyopaque,
    SetByteField: *const anyopaque,
    SetCharField: *const anyopaque,
    SetShortField: *const anyopaque,
    SetIntField: *const anyopaque,
    SetLongField: *const anyopaque,
    SetFloatField: *const anyopaque,
    SetDoubleField: *const anyopaque,
    GetStaticMethodID: *const anyopaque,
    CallStaticObjectMethod: *const anyopaque,
    CallStaticObjectMethodV: *const anyopaque,
    CallStaticObjectMethodA: *const anyopaque,
    CallStaticBooleanMethod: *const anyopaque,
    CallStaticBooleanMethodV: *const anyopaque,
    CallStaticBooleanMethodA: *const anyopaque,
    CallStaticByteMethod: *const anyopaque,
    CallStaticByteMethodV: *const anyopaque,
    CallStaticByteMethodA: *const anyopaque,
    CallStaticCharMethod: *const anyopaque,
    CallStaticCharMethodV: *const anyopaque,
    CallStaticCharMethodA: *const anyopaque,
    CallStaticShortMethod: *const anyopaque,
    CallStaticShortMethodV: *const anyopaque,
    CallStaticShortMethodA: *const anyopaque,
    CallStaticIntMethod: *const anyopaque,
    CallStaticIntMethodV: *const anyopaque,
    CallStaticIntMethodA: *const anyopaque,
    CallStaticLongMethod: *const anyopaque,
    CallStaticLongMethodV: *const anyopaque,
    CallStaticLongMethodA: *const anyopaque,
    CallStaticFloatMethod: *const anyopaque,
    CallStaticFloatMethodV: *const anyopaque,
    CallStaticFloatMethodA: *const anyopaque,
    CallStaticDoubleMethod: *const anyopaque,
    CallStaticDoubleMethodV: *const anyopaque,
    CallStaticDoubleMethodA: *const anyopaque,
    CallStaticVoidMethod: *const anyopaque,
    CallStaticVoidMethodV: *const anyopaque,
    CallStaticVoidMethodA: *const anyopaque,
    GetStaticFieldID: *const anyopaque,
    GetStaticObjectField: *const anyopaque,
    GetStaticBooleanField: *const anyopaque,
    GetStaticByteField: *const anyopaque,
    GetStaticCharField: *const anyopaque,
    GetStaticShortField: *const anyopaque,
    GetStaticIntField: *const anyopaque,
    GetStaticLongField: *const anyopaque,
    GetStaticFloatField: *const anyopaque,
    GetStaticDoubleField: *const anyopaque,
    SetStaticObjectField: *const anyopaque,
    SetStaticBooleanField: *const anyopaque,
    SetStaticByteField: *const anyopaque,
    SetStaticCharField: *const anyopaque,
    SetStaticShortField: *const anyopaque,
    SetStaticIntField: *const anyopaque,
    SetStaticLongField: *const anyopaque,
    SetStaticFloatField: *const anyopaque,
    SetStaticDoubleField: *const anyopaque,
    NewString: *const anyopaque,
    GetStringLength: *const fn (*Env, Ptr) callconv(.c) Size,
    GetStringChars: *const anyopaque,
    ReleaseStringChars: *const anyopaque,
    NewStringUTF: *const anyopaque,
    GetStringUTFLength: *const fn (*Env, Ptr) callconv(.c) Size,
    GetStringUTFChars: *const anyopaque,
    ReleaseStringUTFChars: *const anyopaque,
    GetArrayLength: *const fn (*Env, Ptr) callconv(.c) Size,
    NewObjectArray: *const anyopaque,
    GetObjectArrayElement: *const anyopaque,
    SetObjectArrayElement: *const anyopaque,
    NewBooleanArray: *const anyopaque,
    NewByteArray: *const fn (*Env, Size) callconv(.c) Ptr,
    NewCharArray: *const anyopaque,
    NewShortArray: *const anyopaque,
    NewIntArray: *const fn (*Env, Size) callconv(.c) Ptr,
    NewLongArray: *const fn (*Env, Size) callconv(.c) Ptr,
    NewFloatArray: *const anyopaque,
    NewDoubleArray: *const anyopaque,
    GetBooleanArrayElements: *const anyopaque,
    GetByteArrayElements: *const anyopaque,
    GetCharArrayElements: *const anyopaque,
    GetShortArrayElements: *const anyopaque,
    GetIntArrayElements: *const anyopaque,
    GetLongArrayElements: *const anyopaque,
    GetFloatArrayElements: *const anyopaque,
    GetDoubleArrayElements: *const anyopaque,
    ReleaseBooleanArrayElements: *const anyopaque,
    ReleaseByteArrayElements: *const anyopaque,
    ReleaseCharArrayElements: *const anyopaque,
    ReleaseShortArrayElements: *const anyopaque,
    ReleaseIntArrayElements: *const anyopaque,
    ReleaseLongArrayElements: *const anyopaque,
    ReleaseFloatArrayElements: *const anyopaque,
    ReleaseDoubleArrayElements: *const anyopaque,
    GetBooleanArrayRegion: *const anyopaque,
    GetByteArrayRegion: *const fn (*Env, Ptr, Size, Size, [*]u8) callconv(.c) void,
    GetCharArrayRegion: *const anyopaque,
    GetShortArrayRegion: *const anyopaque,
    GetIntArrayRegion: *const anyopaque,
    GetLongArrayRegion: *const fn (*Env, Ptr, Size, Size, [*]i64) callconv(.c) void,
    GetFloatArrayRegion: *const anyopaque,
    GetDoubleArrayRegion: *const anyopaque,
    SetBooleanArrayRegion: *const anyopaque,
    SetByteArrayRegion: *const fn (*Env, Ptr, Size, Size, [*]const u8) callconv(.c) void,
    SetCharArrayRegion: *const anyopaque,
    SetShortArrayRegion: *const anyopaque,
    SetIntArrayRegion: *const fn (*Env, Ptr, Size, Size, [*]const i32) callconv(.c) void,
    SetLongArrayRegion: *const fn (*Env, Ptr, Size, Size, [*]const i64) callconv(.c) void,
    SetFloatArrayRegion: *const anyopaque,
    SetDoubleArrayRegion: *const anyopaque,
    RegisterNatives: *const fn (*Env, Ptr, [*]const NativeMethod, Int) callconv(.c) Int,
    UnregisterNatives: *const anyopaque,
    MonitorEnter: *const anyopaque,
    MonitorExit: *const anyopaque,
    GetJavaVM: *const anyopaque,
    GetStringRegion: *const anyopaque,
    GetStringUTFRegion: *const fn (*Env, Ptr, Size, Size, [*]u8) callconv(.c) void,
    GetPrimitiveArrayCritical: *const anyopaque,
    ReleasePrimitiveArrayCritical: *const anyopaque,
    GetStringCritical: *const anyopaque,
    ReleaseStringCritical: *const anyopaque,
    NewWeakGlobalRef: *const anyopaque,
    DeleteWeakGlobalRef: *const anyopaque,
    ExceptionCheck: *const fn (*Env) callconv(.c) Boolean,
    NewDirectByteBuffer: *const anyopaque,
    GetDirectBufferAddress: *const fn (*Env, Ptr) callconv(.c) ?[*]u8,
    GetDirectBufferCapacity: *const fn (*Env, Ptr) callconv(.c) i64,
    GetObjectRefType: *const anyopaque,
};
