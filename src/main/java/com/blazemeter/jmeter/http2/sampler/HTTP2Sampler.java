package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.core.HTTP2Client;
import com.blazemeter.jmeter.http2.core.HTTP2SampleResultBuilder;
import com.blazemeter.jmeter.http2.core.HTTP2StateListener;
import com.helger.commons.annotation.VisibleForTesting;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request.Content;
import org.eclipse.jetty.client.util.FormRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.util.Fields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Sampler extends HTTPSamplerBase implements LoopIterationListener, ThreadListener {

  private static final Set<String> SUPPORTED_METHODS = new HashSet<>(Arrays
      .asList(HTTPConstants.GET, HTTPConstants.POST, HTTPConstants.PUT, HTTPConstants.PATCH,
          HTTPConstants.OPTIONS, HTTPConstants.DELETE));
  private static final Set<String> METHODS_WITH_BODY = new HashSet<>(Arrays
      .asList(HTTPConstants.POST, HTTPConstants.PUT, HTTPConstants.PATCH));
  private static final boolean ADD_CONTENT_TYPE_TO_POST_IF_MISSING = JMeterUtils.getPropDefault(
      "http.post_add_content_type_if_missing", false);
  private static final Pattern PORT_PATTERN = Pattern.compile("\\d+");
  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Sampler.class);
  private static final ThreadLocal<Map<HTTP2ClientKey, HTTP2Client>> CONNECTIONS = ThreadLocal
      .withInitial(HashMap::new);
  private final Callable<HTTP2Client> clientFactory;

  public HTTP2Sampler() {
    setName("HTTP2 Sampler");
    setMethod(HTTPConstants.GET);
    clientFactory = this::getClient;
  }

  @VisibleForTesting
  public HTTP2Sampler(Callable<HTTP2Client> clientFactory) {
    this.clientFactory = clientFactory;
  }

  @Override
  public HTTPSampleResult sample() {
    return sample(null, "", false, 0);
  }

  @Override
  protected HTTPSampleResult sample(URL url, String s, boolean b, int i) {
    HTTP2SampleResultBuilder resultBuilder = new HTTP2SampleResultBuilder();
    try {
      URL newURL = url != null ? url : getUrl();
      resultBuilder.withLabel(getSampleLabel(resultBuilder, newURL)).withMethod(getMethod())
          .withUrl(newURL);

      HTTP2Client client = getHttp2Client(resultBuilder);

      if (!getProxyHost().isEmpty()) {
        client.setProxy(getProxyHost(), getProxyPortInt(), getProxyScheme());
      }

      HttpRequest request = client.createRequest(newURL);
      request.followRedirects(false);
      request.method(getMethod());

      if (getHeaderManager() != null) {
        setHeaders(request, getHeaderManager(), newURL);
      }
      setBody(request, resultBuilder);
      if (isSupportedMethod(getMethod())) {
        ContentResponse contentResponse = request.send();
        resultBuilder.withContentResponse(contentResponse);
        if (resultBuilder.getResult().isRedirect()) {
          resultBuilder.withRedirectLocation(
              contentResponse.getHeaders() != null
                  ? contentResponse.getHeaders().get(HTTPConstants.HEADER_LOCATION)
                  : null);
        }
      } else {
        throw new UnsupportedOperationException(
            String.format("Method %s is not supported", getMethod()));
      }

      resultBuilder
          .withRequestHeaders(
              request.getHeaders() != null ? request.getHeaders().asString() : "")
          .build();

      resultBuilder.setResult(resultProcessing(b, i, resultBuilder.getResult()));

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.error("The sampling has been interrupted", e);
      resultBuilder.withFailure(e);
    } catch (Exception e) {
      LOG.error("Error while sampling", e);
      resultBuilder.withFailure(e);
    }
    return resultBuilder.getResult();
  }

  private HTTP2Client getHttp2Client(HTTP2SampleResultBuilder resultBuilder) throws Exception {
    HTTP2Client client = clientFactory.call();
    client.setHTTP2StateListener(new HTTP2StateListener() {
      @Override
      public void onConnectionEnd() {
        resultBuilder.withConnectionEnd();
      }

      @Override
      public void onLatencyEnd() {
        resultBuilder.withLatencyEnd();
      }
    });
    return client;
  }

  private void setBody(HttpRequest request, HTTP2SampleResultBuilder resultBuilder)
      throws UnsupportedEncodingException {
    final String contentEncoding = getContentEncoding();
    Charset contentCharset =
        !contentEncoding.isEmpty() ? Charset.forName(contentEncoding) : StandardCharsets.UTF_8;
    String contentTypeHeader = request.getHeaders().get(HTTPConstants.HEADER_CONTENT_TYPE);
    boolean hasContentTypeHeader = contentTypeHeader != null && contentTypeHeader.isEmpty();
    if (!hasContentTypeHeader && ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
      request.addHeader(new HttpField(HTTPConstants.HEADER_CONTENT_TYPE,
          HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED));
    }
    StringBuilder postBody = new StringBuilder();
    Content requestContent;
    if (getSendParameterValuesAsPostBody()) {
      for (JMeterProperty jMeterProperty : getArguments()) {
        HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
        postBody.append(arg.getEncodedValue(contentCharset.name()));
      }
      requestContent = new StringRequestContent(contentTypeHeader, postBody.toString(),
          contentCharset);
      resultBuilder.withContent(postBody.toString());
      request.body(requestContent);
    } else if (isMethodWithBody(getMethod())) {
      PropertyIterator args = getArguments().iterator();
      Fields fields = new Fields();
      while (args.hasNext()) {
        HTTPArgument arg = (HTTPArgument) args.next().getObjectValue();
        String parameterName = arg.getName();
        if (!arg.isSkippable(parameterName)) {
          String parameterValue = arg.getValue();
          if (!arg.isAlwaysEncoded()) {
            // The FormRequestContent always urlencodes both name and value, in this case the value
            // is already encoded by the user so is needed to decode the value now, so that when the
            // httpclient encodes it, we end up with the same value as the user had entered.
            parameterName = URLDecoder.decode(parameterName, contentCharset.name());
            parameterValue = URLDecoder.decode(parameterValue, contentCharset.name());
          }
          fields.add(parameterName, parameterValue);
        }
      }
      requestContent = new FormRequestContent(fields, contentCharset);
      resultBuilder.withContent(FormRequestContent.convert(fields));
      request.body(requestContent);
    }
  }

  private boolean isSupportedMethod(String method) {
    return SUPPORTED_METHODS.contains(method);
  }

  private boolean isMethodWithBody(String method) {
    return METHODS_WITH_BODY.contains(method);
  }

  private String getSampleLabel(HTTP2SampleResultBuilder resultBuilder, URL url)
      throws MalformedURLException {
    return resultBuilder.isRenameSampleLabel() ? getName() : url.toString();
  }

  private HTTP2Client buildClient() throws Exception {
    HTTP2Client client = new HTTP2Client();
    client.start();
    CONNECTIONS.get().put(buildConnectionKey(), client);
    return client;
  }

  private HTTP2ClientKey buildConnectionKey() throws MalformedURLException {
    return new HTTP2ClientKey(getUrl(), !getProxyHost().isEmpty(), getProxyScheme(), getProxyHost(),
        getProxyPortInt());
  }

  private HTTP2Client getClient() throws Exception {
    Map<HTTP2ClientKey, HTTP2Client> clients = CONNECTIONS.get();
    HTTP2ClientKey key = buildConnectionKey();
    return clients.containsKey(key) ? clients.get(key)
        : buildClient();
  }

  private void setHeaders(HttpRequest request, HeaderManager headerManager, URL url) {
    StreamSupport.stream(headerManager.getHeaders().spliterator(), false)
        .map(prop -> (Header) prop.getObjectValue())
        .filter(header -> !HTTPConstants.HEADER_CONTENT_LENGTH.equalsIgnoreCase(header.getName()))
        .forEach(header -> {
          String headerName = header.getName();
          String headerValue = header.getValue();
          if (HTTPConstants.HEADER_HOST.equalsIgnoreCase(headerName)) {
            int port = getPortFromHostHeader(headerValue, url.getPort());
            // remove any port specification
            headerValue = headerValue.replaceFirst(":\\d+$", "");
            if (port != -1 && port == url.getDefaultPort()) {
              // no need to specify the port if it is the default
              port = -1;
            }
            if (port == -1) {
              request.addHeader(new HttpField(HEADER_HOST, headerValue));
            } else {
              request.addHeader(new HttpField(HEADER_HOST, headerValue + ":" + port));
            }
          } else if (!headerName.isEmpty()) {
            request.addHeader(new HttpField(headerName, headerValue));
          }
        });
  }

  private int getPortFromHostHeader(String hostHeaderValue, int defaultValue) {
    String[] hostParts = hostHeaderValue.split(":");
    if (hostParts.length > 1) {
      String portString = hostParts[hostParts.length - 1];
      if (PORT_PATTERN.matcher(portString).matches()) {
        return Integer.parseInt(portString);
      }
    }
    return defaultValue;
  }

  @Override
  public void iterationStart(LoopIterationEvent iterEvent) {
    JMeterVariables jMeterVariables = JMeterContextService.getContext().getVariables();
    if (!jMeterVariables.isSameUserOnNextIteration()) {
      closeConnections();
    }
  }

  private void closeConnections() {
    Map<HTTP2ClientKey, HTTP2Client> clients = CONNECTIONS.get();
    for (HTTP2Client client : clients.values()) {
      try {
        client.stop();
      } catch (Exception e) {
        LOG.error("Error while closing connection", e);
      }
    }
    clients.clear();
  }

  @Override
  public void threadFinished() {
    closeConnections();
  }

  private static final class HTTP2ClientKey {

    private final String target;
    private final boolean hasProxy;
    private final String proxyScheme;
    private final String proxyHost;
    private final int proxyPort;

    private HTTP2ClientKey(URL url, boolean hasProxy, String proxyScheme, String proxyHost,
        int proxyPort) {
      this.target = url.getProtocol() + "://" + url.getAuthority();
      this.hasProxy = hasProxy;
      this.proxyScheme = proxyScheme;
      this.proxyHost = proxyHost;
      this.proxyPort = proxyPort;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      HTTP2ClientKey that = (HTTP2ClientKey) o;
      return hasProxy == that.hasProxy &&
          proxyPort == that.proxyPort &&
          target.equals(that.target) &&
          proxyScheme.equals(that.proxyScheme) &&
          proxyHost.equals(that.proxyHost);
    }

    @Override
    public int hashCode() {
      return Objects.hash(target, hasProxy, proxyScheme, proxyHost, proxyPort);
    }
  }
}
