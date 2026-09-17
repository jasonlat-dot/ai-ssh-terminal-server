package com.jasonlat.ai.domain.agent.service.amory.matter.tool;

import com.google.adk.tools.BaseTool;

import java.util.List;

public interface AdkToolProvider {

    List<? extends BaseTool> getTools();
}
