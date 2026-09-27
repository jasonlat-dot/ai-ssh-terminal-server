package com.jasonlat.ai.domain.sftp.adapter.port;

import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.Entry;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** SFTP 传输端口：一个管理窗口拥有独立连接，每个并发操作独占一个 Channel。 */
public interface ISftpClientPort {
    Connection connect(SshConnectionEntity credentials, SshConnectionConfigEntity config);
    interface Connection extends AutoCloseable {
        Channel channel();
        boolean connected();
        @Override void close();
    }
    interface Channel extends AutoCloseable {
        String realpath(String path);
        /** 不跟随最终符号链接；不存在返回 null，其余错误必须抛出。 */
        Entry stat(String path);
        List<Entry> list(String path, int limit);
        void mkdir(String path);
        void remove(String path);
        void rename(String source, String target);
        void upload(String path, InputStream input, LongConsumer progress, BooleanSupplier cancelled);
        void download(String path, OutputStream output, LongConsumer progress, BooleanSupplier cancelled);
        @Override void close();
    }
}
