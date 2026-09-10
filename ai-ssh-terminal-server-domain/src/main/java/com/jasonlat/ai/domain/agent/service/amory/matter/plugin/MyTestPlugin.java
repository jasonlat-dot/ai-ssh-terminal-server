package com.jasonlat.ai.domain.agent.service.amory.matter.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author jasonlat
 * 2026-04-03  20:46
 */
@Slf4j
@Service("myTestPlugin")
public class MyTestPlugin extends BasePlugin {

    public MyTestPlugin() {
        super("MyTestPlugin");
    }

    @Override
    public Maybe<Content> onUserMessageCallback(InvocationContext invocationContext, Content userMessage) {
        log.info("用户输入信息：{}", userMessage.text());
        return super.onUserMessageCallback(invocationContext, userMessage);
    }

    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        log.info("before - 开始执行智能体：{}", agent.name());
        return super.beforeAgentCallback(agent, callbackContext);
    }

//    @Override
//    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest llmRequest) {
//        log.info("before - AI模型 {}", llmRequest.model());
//        return super.beforeModelCallback(callbackContext, llmRequest);
//    }

//    @Override
//    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
//        log.info("before - AI模型 {}", llmRequest.build().model());
//        return super.beforeModelCallback(callbackContext, llmRequest);
//    }
}
