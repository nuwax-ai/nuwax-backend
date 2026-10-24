package com.xspaceagi.agent.core.infra.component.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentConfig;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.memory.sdk.dto.MemorySaveDto;
import com.xspaceagi.memory.sdk.dto.MemoryUnitQueryDTO;
import com.xspaceagi.memory.sdk.service.IMemoryRpcService;
import com.xspaceagi.system.application.dto.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryToolProvider} 的契约测试（两个 loop 共用这一份清单/执行/纪律，所以它错了两边一起错）。
 *
 * <p>钉住四件最容易悄悄漂移、且漂移后<b>不报错</b>的事：
 * <ol>
 *   <li><b>闸门</b>：只有 {@code agentConfig.openLongMemory == Open} 才算开启（ctx / agentConfig 为 null 一律 false）；</li>
 *   <li><b>同源</b>：提示词段里提到的工具名 == 实际挂载的工具名（只挂不注 ⇒ 模型编造 memoryId；只注不挂 ⇒ 调不存在的函数名）；</li>
 *   <li><b>回喂配对</b>：{@link MemoryToolProvider.Op#action()} 必须等于规范工具名 —— loop 靠它倒查 toolCallId 与
 *       tool_use 配对，名字不一致会让二次调模型时协议校验直接失败；</li>
 *   <li><b>失败不抛异常</b>：三个执行方法都要把失败收敛成可回喂的结果文本（loop 不再兜底），
 *       且「空入参」必须报成"未执行"而不是"成功 0 条"——否则模型会对用户说"已删除"。</li>
 * </ol>
 */
class MemoryToolProviderTest {

    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 1596L;
    private static final long AGENT_ID = 42L;

    private IMemoryRpcService rpc;
    private MemoryToolProvider provider;

    @BeforeEach
    void setUp() {
        rpc = mock(IMemoryRpcService.class);
        provider = new MemoryToolProvider();
        provider.setMemoryRpcService(rpc);
    }

    /** 开启长期记忆的正常上下文。 */
    private AgentContext ctx() {
        return ctx(AgentConfig.OpenStatus.Open);
    }

    private AgentContext ctx(AgentConfig.OpenStatus openLongMemory) {
        UserDto user = new UserDto();
        user.setId(USER_ID);
        user.setTenantId(TENANT_ID);
        AgentConfigDto config = new AgentConfigDto();
        config.setId(AGENT_ID);
        config.setOpenLongMemory(openLongMemory);
        AgentContext ctx = new AgentContext();
        ctx.setUser(user);
        ctx.setAgentConfig(config);
        return ctx;
    }

    // ======================== 闸门 ========================

    @Test
    @DisplayName("isOpen 只认 agentConfig.openLongMemory == Open；null 一律 false（不升级成 NPE）")
    void isOpenOnlyTrustsAgentConfig() {
        assertTrue(provider.isOpen(ctx(AgentConfig.OpenStatus.Open)));
        assertFalse(provider.isOpen(ctx(AgentConfig.OpenStatus.Close)));
        // 智能体没配这一项（历史数据 / DTO 缺省）= 未开启
        assertFalse(provider.isOpen(ctx(null)));

        AgentContext noConfig = new AgentContext();
        noConfig.setUser(new UserDto());
        assertFalse(provider.isOpen(noConfig));
        assertFalse(provider.isOpen(null));
    }

    @Test
    @DisplayName("isMemoryAction 只认三个规范名，sandbox / null 不算")
    void isMemoryActionOnlyMatchesThreeNames() {
        for (String action : MemoryToolProvider.ACTIONS) {
            assertTrue(provider.isMemoryAction(action), action);
        }
        assertFalse(provider.isMemoryAction("sandbox"));
        assertFalse(provider.isMemoryAction("memory"));
        assertFalse(provider.isMemoryAction(null));
    }

    // ======================== 工具清单 ========================

    @Test
    @DisplayName("三个工具：名字/类型(Control ⇒ returnDirect)/targetId 固定，memoryIds 必填")
    void buildToolsShape() {
        List<ComponentConfig> tools = provider.buildTools();
        assertEquals(3, tools.size());

        ComponentConfig query = tools.get(0);
        ComponentConfig save = tools.get(1);
        ComponentConfig delete = tools.get(2);

        assertEquals(MemoryToolProvider.ACTION_QUERY, query.getFunctionName());
        assertEquals(MemoryToolProvider.ACTION_SAVE, save.getFunctionName());
        assertEquals(MemoryToolProvider.ACTION_DELETE, delete.getFunctionName());

        for (ComponentConfig tool : tools) {
            // Control 型才会被 ModelInvoker 强制 returnDirect=true（loop 侧「结果回喂」的前提）
            assertEquals(ComponentTypeEnum.Control, tool.getType(), tool.getFunctionName());
            assertEquals(0L, tool.getTargetId().longValue(), tool.getFunctionName());
            assertFalse(tool.getDescription().isBlank(), tool.getFunctionName());
        }

        // memory_delete 只有一个入参且必填：不填就等于让模型"删了个空"
        List<Arg> deleteArgs = delete.getInputArgs();
        assertEquals(1, deleteArgs.size());
        assertEquals("memoryIds", deleteArgs.get(0).getName());
        assertEquals(DataTypeEnum.Array_Integer, deleteArgs.get(0).getDataType());
        assertTrue(deleteArgs.get(0).isRequire());

        // memory_save 的两组入参都是可选（至少给一项），但嵌套项里 memoryId / keyValues 必填
        Map<String, Arg> saveArgs = argsByName(save.getInputArgs());
        assertTrue(saveArgs.containsKey("insertMemories"));
        assertTrue(saveArgs.containsKey("updateMemoryKeyValues"));
        assertFalse(saveArgs.get("updateMemoryKeyValues").isRequire());
        Map<String, Arg> updateSub = argsByName(saveArgs.get("updateMemoryKeyValues").getSubArgs());
        assertTrue(updateSub.get("memoryId").isRequire(), "更新不带 memoryId 就成了新增");
        assertTrue(updateSub.get("keyValues").isRequire());
    }

    @Test
    @DisplayName("分类体系随 memory_save 的工具描述下发（ArgConverter 不给 Object 节点输出 description）")
    void taxonomyTravelsWithSaveToolDescription() {
        ComponentConfig save = provider.buildTools().get(1);
        assertTrue(save.getDescription().contains(MemoryToolProvider.MEMORY_TAXONOMY));
        // 分类体系里的一级分类必须齐全，缺一个模型就只能瞎填 category
        for (String category : List.of("profile", "auth", "preference", "task", "event", "code", "other")) {
            assertTrue(MemoryToolProvider.MEMORY_TAXONOMY.contains(category), category);
        }
    }

    @Test
    @DisplayName("每次 buildTools 都返回新实例（ArgExtractUtil 会就地改写 Arg，共享实例会跨轮污染）")
    void buildToolsReturnsFreshInstances() {
        List<ComponentConfig> first = provider.buildTools();
        List<ComponentConfig> second = provider.buildTools();
        assertNotSame(first.get(0), second.get(0));
        assertNotSame(first.get(1).getInputArgs().get(0), second.get(1).getInputArgs().get(0));
    }

    @Test
    @DisplayName("提示词段提到的工具名 == 实际挂载的工具名（同源门控的漂移守卫）")
    void promptSectionMentionsExactlyMountedTools() {
        String section = MemoryToolProvider.MEMORY_PROMPT_SECTION;
        List<ComponentConfig> tools = provider.buildTools();
        // 挂了的工具必须在纪律段里被提到，否则模型不知道"memoryId 严禁编造"这类规矩
        for (ComponentConfig tool : tools) {
            assertTrue(section.contains(tool.getFunctionName()), "纪律段漏提 " + tool.getFunctionName());
        }
        // 纪律段提到的工具名必须真的挂着，否则模型会去调一个不存在的函数名
        for (String action : MemoryToolProvider.ACTIONS) {
            assertTrue(section.contains(action), action);
            assertTrue(tools.stream().anyMatch(t -> t.getFunctionName().equals(action)), "清单缺 " + action);
        }
        assertEquals(MemoryToolProvider.ACTIONS, tools.stream().map(ComponentConfig::getFunctionName).toList());
    }

    // ======================== memory_query ========================

    @Test
    @DisplayName("query：tenantId/userId/agentId 由服务端填充（模型只给查询条件），结果原样回喂")
    void queryFillsServerSideIds() {
        String md = "| memory_id | category | content |\n| 101 | profile | 手机号 138... |";
        when(rpc.queryMemoriesMd(any(MemoryUnitQueryDTO.class), anyBoolean())).thenReturn(md);

        MemoryToolProvider.Op op = provider.query(ctx(),
                JSON.parseObject("{\"action\":\"memory_query\",\"tags\":[\"手机号\"],\"limit\":5}"));

        assertEquals(MemoryToolProvider.ACTION_QUERY, op.action());
        assertEquals(md, op.result());
        assertFalse(op.instruction().isBlank(), "没有 instruction 模型就不知道拿结果干什么");

        ArgumentCaptor<MemoryUnitQueryDTO> captor = ArgumentCaptor.forClass(MemoryUnitQueryDTO.class);
        verify(rpc).queryMemoriesMd(captor.capture(), eq(false));
        MemoryUnitQueryDTO sent = captor.getValue();
        assertEquals(TENANT_ID, sent.getTenantId().longValue());
        assertEquals(USER_ID, sent.getUserId().longValue());
        assertEquals(AGENT_ID, sent.getAgentId().longValue());
        assertEquals(List.of("手机号"), sent.getTags());
        assertEquals(5, sent.getLimit().intValue());
    }

    @Test
    @DisplayName("query：命中为空要说'未找到'，不能把空串回喂给模型（它会当成'查过了、没有'之外的东西）")
    void queryBlankResultBecomesNotFound() {
        when(rpc.queryMemoriesMd(any(MemoryUnitQueryDTO.class), anyBoolean())).thenReturn("  ");
        MemoryToolProvider.Op op = provider.query(ctx(), JSON.parseObject("{\"action\":\"memory_query\"}"));
        assertEquals("未找到相关记忆。", op.result());
    }

    @Test
    @DisplayName("query：RPC 抛异常也收敛成可回喂文本，绝不冒泡（loop 侧不再兜底）")
    void queryFailureIsFedBackNotThrown() {
        when(rpc.queryMemoriesMd(any(MemoryUnitQueryDTO.class), anyBoolean()))
                .thenThrow(new RuntimeException("boom"));
        MemoryToolProvider.Op op = provider.query(ctx(), JSON.parseObject("{\"action\":\"memory_query\"}"));
        assertEquals(MemoryToolProvider.ACTION_QUERY, op.action());
        assertTrue(op.result().startsWith("记忆查询失败："), op.result());
        assertTrue(op.result().contains("boom"));
    }

    // ======================== memory_save ========================

    @Test
    @DisplayName("save：新增一条 → 报实际条数并真的落库")
    void saveInsertsOne() {
        JSONObject control = JSON.parseObject("{\"action\":\"memory_save\",\"insertMemories\":[{\"category\":\"profile\","
                + "\"subCategory\":\"contact\",\"keyValues\":{\"phone\":\"13800000000\"}}]}");

        MemoryToolProvider.Op op = provider.save(ctx(), control);

        assertEquals(MemoryToolProvider.ACTION_SAVE, op.action());
        assertEquals("记忆写入完成：新增 1 条，更新 0 条。", op.result());
        ArgumentCaptor<MemorySaveDto> captor = ArgumentCaptor.forClass(MemorySaveDto.class);
        verify(rpc, times(1)).saveMemories(eq(TENANT_ID), eq(USER_ID), eq(AGENT_ID), captor.capture());
        assertEquals("profile", captor.getValue().getInsertMemories().get(0).getCategory());
    }

    @Test
    @DisplayName("save：insert 与 update 都空/字段不全 → 回'未执行'且不落库（不能报成写入成功）")
    void saveWithNothingValidDoesNotCallRpc() {
        // 完全没有条目
        MemoryToolProvider.Op empty = provider.save(ctx(), JSON.parseObject("{\"action\":\"memory_save\"}"));
        // 有 insert 但缺 keyValues（服务端会静默丢弃，报"成功 1 条"就是骗用户）
        MemoryToolProvider.Op incomplete = provider.save(ctx(),
                JSON.parseObject("{\"action\":\"memory_save\",\"insertMemories\":[{\"category\":\"profile\"}]}"));
        // 有 update 但没有 memoryId
        MemoryToolProvider.Op noId = provider.save(ctx(),
                JSON.parseObject("{\"action\":\"memory_save\",\"updateMemoryKeyValues\":[{\"keyValues\":{\"phone\":\"1\"}}]}"));

        for (MemoryToolProvider.Op op : List.of(empty, incomplete, noId)) {
            assertTrue(op.result().startsWith("记忆写入未执行："), op.result());
        }
        verify(rpc, never()).saveMemories(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("save：RPC 抛异常也收敛成可回喂文本")
    void saveFailureIsFedBackNotThrown() {
        doThrow(new RuntimeException("boom")).when(rpc).saveMemories(anyLong(), anyLong(), anyLong(), any());
        MemoryToolProvider.Op op = provider.save(ctx(),
                JSON.parseObject("{\"action\":\"memory_save\",\"insertMemories\":[{\"category\":\"note\","
                        + "\"subCategory\":\"personal\",\"keyValues\":{\"content\":\"x\"}}]}"));
        assertTrue(op.result().startsWith("记忆写入失败："), op.result());
        assertTrue(op.result().contains("boom"));
    }

    // ======================== memory_delete ========================

    @Test
    @DisplayName("delete：带 memoryIds → 报实际条数并真的软删")
    void deleteByIds() {
        MemoryToolProvider.Op op = provider.delete(ctx(),
                JSON.parseObject("{\"action\":\"memory_delete\",\"memoryIds\":[101,202]}"));
        assertEquals(MemoryToolProvider.ACTION_DELETE, op.action());
        assertEquals("记忆删除完成：已删除 2 条。", op.result());
        verify(rpc).deleteMemories(eq(TENANT_ID), eq(USER_ID), eq(List.of(101L, 202L)));
    }

    @Test
    @DisplayName("delete：memoryIds 为空/缺失 → 回'未执行'且不落库（报成'已删除'会让模型骗用户）")
    void deleteWithoutIdsDoesNotCallRpc() {
        MemoryToolProvider.Op missing = provider.delete(ctx(), JSON.parseObject("{\"action\":\"memory_delete\"}"));
        MemoryToolProvider.Op emptyList = provider.delete(ctx(),
                JSON.parseObject("{\"action\":\"memory_delete\",\"memoryIds\":[]}"));

        for (MemoryToolProvider.Op op : List.of(missing, emptyList)) {
            assertTrue(op.result().startsWith("记忆删除未执行："), op.result());
            assertTrue(op.result().contains("memory_query"), "要告诉模型先查再删");
        }
        verify(rpc, never()).deleteMemories(anyLong(), anyLong(), anyList());
    }

    @Test
    @DisplayName("delete：RPC 抛异常也收敛成可回喂文本")
    void deleteFailureIsFedBackNotThrown() {
        doThrow(new RuntimeException("boom")).when(rpc).deleteMemories(anyLong(), anyLong(), anyList());
        MemoryToolProvider.Op op = provider.delete(ctx(),
                JSON.parseObject("{\"action\":\"memory_delete\",\"memoryIds\":[101]}"));
        assertTrue(op.result().startsWith("记忆删除失败："), op.result());
        assertTrue(op.result().contains("boom"));
    }

    // ======================== 杂项 ========================

    @Test
    @DisplayName("filterSensitive 沿用上下文（与 loadLongMemory 的被动加载口径一致）")
    void queryHonoursFilterSensitive() {
        AgentContext ctx = ctx();
        ctx.setFilterSensitive(true);
        when(rpc.queryMemoriesMd(any(MemoryUnitQueryDTO.class), anyBoolean())).thenReturn("md");
        provider.query(ctx, JSON.parseObject("{\"action\":\"memory_query\"}"));
        verify(rpc).queryMemoriesMd(any(MemoryUnitQueryDTO.class), eq(true));
    }

    private static Map<String, Arg> argsByName(List<Arg> args) {
        return args.stream().collect(Collectors.toMap(Arg::getName, a -> a));
    }
}
