package com.aiops.aiopscopilot.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 巡检事件状态机与去重存储：内存级（重启丢失）。
 * <p>
 * 设计取舍：与"自治诊断"能力边界一致——死锁等故障应用重启后也会消失，
 * 持久化的旧事件反而是噪音。零依赖、零外部状态。
 * <p>
 * 状态机：{@link IncidentStatus#NEW}（首次发现）→
 * {@link IncidentStatus#ACTIVE}（持续中）→
 * {@link IncidentStatus#RESOLVED}（连续 {@value #RESOLVED_NORMAL_ROUNDS} 轮 normal 后自动标记）。
 * <p>
 * 去重规则：同一 fingerprint 未关闭前，后续巡检周期不再走 log.error 全量报告，
 * 改由 {@link OpsAlertReporter#logHeartbeat} 输出 INFO 级心跳。
 * <p>
 * Fingerprint 构造见 {@link #fingerprintOf}：{@code status + "|" + 指标特征串}
 * （如 {@code CRITICAL|DEADLOCK;BLOCKED=2;}），基于指标快照而非 LLM 文本：
 * 同一故障即使 LLM 每轮措辞不同、数值轻微波动，也稳定合并为同一事件；
 * 又不细到用完整指标快照做 hash——那会把 0.91/0.92 这类微波动拆成不同事件，
 * 同一故障反复 NEW、去重失效。
 */
@Component
public class IncidentStore {

    /** 连续 normal 轮数达到该阈值后，将 ACTIVE 事件标记为 RESOLVED */
    static final int RESOLVED_NORMAL_ROUNDS = 3;

    /**
     * RESOLVED 事件的保留窗口：超过该时长后从内存清除。
     * <p>
     * 复发判定依赖"同指纹的旧 RESOLVED 记录还在"——但无限期保留有两个问题：
     * 内存单调增长；且"数月前的同指纹"被当成 relapse 已无意义（早就是独立事件）。
     * 窗口取 1 小时：覆盖同一次故障的合理复发周期，过期后同指纹按全新 NEW 处理。
     */
    static final Duration RESOLVED_TTL = Duration.ofHours(1);

    /** 指纹分桶阈值：CPU / 堆 / GC 的告警线（与 OpsScheduler 兜底口径独立，仅用于指纹特征提取） */
    private static final double CPU_BUCKET_THRESHOLD = 0.8;
    private static final double HEAP_BUCKET_THRESHOLD = 0.85;
    private static final double GC_BUCKET_THRESHOLD = 10;

    /** fingerprint → Incident */
    private final Map<String, Incident> incidents = new ConcurrentHashMap<>();

    /**
     * 记录或更新一个故障事件。
     * <ul>
     *   <li>首次出现：状态置 NEW，调用方应走全量报告</li>
     *   <li>已存在且 ACTIVE：刷新 lastSeen，重置 normal 计数，返回 isNew=false</li>
     *   <li>已 RESOLVED 的同指纹再次出现：作为新事件重新置 NEW（复发）</li>
     * </ul>
     *
     * @return 该事件的当前快照（isNew 字段决定调用方走全量报告还是心跳）
     */
    public Incident recordOrUpdate(String fingerprint, String severity, String summary,
                                   String rootCause, String suggestion) {
        Instant now = Instant.now();
        Incident[] out = new Incident[1];
        incidents.compute(fingerprint, (k, existing) -> {
            if (existing == null || existing.status == IncidentStatus.RESOLVED) {
                Incident fresh = new Incident(fingerprint, IncidentStatus.NEW, now, now,
                        0, severity, summary, rootCause, suggestion);
                out[0] = fresh;
                return fresh;
            }
            // 持续中：刷新 lastSeen 与最新内容，重置 normal 计数（故障仍在，不能误判 RESOLVED）
            Incident updated = new Incident(fingerprint, IncidentStatus.ACTIVE,
                    existing.firstSeen, now, 0, severity, summary, rootCause, suggestion);
            out[0] = updated;
            return updated;
        });
        return out[0];
    }

    /**
     * 巡检结果为 normal 时调用：所有 NEW/ACTIVE 事件递增 normal 计数，
     * 达到 {@value #RESOLVED_NORMAL_ROUNDS} 轮后标记 RESOLVED；
     * 顺带清除超过 {@link #RESOLVED_TTL} 的过期 RESOLVED 记录。
     * <p>
     * 用 {@link ConcurrentHashMap#computeIfPresent} 而非 forEach+put：
     * 后者是 get-then-put，若与 {@link #recordOrUpdate} 并发会发生丢失更新
     * （put 静默覆盖 compute 刚写入的 ACTIVE 版本，复活已 RESOLVED 的事件）。
     * 当前调用方是单线程调度器、竞态本不可达，但这是隐式依赖调度器并发模型的
     * 脆弱正确性——computeIfPresent 让单键原子性不依赖外部约定。
     *
     * @return 本轮被标记为 RESOLVED 的事件列表（可能为空；非空时调用方应输出"故障已恢复"日志）
     */
    public List<Incident> bumpNormalAndResolve() {
        List<Incident> resolved = new ArrayList<>();
        Instant now = Instant.now();
        Instant expiryCutoff = now.minus(RESOLVED_TTL);
        // 收集键再逐个 computeIfPresent：compute 内不能再写同一 map，先确定候选键
        List<String> keys = new ArrayList<>(incidents.keySet());
        for (String fp : keys) {
            Incident result = incidents.computeIfPresent(fp, (k, inc) -> {
                // 过期 RESOLVED：返回 null 即从 map 移除
                if (inc.status == IncidentStatus.RESOLVED) {
                    return inc.lastSeen.isBefore(expiryCutoff) ? null : inc;
                }
                int newCount = inc.consecutiveNormalRounds + 1;
                if (newCount >= RESOLVED_NORMAL_ROUNDS) {
                    return new Incident(fp, IncidentStatus.RESOLVED,
                            inc.firstSeen, now, newCount,
                            inc.severity, inc.summary, inc.rootCause, inc.suggestion);
                }
                // 还在观察期：更新计数（必须新建实例保证并发可见性）
                return new Incident(fp, inc.status, inc.firstSeen, inc.lastSeen,
                        newCount, inc.severity, inc.summary, inc.rootCause, inc.suggestion);
            });
            if (result != null && result.status == IncidentStatus.RESOLVED
                    && result.consecutiveNormalRounds >= RESOLVED_NORMAL_ROUNDS
                    && result.lastSeen.equals(now)) {
                resolved.add(result);
            }
        }
        return resolved;
    }

    /** 仅用于诊断/调试：返回当前活跃（NEW+ACTIVE）事件数。显式枚举而非 !=RESOLVED，防新增状态时静默被计入。 */
    public int activeCount() {
        return (int) incidents.values().stream()
                .filter(i -> i.status == IncidentStatus.NEW || i.status == IncidentStatus.ACTIVE)
                .count();
    }

    /**
     * 计算事件已持续时长（从 firstSeen 到 lastSeen）。
     * <p>
     * 用 lastSeen 而非 now：lastSeen 在每次 recordOrUpdate 中刷新，能反映故障实际最后
     * 被观测到的时间；RESOLVED 事件不会在被查询时"持续增长"。此前方法用 now 但
     * lastSeen 字段全类写而无人读，属于死字段。
     */
    public static Duration durationOf(Incident inc) {
        return Duration.between(inc.firstSeen, inc.lastSeen);
    }

    /**
     * 根据指标快照构造事件指纹。
     * <p>
     * 基于"指标特征"而非 LLM rootCause 文本——LLM 对同一故障的描述每次会略有不
     * 同（"存在死锁，死锁线程为..." vs "存在死锁，deadlockedThreads 列出了..."），
     * 用文本前缀做指纹会导致同故障被反复识别为 NEW，去重失效。
     * <p>
     * 指标特征是稳定的：只要同一类指标异常（如 BLOCKED&gt;0），无论 LLM 怎么描述，
     * 指纹都一致，从而正确合并到同一事件。
     * <p>
     * 例：{@code ("critical", {blockedThreads=2, deadlockDiagnosis=...})} →
     *     {@code "CRITICAL|DEADLOCK;BLOCKED=2;"}
     */
    public static String fingerprintOf(String status, Map<String, Object> snapshot) {
        StringBuilder features = new StringBuilder();
        // 死锁诊断字段优先（已经在 OpsScheduler 中按 BLOCKED>0 触发 ThreadMXBean 二级诊断）
        if (snapshot.get("deadlockDiagnosis") != null) {
            features.append("DEADLOCK;");
        }
        double blocked = asDouble(snapshot.get("blockedThreads"));
        if (blocked > 0) {
            features.append("BLOCKED=").append((int) blocked).append(";");
        }
        double cpu = asDouble(snapshot.get("cpuUsage"));
        if (cpu > CPU_BUCKET_THRESHOLD) {
            // 分桶避免轻微波动（0.91 vs 0.92）产生不同指纹
            features.append("HIGH_CPU=").append((int) (cpu * 10)).append(";");
        }
        double heap = asDouble(snapshot.get("heapUsage"));
        if (heap > HEAP_BUCKET_THRESHOLD) {
            features.append("HIGH_HEAP=").append((int) (heap * 10)).append(";");
        }
        double qps = asDouble(snapshot.get("qpsLast1m"));
        if (qps == 0 && cpu >= 0) {
            features.append("ZERO_QPS;");
        }
        double gc5m = asDouble(snapshot.get("gcCountLast5m"));
        if (gc5m > GC_BUCKET_THRESHOLD) {
            features.append("FREQUENT_GC;");
        }
        return status.toUpperCase() + "|" + features;
    }

    /** 把指标值安全转为 double；非数字或 null 返回 -1 */
    private static double asDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        return -1;
    }

    /** 事件状态枚举 */
    public enum IncidentStatus {
        /** 首次发现（本轮） */
        NEW,
        /** 持续中（已跨多个巡检周期） */
        ACTIVE,
        /** 已恢复（连续 N 轮 normal 后自动标记） */
        RESOLVED
    }

    /**
     * 不可变事件记录（每次更新都新建实例，保证 ConcurrentHashMap 读写的内存可见性）。
     * <p>
     * 所有字段 final，{@link #consecutiveNormalRounds} 由 {@link #bumpNormalAndResolve}
     * 通过新建实例递增，不暴露 setter。
     */
    public static final class Incident {
        private final String fingerprint;
        private final IncidentStatus status;
        private final Instant firstSeen;
        private final Instant lastSeen;
        private final int consecutiveNormalRounds;
        private final String severity;
        private final String summary;
        private final String rootCause;
        private final String suggestion;

        Incident(String fingerprint, IncidentStatus status, Instant firstSeen, Instant lastSeen,
                 int consecutiveNormalRounds, String severity, String summary,
                 String rootCause, String suggestion) {
            this.fingerprint = fingerprint;
            this.status = status;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.consecutiveNormalRounds = consecutiveNormalRounds;
            this.severity = severity;
            this.summary = summary;
            this.rootCause = rootCause;
            this.suggestion = suggestion;
        }

        public String fingerprint() { return fingerprint; }
        public IncidentStatus status() { return status; }
        public Instant firstSeen() { return firstSeen; }
        public Instant lastSeen() { return lastSeen; }
        public int consecutiveNormalRounds() { return consecutiveNormalRounds; }
        public String severity() { return severity; }
        public String summary() { return summary; }
        public String rootCause() { return rootCause; }
        public String suggestion() { return suggestion; }
        public boolean isNew() { return status == IncidentStatus.NEW; }
    }
}
