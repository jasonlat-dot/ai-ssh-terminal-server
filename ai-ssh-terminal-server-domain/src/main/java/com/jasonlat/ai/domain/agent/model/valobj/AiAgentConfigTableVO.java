package com.jasonlat.ai.domain.agent.model.valobj;

import lombok.Data;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author jasonlat
 * 2026-03-31  19:23
 */
@Data
public class AiAgentConfigTableVO {

    /** 应用名称 */
    private String appName;

    /** 智能体基本属性配置 */
    private AgentDefinition  agentDefinition;

    /** 智能体模块 */
    private Module module;

    /** session 过期时间, 默认一小时 */
    private long sessionExpireSeconds = 3600L;

    @Data
    public static class AgentDefinition  {

        /** 智能体ID */
        private String agentId;

        /** 智能体名称 */
        private String agentName;

        /** 智能体描述 */
        private String agentDesc;

    }

    @Data
    public static class Module {

        /** 默认的 AI API 配置 */
        private AiApi aiApi = new AiApi();

        /**
         * 默认的 智能体对话模型配置
         */
        private ChatModel chatModel = new ChatModel();

        /** 智能体 */
        private List<Agent> llmAgents;

        /** 智能体编排配置 */
        private List<AgentWorkflow> agentWorkflows;

        /** 运行器 */
        private Runner runner;

        @Data
        public static class AiApi {
            private String baseUrl;
            private String apiKey;
            private String completionsPath = "/v1/chat/completions";
            private String embeddingsPath = "/v1/embeddings";
            private ProxySettings proxy = new ProxySettings();

            @Data
            public static class ProxySettings {
                private boolean enabled;
                private String host;
                private int port;
                private List<String> nonProxyHosts = new ArrayList<>();
            }
        }

        @Data
        public static class Agent {
            /** 名称 */
            private String name;
            /** 智能体指令 */
            private String instruction;
            /** 描述 */
            private String description;
            /** 输出参数 */
            private String outputKey;

            private List<String> subAgents;

            /** 自定义 - AI API 配置 可为空，不配置就取默认 */
            private AiApi aiApi;

            /**
             * 自定义 - 智能体对话模型配置 可为空，不配置就取默认
             */
            private ChatModel chatModel;
        }

        @Data
        public static class AgentWorkflow {
            /** 类型；loop、parallel、sequential */
            private String type;
            /** 名称 */
            private String name;
            /** 子智能体 */
            private List<String> subAgents;
            /** 描述 */
            private String description;
            /** 最大迭代次数 */
            private Integer maxIterations = 3;

        }

        @Data
        public static class ChatModel {
            /** 模型名称 */
            private String model;

            public String getModelOrDefault(String options) {
                if (StringUtils.isNotBlank(model)) return model;
                return options;
            }

            /**
             * 推理强度；minimal、low、medium、high（仅推理模型生效，非推理模型忽略）
             */
            private String reasoningEffort = "medium";
            /** 模型及实际接入网关支持的媒体 MIME；默认空列表，仅接收文字及转换后的文本文件。 */
            private List<String> supportedMediaTypes = new ArrayList<>();
            /** mcp列表 */
            private List<ToolMcp> toolMcpList = new ArrayList<>(4);
            /** Skills */
            private List<ToolSkills> toolSkillsList = new ArrayList<>(4);

            @Data
            public static class ToolSkills {
                // resource
                private String type = "directory";
                private String rootPath;
            }

            @Data
            public static class ToolMcp {
                /** SSE服务参数 */
                private SSEServerParameters sse;
                /** stdio服务参数 */
                private StdioServerParameters stdio;

                private LocalParameters local;

                @Data
                public static class SSEServerParameters {
                    /** 服务名称 */
                    private String name;
                    /** 服务地址 */
                    private String baseUri;
                    /** SSE服务端点 */
                    private String sseEndpoint;
                    /** 请求超时时间 */
                    private Integer requestTimeout = 3000;

                }

                @Data
                public static class StdioServerParameters {
                    /** 服务名称 */
                    private String name;
                    /** 请求超时时间 */
                    private Integer requestTimeout = 3000;
                    /** 服务参数 */
                    private ServerParameters serverParameters;

                    @Data
                    public static class ServerParameters {
                        /** 命令 */
                        private String command;
                        /** 参数 */
                        private List<String> args;
                        /** 环境变量 */
                        private Map<String, String> env;

                    }
                }

                @Data
                public static class LocalParameters {
                    private String beanName;
                }

            }
        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList = new ArrayList<>(4);
        }
    }
}
