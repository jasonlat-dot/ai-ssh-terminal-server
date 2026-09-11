

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
SET NAMES utf8mb4;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE='NO_AUTO_VALUE_ON_ZERO', SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

CREATE database if NOT EXISTS `ssh_terminal` default character set utf8mb4 collate utf8mb4_0900_ai_ci;
use `ssh_terminal`;

# 转储表 ssh_connection
# ------------------------------------------------------------

DROP TABLE IF EXISTS `ssh_connection`;

CREATE TABLE `ssh_connection` (
                                  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                                  `connection_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '连接唯一标识(UUID)',
                                  `connection_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '连接名称',
                                  `host` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主机地址',
                                  `port` int NOT NULL DEFAULT '22' COMMENT '端口号',
                                  `username` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
                                  `auth_type` tinyint NOT NULL DEFAULT '1' COMMENT '认证类型:1-密码,2-私钥',
                                  `password` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '密码(加密存储)',
                                  `private_key` longtext COLLATE utf8mb4_unicode_ci COMMENT '私钥内容(加密存储)',
                                  `encrypted` tinyint NOT NULL DEFAULT '1' COMMENT '是否加密:0-否,1-是',
                                  `status` tinyint NOT NULL DEFAULT '0' COMMENT '连接状态:0-未连接,1-已连接,2-连接中,3-连接失败',
                                  `user_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '用户ID',
                                  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
                                  PRIMARY KEY (`id`),
                                  UNIQUE KEY `uk_connection_id` (`connection_id`),
                                  KEY `idx_user_id` (`user_id`),
                                  KEY `idx_status` (`status`),
                                  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSH连接配置表';

LOCK TABLES `ssh_connection` WRITE;
/*!40000 ALTER TABLE `ssh_connection` DISABLE KEYS */;

INSERT INTO `ssh_connection` (`id`, `connection_id`, `connection_name`, `host`, `port`, `username`, `auth_type`, `password`, `private_key`, `encrypted`, `status`, `user_id`, `created_at`, `updated_at`, `deleted`)
VALUES
    (2,'0eb185a6c2964c50be6a8a4bf917c2c9','腾讯云服务器-测试机','140.143.183.225',22,'ubuntu',1,'nrF53G0MixGWLTWW9oWZTFkHY+OiH/7tlxkHNPyTKOXDVgzDKkKqJShf',NULL,1,1,'default','2026-05-02 11:32:04','2026-05-15 07:24:14',0),
    (4,'6779824e51c0424fb724a94df114104c','115.190.107.206','115.190.107.206',22,'root',1,'myUlBgwE+R6cmF0ToRfgtGimBMijQ/WPhqOC+9q1th21OepJS9o=',NULL,1,1,'default','2026-05-02 21:19:11','2026-05-13 08:41:19',0);

/*!40000 ALTER TABLE `ssh_connection` ENABLE KEYS */;
UNLOCK TABLES;


# 转储表 ssh_connection_config
# ------------------------------------------------------------

DROP TABLE IF EXISTS `ssh_connection_config`;

CREATE TABLE `ssh_connection_config` (
                                         `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                                         `connection_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '关联的连接ID',
                                         `connect_timeout` int NOT NULL DEFAULT '10' COMMENT '连接超时时间(秒)',
                                         `keepalive_interval` int NOT NULL DEFAULT '60' COMMENT '保活间隔(秒)',
                                         `startup_command` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '连接后执行的启动命令',
                                         `compression` tinyint NOT NULL DEFAULT '0' COMMENT '是否压缩:0-否,1-是',
                                         `strict_host_key_check` tinyint NOT NULL DEFAULT '1' COMMENT '严格主机密钥检查:0-否,1-是',
                                         `known_hosts` longtext COLLATE utf8mb4_unicode_ci COMMENT '已知主机密钥列表',
                                         `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                         PRIMARY KEY (`id`),
                                         UNIQUE KEY `uk_connection_id` (`connection_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSH连接高级配置表';

LOCK TABLES `ssh_connection_config` WRITE;
/*!40000 ALTER TABLE `ssh_connection_config` DISABLE KEYS */;

INSERT INTO `ssh_connection_config` (`id`, `connection_id`, `connect_timeout`, `keepalive_interval`, `startup_command`, `compression`, `strict_host_key_check`, `known_hosts`, `updated_at`)
VALUES
    (4,'0eb185a6c2964c50be6a8a4bf917c2c9',30,60,NULL,0,1,NULL,'2026-05-02 11:32:04'),
    (5,'b6883fbff36d4e34adf758336da7f258',30,60,NULL,0,1,NULL,'2026-05-02 21:07:34'),
    (6,'6779824e51c0424fb724a94df114104c',30,60,NULL,0,1,NULL,'2026-05-02 21:19:11');

/*!40000 ALTER TABLE `ssh_connection_config` ENABLE KEYS */;
UNLOCK TABLES;



/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
