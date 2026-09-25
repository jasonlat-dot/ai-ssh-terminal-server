

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
SET NAMES utf8mb4;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE='NO_AUTO_VALUE_ON_ZERO', SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

CREATE database if NOT EXISTS `ssh_terminal` default character set utf8mb4 collate utf8mb4_0900_ai_ci;
use `ssh_terminal`;


# 转储表 chat_message
# ------------------------------------------------------------

DROP TABLE IF EXISTS `chat_message`;

CREATE TABLE `chat_message` (
                                `id` bigint NOT NULL AUTO_INCREMENT,
                                `session_id` varchar(64) NOT NULL COMMENT '会话ID',
                                `role` varchar(20) NOT NULL COMMENT '角色: user/assistant/tool/system',
                                `content` text COMMENT '消息内容',
                                `tool_name` varchar(100) DEFAULT NULL COMMENT '工具名称',
                                `tool_call_id` varchar(100) DEFAULT NULL COMMENT '工具调用ID',
                                `priority` varchar(20) DEFAULT 'MEDIUM' COMMENT '优先级: CRITICAL/HIGH/MEDIUM/LOW',
                                `token_count` int DEFAULT '0' COMMENT '预估 token 数',
                                `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                                PRIMARY KEY (`id`),
                                KEY `idx_session_time` (`session_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话消息';



# 转储表 chat_milestone
# ------------------------------------------------------------

DROP TABLE IF EXISTS `chat_milestone`;

CREATE TABLE `chat_milestone` (
                                  `id` bigint NOT NULL AUTO_INCREMENT,
                                  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
                                  `type` varchar(30) NOT NULL COMMENT '类型: TASK_CHANGE/ERROR/DECISION/...',
                                  `content` text COMMENT '内容摘要',
                                  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                                  PRIMARY KEY (`id`),
                                  KEY `idx_session_time` (`session_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话里程碑';



# 转储表 chat_session
# ------------------------------------------------------------

DROP TABLE IF EXISTS `chat_session`;

CREATE TABLE `chat_session` (
                                `id` varchar(64) NOT NULL COMMENT '会话ID',
                                `agent_id` varchar(64) NOT NULL COMMENT '智能体ID',
                                `user_id` varchar(64) NOT NULL COMMENT '用户ID',
                                `title` varchar(200) DEFAULT NULL COMMENT '会话标题',
                                `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                                `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                `message_count` int DEFAULT '0' COMMENT '消息数量',
                                PRIMARY KEY (`id`),
                                KEY `idx_user_agent` (`user_id`,`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话会话';



# 转储表 core_memory
# ------------------------------------------------------------

DROP TABLE IF EXISTS `core_memory`;

CREATE TABLE `core_memory` (
                               `id` bigint NOT NULL AUTO_INCREMENT,
                               `user_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '用户ID',
                               `scope` varchar(20) NOT NULL COMMENT '作用域: user/session',
                               `category` varchar(30) NOT NULL COMMENT '分类: Rule/Preference/Decision/Correction/Fact',
                               `title` varchar(200) NOT NULL COMMENT '记忆标题',
                               `keywords` varchar(500) DEFAULT NULL COMMENT '关键词(逗号分隔)',
                               `content` text COMMENT '记忆正文',
                               `priority` int NOT NULL DEFAULT '3' COMMENT '优先级1-5(越高越重要)',
                               `source_session_id` varchar(64) DEFAULT NULL COMMENT '来源会话ID',
                               `use_count` int NOT NULL DEFAULT '1' COMMENT '使用次数',
                               `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                               `last_used_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                               PRIMARY KEY (`id`),
                               KEY `idx_user_priority` (`user_id`,`priority`),
                               KEY `idx_user_last_used` (`user_id`,`last_used_at`),
                               KEY `idx_keywords` (`keywords`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='核心记忆表(对话内学习+跨会话记忆)';



# 转储表 long_term_memory
# ------------------------------------------------------------

DROP TABLE IF EXISTS `long_term_memory`;

CREATE TABLE `long_term_memory` (
                                    `id` bigint NOT NULL AUTO_INCREMENT,
                                    `user_id` varchar(64) NOT NULL COMMENT '用户ID',
                                    `session_id` varchar(64) DEFAULT NULL COMMENT '来源会话ID',
                                    `memory_type` varchar(50) NOT NULL COMMENT '记忆类型',
                                    `memory_key` varchar(128) NOT NULL COMMENT '记忆去重键',
                                    `content` text NOT NULL COMMENT '记忆内容',
                                    `keywords` varchar(512) DEFAULT NULL COMMENT '关键词集合',
                                    `source_role` varchar(20) DEFAULT NULL COMMENT '来源角色',
                                    `confidence` decimal(5,2) DEFAULT '0.50' COMMENT '置信度',
                                    `hit_count` int DEFAULT '1' COMMENT '命中/更新次数',
                                    `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                                    `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                    PRIMARY KEY (`id`),
                                    UNIQUE KEY `uk_user_type_key` (`user_id`,`memory_type`,`memory_key`),
                                    KEY `idx_user_time` (`user_id`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长期记忆';


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
                                  `user_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '用户ID',
                                  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
                                  PRIMARY KEY (`id`),
                                  UNIQUE KEY `uk_connection_id` (`connection_id`),
                                  KEY `idx_user_id` (`user_id`),
                                  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSH连接配置表';

LOCK TABLES `ssh_connection` WRITE;
/*!40000 ALTER TABLE `ssh_connection` DISABLE KEYS */;

INSERT INTO `ssh_connection` (`id`, `connection_id`, `connection_name`, `host`, `port`, `username`, `auth_type`, `password`, `private_key`, `encrypted`, `user_id`, `created_at`, `updated_at`, `deleted`)
VALUES
    (2,'0eb185a6c2964c50be6a8a4bf917c2c9','腾讯云服务器-测试机','140.143.183.225',22,'ubuntu',1,'nrF53G0MixGWLTWW9oWZTFkHY+OiH/7tlxkHNPyTKOXDVgzDKkKqJShf',NULL,1,'default','2026-05-02 11:32:04','2026-05-15 07:24:14',0),
    (4,'6779824e51c0424fb724a94df114104c','115.190.107.206','115.190.107.206',22,'root',1,'myUlBgwE+R6cmF0ToRfgtGimBMijQ/WPhqOC+9q1th21OepJS9o=',NULL,1,'default','2026-05-02 21:19:11','2026-05-13 08:41:19',0);

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
