package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 聚合根：一条网关路由，连同它的全部匹配条件与转发动作。
 *
 * 聚合不变量（本类负责守住）：
 * 1. routeNo 建后不可改，且只允许字母数字与 . _ -；
 * 2. upstream 必须是一个像样的 http/https 地址（协议对、主机非空、端口合法），
 *    空串、纯主机名、一串乱码一律不收；
 * 3. enabled 只认 0 / 1；
 * 4. 同一组的子项顺序号必须从 1 起、连续、不重，撞号要报出是哪两条撞的；
 * 5. 类型/方向/必填项由 {@link GatewayRule#validateAs} 守住；
 * 6. 灰度分组见 {@link #replaceGrayGroups}：组名/标记值路由内唯一，
 *    权重必须是 0~100 整数且各组之和恰好为 100，也不允许全 0。
 *
 * version 承载乐观锁语义：并发保存同一条路由时，旧版本提交会被拒。
 */
@Getter
@Setter
public class GatewayRoute {

    private String id;

    /** 路由编号，业务唯一，建后不可改。 */
    private String routeNo;

    private String name;

    /** 上游地址，如 http://order-svc:8080。 */
    private String upstream;

    /** 1 启用 / 0 停用。 */
    private Integer enabled;

    /**
     * 登录开关：1 = 需登录（调用方必须带一张网关验得过的用户令牌），
     * 0 = 开放（谁都能打，没令牌也照常放行）。缺省 0——这个标记跟着路由配置走，一条一配。
     */
    private Integer authRequired;

    private String remark;

    /** 乐观锁版本号。 */
    private Integer version;

    /** 匹配条件（stage 恒为 REQUEST）。 */
    private List<GatewayRule> conditions = new ArrayList<>();

    /** 转发动作（stage 可为 REQUEST 或 RESPONSE）。 */
    private List<GatewayRule> actions = new ArrayList<>();

    /**
     * 灰度分组：为空（null 或空列表）表示这条路由不做灰度，所有请求都打主上游 {@link #upstream}；
     * 非空时请求在这些组之间分流（标记精确命中优先，其余按权重平滑轮询），
     * 主上游仅作为「一条灰度组都没配」时的目标，不再接灰度流量。
     */
    private List<GrayGroup> grayGroups = new ArrayList<>();

    /**
     * 韧性策略（熔断 + 重试，各自独立开关）。null 表示这条路由两者都不配：
     * 旧配置 JSON 反序列化、或没用到这功能的路由都落 null，转发链路行为与以前完全一致。
     */
    private ResiliencePolicy resilience;

    public static GatewayRoute create(String routeNo, String name, String upstream,
                                      Integer enabled, String remark) {
        GatewayRoute route = new GatewayRoute();
        route.assignRouteNo(routeNo);
        route.rename(name);
        route.changeUpstream(upstream);
        route.changeEnabled(enabled);
        // 默认开放：登录是「随路由单独打开」的开关，不打开就谁都能打
        route.changeAuthRequired(0);
        route.setRemark(remark);
        route.setVersion(0);
        route.setConditions(new ArrayList<>());
        route.setActions(new ArrayList<>());
        route.setGrayGroups(new ArrayList<>());
        return route;
    }

    /** 建号：非空、格式合法、且建后不可修改。 */
    public void assignRouteNo(String routeNo) {
        if (routeNo == null || routeNo.isBlank()) {
            throw new BizException("路由编号不能为空");
        }
        String v = routeNo.trim();
        if (!v.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new BizException("路由编号只能包含字母、数字、点、下划线、短横，最长 64 位");
        }
        if (this.routeNo != null && !this.routeNo.equals(v)) {
            throw new BizException("路由编号建后不可修改（现有编号 " + this.routeNo + "，不能改成 " + v + "）");
        }
        this.routeNo = v;
    }

    public void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("路由名称不能为空");
        }
        String v = name.trim();
        if (v.length() > 128) {
            throw new BizException("路由名称最长 128 位");
        }
        this.name = v;
    }

    /** 启用开关只认 0（停用）/ 1（启用），别的值不收，避免存进一个谁都解释不了的状态。 */
    public void changeEnabled(Integer enabled) {
        if (enabled == null) {
            this.enabled = 1;
            return;
        }
        if (enabled != 0 && enabled != 1) {
            throw new BizException("启用开关只能是 0（停用）或 1（启用），收到的是：" + enabled);
        }
        this.enabled = enabled;
    }

    /** 登录开关只认 0（开放）/ 1（需登录），null 按开放默认，别的值不收。 */
    public void changeAuthRequired(Integer authRequired) {
        if (authRequired == null) {
            this.authRequired = 0;
            return;
        }
        if (authRequired != 0 && authRequired != 1) {
            throw new BizException("登录开关只能是 0（开放）或 1（需登录），收到的是：" + authRequired);
        }
        this.authRequired = authRequired;
    }

    /** 这条路由是否要求登录。 */
    public boolean requiresAuth() {
        return authRequired != null && authRequired == 1;
    }

    /**
     * 改上游：必须是一个合法的 http/https URL。
     * 只看前缀挡不住 "http://"、"http://一串乱码"，所以这里按 URI 真正解析一遍，
     * 再确认主机名/端口像样。灰度分组的上游走同一套校验（{@link UpstreamValidator}）。
     */
    public void changeUpstream(String upstream) {
        this.upstream = UpstreamValidator.requireValid(upstream, "上游地址");
    }

    /**
     * 校验整组子项并挂到聚合上。两个列表都是「整树替换」语义：
     * 保存时先清旧子项、再整批落新的，避免增量合并留下孤儿。
     * 改一条路由时把顺序整批重排，也走这里，重新过一遍全部不变量。
     */
    public void replaceRules(List<GatewayRule> newConditions, List<GatewayRule> newActions) {
        List<GatewayRule> cs = newConditions == null ? new ArrayList<>() : new ArrayList<>(newConditions);
        List<GatewayRule> as = newActions == null ? new ArrayList<>() : new ArrayList<>(newActions);
        validateKind(cs, RuleTypes.KIND_CONDITION);
        validateKind(as, RuleTypes.KIND_ACTION);
        this.conditions = cs;
        this.actions = as;
    }

    /**
     * 同组的顺序号必须从 1 起、连续、不重；每条自身也要通过类型校验。
     * 撞号时报出是组内第几条跟第几条撞，不报半截话。
     */
    private void validateKind(List<GatewayRule> rules, String kind) {
        boolean isCondition = RuleTypes.KIND_CONDITION.equals(kind);
        String label = isCondition ? "匹配条件" : "转发动作";
        // sortNo -> 第一次占用它的子项位次
        Map<Integer, Integer> firstOrdinal = new HashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            GatewayRule r = rules.get(i);
            int ordinal = i + 1;
            if (r == null) {
                throw new BizException(label + "第 " + ordinal + " 条为空");
            }
            r.validateAs(kind, ordinal);
            Integer prev = firstOrdinal.putIfAbsent(r.getSortNo(), ordinal);
            if (prev != null) {
                throw new BizException(label + "第 " + prev + " 条与第 " + ordinal
                        + " 条的顺序号撞了，都是 " + r.getSortNo() + "，同组内顺序号不能重");
            }
        }
        for (int n = 1; n <= rules.size(); n++) {
            if (!firstOrdinal.containsKey(n)) {
                throw new BizException(label + "的顺序号必须从 1 起连续不跳号，缺了 " + n
                        + "（现在有 " + rules.size() + " 条）");
            }
        }
    }

    /**
     * 整组替换灰度分组（与条件/动作一样是「整树替换」语义）。
     *
     * 传 null / 空列表 = 这条路由不做灰度，全部流量回主上游 {@link #upstream}（向后兼容旧配置）。
     * 非空时按「先逐组、再整组」两步校验：
     * - 逐组：组名格式与路由内唯一、上游地址合法、权重为 0~100 整数、
     *   标记值合法且一个标记不能同时挂两组（细节见 {@link GrayGroup#validate}）；
     * - 整组：权重之和必须<b>恰好</b>为 100，报错时把每组权重都列出来，
     *   不用运营自己去猜是哪几组配错；和为 100 自然排除了「全组 0 权重」。
     *
     * 0 权重合法：该组先不接比例流量，但配置保留（标记仍可精确命中），回头把权重一改即可放量。
     */
    public void replaceGrayGroups(List<GrayGroup> newGroups) {
        if (newGroups == null || newGroups.isEmpty()) {
            this.grayGroups = new ArrayList<>();
            return;
        }
        List<GrayGroup> gs = new ArrayList<>(newGroups);
        Set<String> seenNames = new HashSet<>();
        Map<String, String> tagOwner = new HashMap<>();
        int total = 0;
        for (int i = 0; i < gs.size(); i++) {
            GrayGroup g = gs.get(i);
            if (g == null) {
                throw new BizException("灰度分组第 " + (i + 1) + " 条为空");
            }
            g.validate(i + 1, seenNames, tagOwner);
            total += g.getWeight();
        }
        if (total != 100) {
            StringBuilder detail = new StringBuilder();
            for (GrayGroup g : gs) {
                if (detail.length() > 0) {
                    detail.append("，");
                }
                detail.append(g.getGroupName()).append('=').append(g.getWeight());
            }
            throw new BizException("灰度分组的权重之和必须恰好为 100，现在合计是 " + total
                    + "（" + detail + "），请检查是哪几组配错了；想临时关某组把它配成 0 即可，别靠凑和之外的方式留组");
        }
        this.grayGroups = gs;
    }

    /** 是否配了灰度分组；没配时所有请求都打主上游，灰度逻辑完全不介入。 */
    public boolean hasGrayGroups() {
        return grayGroups != null && !grayGroups.isEmpty();
    }

    /**
     * 替换韧性策略（熔断/重试）。传 null = 两者都不开，转发走老链路。
     * 开关与参数全部在 {@link ResiliencePolicy#normalizeAndValidate()} 内校验归一。
     */
    public void replaceResilience(ResiliencePolicy policy) {
        if (policy == null) {
            this.resilience = null;
            return;
        }
        policy.normalizeAndValidate();
        this.resilience = policy;
    }

    /** 熔断策略；没配或没开返回 null（调用方据此完全不介入）。 */
    public CircuitBreakerPolicy circuitBreakerPolicy() {
        return resilience != null && resilience.circuitBreakerOn()
                ? resilience.getCircuitBreaker() : null;
    }

    /** 重试策略；没配或没开返回 null（调用方据此完全不介入）。 */
    public RetryPolicy retryPolicy() {
        return resilience != null && resilience.retryOn() ? resilience.getRetry() : null;
    }
}
