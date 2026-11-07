package com.aiops.aiopscopilot.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多目标监控配置（aiops.targets.*）：被纳管目标的列表。
 * <p>
 * 自己（application 标签 = spring.application.name）**永远在监控列表里**——
 * "监控者自身失效必须可见"（LLM_UNAVAILABLE 防线）依赖这一点：用户配置漏了自己
 * 也会在 {@link #resolved(String)} 里自动补到首位。非 JVM 目标通过
 * metrics 映射接入，语言无关。
 * <p>
 * 校验 fail-fast（装配期抛异常拒绝启动，而非运行期静默失效）：
 * name/application 非空；name 不含指纹分隔符（; 和 |）；name/application 不重复；
 * application 不含 PromQL 字符串的破坏字符（" 和 \——标签值会拼进查询语句）。
 */
@ConfigurationProperties("aiops.targets")
public class TargetsProperties {

    /** 被纳管目标列表；缺省空列表（运行时自动补 self） */
    private List<MonitoredTarget> list = new ArrayList<>();

    public List<MonitoredTarget> getList() {
        return list;
    }

    public void setList(List<MonitoredTarget> list) {
        this.list = list;
    }

    /**
     * 校验用户配置并返回有效目标列表；self（application = selfApplication）缺失时
     * 自动补在首位。顺序即采集与报告的展示顺序。
     */
    public List<MonitoredTarget> resolved(String selfApplication) {
        List<MonitoredTarget> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> applications = new HashSet<>();
        boolean selfPresent = false;
        for (MonitoredTarget t : list) {
            require(t.name() != null && !t.name().isBlank(), "监控目标 name 不能为空");
            require(!t.name().contains(";") && !t.name().contains("|"),
                    "监控目标 name 不能含指纹分隔符 ; 或 |: " + t.name());
            require(t.application() != null && !t.application().isBlank(),
                    "监控目标 application 不能为空: " + t.name());
            require(!t.application().contains("\"") && !t.application().contains("\\"),
                    "监控目标 application 不能含 \" 或 \\（会破坏 PromQL 字符串）: " + t.name());
            require(names.add(t.name()), "监控目标 name 重复: " + t.name());
            require(applications.add(t.application()),
                    "监控目标 application 重复: " + t.application() + "（" + t.name() + "）");
            selfPresent |= t.application().equals(selfApplication);
            out.add(t);
        }
        if (!selfPresent) {
            out.add(0, new MonitoredTarget(selfApplication, selfApplication, null));
        }
        return List.copyOf(out);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("aiops.targets 配置错误: " + message);
        }
    }
}
