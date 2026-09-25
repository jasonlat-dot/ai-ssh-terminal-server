package com.jasonlat.ai.trigger.http;

import com.jasonlat.ai.domain.ssh.model.entity.TerminalSessionEntity;
import com.jasonlat.ai.domain.ssh.model.valobj.TerminalDisconnectReason;
import com.jasonlat.ai.domain.ssh.model.valobj.TerminalReadResult;
import com.jasonlat.ai.domain.ssh.model.valobj.TerminalTermination;
import com.jasonlat.ai.domain.ssh.service.ISshTerminalService;
import com.jasonlat.ai.trigger.api.dto.*;
import com.jasonlat.ai.trigger.api.response.Response;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.concurrent.CompletableFuture;

/**
 * SSH终端操作 HTTP 控制器
 * 提供终端会话的打开、原始I/O读写、大小调整、关闭等能力
 *
 * @author waissh dev
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ssh/terminal")
@CrossOrigin(origins = "*")
public class SshTerminalController implements com.jasonlat.ai.trigger.api.ISshTerminalService {

    @Resource
    private ISshTerminalService sshTerminalDomainService;

    @RequestMapping(value = "open", method = RequestMethod.POST)
    public Response<TerminalOpenResponseDTO> openTerminal(@RequestBody TerminalOpenRequestDTO requestDTO) {
        try {
            log.info("打开终端会话 connectionId={}", requestDTO.getConnectionId());

            int cols = requestDTO.getCols() != null ? requestDTO.getCols() : 120;
            int rows = requestDTO.getRows() != null ? requestDTO.getRows() : 24;

            TerminalSessionEntity entity = sshTerminalDomainService.openTerminal(
                    requestDTO.getConnectionId(), cols, rows);

            // 等待 MOTD 积累完后 drain 缓冲区，作为 initialOutput 返回
            // 这样前端不依赖轮询获取初始输出，避免时序问题导致"有时显示有时不显示"
            String initialOutput = waitForInitialOutput(entity.getSessionId(), 2000);

            TerminalOpenResponseDTO response = TerminalOpenResponseDTO.builder()
                    .sessionId(entity.getSessionId())
                    .connectionId(entity.getConnectionId())
                    .initialOutput(initialOutput)
                    .build();

            return Response.<TerminalOpenResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();
        } catch (AppException e) {
            log.warn("打开终端会话参数异常: {}", e.getMessage());
            return Response.<TerminalOpenResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getMessage())
                    .build();
        }catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("打开终端会话参数错误: {}", e.getMessage());
            return Response.<TerminalOpenResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("打开终端会话失败", e);
            return Response.<TerminalOpenResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("打开终端失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 等待并收集 Shell 初始输出（Last login + MOTD + prompt）
     * openTerminal 已等首数据+200ms，这里只需 drain 缓冲区
     * 不做换行符转换，xterm.js 自己处理 \r 和 \n
     */
    private String waitForInitialOutput(String sessionId, long timeoutMs) {
        // drain 缓冲区：openTerminal 已等待首数据+200ms，MOTD 应该已完整
        String output = sshTerminalDomainService.readTerminal(sessionId);
        if (output == null || output.isEmpty()) {
            return "";
        }

        // 额外 drain 一次，确保残余数据也拿到
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }
        String more = sshTerminalDomainService.readTerminal(sessionId);
        if (more != null && !more.isEmpty()) {
            output += more;
        }

        return output;
    }

    // 暂时不暴露给http调用
//    @RequestMapping(value = "exec", method = RequestMethod.POST)
    public Response<TerminalExecResponseDTO> execCommand(@RequestBody TerminalExecRequestDTO requestDTO) {
        try {
            log.info("执行SSH命令，sessionId:{} command:{}", requestDTO.getSessionId(), requestDTO.getCommand());
            String output = sshTerminalDomainService.executeCommand(
                    requestDTO.getSessionId(), requestDTO.getCommand());

            TerminalExecResponseDTO response = TerminalExecResponseDTO.builder()
                    .output(output)
                    .build();

            return Response.<TerminalExecResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();
        } catch (AppException e) {
            log.warn("执行命令参数错误: {}", e.getMessage());
            return Response.<TerminalExecResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("执行命令失败 sessionId={}", requestDTO.getSessionId(), e);
            return Response.<TerminalExecResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("执行命令失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "write", method = RequestMethod.POST)
    public Response<Void> writeToTerminal(@RequestBody TerminalWriteRequestDTO requestDTO) {
        try {
            sshTerminalDomainService.writeTerminal(requestDTO.getSessionId(), requestDTO.getInput());
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (AppException e) {
            log.warn("写入终端参数错误: {}", e.getMessage());
            return Response.<Void>builder()
                    .code(e.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("写入终端失败 sessionId={}", requestDTO.getSessionId(), e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("写入终端失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "read11", method = RequestMethod.GET)
    public Response<TerminalReadResponseDTO> readFromTerminal(@RequestParam("sessionId") String sessionId) {
        try {
            String output = sshTerminalDomainService.readTerminal(sessionId);
            TerminalReadResponseDTO response = TerminalReadResponseDTO.builder()
                    .output(output != null ? output : "")
                    .build();
            return Response.<TerminalReadResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();
        } catch (AppException appException) {
            log.warn("读取终端参数错误: {}", appException.getMessage());
            return Response.<TerminalReadResponseDTO>builder()
                    .code(appException.getCode())
                    .info(appException.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("读取终端失败 sessionId={}", sessionId, e);
            return Response.<TerminalReadResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("读取终端失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 页签级连接状态查询。connectionId 对应的底层 SSH Session 可以被多个页签复用，
     * 因此页面只能用自己持有的 terminalSessionId 判断是否已连接。会话已结束时同时
     * 返回终止原因，让页面决定自动重连还是等待用户手动操作。
     */
    /**
     * 查询当前页签持有的终端会话是否仍然连接。
     *
     * <p>这里必须使用 terminalSessionId 查询，不能只使用 connectionId。</p>
     *
     * <p>多个浏览器页签可能复用同一个底层 SSH Session，但每个页签拥有独立的
     * ChannelShell 和 terminalSessionId。因此，底层 SSH Session 仍然存在，并不代表
     * 当前页签对应的终端 Channel 仍然可用。</p>
     *
     * <p>会话已经结束时，接口会同时返回：</p>
     * <ul>
     *     <li>disconnectReason：终端断开的具体原因；</li>
     *     <li>reconnectAllowed：前端是否允许自动重新创建终端。</li>
     * </ul>
     *
     * @param sessionId 当前页签持有的 terminalSessionId
     * @return 当前终端的连接状态、断开原因和自动重连策略
     */
    @Override
    @RequestMapping(value = "connected", method = RequestMethod.GET)
    public Response<TerminalConnectionStateDTO> isTerminalConnected(@RequestParam("sessionId") String sessionId) {

        /*
         * 从领域层缓存中获取终端实体。
         *
         * 这里获取 entity 的目的不是直接判断终端是否连接，而是：
         *
         * 1. 获取当前终端对应的 connectionId；
         * 2. 判断领域层是否仍保留该终端的信息；
         * 3. 当底层 Channel 已经断开，但清理任务尚未删除领域实体时，可以将断开原因识别为 CHANNEL_DISCONNECTED；
         * 4. 构建返回给前端的状态 DTO。
         *
         * entity 不为空只能说明领域缓存中仍有这条记录，不能说明底层 SSH Channel 当前仍然正常。
         */
        TerminalSessionEntity entity = sshTerminalDomainService.getTerminalSession(sessionId);

        /*
         * 检查终端是否真实连接。
         *
         * sessionExists 不只是检查 TerminalSessionEntity 是否存在，还会继续检查：
         *
         * 1. 领域实体是否处于 active 状态；
         * 2. 基础设施层是否仍存在对应的 TerminalSessionContext；
         * 3. TerminalSessionContext 是否已经进入 closed 状态；
         * 4. 对应的 SSH ChannelShell 是否仍然 connected。
         *
         * 因此这里不能简单写成：
         *
         *     boolean connected = entity != null;
         *
         * 因为可能出现领域实体仍在，但网络已经断开、Channel 已失效，
         * 清理任务还未来得及删除领域实体的情况。
         */
        boolean connected = sshTerminalDomainService.sessionExists(sessionId);

        /*
         * 只有终端已经断开时，才需要查询短期终止记录。
         *
         * 终止记录用于保存终端最后一次关闭的原因，例如：
         *
         * - IDLE_TIMEOUT：长时间没有有效交互，被后端空闲清理；
         * - CLIENT_CLOSED：前端主动关闭终端；
         * - CHANNEL_DISCONNECTED：SSH Channel 因网络异常断开；
         * - READER_ERROR：终端输出 Reader 异常退出。
         *
         * 如果终端仍然连接，就不需要查询终止记录，避免历史记录影响当前状态。
         */
        TerminalTermination termination = connected ? null : sshTerminalDomainService.getTerminalTermination(sessionId);

        /*
         * 根据当前状态确定最终返回给前端的断开原因。
         *
         * 判断优先级如下：
         *
         * 1. connected == true
         *    当前终端和底层 Channel 均正常，不返回断开原因。
         *
         * 2. termination != null
         *    后端保存了明确的终止记录，优先使用记录中的真实原因。
         *
         * 3. entity != null
         *    领域实体仍然存在，但是 sessionExists 返回 false，说明底层
         *    TerminalSessionContext 或 SSH Channel 已经不可用，按CHANNEL_DISCONNECTED 处理。
         *
         * 4. entity == null && termination == null
         *    领域实体不存在，并且短期终止记录也不存在。可能是：
         *
         *    - sessionId 本身无效；
         *    - 终止记录已经超过 TTL；
         *    - 后端已经重启，内存中的终止记录丢失；
         *    - 前端仍然持有一个很早以前的 sessionId。
         *
         *    这种情况返回 SESSION_NOT_FOUND。
         */
        TerminalDisconnectReason disconnectReason;

        if (connected) {
            disconnectReason = null;
        } else if (termination != null) {
            disconnectReason = termination.getReason();
        } else if (entity != null) {
            disconnectReason = TerminalDisconnectReason.CHANNEL_DISCONNECTED;
        } else {
            disconnectReason = TerminalDisconnectReason.SESSION_NOT_FOUND;
        }

        /*
         * connectionId 优先从仍然存在的领域实体中获取。
         *
         * 如果领域实体已经被清理，则尝试从短期终止记录中获取 connectionId，
         * 方便前端在自动重连时继续使用原来的连接配置。
         *
         * 如果 entity 和 termination 都不存在，说明后端已经无法确认这个
         * terminalSessionId 原来属于哪个 connectionId，此时返回 null。
         */
        String connectionId;

        if (entity != null) {
            connectionId = entity.getConnectionId();
        } else if (termination != null) {
            connectionId = termination.getConnectionId();
        } else {
            connectionId = null;
        }

        /*
         * 构建页签级终端连接状态。
         * connected：表示当前 terminalSessionId 对应的 ChannelShell 是否仍然可用。
         * disconnectReason：表示终端为什么断开。仍然连接时返回 null。
         * reconnectAllowed：表示前端是否允许自动重连。该策略由后端根据断开原因统一决定，前端不需要再次根据字符串推断。
         * 例如：
         * CHANNEL_DISCONNECTED -> true 网络波动导致的异常断开，可以自动重连。
         * IDLE_TIMEOUT -> false 后端主动执行了空闲回收，不应该立即自动重连，否则会和空闲清理冲突。
         * CLIENT_CLOSED -> false 用户主动关闭，不应该自动恢复。
         * SESSION_NOT_FOUND -> false 后端无法确认旧会话状态，避免前端无限创建新终端。
         */
        TerminalConnectionStateDTO state = TerminalConnectionStateDTO.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
                .connected(connected)
                .disconnectReason(disconnectReason == null ? null : disconnectReason.name())
                .reconnectAllowed(disconnectReason != null && disconnectReason.isReconnectAllowed())
                .build();

        /*
         * 查询接口本身执行成功时统一返回 SUCCESS。
         *
         * 这里的 SUCCESS 只表示“连接状态查询成功”，不代表终端一定处于连接状态。
         * 终端是否连接需要由 data.connected 判断。
         */
        return Response.<TerminalConnectionStateDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(state)
                .build();
    }

    @RequestMapping(value = "read", method = RequestMethod.GET)
    public CompletableFuture<Response<TerminalReadResultDTO>> readAsyncFromTerminal(@RequestParam("sessionId") String sessionId) {
        try {
            CompletableFuture<TerminalReadResult> completableFuture = sshTerminalDomainService.readTerminalAsync(sessionId);
            // thenApply：异步完成后，把结果转换DTO
            return completableFuture.handle((readResult, throwable) -> {
                if (throwable != null) {
                    log.error("Terminal Long Poll 异步读取失败 sessionId={}", sessionId, throwable);
                    return Response.<TerminalReadResultDTO>builder()
                            .code(ResponseCode.UN_ERROR.getCode())
                            .info("读取终端失败: " + throwable.getMessage())
                            .build();
                }

                TerminalReadResultDTO response = new TerminalReadResultDTO();
                response.setOutput(readResult.getData());
                response.setStatus(readResult.getStatus().toString());
                response.setHasData(readResult.isHasData());
                response.setConnected(readResult.isConnected());
                response.setEof(readResult.isEof());
                response.setTimeout(readResult.isTimeout());
                response.setBufferOverflow(readResult.isBufferOverflow());
                // status 描述本次读取结果，disconnectReason/reconnectAllowed 描述后续处理策略。
                response.setDisconnectReason(readResult.getDisconnectReason() == null
                        ? null : readResult.getDisconnectReason().name());
                response.setReconnectAllowed(readResult.isReconnectAllowed());

                return Response.<TerminalReadResultDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(response)
                        .build();
            });
        } catch (AppException e) {
            /*
             * 极小竞态下，会话可能在领域层检查后、基础设施读取前刚好被清理。
             * 客户端收到 S0003 后应查询 /connected 获取断开原因，不能直接自动重连。
             */
            log.warn("Terminal Long Poll 会话不可用 sessionId={} code={} reason={}",
                    sessionId, e.getCode(), e.getMessage());
            return CompletableFuture.completedFuture(
                    Response.<TerminalReadResultDTO>builder()
                            .code(e.getCode())
                            .info(e.getMessage())
                            .build()
            );
        } catch (Exception e) {
            log.error("Terminal Long Poll 创建失败 sessionId={}", sessionId, e);
            return CompletableFuture.completedFuture(
                    Response.<TerminalReadResultDTO>builder()
                            .code(ResponseCode.UN_ERROR.getCode())
                            .info("读取终端失败: " + e.getMessage())
                            .build()
            );
        }
    }

    @RequestMapping(value = "resize", method = RequestMethod.POST)
    public Response<Void> resizeTerminal(@RequestBody TerminalResizeRequestDTO requestDTO) {
        try {
            sshTerminalDomainService.resizeTerminal(
                    requestDTO.getSessionId(), requestDTO.getCols(), requestDTO.getRows());

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("调整终端大小失败 sessionId={}", requestDTO.getSessionId(), e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("调整终端大小失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "close", method = RequestMethod.POST)
    public Response<Void> closeTerminal(@RequestParam("sessionId") String sessionId) {
        try {
            log.info("关闭终端会话 sessionId={}", sessionId);
            sshTerminalDomainService.closeTerminal(sessionId);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("关闭终端会话失败 sessionId={}", sessionId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("关闭终端会话失败: " + e.getMessage())
                    .build();
        }
    }

}
