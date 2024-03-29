package com.igsl;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.http.HttpStatus;
import org.apache.http.client.utils.URIBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.client.ClientProperties;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.igsl.model.PagedWithCursor;
import com.igsl.model.PagedWithNumber;

/**
 * Utility to send web request
 */
public class WebRequest {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String ENCODING = "ASCII";
	private static final JacksonJsonProvider JACKSON_JSON_PROVIDER = 
			new JacksonJaxbJsonProvider()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(SerializationFeature.INDENT_OUTPUT, true);
	
	private static RateLimiter limiter = new RateLimiter(100, 1000);
	
	public static void setRate(int rate, int period) {
		limiter = new RateLimiter(rate, period);
	}
	
	public static <T> List<T> fetchObjectsWithStartAt(
			String scheme,
			String host,
			String path,
			Map<String, String> pathParameters,
			String method,
			String userName,
			String password,
			MultivaluedMap<String, Object> headers,
			Collection<Cookie> cookies,
			Map<String, Object> queryParameters,
			String startAtParameterName,
			Object data,
			Class<? extends PagedWithNumber<T>> dataClass
			) throws UnsupportedEncodingException, URISyntaxException {
		List<T> result = new ArrayList<>();
		int startAt = 0;
		int size = 0;
		do {
			// Update cursor if exist
			if (queryParameters == null) {
				queryParameters = new HashMap<>();
			}
			queryParameters.put(startAtParameterName, startAt);
			Response resp = invoke(
					scheme, host, path, pathParameters, method, userName, password, headers, cookies, queryParameters, data);
			if ((resp.getStatus() & HttpStatus.SC_OK) == HttpStatus.SC_OK) {
				PagedWithNumber<T> list = resp.readEntity(dataClass);
				result.addAll(list.getPagedItems());
				size = list.getPagedItems().size();
				startAt += size;
			} else {
				Log.error(LOGGER, "Error fetching objects: " + resp.getStatus());
			}
		} while (size != 0);
		return result;
	}	
	
	public static <T> List<T> fetchObjectsWithCursor(
			String scheme,
			String host,
			String path,
			Map<String, String> pathParameters,
			String method,
			String userName,
			String password,
			MultivaluedMap<String, Object> headers,
			Collection<Cookie> cookies,
			Map<String, Object> queryParameters,
			Object data,
			Class<? extends PagedWithCursor<T>> dataClass
			) throws UnsupportedEncodingException, URISyntaxException {
		List<T> result = new ArrayList<>();
		String cursor = null;
		do {
			// Update cursor if exist
			if (cursor != null) {
				if (queryParameters == null) {
					queryParameters = new HashMap<>();
				}
				queryParameters.put(PagedWithCursor.CURSOR_PARAMETER, cursor);
			}
			Response resp = invoke(
					scheme, host, path, pathParameters, method, userName, password, headers, cookies, queryParameters, data);
			if ((resp.getStatus() & HttpStatus.SC_OK) == HttpStatus.SC_OK) {
				PagedWithCursor<T> list = resp.readEntity(dataClass);
				result.addAll(list.getPagedItems());
				cursor = list.getNextPageCursor();
			} else {
				Log.error(LOGGER, "Error fetching objects: " + resp.getStatus());
			}
		} while (cursor != null);
		return result;
	}
	
	public static Response invoke(
			String scheme,
			String host,
			String path,
			Map<String, String> pathParameters,
			String method,
			String userName,
			String password,
			MultivaluedMap<String, Object> headers,
			Collection<Cookie> cookies,
			Map<String, Object> queryParameters,
			Object data
			) throws UnsupportedEncodingException, URISyntaxException {
		String modifiedPath = path;
		if (pathParameters != null) {
//			Log.debug(LOGGER, "Original Path: " + path);
			for (Map.Entry<String, String> entry : pathParameters.entrySet()) {
				modifiedPath = modifiedPath.replaceAll(Pattern.quote("{" + entry.getKey() + "}"), entry.getValue());
			}
//			Log.debug(LOGGER, "Modified Path: " + modifiedPath);
		}
		Client client = ClientBuilder.newClient();
		client.property(ClientProperties.SUPPRESS_HTTP_COMPLIANCE_VALIDATION, true);	// To allow PUT without body
		client.register(JACKSON_JSON_PROVIDER);
		URI uri = new URIBuilder()
				.setScheme(scheme)
				.setHost(host)
				.setPath(modifiedPath)
				.build();
		WebTarget target = client.target(uri);
		if (queryParameters != null) {
			for (Map.Entry<String, Object> query : queryParameters.entrySet()) {
				target = target.queryParam(query.getKey(), query.getValue());
			}
		}
		if (userName != null && password != null) {
			// Add basic authentication header
			String headerValue = "Basic " + 
					Base64.getEncoder().encodeToString((userName + ":" + password).getBytes(ENCODING));
			List<Object> values = new ArrayList<>();
			values.add(headerValue);
			if (headers == null) {
				headers = new MultivaluedHashMap<>();
			}
			headers.put("Authorization", values);
		}
		Builder builder = target.request();
		builder = builder.headers(headers);
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				builder = builder.cookie(cookie);
			}
		}
		
		// Check rate
		while (!limiter.canProceed()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException iex) {
				// Ignored
			}
		}
		
		Response response = null;
		switch (method) {
		case HttpMethod.DELETE:
			response = builder.delete();
			break;
		case HttpMethod.GET:
			response = builder.get();
			break;
		case HttpMethod.HEAD:
			response = builder.head();
			break;
		case HttpMethod.OPTIONS:
			response = builder.options();
			break;
		case HttpMethod.POST:
			if (data != null && String.class.isAssignableFrom(data.getClass())) {
				response = builder.post(Entity.entity(data, MediaType.TEXT_PLAIN));
			} else {
				response = builder.post(Entity.entity(data, MediaType.APPLICATION_JSON));
			}
			break;
		case HttpMethod.PUT:
			if (data != null && String.class.isAssignableFrom(data.getClass())) {
				response = builder.post(Entity.entity(data, MediaType.TEXT_PLAIN));
			} else {
				response = builder.put(Entity.entity(data, MediaType.APPLICATION_JSON));
			}
			break;
		default:
			throw new InvalidParameterException("Invalid method \"" + method + "\"");
		}
		return response;
	}
}
