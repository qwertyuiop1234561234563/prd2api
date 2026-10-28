package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.dto.LoginDTO;
import com.dbappsecurity.prd2api.dto.RegisterDTO;
import com.dbappsecurity.prd2api.entity.SysUser;
import com.dbappsecurity.prd2api.mapper.SysUserMapper;
import com.dbappsecurity.prd2api.mapper.VipPlanMapper;
import com.dbappsecurity.prd2api.vo.LoginVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private SysUserMapper sysUserMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SetOperations<String, String> setOps;
    private AuthServiceImpl authService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // LambdaUpdateWrapper 依赖 MyBatis-Plus 的字段缓存，纯单测需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
        sysUserMapper = mock(SysUserMapper.class);
        VipPlanMapper vipPlanMapper = mock(VipPlanMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        authService = new AuthServiceImpl(sysUserMapper, vipPlanMapper, redisTemplate, new Prd2ApiProperties());
    }

    @Test
    void loginWithWrongPasswordShouldCountFailAndThrow() {
        SysUser user = activeUser();
        user.setPasswordHash(BCrypt.hashpw("correct-password"));
        user.setFailCount(0);
        when(sysUserMapper.selectByTenantAndUsername("default", "alice")).thenReturn(user);

        LoginDTO dto = loginDTO("wrong-password");
        BizException ex = assertThrows(BizException.class, () -> authService.login(dto));
        assertEquals(401, ex.getCode());
        assertTrue(ex.getMessage().contains("还可尝试 4 次"));
        verify(sysUserMapper).update(isNull(), any());
    }

    @Test
    void lockedAccountShouldBeRejectedBeforePasswordCheck() {
        SysUser user = activeUser();
        user.setPasswordHash(BCrypt.hashpw("correct-password"));
        user.setLockUntil(LocalDateTime.now().plusMinutes(10));
        when(sysUserMapper.selectByTenantAndUsername("default", "alice")).thenReturn(user);

        BizException ex = assertThrows(BizException.class, () -> authService.login(loginDTO("correct-password")));
        assertEquals(423, ex.getCode());
        verify(sysUserMapper, never()).update(isNull(), any());
    }

    @Test
    void disabledAccountShouldBeForbidden() {
        SysUser user = activeUser();
        user.setStatus(SysUser.STATUS_DISABLED);
        when(sysUserMapper.selectByTenantAndUsername("default", "alice")).thenReturn(user);

        BizException ex = assertThrows(BizException.class, () -> authService.login(loginDTO("correct-password")));
        assertEquals(403, ex.getCode());
    }

    @Test
    void loginSuccessShouldIssueSixtyFourCharToken() {
        SysUser user = activeUser();
        user.setPasswordHash(BCrypt.hashpw("correct-password"));
        when(sysUserMapper.selectByTenantAndUsername("default", "alice")).thenReturn(user);

        LoginVO vo = authService.login(loginDTO("correct-password"));
        assertEquals(64, vo.getToken().length());
        assertEquals(43200L, vo.getExpireSeconds());
        assertEquals("alice", vo.getUser().getUsername());
        verify(valueOps).set(anyString(), eq(user.getUserId()), anyLong(), any());
        verify(setOps).add(anyString(), anyString());
    }

    @Test
    void unknownUserShouldNotRevealAccountExistence() {
        when(sysUserMapper.selectByTenantAndUsername(anyString(), anyString())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> authService.login(loginDTO("whatever")));
        assertEquals("账号或密码错误", ex.getMessage());
    }

    @Test
    void registerInUnlistedTenantShouldBeForbidden() {
        RegisterDTO dto = new RegisterDTO();
        dto.setTenantId("other_tenant");
        dto.setUsername("bob");
        dto.setPassword("password123");

        BizException ex = assertThrows(BizException.class, () -> authService.register(dto));
        assertEquals(403, ex.getCode());
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    private LoginDTO loginDTO(String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("alice");
        dto.setPassword(password);
        return dto;
    }

    private SysUser activeUser() {
        SysUser user = new SysUser();
        user.setUserId("u-1");
        user.setTenantId("default");
        user.setUsername("alice");
        user.setRole("USER");
        user.setStatus(SysUser.STATUS_ACTIVE);
        user.setDisplayName("Alice");
        return user;
    }
}
