package com.xspaceagi.system.infra.idp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.system.infra.dao.entity.AuthIdpConfig;
import com.xspaceagi.system.spec.enums.AuthIdpTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.HttpClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CAS 客户端：跳转 CAS 服务端 login，回跳带 ticket 后服务端校验。
 * <p>校验端点按序回退：/p3/serviceValidate（format=JSON，CAS 3.0）→ /p3/serviceValidate（XML）→
 * /serviceValidate（XML，CAS 2.0）。service 参数必须与发起时一致（state 拼在 service 查询串里回跳保留）。
 * <p>XML 解析禁用 DTD 与外部实体（XXE 防护）。
 */
@Slf4j
@Component
public class CasIdpClient implements IdentityProviderClient {

    @Resource
    private HttpClient httpClient;

    @Override
    public AuthIdpTypeEnum type() {
        return AuthIdpTypeEnum.CAS;
    }

    @Override
    public String buildAuthorizeUrl(AuthIdpConfig config, String redirectUri, String state, String codeVerifier) {
        CasIdpConfig cas = IdpConfigParser.parseCas(config);
        // state 拼进 service 查询参数：CAS 回跳原样保留，回调侧据此完成与 OAuth2 一致的防重放校验
        String service = appendState(redirectUri, state);
        return trimSlash(cas.getServerUrl()) + "/login?service=" + urlEnc(service);
    }

    @Override
    public ExternalIdentity exchange(AuthIdpConfig config, IdpExchangeContext ctx) {
        CasIdpConfig cas = IdpConfigParser.parseCas(config);
        String service = appendState(ctx.getRedirectUri(), ctx.getState());
        String serverUrl = trimSlash(cas.getServerUrl());

        String body = tryValidate(serverUrl + "/p3/serviceValidate", service, ctx.getCredential(), true);
        if (body == null) {
            body = tryValidate(serverUrl + "/p3/serviceValidate", service, ctx.getCredential(), false);
        }
        if (body == null) {
            body = tryValidate(serverUrl + "/serviceValidate", service, ctx.getCredential(), false);
        }
        if (body == null) {
            throw new BizException("0001", "CAS 票据校验失败：服务端无可用校验端点");
        }
        CasUser casUser = parseValidation(body);
        return toIdentity(cas, casUser);
    }

    /**
     * 请求一个校验端点；端点不存在（404 等）或响应不含 CAS 关键字时返回 null 以尝试下一策略，
     * 响应含 authenticationFailure 时直接抛出（票据校验结论已明确，无需回退）
     */
    private String tryValidate(String endpoint, String service, String ticket, boolean jsonFormat) {
        StringBuilder url = new StringBuilder(endpoint)
                .append("?service=").append(urlEnc(service))
                .append("&ticket=").append(urlEnc(ticket));
        if (jsonFormat) {
            url.append("&format=JSON");
        }
        try {
            String body = httpClient.get(url.toString());
            if (StringUtils.isBlank(body)) {
                return null;
            }
            if (body.contains("authenticationSuccess") || body.contains("authenticationFailure")
                    || body.contains("serviceResponse")) {
                return body;
            }
            log.warn("CAS 校验端点返回无法识别的内容 endpoint={}", endpoint);
            return null;
        } catch (Exception e) {
            log.warn("CAS 校验端点请求失败 endpoint={} err={}", endpoint, e.getMessage());
            return null;
        }
    }

    private CasUser parseValidation(String body) {
        if (JSON.isValid(body)) {
            return parseJson(body);
        }
        return parseXml(body);
    }

    private CasUser parseJson(String body) {
        JSONObject root = JSON.parseObject(body);
        JSONObject serviceResponse = root.getJSONObject("serviceResponse");
        if (serviceResponse == null) {
            throw new BizException("0001", "CAS 校验响应无法识别");
        }
        JSONObject failure = serviceResponse.getJSONObject("authenticationFailure");
        if (failure != null) {
            throw new BizException("0001", "CAS 认证失败: " + failure.getString("code")
                    + (StringUtils.isBlank(failure.getString("description")) ? "" : " " + failure.getString("description")));
        }
        JSONObject success = serviceResponse.getJSONObject("authenticationSuccess");
        if (success == null) {
            throw new BizException("0001", "CAS 认证失败：响应缺少 authenticationSuccess");
        }
        CasUser user = new CasUser();
        user.user = success.getString("user");
        JSONObject attributes = success.getJSONObject("attributes");
        if (attributes != null) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                user.attributes.put(entry.getKey(), flatten(entry.getValue()));
            }
        }
        return user;
    }

    private CasUser parseXml(String body) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // XXE 防护：禁 DTD 与外部实体
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(new InputSource(new StringReader(body)));
        } catch (Exception e) {
            throw new BizException("0001", "CAS 校验响应 XML 解析失败");
        }
        Element success = firstChildByLocalName(document.getDocumentElement(), "authenticationSuccess");
        if (success == null) {
            Element failure = firstChildByLocalName(document.getDocumentElement(), "authenticationFailure");
            String code = failure == null ? "" : failure.getAttribute("code");
            String description = failure == null ? "" : failure.getTextContent().trim();
            throw new BizException("0001", "CAS 认证失败: " + StringUtils.defaultIfBlank(code, description));
        }
        CasUser user = new CasUser();
        Element userElement = firstChildByLocalName(success, "user");
        user.user = userElement == null ? null : userElement.getTextContent().trim();
        // CAS 3.0 属性在 <cas:attributes> 下；CAS 2.0 附加属性直接挂在 authenticationSuccess 下，两种都收集
        Element attributesElement = firstChildByLocalName(success, "attributes");
        if (attributesElement != null) {
            for (Element child : children(attributesElement)) {
                user.attributes.put(localName(child), child.getTextContent().trim());
            }
        }
        for (Element child : children(success)) {
            String name = localName(child);
            if (!"user".equals(name) && !"attributes".equals(name) && !"proxyGrantingTicket".equals(name)
                    && !"proxies".equals(name)) {
                user.attributes.putIfAbsent(name, child.getTextContent().trim());
            }
        }
        return user;
    }

    private ExternalIdentity toIdentity(CasIdpConfig cas, CasUser casUser) {
        String user = StringUtils.trimToEmpty(casUser.user);
        if (StringUtils.isBlank(user)) {
            throw new BizException("0001", "CAS 认证成功但未返回用户名");
        }
        Map<String, String> mapping = cas.getFieldMapping() == null ? Map.of() : cas.getFieldMapping();
        ExternalIdentity identity = new ExternalIdentity();
        identity.setExternalId(user);
        identity.setUserName(mappedValue(mapping, "userName", casUser.attributes, user));
        String nickName = mappedValue(mapping, "nickName", casUser.attributes, null);
        identity.setNickName(StringUtils.defaultIfBlank(nickName, identity.getUserName()));
        identity.setEmail(mappedValue(mapping, "email", casUser.attributes, null));
        identity.setPhone(mappedValue(mapping, "phone", casUser.attributes, null));
        identity.setAttributes(new LinkedHashMap<>(casUser.attributes));
        log.info("CAS 认证成功 user={}", user);
        return identity;
    }

    private static String mappedValue(Map<String, String> mapping, String platformField,
            Map<String, String> attributes, String fallback) {
        String attrName = mapping.get(platformField);
        if (StringUtils.isNotBlank(attrName)) {
            String value = attributes.get(attrName);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return fallback;
    }

    private static String flatten(Object value) {
        if (value instanceof JSONArray array) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(array.get(i));
            }
            return sb.toString();
        }
        return value == null ? null : value.toString();
    }

    private static Element firstChildByLocalName(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Element child : children(parent)) {
            if (localName(child).equals(localName)) {
                return child;
            }
        }
        return null;
    }

    private static Iterable<Element> children(Element parent) {
        return new Iterable<>() {
            @Override
            public java.util.Iterator<Element> iterator() {
                return new ElementIterator(parent.getChildNodes());
            }
        };
    }

    private static class ElementIterator implements java.util.Iterator<Element> {
        private final NodeList nodes;
        private int index = 0;

        private ElementIterator(NodeList nodes) {
            this.nodes = nodes;
        }

        private Element nextElement() {
            while (index < nodes.getLength()) {
                Node node = nodes.item(index);
                index++;
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    return (Element) node;
                }
            }
            return null;
        }

        @Override
        public boolean hasNext() {
            return nextElement() != null;
        }

        @Override
        public Element next() {
            Element next = nextElement();
            if (next == null) {
                throw new java.util.NoSuchElementException();
            }
            return next;
        }
    }

    private static String localName(Element element) {
        String name = element.getLocalName();
        return name != null ? name : element.getNodeName();
    }

    private static String appendState(String redirectUri, String state) {
        return redirectUri + (redirectUri.contains("?") ? "&" : "?") + "state=" + urlEnc(state);
    }

    private static String trimSlash(String url) {
        return StringUtils.removeEnd(StringUtils.trimToEmpty(url), "/");
    }

    private static String urlEnc(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }

    /** CAS 校验成功结果：user 为主标识，attributes 为展平后的属性表 */
    private static class CasUser {
        String user;
        Map<String, String> attributes = new LinkedHashMap<>();
    }
}
