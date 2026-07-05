package com.xspaceagi.agent.core.adapter.dto.config;

import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.core.spec.enums.GlobalVariableEnum;
import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

@I18n(module = "Args")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "参数")
public class Arg implements Serializable {

    @Schema(description = "参数key，唯一标识，不需要前端传递，后台根据配置自动生成")
    private String key;

    @I18nField(keyPrefix = true)
    @Schema(description = "参数名称，符合函数命名规则")
    private String name;

    @Schema(description = "参数展示名称，供前端展示使用")
    private String displayName;

    @Schema(description = "参数详细描述信息")
    private String description;

    @Schema(description = "数据类型")
    private DataTypeEnum dataType;

    @Schema(description = "原始数据类型，用于避免循环引用内部参数调整类型时误更新", hidden = true)
    private DataTypeEnum originDataType;

    @Schema(description = "是否必须")
    private boolean require;

    @Schema(description = "是否为开启，如果不开启，插件使用者和大模型均看不见该参数；如果bindValueType为空且require为true时，该参数必须开启")
    private Boolean enable;

    @Schema(description = "是否为系统内置变量参数，内置变量前端只可展示不可修改")
    private boolean systemVariable;

    @Schema(description = "值引用类型，Input 输入；Reference 变量引用")
    private BindValueType bindValueType;

    @Schema(description = "参数值，当类型为引用时，示例 1.xxx 绑定节点ID为1的xxx字段；当类型为输入时，该字段为最终使用的值")
    private String bindValue;

    @Schema(description = "下级参数")
    private List<Arg> subArgs;

    @Schema(description = "输入类型,  单行文本 Text, 多行段落 Paragraph, 下拉单选 Select, 下拉多选 MultipleSelect, 数字 Number, 智能识别 AutoRecognition")
    private InputTypeEnum inputType;

    @Schema(description = "下拉参数配置")
    private SelectConfig selectConfig;

    private Long loopId;

    @Schema(description = "参数更新策略")
    private UpdateStrategy updateStrategy;

    public void setSubArgs(List<Arg> subArgs) {
        if (this.subArgs != null) {
            return;
        }
        this.subArgs = subArgs;
    }

    public List<Arg> getSubArgs() {
        return subArgs;
    }

    //配合前端组件使用
    public List<Arg> getChildren() {
        return subArgs;
    }

    public DataTypeEnum getDataType() {
        return dataType == null ? DataTypeEnum.String : dataType;
    }

    //配合前端组件使用
    public void setChildren(List<Arg> subArgs) {
        if (this.subArgs != null) {
            return;
        }
        this.subArgs = subArgs;
    }

    public enum BindValueType {
        Input,      // 输入
        Reference,  // 引用
    }

    public enum InputTypeEnum {
        Query,
        Body,
        Header,
        Path,
        //以下用于其他前端输入组件，包含 单行文本、多行段落、下拉单选、下拉多选、智能识别
        //单行文本
        Text,
        //多行段落
        Paragraph,
        //下拉单选
        Select,
        //下拉多选
        MultipleSelect,
        //数字
        Number,
        //自动识别
        AutoRecognition,
        File,
        Radio,
        FixedValue,
    }

    public enum UpdateStrategy {
        REPLACE,  // Overwrite the existing value entirely
        APPEND    // Append to the existing value (string concatenation, list append, or numeric addition)
    }

    public static List<Arg> updateBindConfigArgs(String startKey, List<Arg> bindConfigArgs, List<Arg> configInputArgs) {
        List<Arg> newBindInputArgs = null;
        if (configInputArgs != null) {
            //configInputArgs不为空，bindConfigArgs为空，直接返回configInputArgs
            //转换后返回
            newBindInputArgs = configInputArgs.stream().map(arg -> {
                ArgBindConfigDto arg1 = new ArgBindConfigDto();
                BeanUtils.copyProperties(arg, arg1);
                return arg1;
            }).collect(Collectors.toList());
        }
        if (bindConfigArgs == null && newBindInputArgs != null) {
            //configInputArgs不为空，bindConfigArgs为空，直接返回configInputArgs
            //转换后返回
            return newBindInputArgs;
        }
        if (newBindInputArgs == null) {
            return new ArrayList<>();
        }
        Map<String, Arg> argMap = new HashMap<>();
        generateKey(startKey, bindConfigArgs, argMap);
        updateInputArgs(startKey, newBindInputArgs, argMap);
        return newBindInputArgs;
    }

    private static <T> void updateInputArgs(String pKey, List<T> args, Map<String, Arg> argMap) {
        if (args != null) {
            args.forEach(arg -> {
                if (arg instanceof Arg) {
                    Arg arg1 = (Arg) arg;
                    if (pKey == null) {
                        arg1.setKey(arg1.getName());
                    } else {
                        arg1.setKey(pKey + "." + (StringUtils.isNotBlank(arg1.getName()) ? arg1.getName() : UUID.randomUUID().toString()));
                    }
                    List<Arg> subArgs = arg1.getSubArgs();
                    Arg bindConfigArg = argMap.get(arg1.getKey());
                    if (bindConfigArg != null && arg1.getEnable()) {
                        bindConfigArg.setRequire(arg1.isRequire());
                        bindConfigArg.setDescription(arg1.getDescription());
                        bindConfigArg.setDataType(arg1.getDataType());
                        BeanUtils.copyProperties(bindConfigArg, arg1);
                        arg1.setSubArgs(subArgs);
                    }
                    updateInputArgs(arg1.getKey(), subArgs, argMap);
                }
            });
        }
    }

    //生成参数key
    public static <T> void generateKey(String pKey, List<T> args, Map<String, Arg> argMap) {
        if (args != null) {
            args.forEach(arg -> {
                if (arg instanceof Arg) {
                    Arg arg1 = (Arg) arg;
                    if (pKey == null) {
                        arg1.setKey(arg1.getName());
                    } else {
                        arg1.setKey(pKey + "." + arg1.getName());
                    }
                    if (argMap != null) {
                        argMap.put(arg1.getKey(), arg1);
                    }
                    generateKey(arg1.getKey(), arg1.getSubArgs(), argMap);
                }
            });
        }
    }

    /**
     * 移除不可见的参数
     *
     * @param inputArgs
     */
    public static void removeDisabledArgs(List<?> inputArgs) {
        if (inputArgs == null) {
            return;
        }
        //遍历 inputArgs，if (!((Arg) arg).isEnable())时移除该arg
        Iterator<?> iterator = inputArgs.iterator();
        while (iterator.hasNext()) {
            Object arg = iterator.next();
            if (arg instanceof Arg) {
                if (((Arg) arg).getEnable() != null && !((Arg) arg).getEnable()) {
                    iterator.remove();
                }
                if (((Arg) arg).getSubArgs() != null) {
                    removeDisabledArgs(((Arg) arg).getSubArgs());
                }
            }
        }
    }

    public Boolean getEnable() {
        return enable == null || enable;
    }

    public static List<Arg> getSystemVariableArgs() {
        List<Arg> systemVariableArgs = new ArrayList<>();
        systemVariableArgs.add(Arg.builder().systemVariable(true).name(GlobalVariableEnum.SYS_USER_ID.name()).description("Platform user ID").require(true).enable(true).build());
        systemVariableArgs.add(Arg.builder().systemVariable(true).name(GlobalVariableEnum.USER_UID.name()).description("User unique identifier").require(true).enable(true).build());
        systemVariableArgs.add(Arg.builder().systemVariable(true).name(GlobalVariableEnum.USER_LANG.name()).description("User language preference").require(true).enable(true).build());
        systemVariableArgs.add(Arg.builder().systemVariable(true).name(GlobalVariableEnum.USER_NAME.name()).description("User name").require(true).enable(true).build());
        systemVariableArgs.add(Arg.builder().systemVariable(true).name(GlobalVariableEnum.AGENT_ID.name()).description("Agent unique identifier").require(true).enable(true).build());
        systemVariableArgs.add(Arg.builder().systemVariable(true).name(GlobalVariableEnum.CONVERSATION_ID.name()).description("Conversation unique identifier").require(true).enable(true).build());
        systemVariableArgs.add(Arg.builder().systemVariable(true).name(GlobalVariableEnum.REQUEST_ID.name()).description("Request unique identifier").require(true).enable(true).build());
        systemVariableArgs.add(Arg.builder().systemVariable(true).name(GlobalVariableEnum.AGENT_USER_MSG.name()).description("User message").require(true).enable(true).build());
        systemVariableArgs.add(Arg.builder().systemVariable(true).name(GlobalVariableEnum.CHAT_CONTEXT.name()).description("Conversation context message list").require(true).enable(true).build());
        I18nUtil.replaceSystemMessage("SystemVariable", systemVariableArgs);
        return systemVariableArgs;
    }

}
