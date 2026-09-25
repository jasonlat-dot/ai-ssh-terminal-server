package com.jasonlat.ai.test.domain.ssh;

import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.valobj.AuthTypeEnum;
import com.jasonlat.ai.domain.ssh.service.ISshConnectionService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.List;

/**
 * SSH连接领域服务测试
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class SshConnectionDomainServiceTest {

    @Resource
    private ISshConnectionService sshConnectionDomainService;

    /**
     * 测试：创建SSH连接（密码认证 + 高级配置）
     */
    @Test
    public void test_createConnection_withConfig() {
        // 构造连接实体
        SshConnectionEntity entity = SshConnectionEntity.builder()
                .connectionName("测试连接-密码认证")
                .host("192.168.1.100")
                .port(22)
                .username("jasonlat")
                .authType(AuthTypeEnum.PASSWORD)
                .password("testPassword123")
                .userId("test-user")
                .build();

        // 构造高级配置
        SshConnectionConfigEntity configEntity = SshConnectionConfigEntity.builder()
                .connectTimeout(15)
                .keepaliveInterval(30)
                .startupCommand("echo hello")
                .compression(true)
                .strictHostKeyCheck(false)
                .build();

        // 执行创建
        sshConnectionDomainService.createConnection(entity, configEntity);

        // 验证连接ID已自动生成
        Assert.assertNotNull("连接ID不应为空", entity.getConnectionId());
        // 查询验证
        SshConnectionEntity result = sshConnectionDomainService.getConnection(entity.getConnectionId());
        Assert.assertNotNull("查询结果不应为空", result);
        Assert.assertEquals("连接名称应匹配", "测试连接-密码认证", result.getConnectionName());
        Assert.assertEquals("主机地址应匹配", "192.168.1.100", result.getHost());
        Assert.assertEquals("端口应匹配", Integer.valueOf(22), result.getPort());
        Assert.assertEquals("用户名应匹配", "jasonlat", result.getUsername());
        Assert.assertEquals("认证类型应匹配", AuthTypeEnum.PASSWORD, result.getAuthType());

        // 验证高级配置
        SshConnectionConfigEntity configResult = sshConnectionDomainService.getConnectionConfig(entity.getConnectionId());
        Assert.assertNotNull("高级配置不应为空", configResult);
        Assert.assertEquals("连接超时应匹配", Integer.valueOf(15), configResult.getConnectTimeout());
        Assert.assertEquals("心跳间隔应匹配", Integer.valueOf(30), configResult.getKeepaliveInterval());
        Assert.assertEquals("启动命令应匹配", "echo hello", configResult.getStartupCommand());
        Assert.assertEquals("压缩应匹配", Boolean.TRUE, configResult.getCompression());
        Assert.assertEquals("严格主机检查应匹配", Boolean.FALSE, configResult.getStrictHostKeyCheck());

        log.info("测试结果：创建连接成功，connectionId:{}", entity.getConnectionId());
    }

    /**
     * 测试：创建SSH连接（私钥认证 + 无高级配置）
     */
    @Test
    public void test_createConnection_privateKey_noConfig() {
        // 构造连接实体
        SshConnectionEntity entity = SshConnectionEntity.builder()
                .connectionName("测试连接-私钥认证")
                .host("10.0.0.1")
                .port(2222)
                .username("admin")
                .authType(AuthTypeEnum.PRIVATE_KEY)
                .privateKey("-----BEGIN RSA PRIVATE KEY-----\ntest-key-content\n-----END RSA PRIVATE KEY-----")
                .userId("test-user")
                .build();

        // 执行创建（不传高级配置）
        sshConnectionDomainService.createConnection(entity, null);

        // 验证
        Assert.assertNotNull("连接ID不应为空", entity.getConnectionId());
        SshConnectionEntity result = sshConnectionDomainService.getConnection(entity.getConnectionId());
        Assert.assertNotNull("查询结果不应为空", result);
        Assert.assertEquals("认证类型应为私钥", AuthTypeEnum.PRIVATE_KEY, result.getAuthType());
        Assert.assertEquals("端口应匹配", Integer.valueOf(2222), result.getPort());

        log.info("测试结果：私钥认证创建连接成功，connectionId:{}", entity.getConnectionId());
    }

    /**
     * 测试：创建SSH连接 - 必填字段校验
     */
    @Test(expected = IllegalArgumentException.class)
    public void test_createConnection_validateFail() {
        // 构造缺少必填字段的实体
        SshConnectionEntity entity = SshConnectionEntity.builder()
                .connectionName("")
                .host(null)
                .build();

        // 应抛出 IllegalArgumentException
        sshConnectionDomainService.createConnection(entity, null);
    }

    /**
     * 测试：更新SSH连接
     */
    @Test
    public void test_updateConnection() {
        // 先创建一条数据
        SshConnectionEntity createEntity = SshConnectionEntity.builder()
                .connectionName("测试连接-更新前")
                .host("192.168.1.100")
                .port(22)
                .username("jasonlat")
                .authType(AuthTypeEnum.PASSWORD)
                .password("oldPassword")
                .userId("jasonlat")
                .build();
        sshConnectionDomainService.createConnection(createEntity, null);
        String connectionId = createEntity.getConnectionId();

        // 构造更新实体（密码留空，保留原值）
        SshConnectionEntity updateEntity = SshConnectionEntity.builder()
                .connectionId(connectionId)
                .connectionName("测试连接-更新后")
                .host("192.168.1.200")
                .port(2222)
                .username("updateduser")
                .authType(AuthTypeEnum.PRIVATE_KEY)
                .password("")
                .privateKey("-----BEGIN RSA PRIVATE KEY-----\nnew-key\n-----END RSA PRIVATE KEY-----")
                .build();

        // 构造更新后的高级配置
        SshConnectionConfigEntity updateConfig = SshConnectionConfigEntity.builder()
                .connectTimeout(20)
                .keepaliveInterval(45)
                .build();

        // 执行更新
        sshConnectionDomainService.updateConnection(updateEntity, updateConfig);

        // 查询验证
        SshConnectionEntity result = sshConnectionDomainService.getConnection(connectionId);
        Assert.assertNotNull("查询结果不应为空", result);
        Assert.assertEquals("连接名称应已更新", "测试连接-更新后", result.getConnectionName());
        Assert.assertEquals("主机地址应已更新", "192.168.1.200", result.getHost());
        Assert.assertEquals("端口应已更新", Integer.valueOf(2222), result.getPort());
        Assert.assertEquals("用户名应已更新", "updateduser", result.getUsername());
        Assert.assertEquals("认证类型应已更新", AuthTypeEnum.PRIVATE_KEY, result.getAuthType());
        // 密码留空时应保留原值
        Assert.assertEquals("密码应保留原值", "oldPassword", result.getPassword());

        // 验证高级配置已更新
        SshConnectionConfigEntity configResult = sshConnectionDomainService.getConnectionConfig(connectionId);
        Assert.assertNotNull("高级配置不应为空", configResult);
        Assert.assertEquals("连接超时应已更新", Integer.valueOf(20), configResult.getConnectTimeout());
        Assert.assertEquals("心跳间隔应已更新", Integer.valueOf(45), configResult.getKeepaliveInterval());

        log.info("测试结果：更新连接成功，connectionId:{}", connectionId);
    }

    /**
     * 测试：更新不存在的连接
     */
    @Test(expected = IllegalArgumentException.class)
    public void test_updateConnection_notExist() {
        // 构造不存在的连接ID
        SshConnectionEntity entity = SshConnectionEntity.builder()
                .connectionId("not-exist-connection-id")
                .connectionName("不存在的连接")
                .host("192.168.1.100")
                .port(22)
                .username("testuser")
                .build();

        // 应抛出 IllegalArgumentException
        sshConnectionDomainService.updateConnection(entity, null);
    }

    /**
     * 测试：删除SSH连接
     */
    @Test
    public void test_deleteConnection() {
        // 先创建一条数据
        SshConnectionEntity entity = SshConnectionEntity.builder()
                .connectionName("测试连接-待删除")
                .host("192.168.1.100")
                .port(22)
                .username("testuser")
                .authType(AuthTypeEnum.PASSWORD)
                .password("testPassword123")
                .userId("test-user")
                .build();
        sshConnectionDomainService.createConnection(entity, null);
        String connectionId = entity.getConnectionId();

        // 验证数据存在
        SshConnectionEntity beforeDelete = sshConnectionDomainService.getConnection(connectionId);
        Assert.assertNotNull("删除前数据应存在", beforeDelete);

        // 执行删除
        sshConnectionDomainService.deleteConnection(connectionId);

        // 验证删除后查询为空
        SshConnectionEntity afterDelete = sshConnectionDomainService.getConnection(connectionId);
        Assert.assertNull("删除后查询结果应为空", afterDelete);

        log.info("测试结果：删除连接成功，connectionId:{}", connectionId);
    }

    /**
     * 测试：删除连接 - 连接ID为空
     */
    @Test(expected = IllegalArgumentException.class)
    public void test_deleteConnection_emptyId() {
        // 应抛出 IllegalArgumentException
        sshConnectionDomainService.deleteConnection("");
    }

    /**
     * 测试：查询单个连接
     */
    @Test
    public void test_getConnection() {
        // 先创建一条数据
        SshConnectionEntity entity = SshConnectionEntity.builder()
                .connectionName("测试连接-查询测试")
                .host("140.143.183.225")
                .port(22)
                .username("ubuntu")
                .authType(AuthTypeEnum.PASSWORD)
                .password("testPassword")
                .userId("test-user")
                .build();
        sshConnectionDomainService.createConnection(entity, null);
        String connectionId = entity.getConnectionId();

        // 执行查询
        SshConnectionEntity result = sshConnectionDomainService.getConnection(connectionId);

        // 验证
        Assert.assertNotNull("查询结果不应为空", result);
        Assert.assertEquals("连接ID应匹配", connectionId, result.getConnectionId());
        Assert.assertEquals("连接名称应匹配", "测试连接-查询测试", result.getConnectionName());
        Assert.assertEquals("主机地址应匹配", "140.143.183.225", result.getHost());
        Assert.assertEquals("端口应匹配", Integer.valueOf(22), result.getPort());
        Assert.assertEquals("用户名应匹配", "ubuntu", result.getUsername());
        Assert.assertEquals("认证类型应匹配", AuthTypeEnum.PASSWORD, result.getAuthType());
        log.info("测试结果：查询连接成功，connectionId:{}, name:{}", result.getConnectionId(), result.getConnectionName());
    }

    /**
     * 测试：查询不存在的连接
     */
    @Test
    public void test_getConnection_notExist() {
        SshConnectionEntity result = sshConnectionDomainService.getConnection("not-exist-id");
        Assert.assertNull("查询不存在的连接应返回null", result);
        log.info("测试结果：查询不存在的连接返回null");
    }

    /**
     * 测试：查询用户的所有连接
     */
    @Test
    public void test_getConnectionList() {
        // 先创建两条数据
        SshConnectionEntity entity1 = SshConnectionEntity.builder()
                .connectionName("测试连接-列表1")
                .host("192.168.1.100")
                .port(22)
                .username("user1")
                .authType(AuthTypeEnum.PASSWORD)
                .password("password1")
                .userId("test-list-user")
                .build();
        sshConnectionDomainService.createConnection(entity1, null);

        SshConnectionEntity entity2 = SshConnectionEntity.builder()
                .connectionName("测试连接-列表2")
                .host("192.168.1.200")
                .port(22)
                .username("user2")
                .authType(AuthTypeEnum.PRIVATE_KEY)
                .privateKey("-----BEGIN RSA PRIVATE KEY-----\nlist-key\n-----END RSA PRIVATE KEY-----")
                .userId("test-list-user")
                .build();
        sshConnectionDomainService.createConnection(entity2, null);

        // 执行查询
        List<SshConnectionEntity> result = sshConnectionDomainService.getConnectionList("test-list-user");

        // 验证
        Assert.assertNotNull("查询结果列表不应为空", result);
        Assert.assertTrue("应至少有2条记录", result.size() >= 2);

        // 验证所有记录的userId匹配
        for (SshConnectionEntity conn : result) {
            Assert.assertEquals("用户ID应匹配", "test-list-user", conn.getUserId());
        }

        log.info("测试结果：查询连接列表成功，共{}条记录", result.size());
        result.forEach(conn -> log.info("  - 连接:[{}], 主机:[{}], 用户名:[{}]",
                conn.getConnectionName(), conn.getHost(), conn.getUsername()));
    }

    /**
     * 测试：查询用户连接列表 - userId为空时使用默认值
     */
    @Test
    public void test_getConnectionList_defaultUser() {
        // userId为空时应使用默认值"default"
        List<SshConnectionEntity> result = sshConnectionDomainService.getConnectionList(null);
        Assert.assertNotNull("查询结果不应为null", result);

        log.info("测试结果：默认用户查询连接列表，共{}条记录", result.size());
    }

    /**
     * 测试：获取连接的高级配置
     */
    @Test
    public void test_getConnectionConfig() {
        // 先创建连接（带高级配置）
        SshConnectionEntity entity = SshConnectionEntity.builder()
                .connectionName("测试连接-配置查询")
                .host("192.168.1.100")
                .port(22)
                .username("testuser")
                .authType(AuthTypeEnum.PASSWORD)
                .password("testPassword")
                .userId("test-user")
                .build();

        SshConnectionConfigEntity configEntity = SshConnectionConfigEntity.builder()
                .connectTimeout(10)
                .keepaliveInterval(60)
                .startupCommand("ls -la")
                .compression(false)
                .strictHostKeyCheck(true)
                .build();

        sshConnectionDomainService.createConnection(entity, configEntity);
        String connectionId = entity.getConnectionId();

        // 执行查询
        SshConnectionConfigEntity result = sshConnectionDomainService.getConnectionConfig(connectionId);

        // 验证
        Assert.assertNotNull("高级配置不应为空", result);
        Assert.assertEquals("连接ID应匹配", connectionId, result.getConnectionId());
        Assert.assertEquals("连接超时应匹配", Integer.valueOf(10), result.getConnectTimeout());
        Assert.assertEquals("心跳间隔应匹配", Integer.valueOf(60), result.getKeepaliveInterval());
        Assert.assertEquals("启动命令应匹配", "ls -la", result.getStartupCommand());
        Assert.assertEquals("压缩应匹配", Boolean.FALSE, result.getCompression());
        Assert.assertEquals("严格主机检查应匹配", Boolean.TRUE, result.getStrictHostKeyCheck());

        log.info("测试结果：查询高级配置成功，connectTimeout:{}, keepaliveInterval:{}",
                result.getConnectTimeout(), result.getKeepaliveInterval());
    }

    /**
     * 测试：获取不存在连接的高级配置
     */
    @Test
    public void test_getConnectionConfig_notExist() {
        SshConnectionConfigEntity result = sshConnectionDomainService.getConnectionConfig("not-exist-id");
        Assert.assertNull("查询不存在的配置应返回null", result);
        log.info("测试结果：查询不存在的配置返回null");
    }

    /**
     * 测试：建立SSH连接
     * 注意：此测试需要有效的SSH服务器才能成功，否则返回false
     */
    @Test
    public void test_connect() {
        // 先创建连接
        SshConnectionEntity entity = SshConnectionEntity.builder()
                .connectionName("测试连接-连接测试")
                .host("140.143.183.225")
                .port(22)
                .username("ubuntu")
                .authType(AuthTypeEnum.PASSWORD)
                .password("testPassword")
                .userId("test-user")
                .build();
        sshConnectionDomainService.createConnection(entity, null);
        String connectionId = entity.getConnectionId();

        // 执行连接（可能因密码不正确而失败，这是预期行为）
        boolean connected = sshConnectionDomainService.connect(connectionId);

        // 连接运行状态只存在于内存，不再写入连接配置表。
        SshConnectionEntity result = sshConnectionDomainService.getConnection(connectionId);
        Assert.assertNotNull("查询结果不应为空", result);
        log.info("测试结果：连接测试，connected:{}", connected);
    }

    /**
     * 测试：连接不存在的连接ID
     */
    @Test(expected = IllegalArgumentException.class)
    public void test_connect_notExist() {
        // 应抛出 IllegalArgumentException
        sshConnectionDomainService.connect("not-exist-connection-id");
    }

    /**
     * 测试：断开SSH连接
     */
    @Test
    public void test_disconnect() {
        // 先创建连接
        SshConnectionEntity entity = SshConnectionEntity.builder()
                .connectionName("测试连接-断开测试")
                .host("192.168.1.100")
                .port(22)
                .username("testuser")
                .authType(AuthTypeEnum.PASSWORD)
                .password("testPassword")
                .userId("test-user")
                .build();
        sshConnectionDomainService.createConnection(entity, null);
        String connectionId = entity.getConnectionId();

        // 执行断开（即使未连接也不应报错）
        sshConnectionDomainService.disconnect(connectionId);

        // 配置记录仍然存在，断开只清理内存中的 SSH 传输。
        SshConnectionEntity result = sshConnectionDomainService.getConnection(connectionId);
        Assert.assertNotNull("查询结果不应为空", result);
        log.info("测试结果：断开连接成功，connectionId:{}", connectionId);
    }

    /**
     * 测试：完整生命周期（创建 → 查询 → 更新 → 查询 → 删除 → 查询）
     */
    @Test
    public void test_fullLifecycle() {
        log.info("=== 完整生命周期测试开始 ===");

        // 1. 创建
        SshConnectionEntity createEntity = SshConnectionEntity.builder()
                .connectionName("生命周期测试连接")
                .host("192.168.1.100")
                .port(22)
                .username("lifecycle-user")
                .authType(AuthTypeEnum.PASSWORD)
                .password("lifecycle-password")
                .userId("lifecycle-user")
                .build();

        SshConnectionConfigEntity createConfig = SshConnectionConfigEntity.builder()
                .connectTimeout(10)
                .keepaliveInterval(60)
                .build();

        sshConnectionDomainService.createConnection(createEntity, createConfig);
        String connectionId = createEntity.getConnectionId();
        Assert.assertNotNull("创建后连接ID不应为空", connectionId);
        log.info("1. 创建成功，connectionId:{}", connectionId);

        // 2. 查询
        SshConnectionEntity queryResult = sshConnectionDomainService.getConnection(connectionId);
        Assert.assertNotNull("查询结果不应为空", queryResult);
        Assert.assertEquals("生命周期测试连接", queryResult.getConnectionName());
        log.info("2. 查询成功，name:{}", queryResult.getConnectionName());

        // 3. 更新
        SshConnectionEntity updateEntity = SshConnectionEntity.builder()
                .connectionId(connectionId)
                .connectionName("生命周期测试连接-已更新")
                .host("192.168.1.200")
                .port(2222)
                .username("updated-user")
                .authType(AuthTypeEnum.PASSWORD)
                .password("")
                .build();
        sshConnectionDomainService.updateConnection(updateEntity, null);
        log.info("3. 更新成功");

        // 4. 查询验证更新
        SshConnectionEntity afterUpdate = sshConnectionDomainService.getConnection(connectionId);
        Assert.assertNotNull("更新后查询结果不应为空", afterUpdate);
        Assert.assertEquals("生命周期测试连接-已更新", afterUpdate.getConnectionName());
        Assert.assertEquals("192.168.1.200", afterUpdate.getHost());
        Assert.assertEquals(Integer.valueOf(2222), afterUpdate.getPort());
        log.info("4. 更新验证成功，name:{}", afterUpdate.getConnectionName());

        // 5. 删除
        sshConnectionDomainService.deleteConnection(connectionId);
        log.info("5. 删除成功");

        // 6. 查询验证删除
        SshConnectionEntity afterDelete = sshConnectionDomainService.getConnection(connectionId);
        Assert.assertNull("删除后查询结果应为空", afterDelete);
        log.info("6. 删除验证成功");

        log.info("=== 完整生命周期测试通过 ===");
    }

}
