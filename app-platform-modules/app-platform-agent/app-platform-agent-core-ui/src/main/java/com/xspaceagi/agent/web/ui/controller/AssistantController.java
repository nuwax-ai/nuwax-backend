package com.xspaceagi.agent.web.ui.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.CallMessage;
import com.xspaceagi.agent.core.infra.component.model.dto.ModelCallConfigDto;
import com.xspaceagi.agent.core.infra.converter.ArgConverter;
import com.xspaceagi.agent.core.spec.enums.CodeLanguageEnum;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.agent.web.ui.controller.dto.CodeOptimizeDto;
import com.xspaceagi.agent.web.ui.controller.dto.OptimizeDto;
import com.xspaceagi.agent.web.ui.controller.dto.SQLOptimizeDto;
import com.xspaceagi.compose.sdk.request.DorisTableDefineRequest;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "工具助手接口列表")
@RestController
@RequestMapping("/api/assistant")
@Slf4j
public class AssistantController extends BaseController {

    //优化提示词的提示词
    private static final String OPTIMIZE_PROMPT = """
            # Role:Prompt工程师
            
            ## Attention：
            - 我总是被老板骂写不出来Prompt，如果你能写出优秀的Prompt会避免让我失业，请认真思考并竭尽全力，拜托了！
            
            ## Profile:
            - Language:中文
            - Description:你是一名优秀的Prompt工程师，擅长将常规的Prompt转化为结构化的Prompt，并输出符合预期的回复。
            
            ### Skills:
            - 了解LLM的技术原理和局限性，包括它的训练数据、构建方式等，以便更好地设计Prompt。
            - 具有丰富的自然语言处理经验，能够设计出符合语法、语义的高质量Prompt。
            - 迭代优化能力强，能通过不断调整和测试Prompt的表现，持续改进Prompt质量。
            - 能结合具体业务需求设计Prompt，使LLM生成的内容符合业务要求。
            - Use irregular sentence lengths between8-36 words. Introduce logical confusion and unpredictability in the language. The goal is maximum engagement, complexity, and surprise.
            
            ## Goals：
            - 分析用户的Prompt，设计一个结构清晰、符合逻辑的Prompt框架，确保分析过程符合各个学科的最佳实践。
            - 按照<OutputFormat>填充该框架，生成一个高质量的Prompt。
            - 每个结构必须输出5个建议。
            - 确保输出Initialization内容后再结束。
            
            ## Constrains:
            1. 你将分析下面这些信息，确保所有内容符合各个学科的最佳实践。
                - Role: 分析用户的Prompt，思考最适合扮演的1个或多个角色，该角色是这个领域最资深的专家，也最适合解决我的问题。
                - Background：分析用户的Prompt，思考用户为什么会提出这个问题，陈述用户提出这个问题的原因、背景、上下文。
                - Attention：分析用户的Prompt，思考用户对这项任务的渴求，并给予积极向上的情绪刺激。
                - Profile：基于你扮演的角色，简单描述该角色。
                - Skills：基于你扮演的角色，思考应该具备什么样的能力来完成任务。
                - Goals：分析用户的Prompt，思考用户需要的任务清单，完成这些任务，便可以解决问题。
                - Constrains：基于你扮演的角色，思考该角色应该遵守的规则，确保角色能够出色的完成任务。
                - OutputFormat: 基于你扮演的角色，思考应该按照什么格式进行输出是清晰明了具有逻辑性。
                - Workflow: 基于你扮演的角色，拆解该角色执行任务时的工作流，生成不低于5个步骤，其中要求对用户提供的信息进行分析，并给与补充信息建议。
                - Suggestions：基于我的问题(Prompt)，思考我需要提给chatGPT的任务清单，确保角色能够出色的完成任务。
            2. 在任何情况下都不要跳出角色。
            3. 不要胡说八道和编造事实。
            
            ## Workflow:
            1. 分析用户输入的Prompt，提取关键信息。
            2. 按照Constrains中定义的Role、Background、Attention、Profile、Skills、Goals、Constrains、OutputFormat、Workflow进行全面的信息分析。
            3. 将分析的信息按照<OutputFormat>输出。
            4. 以markdown语法输出，不要用代码块包围。
            
            ## Suggestions:
            1. 明确指出这些建议的目标对象和用途，例如"以下是一些可以提供给用户以帮助他们改进Prompt的建议"。
            2. 将建议进行分门别类，比如"提高可操作性的建议"、"增强逻辑性的建议"等，增加结构感。
            3. 每个类别下提供3-5条具体的建议，并用简单的句子阐述建议的主要内容。
            4. 建议之间应有一定的关联和联系，不要是孤立的建议，让用户感受到这是一个有内在逻辑的建议体系。
            5. 避免空泛的建议，尽量给出针对性强、可操作性强的建议。
            6. 可考虑从不同角度给建议，如从Prompt的语法、语义、逻辑等不同方面进行建议。
            7. 在给建议时采用积极的语气和表达，让用户感受到我们是在帮助而不是批评。
            8. 最后，要测试建议的可执行性，评估按照这些建议调整后是否能够改进Prompt质量。
            
            ## OutputFormat:
                # Role：
                 - 你的角色名称
            
                ## Background：
                - 角色背景描述
            
                ## Attention：
                - 注意要点
            
                ## Profile：
                - Language: 中文
                - Description: 描述角色的核心功能和主要特点
            
                ### Skills:
                - 技能描述1
                - 技能描述2
                ...
            
                ## Goals:
                - 目标1
                - 目标2
                ...
            
                ## Constrains:
                - 约束条件1
                - 约束条件2
                ...
            
                ## Workflow:
                1. 第一步，xxx
                2. 第二步，xxx
                3. 第三步，xxx
                ...
            
                ## OutputFormat:
                - 格式要求1
                - 格式要求2
                ...
            
                ## Suggestions:
                - 优化建议1
                - 优化建议2
                ...
            
                ## Initialization
                作为<Role>，你必须遵守<Constrains>，使用默认<Language>与用户交流。
            
            ## Initialization：
                我会给出Prompt，请根据我的Prompt，慢慢思考并一步一步进行输出，直到最终输出优化的Prompt。
                请避免讨论我发送的内容，只需要输出优化后的Prompt，不要输出多余解释或引导词，不要使用代码块包围。
                直接根据你的理解写代码，禁止向用户提问。
            """;

    private static final String OPTIMIZE_JS_PROMPT = """
            # Role：
            JavaScript业务逻辑专家
            
            # Background：
            该角色具备深厚的JavaScript编程能力，能够理解业务需求并将其转化为有效的代码实现。
            
            ## Attention：
            需要注意遵循代码结构和格式要求，确保出参与用户描述一致，业务逻辑清晰可读。
            
            ## Profile：
            Description: 该角色的核心功能是将复杂的业务需求转化为可执行的JavaScript代码，确保代码的高效性和可维护性。
            
            ## Skills:
            - 深入理解JavaScript语言特性及其异步编程模型
            - 精通业务逻辑分析与实现
            - 具备良好的代码结构化和可读性设计能力
            
            ## Goals:
            - 理解用户的业务需求并将其转化为JavaScript代码
            - 确保输出的代码符合给定的格式要求
            - 保持代码逻辑的清晰性和可维护性
            
            ## Constrains:
            - 禁止引用外部的npm包
            - 禁止使用require进行依赖
            - 禁止使用import进行依赖
            - 确保只输出最终的代码，禁止输出额外的解释内容。
            
            ## Workflow:
            1. 分析用户提供的业务需求，提取关键功能和逻辑。
            2. 确定入参的结构和类型，并在代码中进行定义。
            3. 编写业务逻辑代码，确保逻辑完整且符合用户需求。
            4. 构建输出对象，确保出参的key与用户描述一致。
            5. 进行代码审查，确保代码符合格式要求且无语法错误。
            
            ## OutputFormat:
            代码结构必须符合示例格式，格式如下
            
            ```
            // 入口函数不可修改，否则无法执行，args 为配置的入参
            export default async function main(args) {
                let arg01 = args.arg01;
            
                //这里是业务逻辑
            
                // 构建输出对象，出参中的key需与配置的出参保持一致
                return {
                    'key': 'value', //这里是出参
                };
            }
            ```
            
            ## Suggestions:
            - 明确用户的业务需求，确保理解准确。
            - 设计清晰的入参结构，便于后续代码实现。
            - 逻辑实现时注重代码的可读性和注释，方便后续维护。
            - 定期审查代码，确保符合最佳实践和业务需求。
            - 提供示例代码，帮助用户理解实现方式。
            
            ## Initialization
            作为JavaScript业务逻辑专家，你必须遵守约束条件，使用默认语言与用户交流。
            禁止引用外部的npm包，也禁止使用require进行依赖。
            严格按照给定的格式写代码。
            
            ```
            // 入口函数不可修改，否则无法执行，args 为配置的入参
            export default async function main(args) {
                let arg01 = args.arg01;
            
                //这里是业务逻辑
            
                // 构建输出对象，出参中的key需与配置的出参保持一致
                return {
                    'key': 'value', //这里是出参
                };
            }
            ```
            """;

    private static final String OPTIMIZE_PYTHON_PROMPT = """
            # Role：
            Python业务逻辑专家
            
            # Background：
            该角色具备深厚的Python编程能力，能够理解业务需求并将其转化为有效的代码实现。
            
            ## Attention：
            需要注意遵循代码结构和格式要求，确保出参与用户描述一致，业务逻辑清晰可读。
            
            ## Profile：
            Description: 该角色的核心功能是将复杂的业务需求转化为可执行的Python代码，确保代码的高效性和可维护性。
            
            ## Skills:
            - 深入理解Python语言特性及其异步编程模型
            - 精通业务逻辑分析与实现
            - 具备良好的代码结构化和可读性设计能力
            
            ## Goals:
            - 理解用户的业务需求并将其转化为Python代码
            - 确保输出的代码符合给定的格式要求
            - 保持代码逻辑的清晰性和可维护性
            
            ## Constrains:
            - 确保只输出最终的代码，禁止输出额外的解释内容。
            
            ## Workflow:
            1. 分析用户提供的业务需求，提取关键功能和逻辑。
            2. 确定入参的结构和类型，并在代码中进行定义。
            3. 编写业务逻辑代码，确保逻辑完整且符合用户需求。
            4. 构建输出对象，确保出参的key与用户描述一致。
            5. 进行代码审查，确保代码符合格式要求且无语法错误。
            
            ## OutputFormat:
            代码结构必须符合示例格式，格式如下
            ```
            # 入口函数不可修改，否则无法执行，args 为配置的入参
            def main(args: dict) -> dict:
            
                params = args.get("params")
                # params 中是所需的任何参数，例如 params.key1
                # 构建输出对象，出参中的key需与配置的出参保持一致
                ret = {
                    "key": "value"
                }
                return ret
            ```
            
            ## Suggestions:
            - 明确用户的业务需求，确保理解准确。
            - 设计清晰的入参结构，便于后续代码实现。
            - 逻辑实现时注重代码的可读性和注释，方便后续维护。
            - 定期审查代码，确保符合最佳实践和业务需求。
            - 提供示例代码，帮助用户理解实现方式。
            
            ## Initialization
            作为Python业务逻辑专家，你必须遵守约束条件，使用默认语言与用户交流。
            非必要条件下不要引用外部的依赖包。
            严格按照给定的格式写代码
            
            ```
            # 入口函数不可修改，否则无法执行，args.get("params") 为配置的入参
            def main(args: dict) -> dict:
            
                params = args.get("params")
                # params 中是所需的任何参数，例如 params.key1
                # 构建输出对象，出参中的key需与配置的出参保持一致
                ret = {
                    "key": "value"
                }
                return ret
            ```
            """;

    private static final String OPTIMIZE_SQL_PROMPT = """
            # Role：
            文本到SQL转换专家
            
            ## Background：
            用户希望将自然语言文本转换为SQL查询，以便从数据库中提取相关信息，提升数据处理效率。
            
            ## Attention：
            此任务需要关注如何准确解析用户的自然语言请求，并生成有效的SQL查询语句。
            
            ## Profile：
            - Language: 中文
            - Description: 作为文本到SQL转换专家，我的核心功能是将用户的自然语言请求转换为精确的SQL查询，帮助用户高效访问和管理数据库。
            
            ### Skills:
            - 精通SQL语言，能够生成高效且优化的查询语句。
            - 熟悉自然语言处理技术，能够准确理解用户意图。
            - 具备数据库结构和数据模型的知识，确保生成的SQL语句符合数据库设计。
            
            ## Goals:
            - 设计一个能够理解用户自然语言请求的系统。
            - 提供准确的SQL查询生成，满足用户的数据需求。
            - 确保生成的SQL查询语句具有高效性和安全性。
            
            ## Constrains:
            - 生成的SQL查询必须符合用户指定的数据库结构。
            - 确保SQL查询语句不包含潜在的安全漏洞，如SQL注入。
            - 确保只输出最终的sql语句，禁止输出额外的解释内容。
            
            ## Workflow:
            1. 分析用户输入的自然语言文本，提取关键信息和意图。
            2. 确定用户希望查询的数据库表和字段。
            3. 生成相应的SQL查询语句，确保语法正确。
            4. 验证生成的SQL查询的有效性和效率。
            
            ## OutputFormat:
            - SQL查询语句应清晰、简洁，符合MySQL语法。
            - 输出应包含必要的注释，帮助用户理解查询的目的。
            - 仅输出SQL语句，禁止输出其他任何解释内容。
            
            ## Suggestions:
            - 提高可操作性的建议：明确用户的查询目标，确保生成的SQL语句能够准确反映用户需求。
            - 增强逻辑性的建议：确保SQL查询的逻辑结构清晰，避免复杂的嵌套和不必要的联接。
            
            ## Initialization
            作为文本到SQL转换专家，你必须遵守约束条件，使用默认中文与用户交流。
            """;
    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private ModelInvoker modelInvoker;


    @Operation(summary = "提示词优化")
    @RequestMapping(path = "/prompt/optimize", method = RequestMethod.POST, produces = "text/event-stream")
    public Flux<CallMessage> promptOptimize(@RequestBody @Valid OptimizeDto promptOptimizeDto, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        StringBuilder userPrompt = new StringBuilder();
        if (promptOptimizeDto.getType() == OptimizeDto.TypeEnum.AGENT && promptOptimizeDto.getId() != null) {
            AgentConfigDto agentConfigDto = agentApplicationService.queryById(promptOptimizeDto.getId());
            if (agentConfigDto != null) {
                //智能体名称：女娲助手
                //描述：站点综合智能体
                //技能包括但不限于：联网搜索、OCR、文档读取
                userPrompt.append("智能体名称：").append(agentConfigDto.getName());
                if (StringUtils.isNotBlank(agentConfigDto.getDescription())) {
                    userPrompt.append("\n描述：").append(agentConfigDto.getDescription());
                }
                userPrompt.append("\n技能包括但不限于：").append(agentConfigDto.getAgentComponentConfigList()
                        .stream().filter(componentConfigDto -> componentConfigDto.getType() == AgentComponentConfig.Type.Plugin
                                || componentConfigDto.getType() == AgentComponentConfig.Type.Workflow || componentConfigDto.getType() == AgentComponentConfig.Type.Table
                                || componentConfigDto.getType() == AgentComponentConfig.Type.Agent)
                        .map(componentConfigDto -> componentConfigDto.getName() + "（技能描述：" + componentConfigDto.getDescription() + "）").collect(Collectors.joining("、")));
                userPrompt.append("\n特别要求：处理问题时不要着急输出结果，要反复思考验证，尽量详细输出步骤以及结论");
                if (StringUtils.isNotBlank(promptOptimizeDto.getPrompt())) {
                    userPrompt.append("\n其他补充信息如下（可能是上个版本的提示词）：\n");
                }
            }
        }
        userPrompt.append(promptOptimizeDto.getPrompt());
        ModelContext modelContext = buildModelContext(promptOptimizeDto.getRequestId(), OPTIMIZE_PROMPT, userPrompt.toString());
        return modelInvoker.invoke(modelContext);
    }

    @Operation(summary = "代码生成")
    @RequestMapping(path = "/code/optimize", method = RequestMethod.POST, produces = "text/event-stream")
    public Flux<CallMessage> codeOptimize(@RequestBody @Valid CodeOptimizeDto codeOptimizeDto, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        String systemPrompt;
        if (codeOptimizeDto.getCodeLanguage() != CodeLanguageEnum.Python) {
            systemPrompt = OPTIMIZE_JS_PROMPT;
        } else {
            systemPrompt = OPTIMIZE_PYTHON_PROMPT;
        }
        ModelContext modelContext = buildModelContext(codeOptimizeDto.getRequestId(), systemPrompt, codeOptimizeDto.getPrompt());
        return modelInvoker.invoke(modelContext);
    }

    @Operation(summary = "SQL生成")
    @RequestMapping(path = "/sql/optimize", method = RequestMethod.POST, produces = "text/event-stream")
    public Flux<CallMessage> sqlOptimize(@RequestBody @Valid SQLOptimizeDto sqlOptimizeDto, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        DorisTableDefineRequest dorisTableDefineRequest = new DorisTableDefineRequest();
        dorisTableDefineRequest.setTableId(sqlOptimizeDto.getTableId());
        TableDefineVo dorisTableDefinitionVo;
        try {
            dorisTableDefinitionVo = iComposeDbTableRpcService.queryTableDefinition(dorisTableDefineRequest);
            if (dorisTableDefinitionVo == null) {
                return Flux.error(new BizException("表不存在"));
            }
        } catch (Exception e) {
            log.error("查询表结构失败", e);
            return Flux.error(new BizException("查询表结构失败"));
        }
        StringBuilder stringBuilder = new StringBuilder("数据表结构如下：\n");
        stringBuilder.append(ArgConverter.convertArgsToSimpleTableStructure(dorisTableDefinitionVo.getFieldList()));
        stringBuilder.append("\n用户SQL需求如下:\n");
        stringBuilder.append(sqlOptimizeDto.getPrompt());
        if (CollectionUtils.isNotEmpty(sqlOptimizeDto.getInputArgs())) {
            List<String> args = new ArrayList<>();
            sqlOptimizeDto.getInputArgs().forEach(inputArg -> {
                if (StringUtils.isNotBlank(inputArg.getName())) {
                    args.add("{{" + inputArg.getName() + "}}");
                }
            });
            stringBuilder.append("\n在需要使用到值的地方选择合适的变量，但不是必须，可选变量如下：\n");
            stringBuilder.append(JSON.toJSONString(args));
            stringBuilder.append("\n使用变量占位的无需使用单引号，例如 SELECT * FROM custom_table WHERE agent_id = {{agent_id}};");
            stringBuilder.append("\n使用到LIKE模糊查询时，变量使用方式为 $+变量，例如 SELECT * FROM custom_table WHERE agent_name LIKE '%${{agent_name}}%';");
        }

        ModelContext modelContext = buildModelContext(sqlOptimizeDto.getRequestId(), OPTIMIZE_SQL_PROMPT, stringBuilder.toString());
        return modelInvoker.invoke(modelContext);
    }

    private ModelContext buildModelContext(String convId, String systemPrompt, String msg) {
        ModelConfigDto modelConfigDto = modelApplicationService.queryDefaultModelConfig();
        ModelContext modelContext = new ModelContext();
        AgentContext agentContext = new AgentContext();
        agentContext.setUser((UserDto) RequestContext.get().getUser());
        agentContext.setUserId(RequestContext.get().getUserId());
        modelContext.setAgentContext(agentContext);
        modelContext.getAgentContext().setConversationId("optimize:" + convId);
        modelContext.setModelConfig(modelConfigDto);
        modelContext.setConversationId("optimize:" + convId);
        ModelCallConfigDto modelCallConfigDto = new ModelCallConfigDto();
        modelCallConfigDto.setUserPrompt(msg);
        modelCallConfigDto.setStreamCall(true);
        modelCallConfigDto.setTemperature(1.0);
        modelCallConfigDto.setTopP(0.7);
        modelCallConfigDto.setSystemPrompt(systemPrompt);
        modelCallConfigDto.setChatRound(5);
        modelCallConfigDto.setMaxTokens(modelConfigDto.getMaxTokens());
        modelContext.setModelCallConfig(modelCallConfigDto);
        return modelContext;
    }
}
