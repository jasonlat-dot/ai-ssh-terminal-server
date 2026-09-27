package com.jasonlat.ai.domain.sftp.model.valobj;

import com.jasonlat.ai.domain.sftp.model.SftpException;
import java.util.ArrayDeque;

/** 使用远程 POSIX 路径规则，不能使用后端操作系统的 Path 去解析服务器路径。 */
public final class RemotePath {
    private RemotePath() { }
    public static String absolute(String value) {
        check(value);
        if (!value.startsWith("/")) throw invalid();
        ArrayDeque<String> parts = new ArrayDeque<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); }
            else parts.addLast(part);
        }
        return "/" + String.join("/", parts);
    }
    public static String relative(String value, int maxDepth) {
        check(value);
        if (value.startsWith("/") || value.contains("\\")) throw invalid();
        String[] parts = value.split("/", -1);
        if (parts.length > maxDepth) throw invalid();
        for (String part : parts) if (part.isBlank() || part.equals(".") || part.equals("..")) throw invalid();
        return value;
    }
    public static String join(String parent, String child) { return absolute(parent + "/" + child); }
    public static String parent(String path) { int i = path.lastIndexOf('/'); return i <= 0 ? "/" : path.substring(0, i); }
    public static String name(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    public static String within(String root, String path) {
        String normalized = absolute(path);
        if (!root.equals("/") && !normalized.equals(root) && !normalized.startsWith(root + "/")) {
            throw new SftpException("SFTP_PATH_FORBIDDEN", "路径超出当前文件管理根目录");
        }
        return normalized;
    }
    private static void check(String value) {
        // JSch 0.1.x 的多个路径接口解释通配符，首版明确拒绝以免操作到非目标文件。
        if (value == null || value.isBlank() || value.length() > 4096 || value.indexOf('*') >= 0
                || value.indexOf('?') >= 0 || value.indexOf('\\') >= 0
                || value.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
    }
    private static SftpException invalid() { return new SftpException("SFTP_INVALID", "文件路径不合法"); }
}
