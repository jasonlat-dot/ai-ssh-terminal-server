-- Fresh-volume schema only: no DROP, no sample connections, no credentials.
-- Derived from docs/dev-ops/mysql/1-4-ssh_terminal.sql and docs/dev-ops/mysql/2-14-file-upload.sql.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `chat_message` (
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

CREATE TABLE IF NOT EXISTS `chat_milestone` (
                                  `id` bigint NOT NULL AUTO_INCREMENT,
                                  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
                                  `type` varchar(30) NOT NULL COMMENT '类型: TASK_CHANGE/ERROR/DECISION/...',
                                  `content` text COMMENT '内容摘要',
                                  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                                  PRIMARY KEY (`id`),
                                  KEY `idx_session_time` (`session_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话里程碑';

CREATE TABLE IF NOT EXISTS `chat_session` (
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

CREATE TABLE IF NOT EXISTS `core_memory` (
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

CREATE TABLE IF NOT EXISTS `long_term_memory` (
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

CREATE TABLE IF NOT EXISTS `ssh_connection` (
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

CREATE TABLE IF NOT EXISTS `ssh_connection_config` (
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

CREATE TABLE IF NOT EXISTS file_asset (
    file_id varchar(36) NOT NULL COMMENT '服务端生成的稳定文件ID',
    owner_id varchar(128) DEFAULT NULL COMMENT '认证Principal；当前未接入登录时为空',
    original_name varchar(255) NOT NULL COMMENT '展示文件名，不作为存储路径',
    content_type varchar(127) NOT NULL COMMENT '客户端声明类型，不代表内容已校验',
    size_bytes bigint NOT NULL COMMENT '文件字节数',
    sha256 char(64) DEFAULT NULL COMMENT '服务端读取流时计算的SHA-256',
    storage_id varchar(64) NOT NULL COMMENT '具体存储实例，例如minio-main',
    bucket varchar(63) NOT NULL,
    object_key varchar(512) NOT NULL,
    object_version varchar(255) DEFAULT NULL,
    etag varchar(255) DEFAULT NULL,
    status varchar(32) NOT NULL COMMENT 'UPLOADING/UPLOADED/FAILED/CLEANUP_REQUIRED',
    error_code varchar(64) DEFAULT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (file_id),
    UNIQUE KEY uk_storage_object (storage_id, bucket, object_key),
    KEY idx_file_status_time (status, updated_at),
    KEY idx_file_owner_time (owner_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='独立文件上传元数据';
