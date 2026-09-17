package com.jasonlat.ai.domain.agent.service.util;

/**
 * @author jasonlat
 * 2026-09-17  19:45
 */
public class AgentUtils {

    public static boolean isAssistant(String role) {
        if (role == null || role.trim().isEmpty()) return false;
        return "assistant".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role);
    }
}
