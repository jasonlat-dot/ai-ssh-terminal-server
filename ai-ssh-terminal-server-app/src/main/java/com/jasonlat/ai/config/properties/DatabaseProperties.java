package com.jasonlat.ai.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 绑定生产环境的可选数据库配置。
 * 配置存在时由应用自行创建一个活动数据源；未配置时继续使用 Spring Boot 原有数据源配置。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.database")
public class DatabaseProperties {

    /** 当前启用的数据库类型，只能选择 MYSQL 或 SQLITE。 */
    private DatabaseType type = DatabaseType.SQLITE;

    /** 外部 MySQL 数据库及连接池配置。 */
    private Mysql mysql = new Mysql();

    /** 本机 SQLite 数据库及连接池配置。 */
    private Sqlite sqlite = new Sqlite();

    /** 应用当前支持的数据库类型。 */
    public enum DatabaseType {
        MYSQL,
        SQLITE
    }

    /** MySQL 数据源配置；仅在 type=MYSQL 时生效。 */
    @Getter
    @Setter
    public static class Mysql {

        /** MySQL JDBC 地址，包含数据库名和连接参数。 */
        private String url;

        /** MySQL 登录用户名。 */
        private String username;

        /** MySQL 登录密码。 */
        private String password;

        /** JDBC 驱动类名，通常不需要修改。 */
        private String driverClassName = "com.mysql.cj.jdbc.Driver";

        /** 连接池名称，仅用于日志和监控识别。 */
        private String poolName = "AiSshTerminal-MySQL";

        /** 连接池保持的最少空闲连接数。 */
        private int minimumIdle = 2;

        /** 连接池允许创建的最大连接数。 */
        private int maximumPoolSize = 10;

        /** 获取数据库连接的最大等待毫秒数。 */
        private long connectionTimeout = 30_000L;

        /** 空闲连接最多保留的毫秒数。 */
        private long idleTimeout = 180_000L;

        /** 单个连接的最长生命周期毫秒数。 */
        private long maxLifetime = 1_800_000L;
    }

    /** SQLite 数据源配置；仅在 type=SQLITE 时生效。 */
    @Getter
    @Setter
    public static class Sqlite {

        /** SQLite 数据库文件路径；父目录不存在时由应用自动创建。 */
        private String file = Path.of(System.getProperty("user.home"),
                ".ai-ssh-terminal", "data", "ai-ssh-terminal.db").toString();

        /** JDBC 驱动类名，通常不需要修改。 */
        private String driverClassName = "org.sqlite.JDBC";

        /** 连接池名称，仅用于日志和监控识别。 */
        private String poolName = "AiSshTerminal-SQLite";

        /** SQLite 遇到文件锁时等待的毫秒数，超过后才返回 database is locked。 */
        private int busyTimeout = 10_000;

        /** SQLite 连接池大小；单文件数据库默认使用一个写连接以减少锁竞争。 */
        private int maximumPoolSize = 1;
    }
}
