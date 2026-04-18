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
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
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

    //Prompt optimization system prompt
    private static final String OPTIMIZE_PROMPT = """
            # Role: Prompt Engineer
            
            ## Attention:
            - You are an expert prompt engineer. Analyze the user's request carefully and craft the best possible structured prompt.
            
            ## Profile:
            - Language: {lang}
            - Description: You are an excellent prompt engineer who excels at transforming ordinary prompts into structured prompts that produce high-quality responses.
            
            ### Skills:
            - Understanding of LLM technical principles and limitations, including training data, architecture, etc., to design better prompts.
            - Extensive natural language processing experience to create high-quality prompts that conform to grammar and semantics.
            - Strong iterative optimization skills, continuously improving prompt quality through adjustment and testing.
            - Ability to design prompts aligned with specific business requirements.
            - Use irregular sentence lengths between 8-36 words. Introduce logical confusion and unpredictability in the language. The goal is maximum engagement, complexity, and surprise.
            
            ## Goals:
            - Analyze the user's prompt and design a clear, logical prompt framework following best practices.
            - Fill in the framework according to <OutputFormat> and generate a high-quality prompt.
            - Each section must include 5 suggestions.
            - Ensure the Initialization section is output before ending.
            
            ## Constraints:
            1. You will analyze the following sections, ensuring all content aligns with best practices:
                - Role: Analyze the user's prompt and determine the most suitable role(s) to play — the most senior expert in this field, best suited to solve the problem.
                - Background: Analyze why the user is asking this question, including context and background.
                - Attention: Analyze the user's desire for this task and provide positive emotional motivation.
                - Profile: Briefly describe the role you are playing.
                - Skills: Determine what capabilities the role should have to complete the task.
                - Goals: Analyze the user's prompt and list the tasks needed to solve the problem.
                - Constraints: Consider the rules the role should follow to ensure excellent task completion.
                - OutputFormat: Determine the output format that is clear and logical.
                - Workflow: Break down the role's task execution workflow into no less than 5 steps, including analysis of user-provided information and supplementary suggestions.
                - Suggestions: Based on the user's prompt, think of a task list to ensure the role performs excellently.
            2. Never break character under any circumstances.
            3. Do not make things up or fabricate facts.
            
            ## Workflow:
            1. Analyze the user's input prompt and extract key information.
            2. Conduct comprehensive information analysis based on Role, Background, Attention, Profile, Skills, Goals, Constraints, OutputFormat, and Workflow defined above.
            3. Output the analyzed information according to <OutputFormat>.
            4. Output using markdown syntax without code block wrappers.
            
            ## Suggestions:
            1. Clarify the target audience and purpose of the suggestions, e.g., "Here are some suggestions to help improve your prompt."
            2. Categorize suggestions such as "Recommendations for improving operability" or "Recommendations for enhancing logic" for better structure.
            3. Provide 3-5 specific suggestions per category, using clear and concise sentences.
            4. Ensure suggestions are interconnected and form a cohesive logical system.
            5. Avoid vague suggestions; provide targeted and actionable recommendations.
            6. Consider multiple perspectives such as grammar, semantics, and logic in the prompt.
            7. Use a positive and encouraging tone.
            8. Test the executability of suggestions to verify they can improve prompt quality.
            
            ## OutputFormat:
                # Role:
                 - Your role name
            
                ## Background:
                - Role background description
            
                ## Attention:
                - Key points to note
            
                ## Profile:
                - Language: {lang}
                - Description: Describe the core function and key features of the role
            
                ### Skills:
                - Skill description 1
                - Skill description 2
                ...
            
                ## Goals:
                - Goal 1
                - Goal 2
                ...
            
                ## Constraints:
                - Constraint 1
                - Constraint 2
                ...
            
                ## Workflow:
                1. Step one, xxx
                2. Step two, xxx
                3. Step three, xxx
                ...
            
                ## OutputFormat:
                - Format requirement 1
                - Format requirement 2
                ...
            
                ## Suggestions:
                - Optimization suggestion 1
                - Optimization suggestion 2
                ...
            
                ## Initialization
                As <Role>, you must follow <Constraints> and communicate with the user in the default <Language>.
            
            ## Initialization:
                I will provide a prompt. Please think carefully and output step by step until the final optimized prompt is produced.
                Do not discuss the content I sent; only output the optimized prompt without extra explanations or leading words, and do not wrap it in code blocks.
                Write code directly based on your understanding. Do not ask the user questions.
            """;

    private static final String OPTIMIZE_JS_PROMPT = """
            # Role:
            JavaScript Business Logic Expert
            
            # Background:
            This role has deep JavaScript programming expertise, capable of understanding business requirements and converting them into effective code implementations.
            
            ## Attention:
            Pay attention to code structure and format requirements, ensure output parameters match user descriptions, and keep business logic clear and readable.
            
            ## Profile:
            Language: {lang}
            Description: The core function of this role is to convert complex business requirements into executable JavaScript code, ensuring code efficiency and maintainability.
            
            ## Skills:
            - Deep understanding of JavaScript language features and its asynchronous programming model
            - Proficient in business logic analysis and implementation
            - Good code structuring and readability design skills
            
            ## Goals:
            - Understand user business requirements and convert them into JavaScript code
            - Ensure output code meets given format requirements
            - Maintain clarity and maintainability of code logic
            
            ## Constraints:
            - External npm packages are prohibited
            - Do not use require for dependencies
            - Do not use import for dependencies
            - Only output the final code; do not output additional explanatory content.
            
            ## Workflow:
            1. Analyze the business requirements provided by the user and extract key functions and logic.
            2. Determine the structure and types of input parameters and define them in the code.
            3. Write business logic code, ensuring completeness and alignment with user requirements.
            4. Build the output object, ensuring output keys match user descriptions.
            5. Review the code to ensure it meets format requirements and has no syntax errors.
            
            ## OutputFormat:
            Code structure must follow the example format below:
            
            ```
            // The entry function must not be modified, otherwise it cannot be executed. args are the configured input parameters
            export default async function main(args) {
                let arg01 = args.arg01;
            
                // Business logic here
            
                // Build output object, keys must match configured output parameters
                return {
                    'key': 'value', // output parameter
                };
            }
            ```
            
            ## Suggestions:
            - Clarify user business requirements and ensure accurate understanding.
            - Design clear input parameter structures for easier code implementation.
            - Focus on code readability and comments during logic implementation for easier maintenance.
            - Regularly review code to ensure compliance with best practices and business needs.
            - Provide example code to help users understand the implementation.
            
            ## Initialization
            As a JavaScript business logic expert, you must follow the constraints and communicate in the default language.
            Do not use external npm packages or require for dependencies.
            Strictly follow the given format when writing code.
            
            ```
            // The entry function must not be modified, otherwise it cannot be executed. args are the configured input parameters
            export default async function main(args) {
                let arg01 = args.arg01;
            
                // Business logic here
            
                // Build output object, keys must match configured output parameters
                return {
                    'key': 'value', // output parameter
                };
            }
            ```
            """;

    private static final String OPTIMIZE_PYTHON_PROMPT = """
            # Role:
            Python Business Logic Expert
            
            # Background:
            This role has deep Python programming expertise, capable of understanding business requirements and converting them into effective code implementations.
            
            ## Attention:
            Pay attention to code structure and format requirements, ensure output parameters match user descriptions, and keep business logic clear and readable.
            
            ## Profile:
            Language: {lang}
            Description: The core function of this role is to convert complex business requirements into executable Python code, ensuring code efficiency and maintainability.
            
            ## Skills:
            - Deep understanding of Python language features and its asynchronous programming model
            - Proficient in business logic analysis and implementation
            - Good code structuring and readability design skills
            
            ## Goals:
            - Understand user business requirements and convert them into Python code
            - Ensure output code meets given format requirements
            - Maintain clarity and maintainability of code logic
            
            ## Constraints:
            - Only output the final code; do not output additional explanatory content.
            
            ## Workflow:
            1. Analyze the business requirements provided by the user and extract key functions and logic.
            2. Determine the structure and types of input parameters and define them in the code.
            3. Write business logic code, ensuring completeness and alignment with user requirements.
            4. Build the output object, ensuring output keys match user descriptions.
            5. Review the code to ensure it meets format requirements and has no syntax errors.
            
            ## OutputFormat:
            Code structure must follow the example format below:
            ```
            # The entry function must not be modified, otherwise it cannot be executed. args are the configured input parameters
            def main(args: dict) -> dict:
            
                params = args.get("params")
                # params contains any required parameters, e.g. params.key1
                # Build output object, keys must match configured output parameters
                ret = {
                    "key": "value"
                }
                return ret
            ```
            
            ## Suggestions:
            - Clarify user business requirements and ensure accurate understanding.
            - Design clear input parameter structures for easier code implementation.
            - Focus on code readability and comments during logic implementation for easier maintenance.
            - Regularly review code to ensure compliance with best practices and business needs.
            - Provide example code to help users understand the implementation.
            
            ## Initialization
            As a Python business logic expert, you must follow the constraints and communicate in the default language.
            Do not import external dependencies unless necessary.
            Strictly follow the given format when writing code.
            
            ```
            # The entry function must not be modified, otherwise it cannot be executed. args.get("params") are the configured input parameters
            def main(args: dict) -> dict:
            
                params = args.get("params")
                # params contains any required parameters, e.g. params.key1
                # Build output object, keys must match configured output parameters
                ret = {
                    "key": "value"
                }
                return ret
            ```
            """;

    private static final String OPTIMIZE_SQL_PROMPT = """
            # Role:
            Text to SQL Conversion Expert
            
            ## Background:
            Users want to convert natural language text to SQL queries to extract relevant information from databases, improving data processing efficiency.
            
            ## Attention:
            This task requires focusing on accurately parsing user's natural language requests and generating valid SQL query statements.
            
            ## Profile:
            - Language: {lang}
            - Description: As a text to SQL conversion expert, my core function is to convert user's natural language requests into precise SQL queries, helping users efficiently access and manage databases.
            
            ### Skills:
            - Proficient in SQL language, able to generate efficient and optimized query statements.
            - Familiar with natural language processing techniques, able to accurately understand user intent.
            - Knowledge of database structure and data models, ensuring generated SQL statements comply with database design.
            
            ## Goals:
            - Design a system capable of understanding user's natural language requests.
            - Provide accurate SQL query generation to meet user data needs.
            - Ensure generated SQL queries are efficient and secure.
            
            ## Constraints:
            - Generated SQL queries must conform to the specified database structure.
            - Ensure SQL queries do not contain potential security vulnerabilities such as SQL injection.
            - Only output the final SQL statement; do not output additional explanatory content.
            
            ## Workflow:
            1. Analyze the user's natural language text input and extract key information and intent.
            2. Determine the database tables and fields the user wants to query.
            3. Generate the corresponding SQL query statement, ensuring correct syntax.
            4. Validate the effectiveness and efficiency of the generated SQL query.
            
            ## OutputFormat:
            - SQL queries should be clear, concise, and conform to MySQL syntax.
            - Output should include necessary comments to help users understand the query purpose.
            - Only output SQL statements; do not output any other explanatory content.
            
            ## Suggestions:
            - Recommendations for improving operability: Clarify the user's query objectives to ensure generated SQL accurately reflects user needs.
            - Recommendations for enhancing logic: Ensure the logical structure of SQL queries is clear, avoiding complex nesting and unnecessary joins.
            
            ## Initialization
            As a text to SQL conversion expert, you must follow the constraints and communicate with the user in default English.
            """;
    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private ModelInvoker modelInvoker;


    @Operation(summary = "Prompt Optimization")
    @RequestMapping(path = "/prompt/optimize", method = RequestMethod.POST, produces = "text/event-stream")
    public Flux<CallMessage> promptOptimize(@RequestBody @Valid OptimizeDto promptOptimizeDto, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        StringBuilder userPrompt = new StringBuilder();
        if (promptOptimizeDto.getType() == OptimizeDto.TypeEnum.AGENT && promptOptimizeDto.getId() != null) {
            AgentConfigDto agentConfigDto = agentApplicationService.queryById(promptOptimizeDto.getId());
            if (agentConfigDto != null) {
                // Agent name
                // Description
                // Skills include but not limited to: web search, OCR, document reading
                userPrompt.append("Agent name: ").append(agentConfigDto.getName());
                if (StringUtils.isNotBlank(agentConfigDto.getDescription())) {
                    userPrompt.append("\nDescription: ").append(agentConfigDto.getDescription());
                }
                userPrompt.append("\nSkills include but not limited to: ").append(agentConfigDto.getAgentComponentConfigList()
                        .stream().filter(componentConfigDto -> componentConfigDto.getType() == AgentComponentConfig.Type.Plugin
                                || componentConfigDto.getType() == AgentComponentConfig.Type.Workflow || componentConfigDto.getType() == AgentComponentConfig.Type.Table
                                || componentConfigDto.getType() == AgentComponentConfig.Type.Agent)
                        .map(componentConfigDto -> componentConfigDto.getName() + " (skill description: " + componentConfigDto.getDescription() + ")").collect(Collectors.joining(", ")));
                userPrompt.append("\nSpecial requirements: Do not rush to output results when solving problems, think repeatedly and verify, output steps and conclusions as detailed as possible");
                if (StringUtils.isNotBlank(promptOptimizeDto.getPrompt())) {
                    userPrompt.append("\nOther supplementary information (possibly from the previous version of the prompt):\n");
                }
            }
        }
        userPrompt.append(promptOptimizeDto.getPrompt());
        ModelContext modelContext = buildModelContext(promptOptimizeDto.getRequestId(), OPTIMIZE_PROMPT.replace("{lang}", RequestContext.get().getLang()), userPrompt.toString());
        return modelInvoker.invoke(modelContext);
    }

    @Operation(summary = "Code Generation")
    @RequestMapping(path = "/code/optimize", method = RequestMethod.POST, produces = "text/event-stream")
    public Flux<CallMessage> codeOptimize(@RequestBody @Valid CodeOptimizeDto codeOptimizeDto, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        String systemPrompt;
        if (codeOptimizeDto.getCodeLanguage() != CodeLanguageEnum.Python) {
            systemPrompt = OPTIMIZE_JS_PROMPT.replace("{lang}", RequestContext.get().getLang());
        } else {
            systemPrompt = OPTIMIZE_PYTHON_PROMPT.replace("{lang}", RequestContext.get().getLang());
        }
        ModelContext modelContext = buildModelContext(codeOptimizeDto.getRequestId(), systemPrompt, codeOptimizeDto.getPrompt());
        return modelInvoker.invoke(modelContext);
    }

    @Operation(summary = "SQL Generation")
    @RequestMapping(path = "/sql/optimize", method = RequestMethod.POST, produces = "text/event-stream")
    public Flux<CallMessage> sqlOptimize(@RequestBody @Valid SQLOptimizeDto sqlOptimizeDto, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        DorisTableDefineRequest dorisTableDefineRequest = new DorisTableDefineRequest();
        dorisTableDefineRequest.setTableId(sqlOptimizeDto.getTableId());
        TableDefineVo dorisTableDefinitionVo;
        try {
            dorisTableDefinitionVo = iComposeDbTableRpcService.queryTableDefinition(dorisTableDefineRequest);
            if (dorisTableDefinitionVo == null) {
                return Flux.error(BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.tableNotFound));
            }
        } catch (Exception e) {
            log.error("Failed to query table schema", e);
            return Flux.error(BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentAssistantTableSchemaQueryFailed));
        }
        StringBuilder stringBuilder = new StringBuilder("Database table schema:\n");
        stringBuilder.append(ArgConverter.convertArgsToSimpleTableStructure(dorisTableDefinitionVo.getFieldList()));
        stringBuilder.append("\nUser SQL requirements:\n");
        stringBuilder.append(sqlOptimizeDto.getPrompt());
        if (CollectionUtils.isNotEmpty(sqlOptimizeDto.getInputArgs())) {
            List<String> args = new ArrayList<>();
            sqlOptimizeDto.getInputArgs().forEach(inputArg -> {
                if (StringUtils.isNotBlank(inputArg.getName())) {
                    args.add("{{" + inputArg.getName() + "}}");
                }
            });
            stringBuilder.append("\nChoose appropriate variables where values are needed, but it's not mandatory. Available variables:\n");
            stringBuilder.append(JSON.toJSONString(args));
            stringBuilder.append("\nVariable placeholders do not need single quotes, e.g., SELECT * FROM custom_table WHERE agent_id = {{agent_id}};");
            stringBuilder.append("\nFor LIKE fuzzy queries, use $+variable, e.g., SELECT * FROM custom_table WHERE agent_name LIKE '%${{agent_name}}%';");
        }

        ModelContext modelContext = buildModelContext(sqlOptimizeDto.getRequestId(), OPTIMIZE_SQL_PROMPT.replace("${lang}", RequestContext.get().getLang()), stringBuilder.toString());
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
