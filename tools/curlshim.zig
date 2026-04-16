const std = @import("std");

fn writeAllFile(path: []const u8, data: []const u8) !void {
    const file = try std.fs.createFileAbsolute(path, .{ .truncate = true });
    defer file.close();
    try file.writeAll(data);
}

fn copyFileToWriter(path: []const u8, writer: anytype) !void {
    const file = try std.fs.openFileAbsolute(path, .{});
    defer file.close();
    var buf: [4096]u8 = undefined;
    while (true) {
        const n = try file.read(&buf);
        if (n == 0) break;
        try writer.writeAll(buf[0..n]);
    }
}

pub fn main() !void {
    const allocator = std.heap.page_allocator;
    const bridge_dir = std.process.getEnvVarOwned(allocator, "NULLCLAW_CURL_BRIDGE_DIR") catch {
        std.process.exit(2);
    };
    defer allocator.free(bridge_dir);

    const now = std.time.nanoTimestamp();
    const req_id = try std.fmt.allocPrint(allocator, "{d}", .{now});
    defer allocator.free(req_id);

    const tmp_file = try std.fmt.allocPrint(allocator, "{s}/.req-{s}.txt", .{ bridge_dir, req_id });
    defer allocator.free(tmp_file);
    const ready_file = try std.fmt.allocPrint(allocator, "{s}/ready-{s}.txt", .{ bridge_dir, req_id });
    defer allocator.free(ready_file);
    const body_file = try std.fmt.allocPrint(allocator, "{s}/body-{s}.bin", .{ bridge_dir, req_id });
    defer allocator.free(body_file);
    const out_file = try std.fmt.allocPrint(allocator, "{s}/stdout-{s}.txt", .{ bridge_dir, req_id });
    defer allocator.free(out_file);
    const err_file = try std.fmt.allocPrint(allocator, "{s}/stderr-{s}.txt", .{ bridge_dir, req_id });
    defer allocator.free(err_file);
    const status_file = try std.fmt.allocPrint(allocator, "{s}/status-{s}.txt", .{ bridge_dir, req_id });
    defer allocator.free(status_file);

    const args = try std.process.argsAlloc(allocator);
    defer std.process.argsFree(allocator, args);

    const needs_stdin_body = blk: {
        for (args[1..]) |arg| {
            if (std.mem.eql(u8, arg, "@-")) break :blk true;
        }
        break :blk false;
    };

    if (needs_stdin_body) {
        const stdin_bytes = try std.fs.File.stdin().readToEndAlloc(allocator, 8 * 1024 * 1024);
        defer allocator.free(stdin_bytes);
        try writeAllFile(body_file, stdin_bytes);
    } else {
        try writeAllFile(body_file, "");
    }

    {
        const file = try std.fs.createFileAbsolute(tmp_file, .{ .truncate = true });
        defer file.close();
        var write_buf: [1024]u8 = undefined;
        var writer = file.writer(&write_buf);
        for (args[1..]) |arg| {
            try writer.interface.writeAll(arg);
            try writer.interface.writeByte('\n');
        }
        try writer.interface.flush();
    }

    try std.fs.renameAbsolute(tmp_file, ready_file);

    while (true) {
        if (std.fs.openFileAbsolute(status_file, .{})) |file| {
            file.close();
            break;
        } else |_| {
            std.Thread.sleep(100 * std.time.ns_per_ms);
        }
    }

    copyFileToWriter(out_file, std.fs.File.stdout()) catch {};
    copyFileToWriter(err_file, std.fs.File.stderr()) catch {};

    var exit_code: u8 = 1;
    if (std.fs.openFileAbsolute(status_file, .{})) |file| {
        defer file.close();
        const contents = try file.readToEndAlloc(allocator, 32);
        defer allocator.free(contents);
        exit_code = std.fmt.parseUnsigned(u8, std.mem.trim(u8, contents, " \t\r\n"), 10) catch 1;
    } else |_| {}

    std.fs.deleteFileAbsolute(ready_file) catch {};
    std.fs.deleteFileAbsolute(body_file) catch {};
    std.fs.deleteFileAbsolute(out_file) catch {};
    std.fs.deleteFileAbsolute(err_file) catch {};
    std.fs.deleteFileAbsolute(status_file) catch {};

    std.process.exit(exit_code);
}
