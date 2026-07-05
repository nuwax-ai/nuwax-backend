package com.xspaceagi.system.infra.dao.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.infra.dao.entity.TenantConfig;
import com.xspaceagi.system.infra.dao.mapper.TenantConfigMapper;
import com.xspaceagi.system.infra.dao.service.TenantConfigService;
import com.xspaceagi.system.infra.dao.service.TenantService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TenantConfigServiceImpl extends ServiceImpl<TenantConfigMapper, TenantConfig> implements TenantConfigService {

    private static final List<TenantConfig> tenantConfigDefaultList;

    private static final String CODE_SAFE_CHECK_PROMPT = """
            """;

    private static final String GLOBAL_SYSTEM_PROMPT = """
            """;

    static {
        tenantConfigDefaultList = new ArrayList<>();
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("templateConfig")
                .description("主题配置")
                .value("{}")
                .notice("主题配置")
                .placeholder("请输入主题配置")
                .category(TenantConfig.ConfigCategory.TemplateConfig)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .required(true)
                .sort(0)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("siteName")
                .description("站点名称")
                .value("Nuwax AgentOS")
                .notice("")
                .placeholder("请输入站点名称")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .required(true)
                .sort(0)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("siteUrl")
                .description("站点访问地址")
                .value("http://127.0.0.1")
                .notice("")
                .placeholder("请输入站点访问地址")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .required(true)
                .sort(1)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("siteDescription")
                .description("站点介绍")
                .value("Nuwax AgentOS")
                .notice("")
                .placeholder("请输入站点介绍")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(true)
                .sort(10)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("siteLogo")
                .description("站点LOGO")
                .value("https://s3.nuwax.com:9443/nuwax-public/icons/v3-release-icons/f3a054502d644226ae50afa2d5f766c7.png")
                .notice("")
                .placeholder("请上传站点LOGO")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.File)
                .dataType(TenantConfig.DataType.String)
                .required(true)
                .sort(20)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("faviconUrl")
                .description("浏览器地址栏显示图标")
                .value("https://s3.nuwax.com:9443/nuwax-public/icons/v3-release-icons/961a8939eb384eafb102008ad045f5de.ico")
                .notice("")
                .placeholder("请上传浏览器地址栏显示图标")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.File)
                .dataType(TenantConfig.DataType.String)
                .required(true)
                .sort(21)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("squareBanner")
                .description("智能体广场Banner")
                .value("")
                .notice("")
                .placeholder("请上传智能体广场Banner")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.File)
                .dataType(TenantConfig.DataType.String)
                .required(false)
                .sort(50)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("squareBannerText")
                .description("智能体广场Banner文案标题")
                .value("Nuwax - an open-source intelligent agent operating system")
                .notice("")
                .placeholder("请输入智能体广场Banner文案标题")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(true)
                .sort(60)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("squareBannerSubText")
                .description("智能体广场Banner文案副标题")
                .value("Provides a complete set of intelligent agent development components (orchestration, debugging, monitoring, publishing, etc.)")
                .notice("")
                .placeholder("请输入智能体广场Banner文案副标题")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(true)
                .sort(61)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("squareBannerLinkUrl")
                .description("智能体广场Banner链接")
                .value("")
                .notice("")
                .placeholder("请输入智能体广场Banner跳转链接")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(62)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("loginPageText")
                .description("登录页面文案标题")
                .value("Nuwax<br>An open-source agent operating system")
                .notice("")
                .placeholder("请输入登录页文案标题")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(true)
                .sort(63)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("loginPageSubText")
                .description("登录页面文案副标题")
                .value("Provides a complete set of agent development components (orchestration, debugging, monitoring, publishing, etc.)")
                .notice("")
                .placeholder("请输入登录页面文案副标题")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(true)
                .sort(64)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("agentPublishAudit")
                .description("是否开启智能体发布审核")
                .value(1)
                .notice("开启后智能体发布需要审核通过后才能上线")
                .placeholder("1:开启,0:关闭")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(true)
                .sort(65)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("pluginPublishAudit")
                .description("是否开启插件发布审核")
                .value(0)
                .notice("开启后插件发布需要审核通过后才能上线")
                .placeholder("1:开启,0:关闭")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(true)
                .sort(66)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("workflowPublishAudit")
                .description("是否开启工作流发布审核")
                .value(0)
                .notice("开启后工作流发布需要审核通过后才能上线")
                .placeholder("1:开启,0:关闭")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(true)
                .sort(67)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("skillPublishAudit")
                .description("是否开启技能发布审核")
                .value(0)
                .notice("开启后技能发布需要审核通过后才能上线")
                .placeholder("1:开启,0:关闭")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(true)
                .sort(68)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("authType")
                .description("认证类型")
                .value(3)
                .notice("是否开启注册，认证类型为手机登录时有效")
                .placeholder("1:手机登录,2:CAS单点登录,3:邮箱登录")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(true)
                .sort(65)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("authExpire")
                .description("认证过期时间（分钟）")
                .value(60 * 24 * 7)
                .notice("在配置的认证过期时间内如果用户没有活跃，则过期退出登录，若用户持续活跃则自动续期")
                .placeholder("请输入认证过期时间")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(true)
                .sort(66)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("openRegister")
                .description("是否开启注册")
                .value(1)
                .notice("是否开启注册，认证类型为手机或邮箱登录时有效")
                .placeholder("1:开启,0:关闭")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(true)
                .sort(70)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("casLoginUrl")
                .description("CAS登录地址")
                .value("")
                .notice("当认证类型选择为CAS单点登录时有效，例如 https://abc.com/cas/login")
                .placeholder("请输入CAS登录地址")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(71)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("casValidateUrl")
                .description("CAS验证地址")
                .value("")
                .notice("当认证类型选择为CAS单点登录时有效,例如 https://abc.com/cas/serviceValidate")
                .placeholder("请输入CAS验证地址")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(71)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("casClientHostUrl")
                .description("CAS客户端主机根地址")
                .value("")
                .notice("当认证类型选择为CAS单点登录时有效,例如 https://yourdomain.com/")
                .placeholder("请输入CAS客户端主机根地址")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(72)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("openCodeSafeCheck")
                .description("是否开启代码插件安全检查")
                .value(0)
                .notice("")
                .placeholder("1:开启,0:关闭")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(false)
                .sort(73)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("codeSafeCheckPrompt")
                .description("代码插件安全检查提示词")
                .value(CODE_SAFE_CHECK_PROMPT)
                .notice("用于审查代码中是否出现了安全漏洞，比如禁止端口扫描、内网请求等")
                .placeholder("请输入代码插件安全检查提示词")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(74)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("userWhiteList")
                .description("代码插件安全检查白名单用户")
                .value(new ArrayList<>())
                .notice("这些用户将不做任何安全检查")
                .placeholder("请输入用户名")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.MultiInput)
                .dataType(TenantConfig.DataType.Array)
                .minHeight(100)
                .required(false)
                .sort(75)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("globalSystemPrompt")
                .description("全局提示词")
                .value(GLOBAL_SYSTEM_PROMPT)
                .notice("全局提示词，用于对大模型进行全局性的偏向引导等")
                .placeholder("请输入全局提示词")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(76)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("allowAgentTempChat")
                .description("开启允许用户使用临时会话链接（管理员不受限）")
                .value(1)
                .notice("开启后，用户可以将智能体生成独立的会话链接，挂载到其他网站上进行使用")
                .placeholder("1:开启,0:关闭")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(false)
                .sort(76)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("allowAgentApi")
                .description("开启允许用户通过API调用智能体（管理员不受限）")
                .value(1)
                .notice("开启后，用户可以通过API调用智能体")
                .placeholder("1:开启,0:关闭")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(false)
                .sort(76)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("allowMcpExport")
                .description("开启允许用户导出MCP服务（管理员不受限）")
                .value(1)
                .notice("开启后，用户可以将在平台开发的插件、工作流、数据表、知识库包装成MCP服务导出到其他平台或客户端进行使用")
                .placeholder("1:开启,0:关闭")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(false)
                .sort(76)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("smtpHost")
                .description("邮件服务器smtp主机地址")
                .value("")
                .notice("邮件服务器smtp主机地址，例如 smtp.your-domain.com")
                .placeholder("请输入你的邮件服务器smtp主机地址")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(77)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("smtpPort")
                .description("邮件服务器smtp主机端口")
                .value(465)
                .notice("邮件服务器smtp主机端口，例如 465")
                .placeholder("请输入你的邮件服务器smtp主机地址")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(false)
                .sort(78)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("smtpUsername")
                .description("邮件服务器smtp用户名")
                .value("")
                .notice("邮件服务器smtp用户名")
                .placeholder("请输入你的邮件服务器smtp用户名")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(79)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("smtpPassword")
                .description("邮件服务器smtp密码")
                .value("")
                .notice("邮件服务器smtp密码")
                .placeholder("请输入你的邮件服务器smtp密码")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(80)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("smsAccessKeyId")
                .description("阿里云短信AccessKeyId")
                .value("")
                .notice("支持阿里云短信")
                .placeholder("请输入短信AccessKeyId")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(81)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("smsAccessKeySecret")
                .description("阿里云短信AccessKeySecret")
                .value("")
                .notice("支持阿里云短信")
                .placeholder("请输入短信AccessKeySecret")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(82)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("smsSignName")
                .description("短信签名")
                .value("")
                .notice("支持阿里云短信，在阿里云短信平台申请")
                .placeholder("请输入短信签名")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(83)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("smsTemplateCode")
                .description("短信模板CODE")
                .value("")
                .notice("支持阿里云短信，在阿里云短信平台申请，模板动态参数为：code")
                .placeholder("请输入短信模板CODE")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(84)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("openCaptcha")
                .description("是否开启验证码")
                .value(0)
                .notice("是否开启验证码，开启后在登录注册时会触发验证")
                .placeholder("1:开启,0:关闭")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .minHeight(100)
                .required(true)
                .sort(85)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("captchaAccessKeyId")
                .description("阿里云验证码AccessKeyId")
                .value("")
                .notice("支持阿里云验证码")
                .placeholder("请输入阿里云验证码AccessKeyId")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(86)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("captchaAccessKeySecret")
                .description("阿里云验证码AccessKeySecret")
                .value("")
                .notice("支持阿里云验证码")
                .placeholder("请输入阿里云验证码AccessKeySecret")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(87)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("captchaPrefix")
                .description("阿里云验证码身份标")
                .value("")
                .notice("支持阿里云验证码，在阿里云申请，文档 https://help.aliyun.com/zh/captcha/captcha2-0/user-guide/access-guidelines?spm=a2c4g.11186623.help-menu-2401270.d_2_1.e91d7f0936Xygi")
                .placeholder("请输入阿里云验证码身份标")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(88)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("captchaSceneId")
                .description("阿里云验证码场景ID")
                .value("")
                .notice("支持阿里云验证码，在阿里云申请")
                .placeholder("请输入阿里云验证码场景ID")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(89)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("mpAppId")
                .description("小程序appId（未发布小程序时可忽略）")
                .value("")
                .notice("小程序appId")
                .placeholder("请输入小程序appId")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(90)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("mpAppSecret")
                .description("小程序secret（未发布小程序时可忽略）")
                .value("")
                .notice("小程序secret")
                .placeholder("请输入小程序secret")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(90)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("oaAppId")
                .description("公众号 appId（微信内 H5 JSAPI / OAuth，未使用时忽略）")
                .value("")
                .notice("公众号 appId")
                .placeholder("请输入公众号 appId")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(91)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("oaAppSecret")
                .description("公众号 secret（微信内 H5 JSAPI / OAuth，未使用时忽略）")
                .value("")
                .notice("公众号 secret")
                .placeholder("请输入公众号 secret")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(91)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("pageFooterText")
                .description("登录页底部展示信息")
                .value("")
                .notice("登录页底部展示信息，比如备案信息")
                .placeholder("请输入登录页底部展示信息")
                .category(TenantConfig.ConfigCategory.BaseConfig)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(99)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("defaultChatModelId")
                .description("默认会话模型")
                .notice("创建智能体时默认选中的模型")
                .placeholder("请选择模型")
                .category(TenantConfig.ConfigCategory.ModelSetting)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .sort(80)
                .value(1)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("defaultSummaryModelId")
                .description("默认会话总结模型")
                .notice("用于对话总结形成长期记忆")
                .placeholder("请选择模型")
                .category(TenantConfig.ConfigCategory.ModelSetting)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .sort(90)
                .value(1)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("defaultSuggestModelId")
                .description("默认问题建议模型")
                .notice("用于每轮对话结束后，向用户生成建议问题")
                .placeholder("请选择模型")
                .category(TenantConfig.ConfigCategory.ModelSetting)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .sort(100)
                .value(1)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("defaultCodingModelId")
                .description("默认长任务模型")
                .notice("用于页面开发编码、任务执行等")
                .placeholder("请选择模型")
                .category(TenantConfig.ConfigCategory.ModelSetting)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .required(false)
                .sort(101)
                .value(0)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("defaultVisualModelId")
                .description("默认编码视觉模型")
                .notice("用于识别用户上传的图片手稿等")
                .placeholder("请选择模型")
                .category(TenantConfig.ConfigCategory.ModelSetting)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .required(false)
                .sort(102)
                .value(0)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("defaultEmbedModelId")
                .description("知识库向量化模型")
                .notice("用于知识库向量化的模型")
                .placeholder("请选择模型")
                .category(TenantConfig.ConfigCategory.ModelSetting)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .sort(110)
                .value(2)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("defaultKnowledgeModelId")
                .description("知识库文档预处理模型")
                .notice("用于知识库文档预处理的模型")
                .placeholder("请选择模型")
                .category(TenantConfig.ConfigCategory.ModelSetting)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .sort(111)
                .value(1)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("defaultAgentId")
                .description("站点默认问答型智能体")
                .notice("设置为站点默认智能体后，将在首页默认对话框中使用，一般用在快问快答场景")
                .placeholder("请选择问答型智能体")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .sort(120)
                .value(0)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("defaultTaskAgentId")
                .description("站点默认通用型智能体")
                .notice("设置为站点默认通用型智能体后，将在首页默认对话框中展示可选，一般用在复杂任务场景")
                .placeholder("请选择通用型智能体")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.Select)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .sort(120)
                .value(0)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("homeSlogan")
                .description("首页标语")
                .notice("将在首页对话框上展示，可选变量 {{USER_NAME}}")
                .placeholder("请输入首页标语，可选变量 {{USER_NAME}}")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .required(true)
                .sort(121)
                .value("嗨 {{USER_NAME}}，有什么我可以帮忙的吗？")
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("defaultAgentIds")
                .description("默认智能体集群")
                .notice("默认智能体无法回答的问题，将从配置的智能体集群中选择合适的智能体为用户解决问题，仅站点默认智能体为问答型智能体时有效")
                .placeholder("请选择智能体")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.MultiSelect)
                .dataType(TenantConfig.DataType.Array)
                .required(true)
                .sort(130)
                .value(new ArrayList<>())
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("recommendAgentIds")
                .description("广场推荐智能体")
                .notice("推荐的智能体将始终放置在最前面")
                .placeholder("请选择智能体")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.MultiSelect)
                .dataType(TenantConfig.DataType.Array)
                .value(new ArrayList<>())
                .required(true)
                .sort(140)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("officialAgentIds")
                .description("官方智能体")
                .notice("官网智能体展示将统一使用下面配置的官方用户名")
                .placeholder("请选择智能体")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.MultiSelect)
                .dataType(TenantConfig.DataType.Array)
                .value(new ArrayList<>())
                .required(true)
                .sort(141)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("officialPluginIds")
                .description("官方插件")
                .value("")
                .notice("官网展示将统一使用下面配置的官方用户名")
                .placeholder("请输入插件Id（在浏览器地址栏查看），多个用英文逗号隔开")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(142)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("officialWorkflowIds")
                .description("官方工作流")
                .value("")
                .notice("官网展示将统一使用下面配置的官方用户名")
                .placeholder("请输入工作流Id（在浏览器地址栏查看），多个用英文逗号隔开")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(143)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("officialSkillIds")
                .description("官方技能")
                .value("")
                .notice("官网展示将统一使用下面配置的官方用户名")
                .placeholder("请输入技能Id（在浏览器地址栏查看），多个用英文逗号隔开")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(145)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("officialUserName")
                .description("官方用户名")
                .value("")
                .notice("用于替换官方智能体的发布者名称")
                .placeholder("请输入官方用户名")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(148)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("userComputerDefaultSkillIds")
                .description("智能体电脑客户端初始化技能列表")
                .value("")
                .notice("智能体电脑客户端初始化技能列表")
                .placeholder("请输入skillId（在浏览器地址栏查看），多个用英文逗号隔开")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(148)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("sandboxConfig")
                .description("智能体沙箱配置（已迁移到系统管理->沙箱配置）")
                .value("")
                .notice("对于通用型智能体分配沙箱执行环境")
                .placeholder("沙箱环境配置，参考官方配置文档")
                .category(TenantConfig.ConfigCategory.AgentSetting)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .minHeight(100)
                .required(false)
                .sort(149)
                .build());
        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("domainNames")
                .description("域名绑定")
                .notice("绑定域名cname指向你的默认域名（需已完成过ICP备案）")
                .placeholder("请输入域名")
                .category(TenantConfig.ConfigCategory.DomainBind)
                .inputType(TenantConfig.InputType.MultiInput)
                .dataType(TenantConfig.DataType.Array)
                .required(true)
                .value(new ArrayList<>())
                .sort(150)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("revenueRatio")
                .description("开发者分成比例（%）")
                .notice("开发者收益与平台服务比例，配置时请注意支付平台收取的服务费成本")
                .placeholder("请输入比例值")
                .category(TenantConfig.ConfigCategory.Payment)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .value(0.1)
                .sort(201)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("paymentGateway")
                .description("支付网关地址")
                .notice("该地址不要随便改动")
                .placeholder("请输入支付网关地址")
                .category(TenantConfig.ConfigCategory.Payment)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.String)
                .required(true)
                .value("https://pay.nuwax.com")
                .sort(202)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("enableSubscription")
                .description("订阅与积分功能开关")
                .notice("全局控制订阅计划和积分体系的启用状态")
                .placeholder("")
                .category(TenantConfig.ConfigCategory.Subscription)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .value(0)
                .sort(301)
                .build());


        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("creditExchangeRate")
                .description("积分兑换比例（¥1.00=?积分）")
                .notice("设置积分与货币的兑换比率，用户可使用积分抵扣金额")
                .placeholder("")
                .category(TenantConfig.ConfigCategory.Credit)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .value(1000)
                .sort(401)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("creditExchangeDesc")
                .description("积分解释")
                .notice("使用通俗的语言向用户解释积分含义")
                .placeholder("")
                .category(TenantConfig.ConfigCategory.Credit)
                .inputType(TenantConfig.InputType.Textarea)
                .dataType(TenantConfig.DataType.String)
                .required(true)
                .value("1000积分约可以使用DeepSeek-V4-flash模型100万输入TOKENS")
                .sort(402)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("enableGiftCredit")
                .description("新用户注册赠送积分")
                .notice("用户首次注册完成后，自动获得赠送积分")
                .placeholder("")
                .category(TenantConfig.ConfigCategory.Credit)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .value(0)
                .sort(403)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("giftCreditAmount")
                .description("新用户赠送积分数量")
                .notice("用户首次注册完成后，赠送积分数量")
                .placeholder("")
                .category(TenantConfig.ConfigCategory.Credit)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .value(0)
                .sort(404)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("giftCreditExpire")
                .description("赠送积分有效期（天）")
                .notice("用户首次注册完成后，赠送积分的有效期")
                .placeholder("")
                .category(TenantConfig.ConfigCategory.Credit)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .value(30)
                .sort(405)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("enableDailyGiftCredit")
                .description("启用每日登录赠送积分")
                .notice("启用用户每日首次登录时自动获得赠送积分")
                .placeholder("")
                .category(TenantConfig.ConfigCategory.Credit)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .value(0)
                .sort(403)
                .build());

        tenantConfigDefaultList.add(TenantConfig.builder()
                .name("dailyGiftCreditAmount")
                .description("每日登录赠送积分数量")
                .notice("用户每日首次登录时自动获得赠送积分数量")
                .placeholder("")
                .category(TenantConfig.ConfigCategory.Credit)
                .inputType(TenantConfig.InputType.Input)
                .dataType(TenantConfig.DataType.Number)
                .required(true)
                .value(0)
                .sort(404)
                .build());
    }

    @Resource
    private TenantService tenantService;

    @Resource
    private RedisUtil redisUtil;

    @Override
    public List<TenantConfig> getTenantConfigList() {
        List<TenantConfig> tenantConfigs = list(new LambdaQueryWrapper<TenantConfig>().orderByAsc(TenantConfig::getSort, TenantConfig::getId));
        //tenantConfigs转name为key的map
        Map<String, TenantConfig> tenantConfigMap = tenantConfigs.stream().collect(Collectors.toMap(TenantConfig::getName, config -> config, (c1, c2) -> c1));
        List<TenantConfig> newTenantConfigs = new ArrayList<>();
        List<TenantConfig> updateTenantConfigs = new ArrayList<>();
        Tenant tenant = null;
        for (TenantConfig tenantConfig0 : tenantConfigDefaultList) {
            TenantConfig tenantConfig = new TenantConfig();
            BeanUtils.copyProperties(tenantConfig0, tenantConfig);
            TenantConfig config = tenantConfigMap.get(tenantConfig.getName());
            if (config == null) {
                if (tenant == null) {
                    tenant = tenantService.getById(RequestContext.get().getTenantId());
                }
                if (tenantConfig.getName().equals("siteName")) {
                    tenantConfig.setValue(tenant.getName());
                }
                if (tenantConfig.getName().equals("siteDescription")) {
                    tenantConfig.setValue(tenant.getDescription() == null ? "" : tenant.getDescription());
                }
                tenantConfig.setValue(JsonSerializeUtil.toJSONStringGeneric(tenantConfig.getValue()));
                newTenantConfigs.add(tenantConfig);
            } else {
                if (!tenantConfig0.getDescription().equals(config.getDescription()) || !tenantConfig0.getNotice().equals(config.getNotice())
                        || !tenantConfig0.getPlaceholder().equals(config.getPlaceholder()) || !tenantConfig0.getSort().equals(config.getSort())) {
                    config.setDescription(tenantConfig0.getDescription());
                    config.setNotice(tenantConfig0.getNotice());
                    config.setPlaceholder(tenantConfig0.getPlaceholder());
                    config.setSort(tenantConfig0.getSort());
                    updateTenantConfigs.add(config);
                }
            }
        }
        if (CollectionUtils.isNotEmpty(updateTenantConfigs) || CollectionUtils.isNotEmpty(newTenantConfigs)) {
            if (CollectionUtils.isNotEmpty(updateTenantConfigs)) {
                updateBatchById(updateTenantConfigs);
            }
            if (CollectionUtils.isNotEmpty(newTenantConfigs)) {
                saveBatch(newTenantConfigs);
            }
            redisUtil.expire("tenant_config:" + RequestContext.get().getTenantId(), 0);
        }

        List<TenantConfig> tenantConfigs0 = list(new LambdaQueryWrapper<TenantConfig>().orderByAsc(TenantConfig::getSort, TenantConfig::getId)).stream().map(tenantConfig -> {
            if (tenantConfig.getValue() != null) {
                tenantConfig.setValue(JsonSerializeUtil.parseObjectGeneric(tenantConfig.getValue().toString()));
            }
            return tenantConfig;
        }).collect(Collectors.toList());

        //tenantConfigDefaultList转Map
        Map<String, TenantConfig> tenantConfigDefaultMap = tenantConfigDefaultList.stream().collect(Collectors.toMap(TenantConfig::getName, config -> config));
        //tenantConfigs0移除tenantConfigDefaultMap中不存在的项
        tenantConfigs0.removeIf(tenantConfig -> tenantConfig.getName() == null || !tenantConfigDefaultMap.containsKey(tenantConfig.getName()));
        return tenantConfigs0;
    }

    @Override
    public Map<String, String> getAllTenantDomainConfigMap() {
        RequestContext.addTenantIgnoreEntity(TenantConfig.class);
        try {
            List<TenantConfig> list = list(new LambdaQueryWrapper<TenantConfig>().eq(TenantConfig::getCategory, TenantConfig.ConfigCategory.DomainBind));
            Map<String, String> domainMap = new HashMap<>();
            list.forEach(tenantConfig -> {
                List<String> domains = (List<String>) JsonSerializeUtil.parseObjectGeneric(tenantConfig.getValue().toString());
                if (domains != null) {
                    domains.forEach(domain -> domainMap.put(domain, tenantConfig.getTenantId().toString()));
                }
            });
            return domainMap;
        } finally {
            RequestContext.removeTenantIgnoreEntity(TenantConfig.class);
        }
    }

    @DSTransactional
    public void updateConfig(Map<String, Object> config) {
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            LambdaUpdateWrapper<TenantConfig> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.set(TenantConfig::getValue, JsonSerializeUtil.toJSONStringGeneric(entry.getValue()));
            updateWrapper.eq(TenantConfig::getName, entry.getKey());
            update(updateWrapper);
        }
    }
}