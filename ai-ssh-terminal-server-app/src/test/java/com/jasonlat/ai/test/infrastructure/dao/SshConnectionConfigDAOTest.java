package com.jasonlat.ai.test.infrastructure.dao;

import com.jasonlat.ai.infrastructure.dao.ISshConnectionConfigDAO;
import com.jasonlat.ai.infrastructure.dao.po.SshConnectionConfigPO;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

/**
 * SSH连接高级配置DAO测试
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class SshConnectionConfigDAOTest {

    @Resource
    private ISshConnectionConfigDAO sshConnectionConfigDAO;

    /**
     * 测试：插入或更新SSH连接高级配置
     */
    @Test
    public void test_insertOrUpdate() {
        // 构造测试数据
        SshConnectionConfigPO po = SshConnectionConfigPO.builder()
                .connectionId("0eb185a6c2964c50be6a8a4bf917c2c9")
                .connectTimeout(30)
                .keepaliveInterval(60)
                .startupCommand(null)
                .compression(0)
                .strictHostKeyCheck(1)
                .knownHosts(null)
                .build();

        // 执行插入或更新
        sshConnectionConfigDAO.insertOrUpdate(po);

        log.info("测试结果：插入或更新成功");
    }

    /**
     * 测试：根据连接ID查询高级配置
     */
    @Test
    public void test_queryByConnectionId() {
        // 执行查询
        SshConnectionConfigPO result = sshConnectionConfigDAO.queryByConnectionId("0eb185a6c2964c50be6a8a4bf917c2c9");

        // 验证结果
        Assert.assertNotNull("查询结果不应为空", result);
        Assert.assertEquals("连接ID应匹配", "0eb185a6c2964c50be6a8a4bf917c2c9", result.getConnectionId());
        Assert.assertEquals("连接超时时间应匹配", Integer.valueOf(30), result.getConnectTimeout());
        Assert.assertEquals("保活间隔应匹配", Integer.valueOf(60), result.getKeepaliveInterval());

        log.info("测试结果：查询成功，配置信息 - connectTimeout:{}, keepaliveInterval:{}",
                result.getConnectTimeout(), result.getKeepaliveInterval());
    }

    /**
     * 测试：插入新配置后查询验证
     */
    @Test
    public void test_insertOrUpdate_and_query() {
        // 构造新测试数据（使用UUID作为connectionId避免冲突）
        String testConnectionId = "test-" + System.currentTimeMillis();
        SshConnectionConfigPO po = SshConnectionConfigPO.builder()
                .connectionId(testConnectionId)
                .connectTimeout(60)
                .keepaliveInterval(120)
                .startupCommand("echo 'Hello World'")
                .compression(1)
                .strictHostKeyCheck(0)
                .knownHosts("github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl")
                .build();

        // 插入配置
        sshConnectionConfigDAO.insertOrUpdate(po);
        log.info("测试结果：插入新配置成功，connectionId:{}", testConnectionId);

        // 查询验证
        SshConnectionConfigPO queryResult = sshConnectionConfigDAO.queryByConnectionId(testConnectionId);
        Assert.assertNotNull("查询结果不应为空", queryResult);
        Assert.assertEquals("连接ID应匹配", testConnectionId, queryResult.getConnectionId());
        Assert.assertEquals("连接超时时间应匹配", Integer.valueOf(60), queryResult.getConnectTimeout());
        Assert.assertEquals("保活间隔应匹配", Integer.valueOf(120), queryResult.getKeepaliveInterval());
        Assert.assertEquals("启动命令应匹配", "echo 'Hello World'", queryResult.getStartupCommand());
        Assert.assertEquals("压缩标志应匹配", Integer.valueOf(1), queryResult.getCompression());
        Assert.assertEquals("严格主机密钥检查应匹配", Integer.valueOf(0), queryResult.getStrictHostKeyCheck());

        log.info("测试结果：插入并查询验证成功");
    }

}
