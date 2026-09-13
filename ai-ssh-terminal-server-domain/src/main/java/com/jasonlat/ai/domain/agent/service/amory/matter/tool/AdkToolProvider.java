package com.jasonlat.ai.domain.agent.service.amory.matter.tool;

import com.google.adk.tools.FunctionTool;

import java.util.List;

public interface AdkToolProvider {

    List<FunctionTool> getTools();
}