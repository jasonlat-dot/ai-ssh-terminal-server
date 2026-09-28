package com.jasonlat.ai.domain.file.model.valobj;

import java.io.IOException;
import java.io.InputStream;

/**
 * 本地下载接口即将输出的文件流。
 *
 * @param input 文件输入流，由 Web 响应完成后关闭
 * @param size 文件的实际字节数，用于设置 Content-Length
 * @param fileName 下载时展示给用户的文件名
 */
public record TemporaryDownload(InputStream input, long size, String fileName) implements AutoCloseable {

    /** 非 Web 调用方可以用 try-with-resources 显式释放文件句柄。 */
    @Override
    public void close() throws IOException {
        input.close();
    }
}
