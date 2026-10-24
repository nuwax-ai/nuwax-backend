package com.xspaceagi.connector.application.exec;

import com.xspaceagi.connector.domain.exec.ConnectorRuntime;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CUSTOM 运行时注册表：收集模块内注册的 {@link ConnectorRuntime} Bean，按 exec_ref 查找。
 * 追加新运行时只需新增一个 Bean，本类无需改动（当前内置实现见 exec/runtime 包）。
 */
@Service
public class ConnectorRuntimeRegistry {

    private final Map<String, ConnectorRuntime> runtimes = new HashMap<>();

    public ConnectorRuntimeRegistry(List<ConnectorRuntime> runtimeBeans) {
        for (ConnectorRuntime runtime : runtimeBeans) {
            runtimes.put(runtime.execRef(), runtime);
        }
    }

    public ConnectorRuntime lookup(String execRef) {
        return execRef == null ? null : runtimes.get(execRef);
    }
}
