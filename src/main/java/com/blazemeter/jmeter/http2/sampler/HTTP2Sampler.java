package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.core.HTTP2Client;
import com.blazemeter.jmeter.http2.core.HTTP2SampleResultBuilder;
import com.helger.commons.annotation.VisibleForTesting;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.api.ContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Sampler extends HTTPSamplerBase {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Sampler.class);
  private final HTTP2Client client;

  public HTTP2Sampler() {
    this(new HTTP2Client());
  }

  @VisibleForTesting
  public HTTP2Sampler(HTTP2Client client) {
    this.client = client;
    setName("HTTP2 Sampler");
  }

  @Override
  protected HTTPSampleResult sample(URL url, String s, boolean b, int i) {
    HTTP2SampleResultBuilder resultBuilder = new HTTP2SampleResultBuilder();
    if (!getProxyHost().isEmpty()) {
      client.setProxy(getProxyHost(), getProxyPortInt(), getProxyScheme());
    }
    try {
      if (getMethod().equals(HTTPConstants.GET)) {
        resultBuilder.withUrl(getUrl()).withMethod(HTTPConstants.GET)
            .withRequestHeaders(getHeaderManager());
        ContentResponse contentResponse = client.doGet(getUrl(), getHeaderManager());
        resultBuilder.withContentResponse(contentResponse);
      } else {
        throw new UnsupportedOperationException(
            String.format("Method %s is not supported", getMethod()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.error("The sampling has been interrupted", e);
      resultBuilder.withFailure(e);
    } catch (Exception e) {
      LOG.error("Error while sampling", e);
      resultBuilder.withFailure(e);
    }
    return resultBuilder.build();
  }
}
