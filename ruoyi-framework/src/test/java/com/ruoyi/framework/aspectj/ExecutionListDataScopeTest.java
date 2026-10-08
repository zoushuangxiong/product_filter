package com.ruoyi.framework.aspectj;

import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.security.context.PermissionContextHolder;
import com.ruoyi.system.domain.ProductExecutionList;
import com.ruoyi.system.mapper.ProductExecutionListMapper;
import com.ruoyi.system.service.impl.ProductExecutionListServiceImpl;
import com.ruoyi.system.service.product.ScanModels;
import org.junit.jupiter.api.*;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 DataScope 切面验证清单角色范围及内部调用，Mapper 不连接业务数据库。 */
class ExecutionListDataScopeTest {
    AtomicReference<ProductExecutionList> query = new AtomicReference<>();
    ProductExecutionListServiceImpl service;
    @BeforeEach void setup() throws Exception {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        PermissionContextHolder.setContext("product:executionList:list");
        var target = new ProductExecutionListServiceImpl();
        var mapper = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ProductExecutionListMapper.class}, (proxy, method, args) -> {
            if (method.getName().equals("selectProductExecutionListList")) {
                query.set((ProductExecutionList) args[0]); return List.of();
            }
            throw new AssertionError("未经授权不应读取或修改数据：" + method.getName());
        });
        var field = ProductExecutionListServiceImpl.class.getDeclaredField("productExecutionListMapper");
        field.setAccessible(true); field.set(target, mapper);
        var factory = new AspectJProxyFactory(target); factory.setProxyTargetClass(true); factory.setExposeProxy(true);
        factory.addAspect(new DataScopeAspect()); service = factory.getProxy();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); }
    void login(long id, String scope) {
        SysUser user = new SysUser(); user.setUserId(id); user.setDeptId(100L); user.setUserName("test");
        SysRole role = new SysRole(); role.setRoleId(9L); role.setStatus("0"); role.setDataScope(scope);
        role.setPermissions(Set.of("product:executionList:list")); user.setRoles(List.of(role));
        var principal = new LoginUser(id, 100L, user, Set.of("product:executionList:list"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
    @Test void allFiveRoleScopesUseIdsAndClearClientSql() {
        Map<String, String> expected = Map.of("1", "", "2", "sys_role_dept", "3", "u.dept_id = 100", "4", "find_in_set", "5", "e.user_id = 7");
        expected.forEach((scope, fragment) -> {
            login(7, scope); var item = new ProductExecutionList(); item.getParams().put("dataScope", "injected");
            service.selectProductExecutionListList(item);
            String sql = (String) query.get().getParams().get("dataScope");
            assertFalse(sql.contains("injected")); assertTrue(sql.contains(fragment));
            if (scope.equals("1")) assertEquals("", sql);
        });
    }
    @Test void adminSeesAllAndRolesWithoutPermissionSeeNothing() {
        login(1, "5"); service.selectProductExecutionListList(new ProductExecutionList());
        assertEquals("", query.get().getParams().get("dataScope"));
        login(7, "1"); PermissionContextHolder.setContext("product:executionList:edit");
        service.selectProductExecutionListList(new ProductExecutionList());
        assertTrue(query.get().getParams().get("dataScope").toString().contains("u.dept_id = 0"));
    }
    @Test void directIdRequestsUseProxyAndCannotBypassScope() {
        login(7, "5");
        assertThrows(ServiceException.class, () -> service.selectProductExecutionListById(18L));
        assertEquals(18L, query.get().getId());
        assertTrue(query.get().getParams().get("dataScope").toString().contains("e.user_id = 7"));
        var edit = new ProductExecutionList(); edit.setId(18L); edit.setUserId(999L);
        assertThrows(ServiceException.class, () -> service.updateProductExecutionList(edit));
        assertThrows(ServiceException.class, () -> service.deleteProductExecutionListByIds(new Long[]{18L}));
        assertThrows(ServiceException.class, () -> service.filterProductExecutionList(18L));
        var request = new ScanModels.Request(); request.executionListId = 18L;
        assertThrows(ServiceException.class, () -> service.prepareExecution(request));
    }
}
