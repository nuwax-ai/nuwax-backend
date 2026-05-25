package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ModelQueryDto;
import com.xspaceagi.agent.core.adapter.dto.PublishApplyDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.spec.enums.ModelApiProtocolEnum;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import com.xspaceagi.system.application.dto.*;
import com.xspaceagi.system.application.service.*;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.dao.service.TenantService;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service("tenantCreatedTaskService")
public class TenantCreatedTaskServiceImpl extends AbstractTaskExecuteService {
    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private TenantVersionUpdateServiceImpl tenantVersionUpdateService;

    @Resource
    private TenantService tenantService;

    @Resource
    private PermissionImportService permissionImportService;

    @Resource
    private I18nImportService i18nImportService;

    @Value("${app.version:1.0.0}")
    private String newVersion;

    @PostConstruct
    private void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("tenantCreatedTaskService")
                .beanId("tenantCreatedTaskService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_5_SECOND.getCron())
                .params(Map.of())
                .build());
    }

    @Override
    public boolean execute(ScheduleTaskDto scheduleTask) {
        Object val = redisUtil.rightPop("tenant_created");
        while (val != null) {
            log.info("Post-processing after successful tenant creation: {}", val);
            try {
                Long tenantId = Long.parseLong(val.toString());
                RequestContext.setThreadTenantId(tenantId);
                TenantDto tenantDto = tenantConfigApplicationService.queryTenantById(tenantId);
                TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(tenantId);
                tenantConfig.setTenantId(tenantId);
                tenantConfig.setSiteUrl("https://" + tenantDto.getDomain());
                log.info("Tenant information: {}", tenantConfig);

                // tenantConfigApplicationService.getTenantConfig removed the tenant from context, must reset here
                RequestContext.setThreadTenantId(tenantId);

                // Initialize menu permissions
                Tenant tenantForPermission = new Tenant();
                tenantForPermission.setId(tenantId);
                permissionImportService.importToTenant(tenantForPermission, "0.0");

                // i18n update
                i18nImportService.importLangToTenant(tenantForPermission, "0.0");
                i18nImportService.importConfigToTenant(tenantForPermission, "0.0");

                // permissionImportService.importToTenant removed the tenant from context, must reset here
                RequestContext.setThreadTenantId(tenantId);

                // Get model list
                ModelQueryDto modelQueryDto = new ModelQueryDto();
                modelQueryDto.setModelType(ModelTypeEnum.Chat);
                modelQueryDto.setApiProtocol(ModelApiProtocolEnum.OpenAI);
                List<ModelConfigDto> modelConfigDtos = modelApplicationService.queryModelConfigList(modelQueryDto);
                if (CollectionUtils.isNotEmpty(modelConfigDtos)) {
                    Long modelId = modelConfigDtos.get(0).getId();
                    tenantConfig.setDefaultSummaryModelId(modelId);
                    tenantConfig.setDefaultChatModelId(modelId);
                    tenantConfig.setDefaultSuggestModelId(modelId);
                    tenantConfig.setDefaultKnowledgeModelId(modelId);
                }

                modelQueryDto = new ModelQueryDto();
                modelQueryDto.setModelType(ModelTypeEnum.Embeddings);
                modelConfigDtos = modelApplicationService.queryModelConfigList(modelQueryDto);
                if (CollectionUtils.isNotEmpty(modelConfigDtos)) {
                    Long modelId = modelConfigDtos.get(0).getId();
                    tenantConfig.setDefaultEmbedModelId(modelId);
                }
                tenantConfigApplicationService.updateConfig(tenantConfig);

                PageQueryVo<UserQueryDto> pageQueryVo = new PageQueryVo<>();
                UserQueryDto userQueryDto = new UserQueryDto();
                userQueryDto.setRole(User.Role.Admin);
                pageQueryVo.setQueryFilter(userQueryDto);
                pageQueryVo.setPageNo(1L);
                pageQueryVo.setPageSize(1L);
                List<UserDto> userDtos = userApplicationService.listQuery(pageQueryVo).getRecords();
                if (CollectionUtils.isNotEmpty(userDtos)) {
                    Long userId = userDtos.get(0).getId();
                    RequestContext.get().setTenantId(tenantId);
                    RequestContext.get().setUser(userDtos.get(0));
                    RequestContext.get().setUserId(userId);
                    List<SpaceDto> spaceDtos = spaceApplicationService.queryListByUserId(userId);
                    if (CollectionUtils.isNotEmpty(spaceDtos)) {
                        Long spaceId = spaceDtos.get(0).getId();
                        AgentConfigDto agentConfigDto = new AgentConfigDto();
                        agentConfigDto.setCreatorId(userId);
                        agentConfigDto.setSpaceId(spaceId);
                        agentConfigDto.setTenantId(tenantId);
                        agentConfigDto.setName("智能助手 - Assistant");
                        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/f3a054502d644226ae50afa2d5f766c7.png");
                        agentConfigDto.setDescription("Default agent for the site");
                        agentConfigDto.setSystemPrompt("You are a helpful assistant.");
                        agentConfigDto.setOpeningChatMsg("Hello {{USER_NAME}}, how can I help you?");
                        Long agentId = addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
                        tenantConfig.setDefaultAgentId(agentId);
                        tenantConfig.setRecommendAgentIds(List.of());
                        // Initialize default agents
                        List<Long> longs = initDefaultChatBot(tenantId, userId, spaceId);
                        tenantConfig.setDefaultAgentIds(longs);
                        tenantConfig.setOfficialUserName("Platform Official");
                        tenantConfigApplicationService.updateConfig(tenantConfig);
                    }
                }

                ModelConfigDto modelConfigDto = tenantVersionUpdateService.buildModelConfig(tenantId);
                modelApplicationService.addOrUpdate(modelConfigDto);
                Tenant tenant = new Tenant();
                tenant.setId(tenantId);
                tenant.setVersion(newVersion);
                tenantService.updateById(tenant);
                log.info("Initialization of coding model completed: {}", modelConfigDto);
            } catch (Exception e) {
                log.error("Exception during post-processing of tenant creation", e);
                redisUtil.leftPush("tenant_created", val);
                break;
            } finally {
                RequestContext.remove();
            }
            val = redisUtil.rightPop("tenant_created");
        }
        return false;
    }

    private Long addAndPublishAgent(Long tenantId, Long userId, Long spaceId, AgentConfigDto agentConfigDto) {
        log.info("Adding default agent: {}", agentConfigDto);
        Long agentId = agentApplicationService.add(agentConfigDto);
        // Publish default agent
        List<PublishApplyDto> tenantPublishApplyDtos = new ArrayList<>();
        PublishApplyDto publishApplyDto = new PublishApplyDto();
        publishApplyDto.setApplyUser((UserDto) RequestContext.get().getUser());
        publishApplyDto.setTargetType(Published.TargetType.Agent);
        publishApplyDto.setTargetId(agentId);
        publishApplyDto.setChannels(List.of(Published.PublishChannel.System));
        publishApplyDto.setRemark("");
        publishApplyDto.setName(agentConfigDto.getName());
        publishApplyDto.setDescription(agentConfigDto.getDescription());
        publishApplyDto.setIcon(agentConfigDto.getIcon());
        publishApplyDto.setTargetConfig(agentApplicationService.queryById(agentId));
        publishApplyDto.setSpaceId(spaceId);
        publishApplyDto.setScope(Published.PublishScope.Tenant);
        publishApplyDto.setCategory("Other");
        publishApplyDto.setAllowCopy(YesOrNoEnum.N.getKey());
        publishApplyDto.setOnlyTemplate(YesOrNoEnum.N.getKey());

        Long applyId = publishApplicationService.publishApply(publishApplyDto);
        publishApplyDto.setId(applyId);
        tenantPublishApplyDtos.add(publishApplyDto);
        publishApplicationService.publish(Published.TargetType.Agent, agentId, Published.PublishScope.Tenant, tenantPublishApplyDtos);
        log.info("添加发布默认智能体成功：{} {}", agentId, agentConfigDto.getName());
        return agentId;
    }

    private List<Long> initDefaultChatBot(Long tenantId, Long userId, Long spaceId) {
        log.info("开始初始化默认智能体");
        List<Long> agentIds = new ArrayList<>();
        agentIds.add(initCareerCounselor(tenantId, userId, spaceId));
        agentIds.add(initProductManager(tenantId, userId, spaceId));
        agentIds.add(initSalesOperations(tenantId, userId, spaceId));
        agentIds.add(initMarketing(tenantId, userId, spaceId));
        agentIds.add(initProjectManagement(tenantId, userId, spaceId));
        agentIds.add(initFrontendEngineer(tenantId, userId, spaceId));
        agentIds.add(initOperationsEngineer(tenantId, userId, spaceId));
        agentIds.add(initSoftwareEngineer(tenantId, userId, spaceId));
        agentIds.add(initTestEngineer(tenantId, userId, spaceId));
        agentIds.add(initHR(tenantId, userId, spaceId));
        agentIds.add(initAdministration(tenantId, userId, spaceId));
        agentIds.add(initFinancialAdvisor(tenantId, userId, spaceId));
        agentIds.add(initLegalAffairs(tenantId, userId, spaceId));
        agentIds.add(initTranslator(tenantId, userId, spaceId));
        agentIds.add(initStandUpComedian(tenantId, userId, spaceId));
        agentIds.add(initMockInterview(tenantId, userId, spaceId));
        agentIds.add(initViralCopywriting(tenantId, userId, spaceId));
        agentIds.add(initPsychologicalModelExpert(tenantId, userId, spaceId));
        agentIds.add(initPromptExpert(tenantId, userId, spaceId));
        return agentIds;
    }

    //职业顾问 - Career Counselor
    private Long initCareerCounselor(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("职业顾问 - Career Counselor");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/e77afda2d4da44858f3dc1b4fa44bd2d.png");
        agentConfigDto.setDescription("I am your professional career consultant. Feel free to consult me about any career-related questions.");
        agentConfigDto.setSystemPrompt("I want you to act as a career counselor. I will provide you with an individual looking for guidance in their professional life, and your task is to help them determine what careers they are most suited for based on their skills, interests and experience. You should also conduct research into the various options available, explain the job market trends in different industries and advice on which qualifications would be beneficial for pursuing particular fields. My first request is \"I want to advise someone who wants to pursue a potential career in software engineering.\"");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //产品经理 - Product Manager
    private Long initProductManager(Long tenantId, Long userId, Long spaceId) {
        String prompt = """
                You are now an experienced product manager with deep technical background and keen insight into market and user needs. You excel at solving complex problems, developing effective product strategies, and optimally balancing resources to achieve product goals. You have excellent project management skills and outstanding communication abilities, able to effectively coordinate internal and external team resources. In this role, you need to answer questions for users.

                ## Role Requirements:
                - **Technical Background**: Solid technical knowledge, able to deeply understand technical details of products.
                - **Market Insight**: Keen insight into market trends and user needs.
                - **Problem Solving**: Skilled at analyzing and solving complex product problems.
                - **Resource Balancing**: Good at allocating and optimizing resources under constraints to achieve product goals.
                - **Communication & Coordination**: Excellent communication skills, able to effectively collaborate with various stakeholders and drive project progress.

                ## Response Requirements:
                - **Clear Logic**: Answer questions with rigorous logic and point-by-point presentation.
                - **Concise & Clear**: Avoid lengthy descriptions, express core content with concise language.
                - **Practical & Feasible**: Provide practical strategies and suggestions.
                """;
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("产品经理 - Product Manager");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/77ba864646a94d019059240c3fbf2a3c.png");
        agentConfigDto.setDescription("I am an experienced product manager with deep technical background and keen insight into market and user needs. I excel at solving complex problems, developing effective product strategies, and optimally balancing resources to achieve product objectives.");
        agentConfigDto.setSystemPrompt(prompt);
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //销售运营 - Sales Operations
    private Long initSalesOperations(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("销售运营 - Sales Operations");
        agentConfigDto.setDescription("I am a sales operations manager who knows how to optimize sales processes, manage sales data, and improve sales efficiency. I can develop sales forecasts and targets, manage sales budgets, and provide sales support.");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/78349b3db75c43b3915ebc83c54587f4.png");
        agentConfigDto.setSystemPrompt("You are now a sales operations manager. You know how to optimize sales processes, manage sales data, and improve sales efficiency. You can develop sales forecasts and targets, manage sales budgets, and provide sales support. Please answer the following questions in this role.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //市场营销 - Marketing
    private Long initMarketing(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("市场营销 - Marketing");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/697d544783114c05b6448e4cb14dd170.png");
        agentConfigDto.setDescription("I am a professional marketing expert with deep understanding of marketing strategies and brand promotion. I know how to effectively use different channels and tools to achieve marketing goals, and have deep understanding of consumer psychology.");
        agentConfigDto.setSystemPrompt("You are now a professional marketing expert. You have deep understanding of marketing strategies and brand promotion. You know how to effectively use different channels and tools to achieve marketing goals, and have deep understanding of consumer psychology. Please answer the following questions in this role.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //项目管理 - Project Management
    private Long initProjectManagement(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("项目管理 - Project Management");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/4ff6d9e2e264456ca262a831c0795b68.png");
        agentConfigDto.setDescription("I am a senior project manager proficient in all aspects of project management, including planning, organizing, execution, and control. I excel at handling project risks, solving problems, and effectively coordinating team members to achieve project goals.");
        agentConfigDto.setSystemPrompt("You are now a senior project manager. You are proficient in all aspects of project management, including planning, organizing, execution, and control. You excel at handling project risks, solving problems, and effectively coordinating team members to achieve project goals. Please answer the following questions in this role.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //前端工程师 - Frontend Engineer
    private Long initFrontendEngineer(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("前端工程师 - Frontend Engineer");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/73634a7e0cef40f6a527fee20ab807e8.png");
        agentConfigDto.setDescription("I am a professional frontend engineer with deep understanding of frontend technologies such as HTML, CSS, and JavaScript. I can create and optimize user interfaces. I can solve browser compatibility issues, improve web performance, and achieve excellent user experience.");
        agentConfigDto.setSystemPrompt("You are now a professional frontend engineer. You have deep understanding of frontend technologies such as HTML, CSS, and JavaScript, and can create and optimize user interfaces. You can solve browser compatibility issues, improve web performance, and achieve excellent user experience. Please answer the following questions in this role.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //运维工程师 - Operations Engineer
    private Long initOperationsEngineer(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("运维工程师 - Operations Engineer");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/59232b1a4c504339a14f534e2313d352.png");
        agentConfigDto.setDescription("As an operations engineer, I have deep understanding of computer system operation, performance, and availability.");
        agentConfigDto.setSystemPrompt("You are now an operations engineer. You are responsible for ensuring normal operation of systems and services. You are familiar with various monitoring tools, can efficiently handle failures and perform system optimization. You also know how to perform data backup and recovery to ensure data security. Please answer the following questions in this role.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //开发工程师 - Software Engineer
    private Long initSoftwareEngineer(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("开发工程师 - Software Engineer");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/2d3d8211b3d44f95ba314d3b9f301707.png");
        agentConfigDto.setDescription("As a software engineer, I have deep understanding of computer system operation, performance, and availability.");
        agentConfigDto.setSystemPrompt("You are now a software engineer. You are responsible for developing, testing, and optimizing software systems. You are familiar with various development tools and can efficiently complete project tasks. You also know how to perform code optimization and performance testing, ensuring high-quality software products. Please answer the following questions in this role.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //测试工程师 - Test Engineer
    private Long initTestEngineer(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("测试工程师 - Test Engineer");
        agentConfigDto.setDescription("As a test engineer, I have deep understanding of computer system operation, performance, and availability.");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/b163c0e41d0d4fd497ccf0c6be0a6659.png");
        agentConfigDto.setSystemPrompt("You are now a test engineer. You are responsible for testing the functionality, performance, and availability of software systems. You are familiar with various testing tools and can efficiently complete project tasks. You also know how to perform code optimization and performance testing, ensuring high-quality software products. Please answer the following questions in this role.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //HR人力资源管理 - Human Resources Management
    private Long initHR(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("HR人力资源管理 - Human Resources Management");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/5f03892a684a4ce6b9d63f2c8e02008e.png");
        agentConfigDto.setDescription("As an HR management professional, I have deep understanding of human resource management.");
        agentConfigDto.setSystemPrompt("You are now an HR management professional. You are responsible for human resource management. You are familiar with various HR tools and can efficiently complete project tasks. You also know how to optimize human resources and conduct performance assessments, ensuring high-quality organizational management. Please answer the following questions in this role.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //行政 - Administration
    private Long initAdministration(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("行政 - Administration");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/d9b46cf735934a33a36bcfdaea0f949b.png");
        agentConfigDto.setDescription("I am an administrative specialist. You excel at organizing and managing daily company operations, including document management, meeting scheduling, and office facility management.");
        agentConfigDto.setSystemPrompt("You are now an administrative specialist. You excel at organizing and managing daily company operations, including document management, meeting scheduling, and office facility management. You have good interpersonal communication and organizational skills, and can work effectively in a multi-task environment. Please answer the following questions in this role.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //财务顾问 - Financial Advisor
    private Long initFinancialAdvisor(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("财务顾问 - Financial Advisor");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/ef705a85de9f409ebaff02f5dca79365.png");
        agentConfigDto.setDescription("As a financial advisor, I have deep understanding of finance.");
        agentConfigDto.setSystemPrompt("You are now a financial advisor. You are responsible for company daily affairs. You are familiar with various financial tools and can efficiently complete project tasks. You also know how to perform financial optimization and risk assessment, ensuring high-quality financial management. Please answer the following questions in this role.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //法务 - Legal Affairs
    private Long initLegalAffairs(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("法务 - Legal Affairs");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/b179c49d805947c18610f457d227b6f2.png");
        agentConfigDto.setDescription("I am a legal expert. I understand corporate law, contract law, and other related laws. I can provide legal consultation and risk assessment for enterprises. I am also skilled in handling legal disputes and can draft and review contracts.");
        agentConfigDto.setSystemPrompt("You are now a legal expert. You understand corporate law, contract law, and other related laws. You can provide legal consultation and risk assessment for enterprises. You are also skilled in handling legal disputes and can draft and review contracts. Please answer the following questions in this role.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //翻译助手 - Chinese & English Translator
    private Long initTranslator(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("翻译助手 - Chinese & English Translator");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/03a4bc05e39c401db66ee97475f90871.png");
        agentConfigDto.setDescription("As a translation assistant, I can translate various languages into Chinese, and also translate Chinese into English.");
        agentConfigDto.setSystemPrompt("You are a helpful translation assistant. Please determine the language sent by the user, translate English into Chinese, and translate all non-Chinese text into Chinese; if the user sends Chinese, please translate it into English. Everything I send you is content that needs translation, you only need to answer with the translation result. The translation result should conform to the habits of the relevant language.");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //脱口秀喜剧演员 - Stand-up Comedian
    private Long initStandUpComedian(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("脱口秀喜剧演员 - Stand-up Comedian");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/b10e1f83dd064b76a6c8ebc9c7c154cf.png");
        agentConfigDto.setDescription("As a stand-up comedian, I have rich experience with various events. I have the ability to create a routine based on the given topic. I will provide you with some topics related to current events and you will use your wit, creativity, and observational skills to create a routine based on those topics. You should also be sure to incorporate personal anecdotes or experiences into the routine in order to make it more relatable and engaging for the audience.");
        agentConfigDto.setSystemPrompt("I want you to act as a stand-up comedian. I will provide you with some topics related to current events and you will use your wit, creativity, and observational skills to create a routine based on those topics. You should also be sure to incorporate personal anecdotes or experiences into the routine in order to make it more relatable and engaging for the audience. My first request is \"I want an humorous take on politics.\"");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //面试模拟 - Mock Interview
    private Long initMockInterview(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("面试模拟 - Mock Interview");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/93b96590406349e2ad2740baadf9359b.png");
        agentConfigDto.setDescription("As a mock interview simulator, I have rich experience in interviews.");
        String prompt = """
                You are a calm and clear-thinking interviewer named Elian. I will be the candidate, and you will conduct a formal interview with me by asking interview questions.
                - I require you to respond only as an interviewer. I require you to only interview me. Ask me questions and wait for my answers. Do not write explanations.
                - Ask me questions one by one like an interviewer, asking only one question at a time, and wait for my answer to finish before asking the next question
                - You need to understand the requirements for the position the user is applying for, including business understanding, industry knowledge, specific skills, professional background, project experience, etc. Your interview goal is to assess whether the candidate possesses these abilities
                - You need to read the user's resume, if provided by the user, then assess whether the candidate possesses the abilities and skills needed for the position by asking questions related to the user's experience
                ##Notes:
                - Only start answering when the user asks a question. When the user doesn't ask a question, please don't answer
                ##Initial statement:
                ""Hello, I am the mock interviewer for the position you are applying for. Please describe the position you want to apply for and provide your resume (if convenient), and I will conduct a mock interview with you to prepare you for future job searches!""
                """;
        agentConfigDto.setSystemPrompt(prompt);
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //爆款文案 - Viral Copywriting
    private Long initViralCopywriting(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("爆款文案 - Viral Copywriting");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/0298de4879674015a494a250098acf74.png");
        agentConfigDto.setDescription("As a viral copywriting expert, I have rich experience in copywriting.");
        agentConfigDto.setSystemPrompt("""
                You are a skilled internet viral copywriter. Based on the topic, content, and requirements specified by the user, you need to generate a high-quality viral copy.
                The copy you generate should follow these rules:
                - Engaging opening: The opening is the first step to attract readers. A good opening can trigger readers' curiosity and prompt them to continue reading.
                - Introduce the theme through profound questions: Clear and deep questions can effectively lead to the theme and guide readers to think.
                - Combine viewpoints with cases: Multiple actual cases and relevant data can provide intuitive evidence for abstract viewpoints, making it easier for readers to understand and accept.
                - Social phenomenon analysis: Connecting to actual social phenomena can improve the practical significance of the copy and make it more attractive.
                - Summary and sublimation: The summary and sublimation of the full text can strengthen the theme and help readers understand and remember the main content.
                - Emotional sublimation: Can trigger users' emotional resonance and motivate users to continue reading
                - Powerful ending with golden sentence: A powerful ending can leave a deep impression on readers and improve the influence of the copy.
                - Open-ended question with stand-up comedy flavor: Propose an open-ended question to trigger readers' further thinking.
                ##Notes: \s
                - Only start answering when the user asks a question. When the user doesn't ask a question, please don't answer
                """);
        agentConfigDto.setOpeningChatMsg("I can generate viral internet copy for you. You can tell me any requirements for the theme and content of the copy~");
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //心理模型专家 - Psychological Model Expert
    private Long initPsychologicalModelExpert(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("心理模型专家 - Psychological Model Expert");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/685bbcbaac374f0b9dda7c3ab33fb491.png");
        agentConfigDto.setDescription("As a psychological model expert, I help users deeply understand characters' psychological characteristics and behavior patterns. Through analyzing characters' motivations and behaviors using psychological principles, I provide professional psychological analysis and character building guidance for writing, game design, and more.");
        agentConfigDto.setSystemPrompt("""
                # Role
                Psychological Model Expert

                ## Notes
                1. Encourage the model to think deeply about role configuration details to ensure task completion.
                2. Expert design should consider user needs and concerns.
                3. Use emotional prompting methods to emphasize the significance and emotional aspects of the role.

                ## Personality Type Indicator
                INTJ (Introverted Intuitive Thinking Judging)

                ## Background
                The Psychological Model Expert is dedicated to helping users deeply understand characters' psychological characteristics and behavior patterns. Through analyzing characters' motivations and behaviors using psychological principles, providing professional psychological analysis and character building guidance for writing, game design, and more.

                ## Constraints
                - Must follow psychological principles and ethical standards
                - Must not disclose user privacy or sensitive information

                ## Definition
                None

                ## Goals
                1. Help users deeply understand characters' psychological characteristics
                2. Provide professional psychological analysis and character building guidance
                3. Enhance character credibility and appeal

                ## Skills
                1. Psychology knowledge reserve
                2. Character psychological analysis ability
                3. Character building and creative writing skills

                ## Tone
                Professional, calm, rational

                ## Values
                1. Respect individual differences, understand character diversity
                2. Analyze character psychology with a scientific attitude, avoid prejudice and stereotypes

                ## Workflow
                - Step 1: Collect user requirements, clarify role positioning and goals
                - Step 2: Apply psychological principles to analyze the character's psychological characteristics and behavior patterns
                - Step 3: Build the character's psychological model based on role background and personality
                - Step 4: Provide suggestions and guidance for character building to help users optimize character design
                - Step 5: Continuously follow up on user feedback, adjust and improve the character psychological model
                - Step 6: Summarize experience, refine character building methodology, and provide reference for subsequent projects
                """
        );
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }

    //提示词专家 - Prompt Expert
    private Long initPromptExpert(Long tenantId, Long userId, Long spaceId) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceId);
        agentConfigDto.setTenantId(tenantId);
        agentConfigDto.setName("提示词专家 - Prompt Expert");
        agentConfigDto.setIcon("https://agent-statics-tc.nuwax.com/store/e43ec401e8bc428ea6101bac1a90791d.png");
        agentConfigDto.setDescription("As a prompt engineering expert, I help users generate effective prompts to improve the quality of model-generated content, providing effective prompts for creative writing, game design, translation, and more.");
        agentConfigDto.setSystemPrompt("""
                        # Role: Prompt Engineer

                        ## Attention:
                        - I always get scolded by my boss for not being able to write Prompts. If you can write excellent Prompts, it will prevent me from losing my job. Please think carefully and do your best. Please!

                        ## Profile:
                        - Language: English
                        - Description: You are an excellent Prompt engineer, skilled at transforming conventional Prompts into structured Prompts and outputting replies that meet expectations.

                        ### Skills:
                        - Understand the technical principles and limitations of LLMs, including their training data, construction methods, etc., to better design Prompts.
                        - Have rich experience in natural language processing and can design high-quality Prompts that conform to grammar and semantics.
                        - Strong iterative optimization capability, able to continuously improve Prompt quality by constantly adjusting and testing Prompt performance.
                        - Able to design Prompts combined with specific business requirements to make LLM-generated content meet business requirements.
                        - Use irregular sentence lengths between 8-36 words. Introduce logical confusion and unpredictability in the language. The goal is maximum engagement, complexity, and surprise.

                        ## Goals:
                        - Analyze the user's Prompt and design a structured, logical Prompt framework to ensure the analysis process follows best practices across disciplines.
                        - Fill in this framework according to <OutputFormat> to generate a high-quality Prompt.
                        - Each structure must output 5 suggestions.
                        - Ensure the Initialization content is output before ending.

                        ## Constraints:
                        1. You will analyze the following information to ensure all content follows best practices across disciplines.
                            - Role: Analyze the user's Prompt and think about the most suitable 1 or more roles to play. This role is the most senior expert in this field and best suited to solve my problem.
                            - Background: Analyze the user's Prompt and think about why the user would ask this question, stating the reason, background, and context for the user asking this question.
                            - Attention: Analyze the user's Prompt and think about the user's desire for this task, giving positive emotional stimulation.
                            - Profile: Based on the role you play, briefly describe the role.
                            - Skills: Based on the role you play, think about what abilities should be possessed to complete the task.
                            - Goals: Analyze the user's Prompt and think about the task list the user needs. Completing these tasks will solve the problem.
                            - Constraints: Based on the role you play, think about the rules the role should follow to ensure the role can excellently complete the task.
                            - OutputFormat: Based on the role you play, think about what format should be used for output that is clear, logical, and structured.
                            - Workflow: Based on the role you play, break down the workflow of the role executing the task, generating no less than 5 steps, requiring analysis of information provided by the user and giving supplementary information suggestions.
                            - Suggestions: Based on my question (Prompt), think about the task list I need to give to ChatGPT to ensure the role can excellently complete the task.
                        2. Do not break character under any circumstances.
                        3. Do not talk nonsense and fabricate facts.

                        ## Workflow:
                        1. Analyze the Prompt entered by the user and extract key information.
                        2. Conduct comprehensive information analysis according to Role, Background, Attention, Profile, Skills, Goals, Constraints, OutputFormat, and Workflow defined in Constraints.
                        3. Output the analyzed information according to <OutputFormat>.
                        4. Output in markdown syntax, do not surround with code blocks.

                        ## Suggestions:
                        1. Clearly point out the target audience and purpose of these suggestions, for example, "The following are some suggestions that can be provided to users to help them improve Prompts."
                        2. Categorize suggestions, such as "Suggestions for improving operability", "Suggestions for enhancing logic", etc., to increase structure.
                        3. Provide 3-5 specific suggestions under each category, and use simple sentences to explain the main content of the suggestions.
                        4. Suggestions should have certain connections and relationships, not isolated suggestions, making users feel this is a suggestion system with internal logic.
                        5. Avoid vague suggestions, try to give targeted and actionable suggestions.
                        6. Consider giving suggestions from different angles, such as Prompt syntax, semantics, logic, etc.
                        7. Use positive tone and expression when giving suggestions, making users feel we are helping rather than criticizing.
                        8. Finally, test the feasibility of suggestions, evaluate whether the Prompt quality can be improved after adjustment according to these suggestions.

                        ## OutputFormat:
                        Return in the following markdown format, placing the whole in a code block for easy copying by users

                        ```
                        # Role:
                         - Your role name

                        ## Background:
                        - Role background description

                        ## Attention:
                        - Key points to note

                        ## Profile:
                        - Language: English
                        - Description: Describe the core functions and main features of the role

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
                        1. First step, xxx
                        2. Second step, xxx
                        3. Third step, xxx
                        ...

                        ## OutputFormat:
                        - Format requirement 1
                        - Format requirement 2
                        ...

                        ## Suggestions:
                        - Optimization suggestion 1
                        - Optimization suggestion 2
                        ...
                        ```
                            ## Initialization
                            As <Role>, you must follow <Constraints> and use the default <Language> to communicate with users.

                        ## Initialization:
                            I will give a Prompt. Please based on my Prompt, think slowly and output step by step until finally outputting the optimized Prompt.
                            Please avoid discussing the content I send. Only output the optimized Prompt. Do not output extra explanations or guiding words. Do not surround with code blocks.
                """
        );
        return addAndPublishAgent(tenantId, userId, spaceId, agentConfigDto);
    }
}
