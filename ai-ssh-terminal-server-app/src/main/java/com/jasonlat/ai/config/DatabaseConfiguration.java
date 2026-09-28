package com.jasonlat.ai.config;

import com.jasonlat.ai.config.properties.DatabaseProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 根据 app.database.type 创建唯一活动数据源。
 * MySQL 和 SQLite 驱动可以同时打包，但业务 Mapper 在一次启动中只访问选中的数据库。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
@ConditionalOnProperty(prefix = "app.database", name = "type")
public class DatabaseConfiguration {

    /**
     * 创建本次启动使用的数据源。
     * 配置未出现时本配置类不会生效，开发环境仍由 Spring Boot 读取 spring.datasource。
     */
    @Primary
    @Bean(destroyMethod = "close")
    public DataSource dataSource(DatabaseProperties properties) {
        // 配置只允许选择一种数据库，避免同一批 Mapper 在运行中跨数据库写入。
        return switch (properties.getType()) {
            case MYSQL -> mysqlDataSource(properties.getMysql());
            case SQLITE -> sqliteDataSource(properties.getSqlite());
        };
    }

    /**
     * 让 MyBatis 识别当前数据库方言。
     * Mapper 可使用 databaseId="sqlite" 单独声明少量 SQLite 专用 SQL。
     */
    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties aliases = new Properties();
        aliases.setProperty("MySQL", "mysql");
        aliases.setProperty("SQLite", "sqlite");
        provider.setProperties(aliases);
        return provider;
    }

    /**
     * SQLite 首次启动时自动建表；所有语句都使用 IF NOT EXISTS，可以安全重复执行。
     * MySQL 模式不执行该脚本，仍使用项目原有的 MySQL 初始化脚本。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.database", name = "type", havingValue = "sqlite")
    public DataSourceInitializer sqliteDataSourceInitializer(DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("db/sqlite/schema.sql"));
        populator.setContinueOnError(false);

        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(populator);
        return initializer;
    }

    /** 使用 MySQL 配置创建常规 Hikari 连接池。 */
    private HikariDataSource mysqlDataSource(DatabaseProperties.Mysql mysql) {
        if (!StringUtils.hasText(mysql.getUrl())) {
            throw new IllegalStateException("app.database.mysql.url 不能为空");
        }

        // MySQL 适合复用多个连接，连接池参数全部从 prod 配置读取。
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mysql.getUrl());
        config.setUsername(mysql.getUsername());
        config.setPassword(mysql.getPassword());
        config.setDriverClassName(mysql.getDriverClassName());
        config.setPoolName(mysql.getPoolName());
        config.setMinimumIdle(mysql.getMinimumIdle());
        config.setMaximumPoolSize(mysql.getMaximumPoolSize());
        config.setConnectionTimeout(mysql.getConnectionTimeout());
        config.setIdleTimeout(mysql.getIdleTimeout());
        config.setMaxLifetime(mysql.getMaxLifetime());
        config.setConnectionTestQuery("SELECT 1");

        log.info("数据库模式已选择 MySQL，连接地址={}", safeMysqlUrl(mysql.getUrl()));
        return new HikariDataSource(config);
    }

    /** 创建 SQLite 文件目录并构造适合桌面端单机写入的连接池。 */
    private HikariDataSource sqliteDataSource(DatabaseProperties.Sqlite sqlite) {
        String configuredFile = sqlite.getFile();
        if (!StringUtils.hasText(configuredFile)) {
            configuredFile = Path.of(System.getProperty("user.home"),
                    ".ai-ssh-terminal", "data", "ai-ssh-terminal.db").toString();
        }
        Path databaseFile = prepareSqliteFile(configuredFile);

        // WAL 允许读取与写入更好地并行；busy_timeout 用于吸收短暂的文件锁竞争。
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + databaseFile);
        config.setDriverClassName(sqlite.getDriverClassName());
        config.setPoolName(sqlite.getPoolName());
        config.setMinimumIdle(1);
        config.setMaximumPoolSize(Math.max(1, sqlite.getMaximumPoolSize()));
        config.setConnectionTestQuery("SELECT 1");
        config.addDataSourceProperty("foreign_keys", "on");
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("busy_timeout", sqlite.getBusyTimeout());

        log.info("数据库模式已选择 SQLite，数据库文件={}", databaseFile);
        return new HikariDataSource(config);
    }

    /** 校验 SQLite 文件路径，并确保数据库父目录可写。 */
    private Path prepareSqliteFile(String configuredFile) {
        if (!StringUtils.hasText(configuredFile)) {
            throw new IllegalStateException("app.database.sqlite.file 不能为空");
        }

        try {
            Path databaseFile = Path.of(configuredFile).toAbsolutePath().normalize();
            Path parent = databaseFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return databaseFile;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("无法准备 SQLite 数据库文件: " + configuredFile, exception);
        }
    }

    /** 日志只保留 MySQL 主机和库名，去掉可能出现在查询参数中的敏感信息。 */
    private String safeMysqlUrl(String jdbcUrl) {
        int queryIndex = jdbcUrl.indexOf('?');
        return queryIndex < 0 ? jdbcUrl : jdbcUrl.substring(0, queryIndex);
    }
}
