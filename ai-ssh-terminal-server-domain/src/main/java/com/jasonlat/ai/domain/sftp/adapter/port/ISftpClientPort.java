package com.jasonlat.ai.domain.sftp.adapter.port;

import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.Entry;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * SFTP 客户端领域端口。
 *
 * <p>一个文件管理窗口拥有一条独立连接；连接上的每个并发文件操作独占一个 Channel。
 * 基础设施层负责实现具体协议，领域层只依赖这里定义的稳定行为。</p>
 */
public interface ISftpClientPort {

    /** 使用指定的 SSH 凭据和连接配置建立文件管理连接。 */
    Connection connect(SshConnectionEntity credentials, SshConnectionConfigEntity config);

    interface Connection extends AutoCloseable {

        /** 为一次目录操作或文件传输创建独立 Channel。 */
        Channel channel();

        /** 返回底层 SSH 连接当前是否仍然可用。 */
        boolean connected();

        @Override
        void close();
    }

    interface Channel extends AutoCloseable {

        /** 解析服务器端真实绝对路径。 */
        String realpath(String path);

        /** 不跟随最终符号链接；不存在返回 null，其余错误必须抛出。 */
        Entry stat(String path);

        /** 列出目录内容；超过 {@code limit} 时必须终止并报告容量错误。 */
        List<Entry> list(String path, int limit);

        /** 创建一个单级远程目录。 */
        void mkdir(String path);

        /** 删除一个普通文件。 */
        void remove(String path);

        /** 删除一个空目录；实现不得递归删除目录内容。 */
        void rmdir(String path);

        /** 在服务器内重命名或移动条目，主要用于临时文件的提交与回滚。 */
        void rename(String source, String target);

        /**
         * 上传文件流。
         *
         * @param progress  每次写入后的增量字节回调
         * @param cancelled 协作式取消检查；返回 true 时应尽快终止传输
         */
        void upload(String path, InputStream input, LongConsumer progress, BooleanSupplier cancelled);

        /**
         * 下载文件流。
         *
         * @param progress  每次读取后的增量字节回调
         * @param cancelled 协作式取消检查；返回 true 时应尽快终止传输
         */
        void download(String path, OutputStream output, LongConsumer progress, BooleanSupplier cancelled);

        /** 关闭本次操作独占的 SFTP Channel。 */
        @Override
        void close();
    }
}
