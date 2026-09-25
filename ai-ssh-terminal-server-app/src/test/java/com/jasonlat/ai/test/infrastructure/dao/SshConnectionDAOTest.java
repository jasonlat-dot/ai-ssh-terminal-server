package com.jasonlat.ai.test.infrastructure.dao;

import com.jasonlat.ai.infrastructure.dao.ISshConnectionDAO;
import com.jasonlat.ai.infrastructure.dao.po.SshConnectionPO;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.List;

/**
 * SSH连接配置DAO测试
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class SshConnectionDAOTest {

    @Resource
    private ISshConnectionDAO sshConnectionDAO;

    /**
     * 测试：插入SSH连接配置
     */
    @Test
    public void test_insert() {
        // 构造测试数据
        SshConnectionPO po = SshConnectionPO.builder()
                .connectionId("test-insert-" + System.currentTimeMillis())
                .connectionName("测试连接-插入")
                .host("192.168.1.100")
                .port(22)
                .username("testuser")
                .authType(1)
                .password("testPassword123")
                .privateKey(null)
                .encrypted(1)
                .userId("default")
                .build();

        // 执行插入
        sshConnectionDAO.insert(po);

        // 验证自增主键已回填
        Assert.assertNotNull("主键不应为空", po.getId());
        log.info("测试结果：插入成功，主键ID:{}", po.getId());
    }

    /**
     * 测试：更新SSH连接配置
     */
    @Test
    public void test_update() {
        // 先插入一条数据
        String testConnectionId = "test-update-" + System.currentTimeMillis();
        SshConnectionPO insertPo = SshConnectionPO.builder()
                .connectionId(testConnectionId)
                .connectionName("测试连接-原始名称")
                .host("192.168.1.100")
                .port(22)
                .username("testuser")
                .authType(1)
                .password("testPassword123")
                .privateKey(null)
                .encrypted(1)
                .userId("default")
                .build();
        sshConnectionDAO.insert(insertPo);

        // 执行更新
        SshConnectionPO updatePo = SshConnectionPO.builder()
                .connectionId(testConnectionId)
                .connectionName("测试连接-更新后名称")
                .host("192.168.1.200")
                .port(2222)
                .username("updateduser")
                .authType(2)
                .password(null)
                .privateKey("-----BEGIN RSA PRIVATE KEY-----")
                .encrypted(1)
                .userId("default")
                .build();
        sshConnectionDAO.update(updatePo);

        // 查询验证
        SshConnectionPO queryResult = sshConnectionDAO.queryByConnectionId(testConnectionId);
        Assert.assertNotNull("查询结果不应为空", queryResult);
        Assert.assertEquals("连接名称应已更新", "测试连接-更新后名称", queryResult.getConnectionName());
        Assert.assertEquals("主机地址应已更新", "192.168.1.200", queryResult.getHost());
        Assert.assertEquals("端口应已更新", Integer.valueOf(2222), queryResult.getPort());
        Assert.assertEquals("用户名应已更新", "updateduser", queryResult.getUsername());
        Assert.assertEquals("认证类型应已更新", Integer.valueOf(2), queryResult.getAuthType());
        log.info("测试结果：更新成功");
    }

    /**
     * 测试：根据连接ID查询配置
     */
    @Test
    public void test_queryByConnectionId() {
        // 执行查询（查询数据库已存在的测试数据）
        SshConnectionPO result = sshConnectionDAO.queryByConnectionId("0eb185a6c2964c50be6a8a4bf917c2c9");

        // 验证结果
        Assert.assertNotNull("查询结果不应为空", result);
        Assert.assertEquals("连接ID应匹配", "0eb185a6c2964c50be6a8a4bf917c2c9", result.getConnectionId());
        Assert.assertEquals("连接名称应匹配", "腾讯云服务器-测试机", result.getConnectionName());
        Assert.assertEquals("主机地址应匹配", "140.143.183.225", result.getHost());
        Assert.assertEquals("端口应匹配", Integer.valueOf(22), result.getPort());
        Assert.assertEquals("用户名应匹配", "ubuntu", result.getUsername());

        log.info("测试结果：查询成功，连接名称:{}, 主机:{}", result.getConnectionName(), result.getHost());
    }

    /**
     * 测试：根据连接ID删除配置（逻辑删除）
     */
    @Test
    public void test_delete() {
        // 先插入一条数据
        String testConnectionId = "test-delete-" + System.currentTimeMillis();
        SshConnectionPO insertPo = SshConnectionPO.builder()
                .connectionId(testConnectionId)
                .connectionName("测试连接-删除测试")
                .host("192.168.1.100")
                .port(22)
                .username("testuser")
                .authType(1)
                .password("testPassword123")
                .privateKey(null)
                .encrypted(1)
                .userId("default")
                .build();
        sshConnectionDAO.insert(insertPo);

        // 验证数据存在
        SshConnectionPO beforeDelete = sshConnectionDAO.queryByConnectionId(testConnectionId);
        Assert.assertNotNull("删除前数据应存在", beforeDelete);
        Assert.assertEquals("删除前deleted应为0", Integer.valueOf(0), beforeDelete.getDeleted());

        // 执行删除（逻辑删除）
        sshConnectionDAO.delete(testConnectionId);

        // 验证逻辑删除生效（查询会自动过滤deleted=1的数据）
        SshConnectionPO afterDelete = sshConnectionDAO.queryByConnectionId(testConnectionId);
        Assert.assertNull("逻辑删除后查询结果应为空", afterDelete);

        log.info("测试结果：逻辑删除成功");
    }

    /**
     * 测试：根据用户ID查询配置列表
     */
    @Test
    public void test_queryListByUserId() {
        // 执行查询（查询数据库已存在的测试数据）
        List<SshConnectionPO> result = sshConnectionDAO.queryListByUserId("default");

        // 验证结果
        Assert.assertNotNull("查询结果列表不应为空", result);
        Assert.assertFalse("应至少有一条记录", result.isEmpty());

        // 验证所有记录的userId都是default
        for (SshConnectionPO po : result) {
            Assert.assertEquals("用户ID应匹配", "default", po.getUserId());
        }

        log.info("测试结果：查询成功，共{}条记录", result.size());
        result.forEach(po -> log.info("  - 连接:[{}], 主机:[{}], 用户名:[{}]",
                po.getConnectionName(), po.getHost(), po.getUsername()));
    }

}
