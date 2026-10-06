package com.apigw.proxy.forward;

/**
 * 已读到内存里的上游响应（重试路径上，仅对 5xx 错误响应、且有上限地读）。
 *
 * <p>{@code oversized=true} 表示响应体超过了允许缓存的上限，{@code bytes} 只是被读到的前截
 * （后续字节已释放）。语义：这种异常大的错误体不再换发重试；过滤器兜底时只把已读到的前截
 * 原样写回（状态码与头仍是上游真实的那一份），不伪造、不吞掉。
 */
public record BufferedUpstreamResponse(byte[] bytes, boolean oversized) {
}
