package indi.atlantis.framework.seafloor.gateway;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_DISPOSITION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import com.github.paganini2008.devtools.StringUtils;
import com.github.paganini2008.devtools.collection.CollectionUtils;
import com.github.paganini2008.devtools.collection.MapUtils;
import com.github.paganini2008.devtools.io.FileUtils;
import com.github.paganini2008.devtools.io.PathUtils;

import indi.atlantis.framework.seafloor.gateway.Router.ResourceType;
import indi.atlantis.framework.seafloor.http.ForwardedRequest;
import indi.atlantis.framework.seafloor.http.Request;
import indi.atlantis.framework.seafloor.http.RequestTemplate;
import indi.atlantis.framework.seafloor.http.RestTemplateDownloadHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import lombok.extern.slf4j.Slf4j;

/**
 * 
 * StaticRequestDispatcher
 * 
 * @author Jimmy Hoff
 *
 * @version 1.0
 */
@Slf4j
@Sharable
public class StaticRequestDispatcher extends RequestDispatcher {

	@Autowired
	private RequestTemplate requestTemplate;

	@Autowired
	private RouterManager routerManager;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object data) throws Exception {
		final FullHttpRequest httpRequest = (FullHttpRequest) data;
		final String path = httpRequest.uri();
		Router router = routerManager.match(path);
		String url = router.url();
		if (StringUtils.isBlank(url)) {
			ctx.fireChannelRead(data);
		} else {
			if (log.isTraceEnabled()) {
				log.trace("Send request to path: {}", url);
			}
			switch (router.resourceType()) {
			case REDIRECT:
				HttpResponseUtils.sendRedirect(ctx, url);
				break;
			case STREAM:
			case FILE:
				downloadResource(httpRequest, router, url, ctx);
				break;
			default:
				break;
			}
		}
	}

	private void downloadResource(FullHttpRequest httpRequest, Router router, String url, ChannelHandlerContext ctx) throws Exception {
		Request request = makeRequest(httpRequest, router, url);
		String fileName = PathUtils.getName(url.contains("?") ? url.substring(0, url.indexOf("?")) : url);
		if (StringUtils.isBlank(fileName)) {
			fileName = PathUtils.getName(router.prefix());
		}
		if (StringUtils.isBlank(fileName)) {
			fileName = UUID.randomUUID().toString();
		}
		ResponseEntity<File> responseEntity = requestTemplate.sendRequest(router.provider(), request,
				new RestTemplateDownloadHandler(FileUtils.getTempDirectory(), fileName));
		final File file = responseEntity.getBody();
		HttpResponse httpResponse = new DefaultHttpResponse(httpRequest.protocolVersion(), HttpResponseStatus.OK);
		Map<String, String> headerMap = responseEntity.getHeaders().toSingleValueMap();
		for (Map.Entry<String, String> entry : headerMap.entrySet()) {
			httpResponse.headers().set(entry.getKey(), entry.getValue());
		}
		httpResponse.headers().set(CONTENT_LENGTH, file.length());
		String contentType = httpResponse.headers().get(CONTENT_TYPE);
		if (StringUtils.isBlank(contentType)) {
			contentType = Files.probeContentType(file.toPath());
		}
		if (StringUtils.isBlank(contentType)) {
			httpResponse.headers().set(CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM.toString());
		}
		if (router.resourceType() == ResourceType.FILE) {
			httpResponse.headers().add(CONTENT_DISPOSITION, String.format("attachment;filename=\"%s\"", file.getName()));
		}
		if (HttpUtil.isKeepAlive(httpRequest)) {
			httpResponse.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		}
		ctx.write(httpResponse);

		ChannelFuture sendFileFuture = ctx.write(new ChunkedFile(file), ctx.newProgressivePromise());
		sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
			@Override
			public void operationComplete(ChannelProgressiveFuture future) throws Exception {
				log.info("File {} transfer completedly.", file.getName());
				Throwable e = future.cause();
				if (e != null) {
					log.error(e.getMessage(), e);
				}
			}

			@Override
			public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) throws Exception {
				if (log.isTraceEnabled()) {
					if (total < 0) {
						log.trace("File '{}' transfer progress: {}", file.getName(), progress);
					} else {
						log.trace("File '{}' transfer progress: {}/{}", file.getName(), progress, total);
					}
				}
			}
		});
		ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
		if (!HttpUtil.isKeepAlive(httpRequest)) {
			lastContentFuture.addListener(ChannelFutureListener.CLOSE);
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
		return request;
	}

	protected HttpHeaders copyHttpHeaders(FullHttpRequest httpRequest) {
		HttpHeaders headers = new HttpHeaders();
		for (Map.Entry<String, String> headerEntry : httpRequest.headers()) {
			headers.add(headerEntry.getKey(), headerEntry.getValue());
		}
		return headers;
	}

}
