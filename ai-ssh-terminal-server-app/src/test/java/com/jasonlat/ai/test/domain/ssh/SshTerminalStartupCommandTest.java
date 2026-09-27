package com.jasonlat.ai.test.domain.ssh;

import com.jasonlat.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.jasonlat.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import com.jasonlat.ai.domain.ssh.adapter.repository.ISshConnectionRepository;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.service.terminal.SshTerminalService;
import org.junit.Test;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SshTerminalStartupCommandTest {

    @Test
    @SuppressWarnings("unchecked")
    public void shouldSendConfiguredCommandAfterOpeningShell() {
        ISshSessionPort ssh = mock(ISshSessionPort.class);
        ITerminalSessionPort terminal = mock(ITerminalSessionPort.class);
        ISshConnectionRepository repository = mock(ISshConnectionRepository.class);
        SshTerminalService service = new SshTerminalService(ssh, terminal, repository);

        when(ssh.withConnectionLock(eq("connection-1"), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(ssh.isConnected("connection-1")).thenReturn(true);
        when(repository.queryConnectionById("connection-1"))
                .thenReturn(SshConnectionEntity.builder().connectionId("connection-1").userId("user-1").build());
        when(repository.queryConnectionConfigById("connection-1"))
                .thenReturn(SshConnectionConfigEntity.builder().startupCommand("  cd /opt/app  ").build());
        when(terminal.openTerminal("user-1", "connection-1", 120, 30)).thenReturn("terminal-1");

        service.openTerminal("connection-1", 120, 30);

        verify(terminal).write("terminal-1", "cd /opt/app\r");
    }
}
