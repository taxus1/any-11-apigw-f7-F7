package com.apigw.proxy.match;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 路由匹配：拿一份「当前启用、可匹配」的路由快照，对一个请求找出唯一命中的路由。
 *
 * 命中规则：同一条路由上的全部匹配条件是 AND，任何一条不满足就不命中；
 * 多条路由同时命中时，按下列确定次序选出唯一一条（同样的请求永远走同一条，不会漂移）：
 *   1. PATH_PREFIX 前缀更长的优先（更具体的路径赢；没写路径条件的按 0 长度排最后）；
 *   2. 仍并列（如路径条件相同、或都没路径条件）时，条件总数更多的赢（约束更具体）；
 *   3. 还并列就按路由编号字典序（routeNo 只含字母数字 . _ -，字典序确定且稳定）。
 *
 * 该次序只依赖路由自身的静态属性，与路由在配置里的先后顺序无关——遍历顺序怎么换，
 * 选出来的赢家都一样（有测试钉住）。
 *
 * 各条件类型的语义：
 * - PATH_PREFIX：见 {@link PathPrefixMatcher}，按常规 URL 语义路径大小写敏感；
 * - METHOD：HTTP 方法名大小写不敏感（GET 与 get 等价），比较时统一大写；
 * - HEADER：头名大小写不敏感（HTTP 头本来就不区分大小写），头值大小写敏感、精确相等；
 * - QUERY：参数名大小写敏感，参数值大小写敏感、精确相等（只判断 URL 查询串上该参数是否带着这个值）。
 *
 * 判定统一针对 {@link MatchableRequest}：真实转发用 ServerHttpRequest 适配，排查接口用手工构造，
 * 两边共用这里的同一份条件判定与定序，不会各算各的。
 * 这类不持有状态、不碰 Redis，快照由上层 RouteCatalog 提供，方便直接单测。
 */
@Component
public class RouteMatcher {

    /**
     * 多路由撞配时的稳定次序：前缀长度降序 → 条件数降序 → 编号升序。
     * compare(a,b) &lt; 0 表示 a 优先于 b。对外暴露给排查器，保证「解释的名次」与「实际选择」同源。
     */
    public static final Comparator<GatewayRoute> PRECEDENCE = Comparator
            .comparingInt((GatewayRoute r) -> pathPrefixLength(r))
            .reversed()
            .thenComparing(Comparator.comparingInt((GatewayRoute r) -> r.getConditions().size()).reversed())
            .thenComparing(GatewayRoute::getRouteNo);

    /** 转发链路入口：对 ServerHttpRequest 匹配唯一路由；一条都不命中返回 null。 */
    public GatewayRoute match(List<GatewayRoute> routes, ServerHttpRequest request) {
        return match(routes, ServerHttpRequestView.of(request));
    }

    /** 排查入口：对一笔抽象请求匹配唯一路由；一条都不命中返回 null。 */
    public GatewayRoute match(List<GatewayRoute> routes, MatchableRequest request) {
        GatewayRoute best = null;
        for (GatewayRoute route : routes) {
            if (!allConditionsMatch(route, request)) {
                continue;
            }
            if (best == null || PRECEDENCE.compare(route, best) < 0) {
                best = route;
            }
        }
        return best;
    }

    /** 一条路由的全部条件 AND：任一不满足即整体不命中。 */
    public boolean allConditionsMatch(GatewayRoute route, MatchableRequest request) {
        for (GatewayRule c : route.getConditions()) {
            if (!conditionMatches(c, request)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 单条条件判定。判定逻辑只此一处，转发与排查共用。
     */
    public boolean conditionMatches(GatewayRule c, MatchableRequest request) {
        switch (c.getType()) {
            case RuleTypes.TYPE_PATH_PREFIX -> {
                return PathPrefixMatcher.matches(c.getValue(), request.path());
            }
            case RuleTypes.TYPE_METHOD -> {
                // 方法名是大小写不敏感的 token
                return request.method() != null
                        && c.getValue().equalsIgnoreCase(request.method());
            }
            case RuleTypes.TYPE_HEADER -> {
                String actual = request.header(c.getName());
                return actual != null && actual.equals(c.getValue());
            }
            case RuleTypes.TYPE_QUERY -> {
                List<String> values = request.queryParams(c.getName());
                return values != null && values.contains(c.getValue());
            }
            default -> {
                // 条件白名单在配置保存时已守住，这里属于数据异常，保守按不命中处理
                return false;
            }
        }
    }

    /** 取路由上路径前缀条件的前缀长度（多条时取最长）；没有路径条件返回 0。 */
    public static int pathPrefixLength(GatewayRoute route) {
        int len = 0;
        for (GatewayRule c : route.getConditions()) {
            if (RuleTypes.TYPE_PATH_PREFIX.equals(c.getType()) && c.getValue() != null) {
                len = Math.max(len, c.getValue().length());
            }
        }
        return len;
    }

    /** 排序/日志里用得上：把方法名归一化成大写。 */
    public static String normalizeMethod(String method) {
        return method == null ? "" : method.toUpperCase(Locale.ROOT);
    }
}
