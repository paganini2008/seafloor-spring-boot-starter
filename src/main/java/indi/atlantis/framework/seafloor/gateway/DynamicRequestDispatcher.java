package indi.atlantis.framework.seafloor.gateway;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.github.paganini2008.devtools.StringUtils;
import com.github.paganini2008.devtools.cache.Cache;
import com.github.paganini2008.devtools.collection.CollectionUtils;
import com.github.paganini2008.devtools.collection.MapUtils;

import indi.atlantis.framework.seafloor.http.FallbackProvider;
import indi.atlantis.framework.seafloor.http.ForwardedRequest;
import indi.atlantis.framework.seafloor.http.Request;
import indi.atlantis.framework.seafloor.http.RequestTemplate;
import indi.atlantis.framework.seafloor.utils.ApplicationContextUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 
 * DynamicRequestDispatcher
 *
 * @author Jimmy Hoff
 * @version 1.0
 */
@Slf4j
@Sharable
public class DynamicRequestDispatcher extends RequestDispatcher {

	@Autowired
	private RequestTemplate requestTemplate;

	@Autowired
	private RouterManager routerManager;

	@Autowired
	private Cache cache;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object data) throws Exception {
		final FullHttpRequest httpRequest = (FullHttpRequest) data;
		final String path = httpRequest.uri();
		Router router = routerManager.match(path);
		String rawPath = router.trimPath(path);
		if (log.isTraceEnabled()) {
			log.trace("Send request to path: {}", rawPath);
		}
		Request request = makeRequest(httpRequest, router, rawPath);
		ResponseEntity<String> responseEntity;
		if (router.cached()) {
			String content = (String) cache.getObject(rawPath);
			if (StringUtils.isBlank(content)) {
				responseEntity = requestTemplate.sendRequest(router.provider(), request, String.class);
				cache.putObject(rawPath, responseEntity.getBody());
			} else {
				responseEntity = new ResponseEntity<String>(content, HttpStatus.OK);
			}
		} else {
			responseEntity = requestTemplate.sendRequest(router.provider(), request, String.class);
		}
		ByteBuf buffer = Unpooled.copiedBuffer(responseEntity.getBody(), router.charset());
		DefaultFullHttpResponse httpResponse = new DefaultFullHttpResponse(httpRequest.protocolVersion(),
				HttpResponseStatus.valueOf(responseEntity.getStatusCodeValue()), buffer);
		httpResponse.headers().set(CONTENT_LENGTH, buffer.readableBytes());
		MediaType mediaType = responseEntity.getHeaders().getContentType();
		if (mediaType == null) {
			mediaType = MediaType.APPLICATION_JSON;
		}
		httpResponse.headers().set(CONTENT_TYPE, mediaType.toString());
		boolean keepAlive = HttpUtil.isKeepAlive(httpRequest);
		if (keepAlive) {
			httpResponse.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		}
		ChannelFuture future = ctx.writeAndFlush(httpResponse);
		if (!keepAlive) {
			future.addListener(ChannelFutureListener.CLOSE);
		}

	}

	private Request makeRequest(FullHttpRequest httpRequest, Router router, String rawPath) {
		HttpHeaders httpHeaders = copyHttpHeaders(httpRequest);
		if (MapUtils.isNotEmpty(router.defaultHeaders())) {
			httpHeaders.addAll(router.defaultHeaders());
		}
		if (CollectionUtils.isNotEmpty(router.ignoredHeaders())) {
			MapUtils.removeKeys(httpHeaders, router.ignoredHeaders());
		}
		ByteBuf byteBuf = httpRequest.content();
		byte[] body = null;
		int length = byteBuf.readableBytes();
		if (length > 0) {
			body = new byte[length];
			byteBuf.readBytes(body);
		}
		ForwardedRequest request = new ForwardedRequest(rawPath, HttpMethod.valueOf(httpRequest.method().name()), httpHeaders);
		request.setBody(body);
		request.setTimeout(router.timeout());
		request.setRetries(router.retries());
		request.setAllowedPermits(router.allowedPermits());
		request.setFallback(getFallback(router.fallback()));
		return request;
	}

	private FallbackProvider getFallback(Class<?> fallbackClass) {
		try {
			if (fallbackClass != null && fallbackClass != Void.class && fallbackClass != void.class) {
				return (FallbackProvider) ApplicationContextUtils.getBeanIfNecessary(fallbackClass);
			}
		} catch (RuntimeException e) {
			log.error(e.getMessage(), e);
		}
		return null;
	}

	protected HttpHeaders copyHttpHeaders(FullHttpRequest httpRequest) {
		HttpHeaders headers = new HttpHeaders();
		for (Map.Entry<String, String> headerEntry : httpRequest.headers()) {
			headers.add(headerEntry.getKey(), headerEntry.getValue());
		}
		return headers;
	}

}
