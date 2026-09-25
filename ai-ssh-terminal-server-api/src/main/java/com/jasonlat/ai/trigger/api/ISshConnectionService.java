package com.jasonlat.ai.trigger.api;

import com.jasonlat.ai.trigger.api.dto.SshConnectionRequestDTO;
import com.jasonlat.ai.trigger.api.dto.SshConnectionResponseDTO;
import com.jasonlat.ai.trigger.api.response.Response;

import java.util.List;

/**
 * SSH连接服务远程接口
 */
public interface ISshConnectionService {

    /**
     * 创建SSH连接
     */
    Response<SshConnectionResponseDTO> createConnection(SshConnectionRequestDTO requestDTO);

    /**
     * 更新SSH连接
     */
    Response<SshConnectionResponseDTO> updateConnection(SshConnectionRequestDTO requestDTO);

    /**
     * 删除SSH连接
     */
    Response<Void> deleteConnection(String connectionId);

    /**
     * 查询单个连接
     */
    Response<SshConnectionResponseDTO> getConnection(String connectionId);

    /**
     * 查询用户的所有连接
     */
    Response<List<SshConnectionResponseDTO>> getConnectionList(String userId);

    /**
     * 建立SSH连接
     */
    Response<Void> connect(String connectionId);

}
